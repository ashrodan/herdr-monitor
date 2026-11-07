package io.github.ashrodan.herdrmonitor

import androidx.compose.runtime.Immutable

// Home as an inbox of agents (design brief, Phase 2). Pure: no Android
// types, so JVM tests run it. The screen gets ready-to-draw Sessions.

// A Working session with no output for this long shows a stale chip.
// Global, one constant (design review answer 5).
const val STALE_MS = 30 * 60 * 1000L

enum class Bucket { NeedsYou, Working, Ready, Idle }

// One status, one colour, one shape, everywhere.
enum class Status { Waiting, Failed, Working, Ready, Idle }

// What the app read from one agent's transcript, for its card.
@Immutable
data class Digest(
    val version: String?,
    val snippet: String?,
    val lastAtMs: Long?,
    val ask: Part.Ask?,
)

// An answer sent from a card: the card shows "Sent: <label>" and Working
// until the agent's next output.
data class SentMark(val label: String, val atMs: Long)

@Immutable
data class Session(
    val address: String,
    val name: String,
    val task: String?,
    val snippet: String?,
    val bucket: Bucket,
    val status: Status,
    val timeMs: Long?,
    val unread: Boolean,
    val staleForMs: Long?,
    val branch: String?,
    val pr: OverviewPr?,
    val ask: Part.Ask?,
    val sent: String?,
    val pinned: Boolean,
    val spaceId: String?,
    val spaceName: String?,
    val spaceColor: String?,
    val letter: String,
    val agent: String,
)

data class Counts(val all: Int, val needsYou: Int, val working: Int, val ready: Int, val idle: Int) {
    fun of(b: Bucket?) = when (b) { null -> all; Bucket.NeedsYou -> needsYou; Bucket.Working -> working; Bucket.Ready -> ready; Bucket.Idle -> idle }
}

fun counts(sessions: List<Session>) = Counts(
    sessions.size,
    sessions.count { it.bucket == Bucket.NeedsYou },
    sessions.count { it.bucket == Bucket.Working },
    sessions.count { it.bucket == Bucket.Ready },
    sessions.count { it.bucket == Bucket.Idle },
)

// "MNT-1 Android app · android app" -> "MNT-1 Android app": drop a
// " · suffix" that only repeats the name.
fun dedupName(name: String): String {
    val i = name.lastIndexOf(" · ")
    if (i <= 0) return name
    val head = name.substring(0, i)
    fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")
    val tail = norm(name.substring(i + 3))
    return if (tail.isNotEmpty() && norm(head).contains(tail)) head else name
}

// The agent's last words: the last two sentences of its last prose, with
// Markdown marks and code removed. Never a pane title or a tool name.
fun lastProse(messages: List<Message>): String? {
    val text = messages.lastOrNull { m -> m.role == "assistant" && m.parts.any { it is Part.Text && it.text.isNotBlank() } }
        ?.parts?.filterIsInstance<Part.Text>()?.lastOrNull { it.text.isNotBlank() }?.text ?: return null
    val plain = text
        .replace(Regex("```[\\s\\S]*?(```|$)"), " ")
        .replace(Regex("`([^`]*)`"), "$1")
        .replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("(?m)^\\s{0,3}(#{1,6}\\s+|[-*+]\\s+|\\d+[.)]\\s+|>\\s?|\\|)"), "")
        .replace(Regex("\\*\\*|__|(?<!\\w)[*_](?!\\s)|(?<!\\s)[*_](?!\\w)"), "")
        .replace(Regex("\\s+"), " ").trim()
    if (plain.isEmpty()) return null
    val sentences = Regex("[^.!?]+[.!?]+(\\s|$)|[^.!?]+$").findAll(plain).map { it.value.trim() }.filter { it.isNotEmpty() }.toList()
    val out = sentences.takeLast(2).joinToString(" ").ifEmpty { plain }
    return if (out.length > 220) "…" + out.takeLast(219) else out
}

