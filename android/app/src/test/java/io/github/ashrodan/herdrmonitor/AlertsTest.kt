package io.github.ashrodan.herdrmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Notification V2, the pure parts: health, overview, context, actions.
class AlertsTest {
    private fun agent(addr: String, state: String, msg: String? = null, changed: Long = 0, name: String = "n-$addr") =
        AgentInfo(addr, name, state, null, msg, changed, null)

    // ---- Health ----

    @Test fun failuresMapToTheirCause() {
        assertEquals(Health.ServerTooOld("mac"), healthOfFailure("mac", 404, "HTTP 404", 0, 0))
        assertEquals(Health.Unauthorized("mac", 401), healthOfFailure("mac", 401, "x", 0, 0))
        assertEquals(Health.Unauthorized("mac", 403), healthOfFailure("mac", 403, "x", 0, 0))
        assertTrue(healthOfFailure("mac", null, "timeout", 0, 0) is Health.Offline)
        assertTrue(healthOfFailure("mac", 502, "bad gateway", 0, 0) is Health.Offline)
    }

    @Test fun anOldServerAlertsOnceWithTheRecoveryStep() {
        val h = Health.ServerTooOld("mac")
        val step = planHealth(null, h, 0) as HealthStep.Post
        assertEquals("Agent alerts are unavailable", step.alert.title)
        assertTrue(step.alert.text.contains("Update and restart"))
        // The same cause again: nothing new.
        assertNull(planHealth(step.alert.key, h, 1000))
    }

    @Test fun offlineAlertsOnlyAfterFiveMinutes() {
        val h = Health.Offline("mac", "timeout", sinceMs = 0, retryAtMs = 0)
        assertNull(planHealth(null, h, OFFLINE_ALERT_MS - 1))
        assertTrue(planHealth(null, h, OFFLINE_ALERT_MS) is HealthStep.Post)
    }

    @Test fun aRetryWithNoCauseKeepsTheAlertAndRecoveryCancelsIt() {
        val key = (planHealth(null, Health.ServerTooOld("mac"), 0) as HealthStep.Post).alert.key
        assertNull(planHealth(key, Health.Connecting, 1))
        assertNull(planHealth(key, Health.Offline("mac", "x", 0, 0), 2))
        assertEquals(HealthStep.Cancel, planHealth(key, Health.Connected("mac", 3), 3))
        assertEquals(HealthStep.Cancel, planHealth(key, Health.Off, 3))
        // Nothing on screen: nothing to cancel.
        assertNull(planHealth(null, Health.Connected("mac", 3), 3))
    }

    @Test fun aNewCauseReplacesTheOld() {
        val key = (planHealth(null, Health.ServerTooOld("mac"), 0) as HealthStep.Post).alert.key
        assertTrue(planHealth(key, Health.Unauthorized("mac", 401), 1) is HealthStep.Post)
    }

    // ---- Summary ----

    @Test fun summaryCountsAndOrderIncludeDone() {
        val ov = summaryOf(listOf(
            agent("w1", "working", "bash: ls", changed = 5),
            agent("b1", "blocked", "Allow edit?"),
            agent("d1", "done", "PR #12 is green"),
            agent("e1", "error", "gh: not logged in"),
            agent("i1", "idle"),
        ))
        assertEquals("1 needs you · 1 failed · 1 working · 1 done", ov.title)
        assertEquals(listOf("n-b1 · Needs you: Allow edit?", "n-e1 · Failed: gh: not logged in", "n-w1 · Working: bash: ls", "n-d1 · Done: PR #12 is green"), ov.lines)
        assertEquals(0, ov.more)
    }

    @Test fun doneOnlyMakesASummary() {
        val ov = summaryOf(listOf(agent("d", "done")))
        assertEquals("1 done", ov.title)
        assertEquals(listOf("n-d · Done: finished its turn"), ov.lines)
    }

    @Test fun aSeenDoneLeavesTheSummary() {
        val done = AgentInfo("d", "n-d", "done", null, null, 100, null)
        val seen = done.copy(state = done.stateOf(mapOf("d" to 150L)))
        assertEquals("idle", seen.state)
        assertEquals("No active agents", summaryOf(listOf(seen)).title)
        assertTrue(summaryOf(listOf(seen)).lines.isEmpty())
    }

