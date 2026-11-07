package io.github.ashrodan.herdrmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {
    private fun agent(state: String, changed: Long? = 100, seen: Long? = null, addr: String = "t1", msg: String? = null) =
        AgentInfo(addr, "app", state, "permission", msg, changed, seen)

    @Test fun doneTurnsIdleOnceSeenOnTheMacOrHere() {
        assertEquals("done", effectiveState("done", 100, null))
        assertEquals("idle", effectiveState("done", 100, 100))
        assertEquals("idle", effectiveState("done", 100, null, 150))
        assertEquals("done", effectiveState("done", 100, 50, 90))
        assertEquals("blocked", effectiveState("blocked", 100, 200))
        assertEquals("idle", effectiveState(null, null, null))
    }

    @Test fun planAlertPostsOnNewAlertStatesOnly() {
        val p = planAlert("t1", null, agent("blocked")) as AlertAction.Post
        assertEquals("blocked", p.state)
        assertNull(planAlert("t1", "blocked", agent("blocked", msg = "new msg")))
        assertEquals("done", (planAlert("t1", "blocked", agent("done")) as AlertAction.Post).state)
        assertNull(planAlert("t1", null, agent("working")))
    }

    @Test fun planAlertCancelsWhenTheAgentMovesOnOrGoes() {
        assertEquals(AlertAction.Cancel("t1"), planAlert("t1", "blocked", agent("working")))
        assertEquals(AlertAction.Cancel("t1"), planAlert("t1", "done", agent("done", seen = 100)))
        assertEquals(AlertAction.Cancel("t1"), planAlert("t1", "done", null))
        assertNull(planAlert("t1", null, null))
    }

    @Test fun planAlertOnASnapshotSkipsChangesFromBeforeWeListened() {
        assertNull(planAlert("t1", null, agent("blocked", changed = 100), since = 500))
        assertTrue(planAlert("t1", null, agent("blocked", changed = 600), since = 500) is AlertAction.Post)
        // Something already on screen still goes when it is over.
        assertEquals(AlertAction.Cancel("t1"), planAlert("t1", "blocked", agent("idle", changed = 100), since = 500))
    }

    @Test fun alertText() {
        assertEquals("app · needs you", alertTitle("app", "blocked"))
        assertEquals("Waiting at a permission", alertBody(agent("blocked"), "blocked"))
        assertEquals("Run ls?", alertBody(agent("blocked", msg = "Run ls?"), "blocked"))
    }

    @Test fun displayNameUsesNameThenSpaceAndNamedTab() {
        val spaces = listOf(Space("s1", "app", tabs = listOf(SpaceTab("tb1", "tests", true, listOf("t1")), SpaceTab("tb2", "1", false, listOf("t2")))))
        assertEquals("mine", displayName(Terminal("t9", name = "mine"), spaces))
        assertEquals("app · tests", displayName(Terminal("t1", space = "s1", tab = "tb1"), spaces))
        assertEquals("app", displayName(Terminal("t2", space = "s1", tab = "tb2"), spaces))
        assertEquals("proj", displayName(Terminal("t3", where = "proj / 1", agent = "claude"), spaces))
        assertEquals("claude", displayName(Terminal("t4", agent = "claude"), spaces))
    }

    @Test fun groupAgentsOrdersByStateAndSkipsShellsAndFinished() {
        val ts = listOf(
            Terminal("a", status = "working", changedMs = 1), Terminal("b", status = "blocked", changedMs = 2),
            Terminal("c", status = "blocked", changedMs = 3), Terminal("d"), Terminal("e", status = "done", state = "finished"),
        )
        val g = groupAgents(ts)
        assertEquals(listOf("blocked", "working"), g.map { it.state })
        assertEquals(listOf("c", "b"), g[0].rows.map { it.address })
    }

    @Test fun widgetShowsCountsAndTheTopNeedsYouSession() {
        val agents = listOf(agent("blocked", addr = "a"), agent("error", addr = "b"), agent("done", addr = "c"),
            agent("done", seen = 100, addr = "d"), agent("working", addr = "e"))
        val prs = listOf(OverviewPr("s", "app", null, "b", Pr(7, "Fix", "https://x/7", false, "b", null, null), "ready", 1, 0))
        val ask = Part.Ask("q", listOf(Question("Colours", "Which accent?", false, listOf(Option("Teal", ""), Option("Amber", "")))), false)
        val w = widgetData(true, "mac", agents, prs, mapOf("a" to ask), emptyMap(), 1000)
        // Needs you counts waiting and failed.
        assertEquals(listOf(2, 1, 1), listOf(w.needsYou, w.working, w.prs))
        // Waiting comes before failed; its first answer is the button.
        assertEquals("a", w.top?.address)
        assertEquals("Which accent?", w.top?.line)
        assertEquals("Teal", w.top?.option)
        assertEquals(listOf(QuestionShape("Which accent?", 2, false)), w.top?.shape)
        // No question read yet: no button.
        assertNull(widgetData(true, "mac", agents, prs, emptyMap(), emptyMap(), 1000).top?.option)
        assertNull(widgetData(true, "mac", listOf(agent("working", addr = "e")), prs, emptyMap(), emptyMap(), 1000).top)
    }

    @Test fun tailnetAddresses() {
        assertTrue(isTailnetAddress("100.64.0.1"))
        assertTrue(isTailnetAddress("100.127.255.255"))
        assertFalse(isTailnetAddress("100.128.0.1"))
        assertFalse(isTailnetAddress("192.168.1.2"))
        assertFalse(isTailnetAddress("fd7a:115c:a1e0::1"))
    }

    @Test fun ageWords() {
        assertEquals("<1m", age(1000, 2000))
        assertEquals("5m", age(0 + 1, 300_001))
        assertEquals("2h", age(1, 7_200_001))
        assertEquals("", age(null))
    }
}