fun digest(chat: Chat): Digest {
    val last = chat.messages.lastOrNull()
    return Digest(
        version = chat.version,
        snippet = lastProse(chat.messages),
        lastAtMs = last?.at?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() },
        ask = lastUnansweredAsk(chat.messages),
    )
}

fun buildSessions(
    terminals: Collection<Terminal>,
    spaces: List<Space>,
    prs: List<OverviewPr>,
    localSeen: Map<String, Long>,
    opened: Map<String, Long>,
    unreadSince: Long,
    digests: Map<String, Digest>,
    pinned: Set<String>,
    sent: Map<String, SentMark>,
    now: Long,
): List<Session> {
    val agents = terminals.filter { !it.finished && it.status != null }
    return agents.map { t ->
        val w = whereOf(t, spaces)
        val d = digests[t.address]
        val pr = w.space?.let { s -> prs.firstOrNull { it.space == s.id } }
        val state = t.stateOf(localSeen)
        val lastOutput = maxOf(d?.lastAtMs ?: 0L, t.changedMs ?: 0L).takeIf { it > 0 }
        val mark = sent[t.address]?.takeIf { m -> (lastOutput ?: 0L) <= m.atMs }
        val (bucket, status) = when {
            mark != null -> Bucket.Working to Status.Working
            state == "blocked" -> Bucket.NeedsYou to Status.Waiting
            state == "error" -> Bucket.NeedsYou to Status.Failed
            state == "working" -> Bucket.Working to Status.Working
            state == "done" -> Bucket.Ready to Status.Ready
            pr != null && !pr.pr.isDraft -> Bucket.Ready to Status.Ready
            else -> Bucket.Idle to Status.Idle
        }
        val ask = if (status == Status.Waiting) d?.ask else null
        val name = dedupName(displayName(t, spaces))
        val task = ask?.questions?.firstOrNull()?.let { q -> q.header.ifBlank { null } ?: q.question.lineSequence().firstOrNull() }
            ?: w.space?.branch ?: w.tabName?.takeIf { w.tabNamed } ?: shortPath(t.cwd).ifEmpty { null }
        val stale = if (status == Status.Working && mark == null && d?.lastAtMs != null && now - d.lastAtMs > STALE_MS) now - d.lastAtMs else null
        Session(
            address = t.address,
            name = name,
            task = task?.takeIf { it != name },
            snippet = d?.snippet ?: t.msg?.takeIf { it.isNotBlank() },
            bucket = bucket,
            status = status,
            timeMs = lastOutput,
            unread = (lastOutput ?: 0L) > (opened[t.address] ?: unreadSince),
            staleForMs = stale,
            branch = w.space?.branch,
            pr = pr,
            ask = ask,
            sent = mark?.label,
            pinned = t.address in pinned,
            spaceId = w.space?.id,
            spaceName = w.space?.name,
            spaceColor = w.space?.color,
            letter = (w.space?.name ?: name).trimStart('~', '.', ' ').take(1).uppercase().ifEmpty { "~" },
            agent = t.agent,
        )
    }.sortedWith(compareBy<Session>({ it.bucket.ordinal }, { !it.pinned }, { -(it.timeMs ?: 0L) }))
}

// The Ask Herdr pill filters on the phone: name, task, snippet, space,
// branch. It does not send anything anywhere.
fun matches(s: Session, query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    return listOfNotNull(s.name, s.task, s.snippet, s.spaceName, s.branch, s.agent, s.address).any { it.lowercase().contains(q) }
}

// "3h", "45m": for the stale chip.
fun duration(ms: Long): String {
    val m = ms / 60_000
    return when { m < 60 -> "${m}m"; m < 60 * 24 -> "${m / 60}h"; else -> "${m / (60 * 24)}d" }
}

// PRs waiting for a review: open, not a draft, review required.
fun awaitingReview(prs: List<OverviewPr>) = prs.count { !it.pr.isDraft && it.pr.reviewDecision == "REVIEW_REQUIRED" }