    @Test fun summaryLimitsLinesAndCountsDoneInMore() {
        val ov = summaryOf((1..4).map { agent("w$it", "working", changed = it.toLong()) } + (1..4).map { agent("d$it", "done") })
        assertEquals("4 working · 4 done", ov.title)
        assertEquals(OVERVIEW_LINES, ov.lines.size)
        assertEquals(3, ov.more)
        assertTrue("newest working first", ov.lines.first().startsWith("n-w4"))
        assertTrue("then done", ov.lines.last().startsWith("n-d"))
    }

    @Test fun noAgentsStillASummary() = assertEquals("No active agents", summaryOf(emptyList()).title)

    @Test fun quietSummarySaysOnlyWhoNeedsYou() {
        val all = listOf(agent("b", "blocked"), agent("e", "error"), agent("w", "working"), agent("d", "done"))
        val q = summaryOf(all, quiet = true)
        assertEquals("Quiet agents · 2 need you", q.title)
        assertTrue(q.lines.isEmpty())
        assertEquals("Quiet agents · 1 needs you", summaryOf(listOf(agent("b", "blocked")), quiet = true).title)
        assertEquals("Quiet agents · 0 need you", summaryOf(emptyList(), quiet = true).title)
    }

    @Test fun summaryViewShowsTheLinkWhenItIsDown() {
        val ov = summaryOf(listOf(agent("w", "working", "bash: ls")))
        val up = summaryView(ov, Health.Connected("mac", 1), "mac")
        assertEquals("1 working", up.title)
        assertEquals("n-w · Working: bash: ls", up.text)
        assertEquals("mac", up.sub)
        val down = summaryView(ov, Health.ServerTooOld("mac"), "mac")
        assertEquals("mac has no alert stream (old server)", down.title)
        assertEquals("1 working", down.text)
        assertEquals("Herdr is not running on mac", summaryView(ov, Health.Connected("mac", 1), "mac", herdrUp = false).text)
        val many = summaryView(summaryOf((1..7).map { agent("w$it", "working") }), Health.Connected("mac", 1), "mac")
        assertEquals("+2 more", many.lines.last())
    }

    @Test fun summarySignatureIgnoresNothingThatShows() {
        val a = summaryView(summaryOf(listOf(agent("w", "working", "a"))), Health.Connected("mac", 1), "mac")
        // Same content, a later heartbeat time: same signature.
        assertEquals(a.sig, summaryView(summaryOf(listOf(agent("w", "working", "a"))), Health.Connected("mac", 99), "mac").sig)
        assertTrue(a.sig != summaryView(summaryOf(listOf(agent("w", "working", "b"))), Health.Connected("mac", 1), "mac").sig)
    }

    // ---- Context ----

    @Test fun subtextUsesOnlyExplicitFields() {
        val a = AgentInfo("t1", "herdr-monitor · review", "blocked", null, null, null, null, harness = "claude", spaceName = "herdr-monitor")
        assertEquals("herdr-monitor · claude · mac", subtextOf(a, "mac"))
        // No context from the server: only the host. Nothing parsed from the name.
        assertEquals("mac", subtextOf(agent("t2", "blocked", name = "app · tab"), "mac"))
        assertNull(subtextOf(agent("t3", "blocked"), null))
    }

    // ---- Actions ----

    @Test fun twoQuickAnswersThenReply() {
        assertEquals(listOf(Act.Answer(0, "Yes"), Act.Answer(1, "No"), Act.Reply), actionPlan("blocked", listOf("Yes", "No", "Maybe")))
        assertEquals(listOf(Act.Answer(0, "Yes"), Act.Reply), actionPlan("blocked", listOf("Yes")))
    }

    @Test fun replyIsAlwaysThereAndThreeAtMost() {
        for (state in listOf("blocked", "error"))
            for (quick in listOf(null, emptyList(), listOf("a"), listOf("a", "b", "c", "d"))) {
                val plan = actionPlan(state, quick)
                assertTrue("$state $quick", Act.Reply in plan)
                assertTrue("$state $quick", plan.size <= 3)
            }
    }

    @Test fun plainDialogAndError() {
        assertEquals(listOf(Act.Reply, Act.Open, Act.Stop), actionPlan("blocked", null))
        assertEquals(listOf(Act.Open, Act.Reply), actionPlan("error", null))
    }

    @Test fun replyFailureTitles() {
        assertEquals("Reply queued", replyFailureTitle(Failure.Retry))
        assertEquals("Reply kept in app", replyFailureTitle(Failure.Refused))
    }
}
