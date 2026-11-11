package io.github.ashrodan.herdrmonitor

import org.junit.Assert.assertEquals
import org.junit.Test

class SseParserTest {
    private fun parse(vararg lines: String): Pair<List<SseEvent>, List<String>> {
        val events = mutableListOf<SseEvent>()
        val comments = mutableListOf<String>()
        val p = SseParser({ events += it }, { comments += it })
        lines.forEach(p::line)
        return events to comments
    }

    @Test fun eventAndData() {
        val (e, _) = parse("event: snapshot", "data: {\"connected\":true}", "")
        assertEquals(listOf(SseEvent("snapshot", "{\"connected\":true}")), e)
    }

    @Test fun multiLineDataJoinsWithNewline() {
        val (e, _) = parse("event: x", "data: a", "data: b", "")
        assertEquals("a\nb", e.single().data)
    }

    @Test fun defaultEventIsMessageAndNoSpaceAfterColon() {
        val (e, _) = parse("data:abc", "")
        assertEquals(SseEvent("message", "abc"), e.single())
    }

    @Test fun commentsAndCrlf() {
        val (e, c) = parse(": ping\r", "event: removed\r", "data: {\"address\":\"t1\"}\r", "\r")
        assertEquals(listOf("ping"), c)
        assertEquals(SseEvent("removed", "{\"address\":\"t1\"}"), e.single())
    }

    @Test fun blankLineWithoutDataSendsNothingAndResetsEvent() {
        val (e, _) = parse("event: lost", "", "data: 1", "")
        assertEquals(listOf(SseEvent("message", "1")), e)
    }

    @Test fun incompleteEventIsHeld() {
        val (e, _) = parse("event: agent", "data: {}")
        assertEquals(0, e.size)
    }

    @Test fun backoffDoublesToThirtySecondsAndResets() {
        val b = Backoff()
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L), (1..7).map { b.next() })
        b.reset()
        assertEquals(1000L, b.next())
    }
}
