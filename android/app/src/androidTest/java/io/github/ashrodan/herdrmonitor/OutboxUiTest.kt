package io.github.ashrodan.herdrmonitor

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ashrodan.herdrmonitor.ui.Composer
import io.github.ashrodan.herdrmonitor.ui.QueuedReplies
import io.github.ashrodan.herdrmonitor.ui.ReplyPort
import io.github.ashrodan.herdrmonitor.ui.theme.HerdrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

// The composer and the queued bubbles, with a fake server port. No network,
// no real agent.
class OutboxUiTest {
    @get:Rule val rule = createComposeRule()

    private class Mem : OutboxStorage {
        var json: String? = null
        override fun load() = json
        override fun save(json: String) { this.json = json }
    }

    private class FailingPort : ReplyPort {
        var sends = 0
        override suspend fun send(address: String, text: String, force: Boolean) { sends++; error("HTTP 502") }
        override suspend fun keys(address: String, keys: List<String>) = error("HTTP 502")
        override suspend fun interrupt(address: String) = error("HTTP 502")
        override suspend fun upload(address: String, bytes: ByteArray, type: String): String = error("HTTP 502")
    }

    private fun show(outbox: Outbox, port: ReplyPort, online: Boolean) {
        rule.setContent {
            HerdrTheme {
                val items by outbox.items.collectAsStateWithLifecycle()
                Column {
                    QueuedReplies(items.filter { it.address == "t1" }) { outbox.remove(it.id) }
                    Composer(port, outbox, "t1", online = online, blocked = false, kind = null, working = false)
                }
            }
        }
    }

    @Test fun aQueuedReplyShowsAsABubbleMarkedQueued() {
        val outbox = Outbox(Mem())
        show(outbox, FailingPort(), online = false)
        rule.onNodeWithTag("composer-field").performTextInput("ship it")
        rule.onNodeWithTag("composer-send").performClick()
        val item = outbox.items.value.single()
        assertEquals("ship it", item.text)
        rule.onNodeWithTag("queued-${item.id}").assertTextContains("Queued", substring = true)
        rule.onNodeWithTag("queued-${item.id}").assertTextContains("ship it", substring = true)
    }

    @Test fun afterAFailedSendTheFieldIsEmptyAndSendWorksForNewText() {
        val outbox = Outbox(Mem())
        val port = FailingPort()
        show(outbox, port, online = true)
        rule.onNodeWithTag("composer-field").performTextInput("first")
        rule.onNodeWithTag("composer-send").performClick()
        rule.waitUntil(5_000) { outbox.items.value.isNotEmpty() }
        assertEquals(1, port.sends)
        assertEquals("HTTP 502", outbox.items.value.single().lastError)
        rule.onNodeWithTag("composer-field").assertTextContains("", substring = false)
        rule.onNodeWithTag("composer-send").assertIsNotEnabled()
        rule.onNodeWithTag("composer-field").performTextInput("second")
        rule.onNodeWithTag("composer-send").assertIsEnabled()
        rule.onNodeWithTag("composer-send").performClick()
        rule.waitUntil(5_000) { outbox.items.value.size == 2 }
        assertEquals(listOf("first", "second"), outbox.items.value.map { it.text })
    }

    @Test fun deleteRemovesThatQueuedBubble() {
        val outbox = Outbox(Mem())
        val keep = outbox.add("", "t1", "keep me", false)
        val drop = outbox.add("", "t1", "drop me", false)
        show(outbox, FailingPort(), online = false)
        rule.onAllNodesWithText("drop me").assertCountEquals(1)
        rule.onNodeWithTag("delete-${drop.id}").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText("drop me").assertCountEquals(0)
        rule.onAllNodesWithText("keep me").assertCountEquals(1)
        assertTrue(outbox.items.value.single().id == keep.id)
    }
}
