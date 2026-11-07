package io.github.ashrodan.herdrmonitor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

// A reply typed while the link was down, or whose POST /send failed. It is
// sent later, in order. Interrupts, key presses and image bytes never queue:
// they are live.
data class OutItem(
    val id: String,
    // The server the reply was typed for. A flush sends it only there: the
    // same address on another server is another agent.
    val server: String,
    val address: String,
    val text: String,
    // True when the agent was blocked at a dialog when the reply was typed.
    // The send uses force only if the agent is still blocked then.
    val force: Boolean,
    val createdMs: Long,
    val attempts: Int = 0,
    val lastError: String? = null,
    // WAITING goes on the next flush. FAILED (the server refused it, or
    // MAX_ATTEMPTS failed) and UNCONFIRMED (no answer after the send, so it
    // may have arrived) never go again by themselves: the user sends them
    // again or deletes them. Neither holds back the items after it.
    val state: String = OutState.WAITING,
)

object OutState {
    const val WAITING = "waiting"
    const val FAILED = "failed"
    const val UNCONFIRMED = "unconfirmed"
}

// A send that fails for a reason that can pass (link, 5xx) is tried this many
// times; then the item is FAILED, so it cannot block its address for ever.
const val MAX_ATTEMPTS = 5

enum class Failure {
    // The server answered 4xx (no such terminal, bad text, busy): sending the
    // same thing again gives the same answer.
    Refused,
    // The body went out and no answer came: the text may be in the chat.
    MaybeSent,
    // Not sent (no link, a 5xx, a timeout before the send): try again later.
    Retry,
}

fun failureOf(e: Throwable): Failure = when {
    e is MaybeSentException -> Failure.MaybeSent
    e is HttpError && e.code in 400..499 && e.code != 408 && e.code != 429 -> Failure.Refused
    else -> Failure.Retry
}

// Where the queue is kept between runs (SharedPreferences in the app).
interface OutboxStorage {
    fun load(): String?
    fun save(json: String)
}

// The outbox rules, with no Android types, so JVM tests run them.
//
// Each server and address has its own list, oldest first. A flush sends one
// waiting item at a time. A failure that can pass keeps that item, records the
// error, and stops that address's list (the next item waits), up to
// MAX_ATTEMPTS. A refusal or an unconfirmed send marks the item and goes on
// to the next. Other addresses still flush. A second
// flush while one runs does nothing, so no item is sent twice.
class Outbox(
    private val storage: OutboxStorage,
    // Items saved before items had a server belong to this one (the server
    // in use when the app starts).
    legacyServer: String = "",
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val _items = MutableStateFlow(OutboxCodec.decode(storage.load(), legacyServer))
    val items: StateFlow<List<OutItem>> = _items.asStateFlow()
    private val flushing = AtomicBoolean(false)

    fun forAddress(server: String, address: String): List<OutItem> = _items.value.filter { it.server == server && it.address == address }

    fun add(server: String, address: String, text: String, force: Boolean, error: String? = null): OutItem {
        val item = OutItem(UUID.randomUUID().toString(), server, address, text, force, clock(), lastError = error)
        update { it + item }
        return item
    }

    fun remove(id: String) = update { list -> list.filterNot { it.id == id } }

    // The user sends a failed or unconfirmed item again.
    fun retry(id: String) = update { list ->
        list.map { if (it.id == id) it.copy(state = OutState.WAITING, attempts = 0) else it }
    }

    // Drop items the transcript already shows: a user message with the same
    // text, written after the item was queued (the web outbox's rule).
    fun dropDelivered(server: String, address: String, userMessages: List<Pair<String, Long?>>) = update { list ->
        list.filterNot { item ->
            item.server == server && item.address == address && userMessages.any { (text, at) ->
                at != null && at > item.createdMs && text.trim() == item.text.trim()
            }
        }
    }

    // Send what waits for this server; items of other servers stay. send(item,
    // force) throws on failure. isBlocked tells whether an agent is at a
    // dialog now. Returns the number of items sent.
    suspend fun flush(server: String, isBlocked: (String) -> Boolean, send: suspend (OutItem, Boolean) -> Unit): Int {
        if (!flushing.compareAndSet(false, true)) return 0
        var sent = 0
        try {
            fun waiting(it: OutItem) = it.server == server && it.state == OutState.WAITING
            val addresses = _items.value.filter(::waiting).map { it.address }.distinct()
            for (address in addresses) {
                while (true) {
                    val head = _items.value.firstOrNull { waiting(it) && it.address == address } ?: break
                    try {
                        send(head, head.force && isBlocked(address))
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        val why = e.message ?: e.javaClass.simpleName
                        val attempts = head.attempts + 1
                        val state = when (failureOf(e)) {
                            Failure.Refused -> OutState.FAILED
                            Failure.MaybeSent -> OutState.UNCONFIRMED
                            Failure.Retry -> if (attempts >= MAX_ATTEMPTS) OutState.FAILED else OutState.WAITING
                        }
                        update { list -> list.map { if (it.id == head.id) it.copy(attempts = attempts, lastError = why, state = state) else it } }
                        if (state == OutState.WAITING) break
                        continue
                    }
                    remove(head.id)
                    sent++
                }
            }
        } finally {
            flushing.set(false)
        }
        return sent
    }

    private fun update(f: (List<OutItem>) -> List<OutItem>) {
        synchronized(lock) {
            val next = f(_items.value)
            if (next == _items.value) return
            _items.value = next
            storage.save(OutboxCodec.encode(next))
        }
    }
}

