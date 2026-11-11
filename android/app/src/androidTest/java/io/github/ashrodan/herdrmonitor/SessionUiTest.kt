package io.github.ashrodan.herdrmonitor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import io.github.ashrodan.herdrmonitor.ui.ActivityCard
import io.github.ashrodan.herdrmonitor.ui.Composer
import io.github.ashrodan.herdrmonitor.ui.ReplyPort
import io.github.ashrodan.herdrmonitor.ui.SpaceScreen
import io.github.ashrodan.herdrmonitor.ui.components.QuestionBlock
import io.github.ashrodan.herdrmonitor.ui.theme.HerdrTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

// The brief's UI tests: answering a question, Stop/Send switching, and
// activity group expand. A fake port: no network, no real agent.
class SessionUiTest {
    @get:Rule val rule = createComposeRule()

    private class Mem : OutboxStorage {
        var json: String? = null
        override fun load() = json
        override fun save(json: String) { this.json = json }
    }

    private class FakePort : ReplyPort {
        var interrupts = 0
        val sent = mutableListOf<String>()
        override suspend fun send(address: String, text: String, force: Boolean) { sent += text }
        override suspend fun keys(address: String, keys: List<String>) {}
        override suspend fun interrupt(address: String) { interrupts++ }
        override suspend fun upload(address: String, bytes: ByteArray, type: String): String = "/tmp/x.png"
    }

    @Test fun stopWhileRunningWithAnEmptyFieldElseSend() {
        val port = FakePort()
        rule.setContent { HerdrTheme { Composer(port, Outbox(Mem()), "t1", online = true, blocked = false, kind = null, working = true, harness = "omp") } }
        rule.onNodeWithText("Steer omp…").assertIsDisplayed()
        // Running, field empty: a Stop button.
        rule.onNodeWithTag("composer-stop").performClick()
        rule.waitForIdle()
        assertEquals(1, port.interrupts)
        // Text in the field: the same place is Send.
        rule.onNodeWithTag("composer-field").performTextInput("also do dark mode")
        rule.onNodeWithTag("composer-send").performClick()
        rule.waitUntil(3000) { port.sent.isNotEmpty() }
        assertEquals(listOf("also do dark mode"), port.sent)
        // Sent: the field is empty again, so Stop comes back.
        rule.onNodeWithTag("composer-stop").assertIsDisplayed()
        rule.onNodeWithTag("composer-field").performTextInput("x")
        rule.onNodeWithTag("composer-send").assertIsDisplayed()
        rule.onNodeWithTag("composer-field").performTextClearance()
        rule.onNodeWithTag("composer-stop").assertIsDisplayed()
    }

    @Test fun spaceSwipesBetweenAgentChatsAndKeepsToolsContextual() {
        val first = Terminal("first", name = "First agent", agent = "claude", status = "working", space = "space", tab = "one")
        val second = Terminal("second", name = "Second agent", agent = "omp", status = "idle", space = "space", tab = "two")
        val server = Terminal("server", name = "Preview", agent = "node", space = "space", tab = "one",
            listen = Listen("http", 3000, null))
        val shell = Terminal("shell", agent = "zsh", space = "space", tab = "one")
        val space = Space("space", "MNT-1", tabs = listOf(
            SpaceTab("one", "one", true, listOf(first.address, server.address, shell.address)),
            SpaceTab("two", "two", true, listOf(second.address)),
        ))
        val pr = OverviewPr(space.id, space.name, null, "feature",
            Pr(12, "Modern UX", "https://example.test/12", false, "feature", null, null), "ready", 0, 0)
        val live = Live(link = Link.Offline, terminals = listOf(first, second, server, shell).associateBy { it.address },
            spaces = listOf(space), prs = listOf(pr), prsLoaded = true)

        rule.setContent { HerdrTheme { SpaceScreen(live, space.id, go = {}, back = {}) } }

        rule.onNodeWithText("First agent").assertIsDisplayed()
        rule.onNodeWithTag("session-pill-pr-first").assertIsDisplayed()
        rule.onNodeWithTag("session-pill-commits-first").assertIsDisplayed()
        rule.onNodeWithTag("session-pill-status-first").assertIsDisplayed()
        rule.onNodeWithTag("session-pill-server-first-server").assertExists()
        rule.onNodeWithText("Terminal").assertDoesNotExist()
        rule.onNodeWithText("Changes").assertDoesNotExist()

        rule.onNodeWithTag("space-session-pager").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        rule.onNodeWithText("Second agent").assertIsDisplayed()
        rule.onNodeWithContentDescription("Session 2 of 2. Swipe to change.").assertIsDisplayed()

        rule.onNodeWithTag("session-options-second").performClick()
        rule.onNodeWithText("Raw terminal").assertIsDisplayed()
        rule.onNodeWithText("Share").assertIsDisplayed()
    }

    @Test fun anActivityGroupOpensToItsSteps() {
        val act = Item.Activity("act.1", listOf(
            Part.Tool("bash", "adb devices -l", ToolResult("ok", false, false)),
            Part.Tool("bash", "./gradlew assembleDebug", ToolResult("BUILD FAILED", true, false)),
            Part.Tool("read", "docs/ui.md", ToolResult("", false, false)),
        ))
        rule.setContent { HerdrTheme { ActivityCard(act) } }
        rule.onNodeWithText("2 bash · 1 read").assertIsDisplayed()
        rule.onNodeWithText("· 1 failed", substring = true).assertIsDisplayed()
        rule.onNodeWithText("adb devices -l", substring = true).assertDoesNotExist()
        rule.onNodeWithTag("activity-act.1").performClick()
        rule.onNodeWithText("adb devices -l", substring = true).assertIsDisplayed()
        rule.onNodeWithText("docs/ui.md", substring = true).assertIsDisplayed()
    }

    @Test fun tappingAnAnswerSendsThatOption() {
        val ask = Part.Ask("q1", listOf(Question("Colours", "Which accent?", false, listOf(Option("Teal", ""), Option("Amber", ""), Option("Red", "")))), false)
        var answered: Pair<Int, String>? = null
        rule.setContent { HerdrTheme { QuestionBlock(ask, onAnswer = { j, l -> answered = j to l }, onReply = {}) } }
        rule.onNodeWithText("Which accent?").assertIsDisplayed()
        rule.onNodeWithText("Amber").performClick()
        assertEquals(1 to "Amber", answered)
    }
}
