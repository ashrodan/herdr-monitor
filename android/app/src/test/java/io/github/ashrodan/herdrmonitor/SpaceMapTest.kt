package io.github.ashrodan.herdrmonitor

import io.github.ashrodan.herdrmonitor.ui.harnessIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SpaceMapTest {
    private fun close(a: Float, b: Float) = kotlin.math.abs(a - b) < 1e-4f

    @Test fun panesFollowSplitsAndWeights() {
        // t1 on the left (60 %), t2 over t3 on the right.
        val tree = PaneSplit(true, listOf(PaneLeaf("t1", 0.6f), PaneSplit(false, listOf(PaneLeaf("t2"), PaneLeaf("t3")), 0.4f)))
        val r = paneRects(tree).associateBy { it.terminal }
        assert(close(r["t1"]!!.w, 0.6f) && close(r["t1"]!!.h, 1f) && close(r["t1"]!!.x, 0f))
        assert(close(r["t2"]!!.x, 0.6f) && close(r["t2"]!!.w, 0.4f) && close(r["t2"]!!.h, 0.5f) && close(r["t2"]!!.y, 0f))
        assert(close(r["t3"]!!.y, 0.5f) && close(r["t3"]!!.h, 0.5f))
        assertEquals(emptyList<PaneRect>(), paneRects(null))
    }

    @Test fun aTabWithNoTreeShowsItsTerminalsSideBySide() {
        val r = SpaceTab("tb", "1", false, listOf("a", "b")).panes()
        assertEquals(listOf("a", "b"), r.map { it.terminal })
        assert(close(r[1].x, 0.5f) && close(r[1].w, 0.5f))
        assertEquals(listOf(PaneRect("a", 0f, 0f, 1f, 1f)), SpaceTab("tb", "1", false, listOf("a")).panes())
    }

    private val spaces = listOf(
        Space("main", "app", repo = "/r", tabs = listOf(SpaceTab("tA", "1", false, listOf("t1")), SpaceTab("tB", "2", false, listOf("t2", "t3")))),
        Space("wt1", "fix-x", repo = "/r/.worktrees/fix-x", worktree = true, parent = "main", tabs = listOf(SpaceTab("tC", "1", false, listOf("t4")))),
        Space("other", "notes", repo = "/n"),
    )

    @Test fun placeFindsTheSpaceTabAndIndex() {
        val p = placeOf(Terminal("t3"), "t3", spaces)!!
        assertEquals("main", p.space.id)
        assertEquals("tB", p.tab.id)
        assertEquals(1, p.tabIndex)
        assertNull(placeOf(null, "t9", spaces))
    }

    @Test fun aWorktreeShowsWithItsMainSpace() {
        assertEquals(listOf("main", "wt1"), spaceFamily(spaces, "wt1").map { it.id })
        assertEquals(listOf("main", "wt1"), spaceFamily(spaces, "main").map { it.id })
        assertEquals(listOf("other"), spaceFamily(spaces, "other").map { it.id })
        assertEquals(emptyList<Space>(), spaceFamily(spaces, null))
    }

    @Test fun onlyWorktreesWithNoSpaceHereAreListedApart() {
        val all = listOf(
            Worktree("/r", "r", "main", "main"),
            Worktree("/r/.worktrees/fix-x", "fix-x", "fix-x", "wt1"),
            Worktree("/r/.worktrees/old", "old", "old", null),
        )
        assertEquals(listOf("old"), worktreesWithoutSpace(all, spaceFamily(spaces, "main")).map { it.name })
    }

    // Tab 1: one pane. Tab 2: a on the left (60 %), b over c on the right.
    // Tab 3: only an ended terminal. Tab 4: d over e.
    private val grid = Space("g", "grid", tabs = listOf(
        SpaceTab("g1", "1", false, listOf("z")),
        SpaceTab("g2", "2", false, listOf("a", "b", "c"),
            PaneSplit(true, listOf(PaneLeaf("a", 0.6f), PaneSplit(false, listOf(PaneLeaf("b"), PaneLeaf("c")), 0.4f)))),
        SpaceTab("g3", "3", false, listOf("dead")),
        SpaceTab("g4", "4", false, listOf("d", "e"), PaneSplit(false, listOf(PaneLeaf("d"), PaneLeaf("e")))),
    ))
    private val alive = { a: String -> a != "dead" }
    private fun from(a: String, m: Move, ok: (String) -> Boolean = alive) = neighbour(placeOf(null, a, listOf(grid)), a, m, ok)

    @Test fun theJoystickGoesToThePaneOnThatSide() {
        // From the wide pane: the pane that shares the most of the edge, then the upper one.
        assertEquals("b", from("a", Move.Right))
        assertEquals("a", from("b", Move.Left))
        assertEquals("a", from("c", Move.Left))
        assertEquals("c", from("b", Move.Down))
        assertEquals("b", from("c", Move.Up))
        // Up and down stay in the tab when the space is alone.
        assertNull(from("a", Move.Up))
        assertNull(from("b", Move.Up))
        assertNull(from("c", Move.Down))
    }

    @Test fun atTheTabsEdgeLeftAndRightGoToTheNextTab() {
        assertEquals("z", from("a", Move.Left))
        assertEquals("a", from("z", Move.Right))
        // Tab 3 has no terminal that runs: it is passed. From the upper
        // pane you land in the upper one, from the lower in the lower.
        assertEquals("d", from("b", Move.Right))
        assertEquals("e", from("c", Move.Right))
        // Back from tab 4: the pane on the near (right) edge at that height.
        assertEquals("b", from("d", Move.Left))
        assertEquals("c", from("e", Move.Left))
        // The first and the last tab: no more.
        assertNull(from("z", Move.Left))
        assertNull(from("d", Move.Right))
    }

    @Test fun aPaneThatEndedIsPassed() {
        // b ended: right of a is c.
        assertEquals("c", from("a", Move.Right) { it != "b" && it != "dead" })
        assertNull(from("c", Move.Up) { it != "b" && it != "dead" })
        // A terminal in no tab has no neighbours there.
        assertNull(neighbour(placeOf(null, "x", listOf(grid)), "x", Move.Right, alive))
    }

    // As on the map: the worktrees of the repo are under their main space.
    @Test fun atTheTabsTopOrBottomTheMoveGoesToTheSpaceAboveOrBelow() {
        val wt1 = Space("w1", "fix", worktree = true, parent = "g", tabs = listOf(SpaceTab("w1a", "1", false, listOf("dead"))))
        val wt2 = Space("w2", "feat", worktree = true, parent = "g", tabs = listOf(
            SpaceTab("w2a", "1", false, listOf("p")),
            SpaceTab("w2b", "2", false, listOf("q", "r"), PaneSplit(true, listOf(PaneLeaf("q"), PaneLeaf("r")))),
        ))
        val all = listOf(grid, wt1, wt2, Space("other", "notes", tabs = listOf(SpaceTab("o", "1", false, listOf("n")))))
        fun go(a: String, m: Move) = neighbour(placeOf(null, a, all), a, m, alive, all)
        // From tab 2 of the main space: wt1 has nothing that runs, so wt2,
        // its tab 2, the pane under where you were.
        assertEquals("q", go("a", Move.Down))
        assertEquals("r", go("c", Move.Down))
        // From tab 4: wt2 has no tab 4, so its nearest tab.
        assertEquals("q", go("e", Move.Down))
        // Up from the worktree: the main space, the same tab, its lower edge.
        assertEquals("z", go("p", Move.Up))
        assertEquals("a", go("q", Move.Up))
        assertEquals("c", go("r", Move.Up))
        // No space above the first or below the last of the family, and
        // another repo is never a neighbour.
        assertNull(go("a", Move.Up))
        assertNull(go("p", Move.Down))
        assertNull(go("n", Move.Down))
    }

    @Test fun aSessionInNoTabGoesThroughTheSwipeList() {
        val pages = listOf("m/t1", "m/t2", "m/t3")
        assertEquals("m/t3", neighbourInList(pages, "m/t2", Move.Right))
        assertEquals("m/t1", neighbourInList(pages, "m/t2", Move.Left))
        assertNull(neighbourInList(pages, "m/t1", Move.Left))
        assertNull(neighbourInList(pages, "m/t2", Move.Up))
        assertNull(neighbourInList(pages, "gone", Move.Right))
    }

    @Test fun aPushPastTheThresholdHasADirection() {
        fun m(x: Float, y: Float) = io.github.ashrodan.herdrmonitor.ui.moveOf(androidx.compose.ui.geometry.Offset(x, y), 10f)
        assertNull(m(4f, -5f))
        assertEquals(Move.Right, m(12f, 3f))
        assertEquals(Move.Left, m(-12f, -11f))
        assertEquals(Move.Up, m(2f, -30f))
        assertEquals(Move.Down, m(-2f, 30f))
    }

    @Test fun eachHarnessHasItsMark() {
        for (a in listOf("claude-code", "claude", "codex", "omp", "pi")) assertNotNull(a, harnessIcon(a))
        assertNull(harnessIcon("zsh"))
        assertNull(harnessIcon(null))
    }
}
