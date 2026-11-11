package io.github.ashrodan.herdrmonitor

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// The notifications as Android holds them (activeNotifications), with fake
// agents only. One routine notification (the agent summary) plus one per
// agent that needs you. Nothing is sent to a server: no action is fired. The shade
// is never opened.
class NotificationTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val c = inst.targetContext
    private val nm = c.getSystemService(NotificationManager::class.java)
    // Granted for the run. Not revoked here: a revoke kills the app
    // process, which is also the test process. Revoke after the run:
    // adb shell pm revoke <package> android.permission.POST_NOTIFICATIONS
    @Before fun setUp() {
        if (Build.VERSION.SDK_INT >= 33 && !c.mayNotify())
            inst.uiAutomation.grantRuntimePermission(c.packageName, Manifest.permission.POST_NOTIFICATIONS)
        Notifier.channels(c)
        clear()
    }

    @After fun tearDown() = clear()

    private fun clear() {
        for (a in listOf("fake-1", "fake-2", "legacy")) Notifier.cancelAgent(c, a)
        nm.cancel(Notifier.ID_SUMMARY)
        Notifier.cancelHealth(c)
        nm.cancel(Notifier.TAG_TEST, Notifier.ID_TEST)
        Thread.sleep(200)
    }

    private fun activeId(id: Int): Notification? {
        repeat(20) {
            nm.activeNotifications.firstOrNull { it.tag == null && it.id == id }?.let { return it.notification }
            Thread.sleep(100)
        }
        return null
    }

    private fun summary(vararg agents: AgentInfo, quiet: Boolean = false) =
        summaryView(summaryOf(agents.toList(), quiet), Health.Connected("mac", 1), "mac")

    private fun active(tag: String, id: Int): Notification? {
        repeat(20) {
            nm.activeNotifications.firstOrNull { it.tag == tag && it.id == id }?.let { return it.notification }
            Thread.sleep(100)
        }
        return null
    }

    private fun gone(tag: String, id: Int): Boolean {
        repeat(20) { if (nm.activeNotifications.none { it.tag == tag && it.id == id }) return true; Thread.sleep(100) }
        return false
    }

    private fun fake(addr: String, state: String, msg: String? = null) =
        AgentInfo(addr, "Fake $addr", state, "permission", msg, System.currentTimeMillis(), null, harness = "claude", spaceName = "probe-space")

    private fun ask(vararg options: String) = Part.Ask("q", listOf(Question("Pick", "Which one?", false, options.map { Option(it, "") })), false)

    @Test fun threeChannelsOnly() {
        assertEquals(NotificationManager.IMPORTANCE_HIGH, nm.getNotificationChannel(Notifier.CH_NEEDS).importance)
        val sum = nm.getNotificationChannel(Notifier.CH_SUMMARY)
        assertEquals(NotificationManager.IMPORTANCE_LOW, sum.importance)
        assertEquals("Agent summary", sum.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, nm.getNotificationChannel(Notifier.CH_HEALTH).importance)
        for (old in listOf("finished", "service", "alerts")) assertNull(old, nm.getNotificationChannel(old))
    }

    // An update from the Notification V2 build: its Running, group summary,
    // overview and Finished notifications go, and so do the old channels.
    @Test fun upgradeCleansUpLegacyNotifications() {
        nm.createNotificationChannel(android.app.NotificationChannel("finished", "Finished", NotificationManager.IMPORTANCE_DEFAULT))
        val old = { ch: String -> Notification.Builder(c, ch).setSmallIcon(R.drawable.ic_stat_herdr).setContentTitle("old").build() }
        nm.notify("legacy", 3, old(Notifier.CH_SUMMARY))
        nm.notify("host:x", 4, old(Notifier.CH_SUMMARY))
        nm.notify("overview", 6, old(Notifier.CH_SUMMARY))
        nm.notify("legacy", Notifier.ID_AGENT, old("finished"))
        assertNotNull(active("overview", 6))
        Notifier.channels(c)
        assertTrue(gone("legacy", 3)); assertTrue(gone("host:x", 4)); assertTrue(gone("overview", 6))
        assertTrue(gone("legacy", Notifier.ID_AGENT))
        assertNull(nm.getNotificationChannel("finished"))
    }

    // One routine notification: the summary, on the service's id, in no
    // group.
    @Test fun theSummaryIsTheOnlyRoutineNotification() {
        Notifier.postSummary(c, summary(fake("w", "working", "bash: ls"), fake("d", "done", "PR is green")))
        val n = activeId(Notifier.ID_SUMMARY)!!
        assertEquals(Notifier.CH_SUMMARY, n.channelId)
        assertTrue(n.flags and Notification.FLAG_GROUP_SUMMARY == 0)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertNull(n.group)
        assertEquals("1 working · 1 done", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        val lines = n.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)!!.map { it.toString() }
        assertEquals(listOf("Fake w · Working: bash: ls", "Fake d · Done: PR is green"), lines)
        // Working and Done have no notification of their own.
        assertEquals(listOf(Notifier.ID_SUMMARY), nm.activeNotifications.map { it.id })
    }

    @Test fun quietSummary() {
        Notifier.postSummary(c, summary(fake("b", "blocked"), fake("w", "working"), quiet = true))
        val n = activeId(Notifier.ID_SUMMARY)!!
        assertEquals("Quiet agents · 1 needs you", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertTrue(n.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES).isNullOrEmpty())
    }

    // The Pixel case: the summary was a group summary, and Android removed
    // it when the one Needs you alert in its group went. Now: blocked posts
    // one standalone Needs you; resolved, it goes and the summary stays.
    @Test fun theSummaryOutlivesTheLastNeedsYou() {
        Notifier.postSummary(c, summary(fake("fake-1", "blocked")))
        Notifier.postAgent(c, fake("fake-1", "blocked", "Allow?"), "blocked", null)
        val child = active("fake-1", Notifier.ID_AGENT)!!
        assertNull(child.group)
        Thread.sleep(1000)
        assertEquals(setOf(null to Notifier.ID_SUMMARY, "fake-1" to Notifier.ID_AGENT), nm.activeNotifications.map { it.tag to it.id }.toSet())
        Notifier.cancelAgent(c, "fake-1")
        assertTrue(gone("fake-1", Notifier.ID_AGENT))
        Thread.sleep(1500)
        assertNotNull(activeId(Notifier.ID_SUMMARY))
        // The same with the test alert, once.
        Notifier.postTest(c)
        assertNotNull(active(Notifier.TAG_TEST, Notifier.ID_TEST))
        nm.cancel(Notifier.TAG_TEST, Notifier.ID_TEST)
        assertTrue(gone(Notifier.TAG_TEST, Notifier.ID_TEST))
        Thread.sleep(1500)
        assertNotNull(activeId(Notifier.ID_SUMMARY))
    }

    // Several Needs you at once (Android may bundle them): the summary
    // stays, and stays after they all go.
    @Test fun theSummaryOutlivesSeveralNeedsYou() {
        Notifier.postSummary(c, summary(fake("fake-1", "blocked"), fake("fake-2", "error")))
        Notifier.postAgent(c, fake("fake-1", "blocked", "Allow?"), "blocked", null)
        Notifier.postAgent(c, fake("fake-2", "error", "boom"), "error", null)
        Thread.sleep(1500)
        assertNotNull(activeId(Notifier.ID_SUMMARY))
        Notifier.cancelAgent(c, "fake-1"); Notifier.cancelAgent(c, "fake-2")
        assertTrue(gone("fake-2", Notifier.ID_AGENT))
        Thread.sleep(1500)
        assertNotNull(activeId(Notifier.ID_SUMMARY))
    }

    @Test fun needsYouHasContextAndInlineReply() {
        Notifier.postAgent(c, fake("fake-1", "blocked", "Allow Bash: ls?"), "blocked", null)
        val n = active("fake-1", Notifier.ID_AGENT)!!
        assertEquals(Notifier.CH_NEEDS, n.channelId)
        assertEquals("Fake fake-1 · needs you", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Allow Bash: ls?", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue(n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString().startsWith("probe-space · claude"))
        assertEquals(listOf("Reply", "Open", "Stop"), n.actions.map { it.title.toString() })
        val reply = n.actions.first()
        assertEquals(1, reply.remoteInputs.size)
        assertEquals(Notification.Action.SEMANTIC_ACTION_REPLY, reply.semanticAction)
    }

    @Test fun aThreeOptionQuestionStillHasReply() {
        Notifier.postAgent(c, fake("fake-1", "blocked"), "blocked", ask("Red", "Green", "Blue"))
        val n = active("fake-1", Notifier.ID_AGENT)!!
        assertEquals(listOf("Red", "Green", "Reply"), n.actions.map { it.title.toString() })
        assertEquals(1, n.actions.last().remoteInputs.size)
        assertEquals("Which one?", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test fun twoAgentsGetTheirOwnIntents() {
        Notifier.postAgent(c, fake("fake-1", "error", "boom"), "error", null)
        Notifier.postAgent(c, fake("fake-2", "error", "bang"), "error", null)
        val a = active("fake-1", Notifier.ID_AGENT)!!
        val b = active("fake-2", Notifier.ID_AGENT)!!
        assertNotEquals(a.contentIntent, b.contentIntent)
        assertNotEquals(a.actions.last().actionIntent, b.actions.last().actionIntent)
    }

    @Test fun healthAlertPostsAndCancels() {
        val step = planHealth(null, Health.ServerTooOld("mac"), 0) as HealthStep.Post
        Notifier.postHealth(c, step.alert)
        val n = active(Notifier.TAG_HEALTH, Notifier.ID_HEALTH)!!
        assertEquals(Notifier.CH_HEALTH, n.channelId)
        assertEquals("Agent alerts are unavailable", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(listOf("Open alert setup", "Retry"), n.actions.map { it.title.toString() })
        assertEquals(1, nm.activeNotifications.count { it.id == Notifier.ID_HEALTH })
        assertEquals(HealthStep.Cancel, planHealth(step.alert.key, Health.Connected("mac", 1), 1))
        Notifier.cancelHealth(c)
        assertTrue(gone(Notifier.TAG_HEALTH, Notifier.ID_HEALTH))
    }

    @Test fun testAlertHasNoAgentActions() {
        Notifier.postTest(c)
        val n = active(Notifier.TAG_TEST, Notifier.ID_TEST)!!
        assertNull(n.actions)
    }

    @Test fun ids() {
        val ids = listOf(Notifier.ID_AGENT, Notifier.ID_SUMMARY, Notifier.ID_HEALTH, Notifier.ID_TEST)
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.none { it in Notifier.LEGACY_IDS })
    }

    // org.json is real only on a device, so the stream parse is tested here.
    @Test fun streamContextFieldsParse() {
        val a = Json.agent(JSONObject("""{"address":"t1","name":"app","state":"working","harness":"codex","space_id":"sp1","space_name":"herdr-monitor","branch":"feat/x"}"""))
        assertEquals("codex", a.harness)
        assertEquals("sp1", a.spaceId)
        assertEquals("herdr-monitor", a.spaceName)
        assertEquals("feat/x", a.branch)
        val old = Json.agent(JSONObject("""{"address":"t1","name":"app","state":"working"}"""))
        assertNull(old.harness)
        assertNotNull(old.name)
    }
}
