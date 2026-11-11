package io.github.ashrodan.herdrmonitor

import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import io.github.ashrodan.herdrmonitor.ui.SpaceMapSheet
import io.github.ashrodan.herdrmonitor.ui.TabCard
import io.github.ashrodan.herdrmonitor.ui.theme.HerdrTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

// A tab of the space map, with fake terminals: your pane says so, and a tap
// on another pane opens that terminal.
class SpaceMapUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun aTapOnAPaneOpensItsTerminal() {
        val tab = SpaceTab("tb", "work", true, listOf("t1", "t2"), PaneSplit(true, listOf(PaneLeaf("t1"), PaneLeaf("t2"))))
        val live = Live(terminals = mapOf(
            "t1" to Terminal("t1", name = "tests", agent = "claude-code", status = "working"),
            "t2" to Terminal("t2", name = "review", agent = "codex", status = "blocked"),
        ), spaces = listOf(Space("sp", "app", tabs = listOf(tab))))
        val opened = mutableListOf<String>()
        rule.setContent { HerdrTheme { TabCard(tab, live, "t1", emptyMap()) { opened += it } } }
        rule.onNodeWithTag("pane-t1").assertContentDescriptionContains("you are here", substring = true)
        rule.onNodeWithTag("pane-t2").performClick()
        assertEquals(listOf("t2"), opened)
    }

    @Test fun aTapOnTheTabNameOpensItsFirstSession() {
        val tab = SpaceTab("tb", "work", true, listOf("gone", "t2"))
        val live = Live(terminals = mapOf("t2" to Terminal("t2", name = "review", agent = "codex", status = "idle")))
        val opened = mutableListOf<String>()
        rule.setContent { HerdrTheme { TabCard(tab, live, "t9", emptyMap()) { opened += it } } }
        rule.onNodeWithText("Tab work").performClick()
        assertEquals(listOf("t2"), opened) // "gone" no longer runs
    }

    // The bug from the phone: a tap on a space in the map did nothing.
    @Test fun aTapOnASpaceOpensIt() {
        val here = Space("sp1", "herdr-monitor", tabs = listOf(SpaceTab("tb1", "1", false, listOf("t1"))))
        val wt = Space("sp2", "MNT-1", worktree = true, parent = "sp1", tabs = listOf(SpaceTab("tb2", "1", false, listOf("t2"))))
        val live = Live(terminals = mapOf(
            "t1" to Terminal("t1", agent = "claude-code", status = "idle", space = "sp1"),
            "t2" to Terminal("t2", agent = "omp", status = "idle", space = "sp2"),
        ), spaces = listOf(here, wt))
        val spaces = mutableListOf<String>()
        var closed = false
        rule.setContent { HerdrTheme { SpaceMapSheet(live, "t1", onDismiss = { closed = true }, onOpen = {}, onSpace = { spaces += it }) } }
        rule.onNodeWithTag("space-line-sp2").performClick()
        rule.waitForIdle()
        assertEquals(listOf("sp2"), spaces)
        assertEquals(true, closed)
    }
}
