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

    // ---- Overview ----

    @Test fun overviewCountsAndPriorityOrder() {
        val ov = overviewOf(listOf(
            agent("w1", "working", "bash: ls", changed = 5),
            agent("b1", "blocked", "Allow edit?"),
            agent("d1", "done"),
            agent("e1", "error", "gh: not logged in"),
            agent("i1", "idle"),
        ))!!
        assertEquals("1 needs you · 1 failed · 1 working", ov.title)
        assertEquals(listOf("n-b1 · Needs you: Allow edit?", "n-e1 · Failed: gh: not logged in", "n-w1 · Working: bash: ls"), ov.lines)
        assertEquals(0, ov.more)
    }

    @Test fun overviewLimitsLinesAndSaysHowManyMore() {
        val ov = overviewOf((1..8).map { agent("w$it", "working", changed = it.toLong()) })!!
        assertEquals("8 agents working", ov.title)
        assertEquals(OVERVIEW_LINES, ov.lines.size)
        assertEquals(3, ov.more)
        assertTrue("newest first", ov.lines.first().startsWith("n-w8"))
    }

    @Test fun noActiveAgentNoOverview() {
        assertNull(overviewOf(listOf(agent("d", "done"), agent("i", "idle"))))
        assertNull(overviewOf(emptyList()))
    }

    @Test fun oneWorkingAgent() = assertEquals("1 agent working", overviewOf(listOf(agent("w", "working")))!!.title)

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
        assertEquals(listOf(Act.Answer(0, "Yes"), Act.Answer(1, "No"), Act.Reply), actionPlan("blocked", listOf("Yes", "No", "Maybe"), false))
        assertEquals(listOf(Act.Answer(0, "Yes"), Act.Reply), actionPlan("blocked", listOf("Yes"), false))
    }

    @Test fun replyIsAlwaysThereAndThreeAtMost() {
        for (state in listOf("blocked", "error", "done", "working"))
            for (quick in listOf(null, emptyList(), listOf("a"), listOf("a", "b", "c", "d")))
                for (pr in listOf(true, false)) {
                    val plan = actionPlan(state, quick, pr)
                    assertTrue("$state $quick $pr", Act.Reply in plan)
                    assertTrue("$state $quick $pr", plan.size <= 3)
                }
    }

    @Test fun plainDialogAndErrorAndDone() {
        assertEquals(listOf(Act.Reply, Act.Open, Act.Stop), actionPlan("blocked", null, false))
        assertEquals(listOf(Act.Open, Act.Reply), actionPlan("error", null, false))
        assertEquals(listOf(Act.OpenPr, Act.View, Act.Reply), actionPlan("done", null, true))
        assertEquals(listOf(Act.View, Act.Reply), actionPlan("done", null, false))
    }

    // ---- Quiet ----

    @Test fun quietKeepsNeedsYouOnly() {
        assertTrue(mayAlert("blocked", quiet = true))
        assertTrue(mayAlert("error", quiet = true))
        assertFalse(mayAlert("done", quiet = true))
        assertFalse(wantsRunning("working", quiet = true, pinned = true))
        assertTrue(wantsRunning("working", quiet = false, pinned = true))
        assertFalse("unpinned: overview only", wantsRunning("working", quiet = false, pinned = false))
        assertEquals(3, MAX_RUNNING)
    }

    @Test fun replyFailureTitles() {
        assertEquals("Reply queued", replyFailureTitle(Failure.Retry))
        assertEquals("Reply kept in app", replyFailureTitle(Failure.Refused))
    }
}
