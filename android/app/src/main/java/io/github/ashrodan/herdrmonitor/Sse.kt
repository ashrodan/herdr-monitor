package io.github.ashrodan.herdrmonitor

// A Server-Sent Events parser (the parts the server uses): "event:",
// "data:" (joined with newlines), comments (": ping") and the blank line
// that ends an event. Feed it lines; it calls onEvent for each event.
data class SseEvent(val event: String, val data: String)

class SseParser(private val onEvent: (SseEvent) -> Unit, private val onComment: (String) -> Unit = {}) {
    private var event: String? = null
    private val data = StringBuilder()
    private var hasData = false

    fun line(raw: String) {
        val line = raw.removeSuffix("\r")
        if (line.isEmpty()) { dispatch(); return }
        if (line.startsWith(":")) { onComment(line.substring(1).trimStart()); return }
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> event = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
    }

    private fun dispatch() {
        if (hasData) onEvent(SseEvent(event ?: "message", data.toString()))
        event = null
        data.setLength(0)
        hasData = false
    }
}

// Reconnect delays: 1 s, 2 s, 4 s ... up to 30 s. reset() after a good
// connection.
class Backoff(private val first: Long = 1000, private val max: Long = 30000) {
    private var next = first
    fun next(): Long = next.also { next = minOf(max, next * 2) }
    fun reset() { next = first }
}
