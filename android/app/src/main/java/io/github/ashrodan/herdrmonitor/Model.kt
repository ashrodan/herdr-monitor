package io.github.ashrodan.herdrmonitor

// Shapes the server sends (lib/hc.js summarize, lib/sidebar.js, lib/agents.js,
// lib/transcript.js, lib/pr.js), and the pure rules the web UI uses
// (web/src/model.ts). No Android types here, so unit tests can run them.

data class Terminal(
    val address: String,
    val name: String? = null,
    val agent: String = "?",
    val cwd: String? = null,
    val state: String = "live",          // live | finished
    val title: String? = null,
    val where: String? = null,
    val reported: Boolean = false,
    val status: String? = null,          // working | blocked | done | idle | error
    val kind: String? = null,
    val msg: String? = null,
    val exitCode: Int? = null,
    val parent: String? = null,
    val space: String? = null,
    val tab: String? = null,
    val changedMs: Long? = null,
    val seenMs: Long? = null,
) {
    val finished get() = state == "finished"
}

data class SpaceTab(val id: String, val name: String, val named: Boolean, val terminals: List<String>)

data class Space(
    val id: String,
    val name: String,
    val color: String? = null,
    val repo: String? = null,
    val branch: String? = null,
    val worktree: Boolean = false,
    val virtual: Boolean = false,
    val tabs: List<SpaceTab> = emptyList(),
)

data class Pr(
    val number: Int,
    val title: String,
    val url: String,
    val isDraft: Boolean,
    val headRefName: String,
    val reviewDecision: String?,
    val updatedAt: String?,
)

data class OverviewPr(
    val space: String,
    val spaceName: String,
    val color: String?,
    val branch: String?,
    val pr: Pr,
    val readiness: String?,
    val fail: Int,
    val pending: Int,
)

// One agent on the small stream (/api/agents/stream).
data class AgentInfo(
    val address: String,
    val name: String,
    val state: String,
    val kind: String?,
    val msg: String?,
    val changedMs: Long?,
    val seenMs: Long?,
)

val GROUPS = listOf("blocked" to "Blocked", "error" to "Error", "done" to "Done", "working" to "Working", "idle" to "Idle")
val ALERT_STATES = setOf("blocked", "done", "error")

// "Done" turns into "idle" once someone looked at the agent after it
// finished: on the Mac (seen_ms) or on this phone (localSeen).
fun effectiveState(state: String?, changedMs: Long?, seenMs: Long?, localSeen: Long? = null): String {
    val s = state ?: "idle"
    if (s == "done" && changedMs != null && maxOf(seenMs ?: 0, localSeen ?: 0) >= changedMs) return "idle"
    return s
}

fun Terminal.stateOf(localSeen: Map<String, Long> = emptyMap()) = effectiveState(status, changedMs, seenMs, localSeen[address])
fun AgentInfo.stateOf(localSeen: Map<String, Long> = emptyMap()) = effectiveState(state, changedMs, seenMs, localSeen[address])

data class Where(val space: Space?, val tabName: String?, val tabNamed: Boolean)

fun whereOf(t: Terminal, spaces: List<Space>): Where {
    for (s in spaces) {
        if (t.space != null && s.id != t.space) continue
        val tab = s.tabs.find { if (t.tab != null) it.id == t.tab else t.address in it.terminals }
        if (tab != null || (t.space != null && s.id == t.space)) return Where(s, tab?.name, tab?.named ?: false)
    }
    return Where(null, null, false)
}

// Its own name, else the space name (+ the tab when the tab has a name).
fun displayName(t: Terminal, spaces: List<Space>): String {
    t.name?.let { return it }
    val w = whereOf(t, spaces)
    if (w.space != null) return w.space.name + if (w.tabNamed) " · ${w.tabName}" else ""
    return t.where?.split(" / ")?.firstOrNull()?.takeIf { it.isNotBlank() } ?: t.agent
}

data class Group(val state: String, val label: String, val rows: List<Terminal>)

// Only terminals with a status are agents (as in Herdr).
fun groupAgents(terminals: Collection<Terminal>, localSeen: Map<String, Long> = emptyMap()): List<Group> {
    val agents = terminals.filter { !it.finished && it.status != null }
    return GROUPS.map { (state, label) ->
        Group(state, label, agents.filter { it.stateOf(localSeen) == state }.sortedByDescending { it.changedMs ?: 0 })
    }.filter { it.rows.isNotEmpty() }
}

// The most urgent state among a space's terminals, for its dot.
fun spaceState(s: Space, terminals: Map<String, Terminal>, localSeen: Map<String, Long> = emptyMap()): String? {
    val states = s.tabs.flatMap { it.terminals }.mapNotNull { terminals[it] }.filter { !it.finished }.map { it.stateOf(localSeen) }
    return listOf("blocked", "error", "working").find { it in states }
}

private val SHELLS = setOf("zsh", "bash", "fish", "sh", "nu", "pwsh")
fun Terminal.isAgent() = reported || agent !in SHELLS

fun age(ms: Long?, now: Long = System.currentTimeMillis()): String {
    if (ms == null || ms == 0L) return ""
    val s = maxOf(0L, (now - ms) / 1000)
    return when {
        s < 60 -> "<1m"
        s < 3600 -> "${s / 60}m"
        s < 86400 -> "${s / 3600}h"
        else -> "${s / 86400}d"
    }
}

