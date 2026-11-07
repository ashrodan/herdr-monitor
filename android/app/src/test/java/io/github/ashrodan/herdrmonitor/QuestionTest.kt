package io.github.ashrodan.herdrmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class QuestionTest {
    private fun q(text: String, n: Int, multi: Boolean = false) = Question("", text, multi, (1..n).map { Option("opt$it", "") })
    private fun ask(id: String, answered: Boolean, vararg qs: Question) = Part.Ask(id, qs.toList(), answered)
    private fun msg(id: String, vararg parts: Part) = Message(id, "assistant", null, parts.toList())

    @Test fun lastUnansweredAskSkipsAnsweredAndTakesTheLatest() {
        val old = ask("a", false, q("Old?", 2))
        val done = ask("b", true, q("Done?", 2))
        val latest = ask("c", false, q("Now?", 3))
        val messages = listOf(msg("1", old), msg("2", Part.Text("hi"), done), msg("3", Part.Tool("Bash", "ls", null), latest), msg("4", Part.Text("after")))
        assertSame(latest, lastUnansweredAsk(messages))
        assertNull(lastUnansweredAsk(listOf(msg("1", done))))
        assertNull(lastUnansweredAsk(emptyList()))
    }

    @Test fun quickOptionsOnlyForOneSingleChoiceQuestion() {
        assertEquals(listOf("opt1", "opt2", "opt3"), ask("x", false, q("Pick", 5)).quickOptions(3))
        assertEquals(listOf("opt1", "opt2"), ask("x", false, q("Pick", 2)).quickOptions(3))
        assertNull(ask("x", false, q("Pick any", 3, multi = true)).quickOptions())
        assertNull(ask("x", false, q("A", 2), q("B", 2)).quickOptions())
        assertNull(ask("x", false, q("None", 0)).quickOptions())
    }

    @Test fun blankLabelsGetAName() {
        val a = Part.Ask("x", listOf(Question("", "Q", false, listOf(Option("", "")))), false)
        assertEquals(listOf("Option"), a.quickOptions())
    }

    @Test fun shapeIsWhatTheAnswerRouteWants() {
        assertEquals(listOf(QuestionShape("A", 2, false), QuestionShape("B", 4, true)),
            ask("x", false, q("A", 2), q("B", 4, multi = true)).shape())
    }
}
