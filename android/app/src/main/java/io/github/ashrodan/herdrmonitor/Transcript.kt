package io.github.ashrodan.herdrmonitor

// The session transcript, flattened for the screen: agent prose first, and
// each run of thinking and tool calls folded into one activity item. Pure,
// so it is unit tested.

sealed interface Item {
    val key: String
    // Agent prose. `who` is true on the first prose after anything else, so
    // the harness mark shows once per run of prose.
    data class Prose(override val key: String, val text: String, val who: Boolean) : Item
    data class User(override val key: String, val text: String) : Item
    data class System(override val key: String, val text: String) : Item
    data class Activity(override val key: String, val steps: List<Part>) : Item {
        val tools get() = steps.filterIsInstance<Part.Tool>()
        val failed get() = tools.count { it.result?.error == true }
    }
    data class Question(override val key: String, val ask: Part.Ask) : Item
}

private val HARNESS_NOTE = Regex("^\\[(Request interrupted|Request cancelled)[^\\]]*\\]$")

fun transcriptItems(messages: List<Message>): List<Item> {
    val out = mutableListOf<Item>()
    var steps = mutableListOf<Part>()
    var stepsKey = ""
    fun flush() {
        if (steps.isNotEmpty()) out.add(Item.Activity(stepsKey, steps))
        steps = mutableListOf()
    }
    messages.forEachIndexed { mi, m ->
        val base = m.id.ifEmpty { "m$mi" }
        when (m.role) {
            "user" -> {
                val text = m.parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text }
                // A harness note in the user turn ("[Request interrupted by
                // user]") is a system line, not your text.
                if (text.isNotBlank()) { flush(); out.add(if (HARNESS_NOTE.matches(text.trim())) Item.System(base, text.trim().removeSurrounding("[", "]")) else Item.User(base, text)) }
            }
            "system" -> {
                val text = m.parts.filterIsInstance<Part.Text>().joinToString(" ") { it.text }
                if (text.isNotBlank()) { flush(); out.add(Item.System(base, text)) }
            }
            else -> m.parts.forEachIndexed { pi, p ->
                val key = "$base.$pi"
                when (p) {
                    is Part.Thinking, is Part.Tool -> { if (steps.isEmpty()) stepsKey = "act.$key"; steps.add(p) }
                    is Part.Text -> if (p.text.isNotBlank()) {
                        flush()
                        out.add(Item.Prose(key, p.text, who = out.lastOrNull() !is Item.Prose))
                    }
                    is Part.Ask -> { flush(); out.add(Item.Question(key, p)) }
                }
            }
        }
    }
    flush()
    return out
}

// "6 bash · 1 read": tool names by first use, with counts. No durations:
// the transcript has no start and end times.
fun toolCounts(tools: List<Part.Tool>): String =
    tools.groupingBy { it.name }.eachCount().entries.joinToString(" · ") { (n, c) -> "$c $n" }

// The tool still running: the last tool with no result, when it is the
// last step of the transcript.
fun runningTool(items: List<Item>): Part.Tool? =
    (items.lastOrNull() as? Item.Activity)?.steps?.lastOrNull()?.let { it as? Part.Tool }?.takeIf { it.result == null }

// The short harness name and its one-letter mark.
fun harnessName(agent: String?): String = when (agent) {
    null, "", "?" -> "agent"
    "claude-code", "claude" -> "Claude"
    "codex" -> "Codex"
    else -> agent
}

fun harnessMark(agent: String?): String = when (agent) {
    "omp", "pi" -> "π"
    else -> harnessName(agent).take(1).uppercase()
}

private val TICKET = Regex("\\b[A-Z][A-Z0-9]+-\\d+\\b")
fun ticketOf(text: String?): String? = text?.let { TICKET.find(it)?.value }

// Elapsed time in the current state: "8:06", or "1h 05m" from an hour.
fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s < 3600) "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    else "${s / 3600}h ${((s % 3600) / 60).toString().padStart(2, '0')}m"
}