fun shortPath(p: String?) = (p ?: "").replace(Regex("^/(Users|home)/[^/]+(?=/|$)"), "~")

// ---- Chat (GET /api/terminals/<addr>/chat) ----

data class Option(val label: String, val description: String)
data class Question(val header: String, val question: String, val multi: Boolean, val options: List<Option>)

sealed interface Part {
    data class Text(val text: String) : Part
    data class Thinking(val text: String) : Part
    data class Tool(val name: String, val summary: String, val result: String?) : Part
    data class Ask(val id: String, val questions: List<Question>, val answered: Boolean) : Part
}

data class Message(val id: String, val role: String, val at: String?, val parts: List<Part>)

data class Chat(
    val available: Boolean,
    val reason: String? = null,
    val version: String? = null,
    val truncated: Boolean = false,
    val messages: List<Message> = emptyList(),
)

// The question the agent waits on: the last message with an unanswered
// question part (web/src/components/ChatPane.tsx, lastAsk).
fun lastUnansweredAsk(messages: List<Message>): Part.Ask? =
    messages.asReversed().firstNotNullOfOrNull { m -> m.parts.firstOrNull { it is Part.Ask && !it.answered } as Part.Ask? }

// The shape the /answer route wants: {question, options (a count), multi}.
data class QuestionShape(val question: String, val options: Int, val multi: Boolean)
fun Part.Ask.shape() = questions.map { QuestionShape(it.question, it.options.size, it.multi) }

// One single-choice question: a tap answers it, so a notification can offer
// its options as buttons (at most `max`). Otherwise null: answer in the app.
fun Part.Ask.quickOptions(max: Int = 3): List<String>? {
    val q = questions.singleOrNull() ?: return null
    if (q.multi || q.options.isEmpty()) return null
    return q.options.take(max).map { it.label.ifBlank { "Option" } }
}

// ---- Alerts ----

// What to do with an agent's notification when its state changes.
sealed interface AlertAction {
    data class Post(val agent: AgentInfo, val state: String, val sound: Boolean) : AlertAction
    data class Cancel(val address: String) : AlertAction
}

// prev: the alert state now on screen for that agent (null: none).
// since: on a snapshot, alert only for changes after this time (the phone
// was not listening before), so a reconnect does not replay old alerts.
// agent null: it went away.
fun planAlert(address: String, prev: String?, agent: AgentInfo?, localSeen: Map<String, Long> = emptyMap(), since: Long? = null): AlertAction? {
    if (agent == null) return if (prev != null) AlertAction.Cancel(address) else null
    val now = agent.stateOf(localSeen)
    if (now !in ALERT_STATES) return if (prev != null) AlertAction.Cancel(address) else null
    if (prev == now) return null
    if (since != null && (agent.changedMs ?: 0) <= since) return null
    return AlertAction.Post(agent, now, sound = true)
}

fun alertTitle(name: String, state: String) = "$name · " + when (state) {
    "blocked" -> "needs you"
    "done" -> "done"
    "error" -> "error"
    else -> state
}

fun alertBody(a: AgentInfo, state: String) = a.msg?.takeIf { it.isNotBlank() } ?: when (state) {
    "blocked" -> "Waiting at a ${a.kind ?: "dialog"}"
    "done" -> "Finished its turn"
    "error" -> "Stopped with an error"
    else -> ""
}

// ---- Widget (like the Summary mod's bar and list) ----

data class WidgetRow(val address: String?, val url: String?, val label: String, val detail: String, val state: String)
data class WidgetData(
    val connected: Boolean,
    val waiting: Int,
    val done: Int,
    val working: Int,
    val prs: Int,
    val rows: List<WidgetRow>,
    val updatedMs: Long,
)

fun widgetData(connected: Boolean, agents: Collection<AgentInfo>, prs: List<OverviewPr>, localSeen: Map<String, Long>, now: Long, maxRows: Int = 8): WidgetData {
    val by = agents.groupBy { it.stateOf(localSeen) }
    val waiting = (by["blocked"].orEmpty() + by["error"].orEmpty()).sortedByDescending { it.changedMs ?: 0 }
    val done = by["done"].orEmpty().sortedByDescending { it.changedMs ?: 0 }
    val working = by["working"].orEmpty()
    val rows = waiting.map { WidgetRow(it.address, null, it.name, it.msg ?: alertBody(it, it.stateOf(localSeen)), it.stateOf(localSeen)) } +
        done.map { WidgetRow(it.address, null, it.name, "done ${age(it.changedMs, now)} ago", "done") } +
        prs.map { WidgetRow(null, it.pr.url, "#${it.pr.number} ${it.pr.title}", it.spaceName, if (it.fail > 0) "error" else if (it.pending > 0) "working" else "pr") }
    return WidgetData(connected, waiting.size, done.size, working.size, prs.size, rows.take(maxRows), now)
}

// Tailscale's address range, 100.64.0.0/10.
fun isTailnetAddress(ip: String): Boolean {
    val p = ip.split('.')
    if (p.size != 4) return false
    val a = p[0].toIntOrNull() ?: return false
    val b = p[1].toIntOrNull() ?: return false
    return a == 100 && b in 64..127
}
