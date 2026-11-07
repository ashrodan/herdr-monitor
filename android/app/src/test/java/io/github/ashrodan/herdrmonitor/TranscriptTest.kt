package io.github.ashrodan.herdrmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTest {
    private fun tool(name: String, ok: Boolean? = true) =
        Part.Tool(name, "$name x", ok?.let { ToolResult("out", error = !it, truncated = false) })

    @Test fun toolsAndThinkingFoldBetweenProse() {
        val items = transcriptItems(listOf(
            Message("1", "assistant", null, listOf(Part.Text("I'll capture it."), Part.Thinking("need id"), tool("bash"), tool("bash"))),
            // The run goes on in the next assistant message.
            Message("2", "assistant", null, listOf(tool("read"), Part.Text("Done."), Part.Text("Next."))),
        ))
        assertEquals(listOf(Item.Prose::class, Item.Activity::class, Item.Prose::class, Item.Prose::class), items.map { it::class })
        val act = items[1] as Item.Activity
        assertEquals(4, act.steps.size)
        assertEquals("2 bash · 1 read", toolCounts(act.tools))
        // The harness mark shows once per run of prose.
        assertEquals(listOf(true, true, false), items.filterIsInstance<Item.Prose>().map { it.who })
    }

    @Test fun userAndSystemBreakTheFold() {
        val items = transcriptItems(listOf(
            Message("1", "assistant", null, listOf(tool("bash"))),
            Message("2", "system", null, listOf(Part.Text("Background job bg_9 completed"))),
            Message("3", "user", null, listOf(Part.Text("also do dark mode"))),
            Message("4", "assistant", null, listOf(tool("grep"), tool("write", ok = false))),
        ))
        assertEquals(listOf(Item.Activity::class, Item.System::class, Item.User::class, Item.Activity::class), items.map { it::class })
        assertEquals(1, (items[3] as Item.Activity).failed)
        // Keys are unique, so the list can use them.
        assertEquals(items.size, items.map { it.key }.toSet().size)
    }

    @Test fun theRunningToolIsTheLastStepWithNoResult() {
        val running = transcriptItems(listOf(Message("1", "assistant", null, listOf(tool("bash"), tool("bash", ok = null)))))
        assertEquals("bash", runningTool(running)?.name)
        val after = transcriptItems(listOf(Message("1", "assistant", null, listOf(tool("bash", ok = null), Part.Text("ok")))))
        assertNull(runningTool(after))
    }

    @Test fun headerHelpers() {
        assertEquals("π", harnessMark("omp"))
        assertEquals("Claude", harnessName("claude-code"))
        assertEquals("C", harnessMark("claude-code"))
        assertEquals("MNT-1", ticketOf("MNT-1 Android app"))
        assertNull(ticketOf("herdr-monitor · worker"))
        assertEquals("8:06", clock(486_000))
        assertEquals("1h 05m", clock(3_900_000))
        assertTrue(transcriptItems(emptyList()).isEmpty())
    }
}
