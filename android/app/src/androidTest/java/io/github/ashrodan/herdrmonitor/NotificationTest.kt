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
// agents only. Nothing is sent to a server: no action is fired. The shade
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
        for (a in listOf("fake-1", "fake-2")) Notifier.cancelAgent(c, a)
        Notifier.postOverview(c, null)
        Notifier.cancelHealth(c)
        nm.cancel(Notifier.TAG_TEST, Notifier.ID_TEST)
        Thread.sleep(200)
    }

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

    @Test fun channelsAndImportance() {
        assertEquals(NotificationManager.IMPORTANCE_HIGH, nm.getNotificationChannel(Notifier.CH_NEEDS).importance)
        assertEquals(NotificationManager.IMPORTANCE_LOW, nm.getNotificationChannel(Notifier.CH_RUNNING).importance)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, nm.getNotificationChannel(Notifier.CH_FINISHED).importance)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, nm.getNotificationChannel(Notifier.CH_HEALTH).importance)
        assertEquals(NotificationManager.IMPORTANCE_MIN, nm.getNotificationChannel(Notifier.CH_SERVICE).importance)
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
        assertTrue(n.group.startsWith("host:"))
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

    @Test fun overviewListsAllStatesInOrder() {
        val ov = overviewOf(listOf(fake("w", "working", "bash: ls"), fake("b", "blocked", "Allow?"), fake("e", "error", "boom")))
        Notifier.postOverview(c, ov)
        val n = active(Notifier.TAG_OVERVIEW, Notifier.ID_OVERVIEW)!!
        assertEquals(Notifier.CH_RUNNING, n.channelId)
        assertEquals("1 needs you · 1 failed · 1 working", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        val lines = n.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)!!.map { it.toString() }
        assertEquals(listOf("Fake b · Needs you: Allow?", "Fake e · Failed: boom", "Fake w · Working: bash: ls"), lines)
        Notifier.postOverview(c, null)
        assertTrue(gone(Notifier.TAG_OVERVIEW, Notifier.ID_OVERVIEW))
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
        val ids = listOf(Notifier.ID_AGENT, Notifier.ID_SERVICE, Notifier.ID_RUNNING, Notifier.ID_SUMMARY, Notifier.ID_HEALTH, Notifier.ID_OVERVIEW, Notifier.ID_TEST)
        assertEquals(ids.size, ids.toSet().size)
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