// The user's messages in a transcript, as (text, ms), for dropDelivered.
fun userTexts(messages: List<Message>): List<Pair<String, Long?>> = messages.filter { it.role == "user" }.map { m ->
    m.parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text } to
        m.at?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
}

// JSON for the outbox: a list of flat objects of strings, numbers, booleans
// and null. org.json is a stub in JVM tests, so this small codec does it.
object OutboxCodec {
    fun encode(items: List<OutItem>): String = items.joinToString(",", "[", "]") { i ->
        "{\"id\":${str(i.id)},\"server\":${str(i.server)},\"address\":${str(i.address)},\"text\":${str(i.text)},\"force\":${i.force}," +
            "\"createdMs\":${i.createdMs},\"attempts\":${i.attempts},\"lastError\":${i.lastError?.let(::str) ?: "null"},\"state\":${str(i.state)}}"
    }

    fun decode(json: String?, legacyServer: String = ""): List<OutItem> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val p = Parser(json)
            (p.value() as? List<*>).orEmpty().mapNotNull { o ->
                val m = o as? Map<*, *> ?: return@mapNotNull null
                OutItem(
                    id = m["id"] as? String ?: return@mapNotNull null,
                    server = m["server"] as? String ?: legacyServer,
                    address = m["address"] as? String ?: return@mapNotNull null,
                    text = m["text"] as? String ?: return@mapNotNull null,
                    force = m["force"] as? Boolean ?: false,
                    createdMs = (m["createdMs"] as? Number)?.toLong() ?: 0L,
                    attempts = (m["attempts"] as? Number)?.toInt() ?: 0,
                    lastError = m["lastError"] as? String,
                    state = m["state"] as? String ?: OutState.WAITING,
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun str(s: String): String {
        val b = StringBuilder("\"")
        for (c in s) when {
            c == '"' -> b.append("\\\"")
            c == '\\' -> b.append("\\\\")
            c == '\n' -> b.append("\\n")
            c == '\r' -> b.append("\\r")
            c == '\t' -> b.append("\\t")
            c < ' ' -> b.append(String.format("\\u%04x", c.code))
            else -> b.append(c)
        }
        return b.append('"').toString()
    }

    private class Parser(val s: String) {
        var i = 0
        fun value(): Any? {
            ws()
            return when (val c = s[i]) {
                '[' -> array()
                '{' -> obj()
                '"' -> string()
                't' -> { expectWord("true"); true }
                'f' -> { expectWord("false"); false }
                'n' -> { expectWord("null"); null }
                else -> if (c == '-' || c.isDigit()) number() else error("bad json at $i")
            }
        }
        fun array(): List<Any?> {
            expect('[')
            val l = mutableListOf<Any?>()
            ws()
            if (s[i] == ']') { i++; return l }
            do { l.add(value()); ws() } while (s[i++] == ',')
            if (s[i - 1] != ']') error("expected ]")
            return l
        }
        fun obj(): Map<String, Any?> {
            expect('{')
            val m = mutableMapOf<String, Any?>()
            ws()
            if (s[i] == '}') { i++; return m }
            do { ws(); val k = string(); ws(); expect(':'); m[k] = value(); ws() } while (s[i++] == ',')
            if (s[i - 1] != '}') error("expected }")
            return m
        }
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun expect(c: Char) { if (s[i++] != c) error("expected $c") }
        fun expectWord(w: String) { if (!s.startsWith(w, i)) error("expected $w"); i += w.length }
        fun number(): Number {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "-+.eE")) i++
            val t = s.substring(start, i)
            return t.toLongOrNull() ?: t.toDouble()
        }
        fun string(): String {
            expect('"')
            val b = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> when (val e = s[i++]) {
                        'n' -> b.append('\n'); 'r' -> b.append('\r'); 't' -> b.append('\t')
                        'b' -> b.append('\b'); 'f' -> b.append('\u000c')
                        'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> b.append(e)
                    }
                    else -> b.append(c)
                }
            }
        }
    }
}
