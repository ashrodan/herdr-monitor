package io.github.ashrodan.herdrmonitor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import io.github.ashrodan.herdrmonitor.ui.HomeScreen
import io.github.ashrodan.herdrmonitor.ui.theme.HerdrTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

// Ask Herdr filters the sessions on the phone. An idle match must show
// (not stay folded under the Idle row) and open on a tap; no match says so.
class SearchUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun anIdleMatchShowsAndOpens() {
        val busy = Terminal("busy", name = "Busy agent", agent = "claude", status = "working", space = "sp", tab = "one")
        val idle = Terminal("quiet", name = "Quiet helper", agent = "omp", status = "idle", space = "sp", tab = "one")
        val space = Space("sp", "Search space", tabs = listOf(SpaceTab("one", "one", true, listOf(busy.address, idle.address))))
        val live = Live(link = Link.Online, terminals = listOf(busy, idle).associateBy { it.address }, spaces = listOf(space), prsLoaded = true)
        val went = mutableListOf<Route>()
        rule.setContent { HerdrTheme { HomeScreen(live, go = { went += it }) } }

        rule.onNodeWithTag("search-pill").performClick()
        rule.onNodeWithTag("search-field").performTextInput("quiet")
        rule.onNodeWithText("Quiet helper").assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals(listOf<Route>(Route.Agent("quiet")), went)

        rule.onNodeWithTag("search-field").performTextClearance()
        rule.onNodeWithTag("search-field").performTextInput("nothing like this")
        rule.onNodeWithText("No session matches the search.").assertIsDisplayed()
    }
}
