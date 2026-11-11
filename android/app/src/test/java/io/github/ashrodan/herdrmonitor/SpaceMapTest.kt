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

    @Test fun eachHarnessHasItsMark() {
        for (a in listOf("claude-code", "claude", "codex", "omp", "pi")) assertNotNull(a, harnessIcon(a))
        assertNull(harnessIcon("zsh"))
        assertNull(harnessIcon(null))
    }
}
