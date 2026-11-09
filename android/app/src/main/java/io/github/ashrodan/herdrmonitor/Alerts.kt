package io.github.ashrodan.herdrmonitor

// Pure logic for notification V2: the health of the alert link, the
// overview of active agents, the context line and the action plan. No
// Android types, so all of it is unit tested.

// ---- Health of the alert link ----

sealed interface Health {
    data object Off : Health
    data object Connecting : Health
    data class Connected(val host: String, val lastEventMs: Long) : Health
    // The stream route answers 404: the server runs code from before it.
    data class ServerTooOld(val host: String) : Health
    data class Unauthorized(val host: String, val code: Int) : Health
    // Network, TLS or timeout. sinceMs: the first failure in this run.
    data class Offline(val host: String, val reason: String, val sinceMs: Long, val retryAtMs: Long) : Health
    // Android refused to start the foreground service.
    data class NotRunning(val reason: String) : Health
}

// Offline alerts only after a sustained failure, not on each retry.
const val OFFLINE_ALERT_MS = 5 * 60_000L

// A failed link -> its health. code: the HTTP status, when there was one.
fun healthOfFailure(host: String, code: Int?, reason: String, sinceMs: Long, retryAtMs: Long): Health = when (code) {
    404 -> Health.ServerTooOld(host)
    401, 403 -> Health.Unauthorized(host, code)
    else -> Health.Offline(host, reason, sinceMs, retryAtMs)
}

// The short line for the Connection notification and the Alerts screen.
fun healthLine(h: Health): String = when (h) {
    Health.Off -> "Background alerts are off"
    Health.Connecting -> "Connecting…"
    is Health.Connected -> "Connected to ${h.host}"
    is Health.ServerTooOld -> "${h.host} has no alert stream (old server)"
    is Health.Unauthorized -> "${h.host} refused the link (HTTP ${h.code})"
    is Health.Offline -> "No link to ${h.host}: ${h.reason}. Retrying."
    is Health.NotRunning -> "The alert service did not start: ${h.reason}"
}

// An alert the user must act on. key: one per cause, so a repeated failure
// posts nothing new.
data class HealthAlert(val key: String, val title: String, val text: String)

fun healthAlert(h: Health, now: Long): HealthAlert? = when (h) {
    is Health.ServerTooOld -> HealthAlert("old:${h.host}", "Agent alerts are unavailable",
        "${h.host} does not provide the alert stream. Update and restart that server.")
    is Health.Unauthorized -> HealthAlert("auth:${h.host}", "Agent alerts need authentication",
        "${h.host} refused the alert link (HTTP ${h.code}). Check the server and its access settings.")
    is Health.Offline -> if (now - h.sinceMs >= OFFLINE_ALERT_MS) HealthAlert("offline:${h.host}", "Agent alerts are offline",
        "No link to ${h.host} for ${(now - h.sinceMs) / 60_000} min: ${h.reason}. The app keeps retrying.") else null
    is Health.NotRunning -> HealthAlert("service", "Agent alerts are not running", "Android did not start the alert service: ${h.reason}.")
    Health.Off, Health.Connecting, is Health.Connected -> null
}

// What to do with the health notification: post a new cause, cancel it,
// or nothing (same cause as on screen, or nothing on screen to cancel).
sealed interface HealthStep {
    data class Post(val alert: HealthAlert) : HealthStep
    data object Cancel : HealthStep
}

fun planHealth(shownKey: String?, h: Health, now: Long): HealthStep? {
    val a = healthAlert(h, now)
    return when {
        a != null -> if (a.key == shownKey) null else HealthStep.Post(a)
        // Recovery (a valid snapshot) clears it. A plain retry with no new
        // cause keeps the alert that is shown.
        h is Health.Connected || h == Health.Off -> if (shownKey != null) HealthStep.Cancel else null
        else -> null
    }
}

// ---- Context line ----

// Subtext: space, harness, host. Only explicit fields, never parsed from the
// display name. A field equal to the one before it is dropped.
fun subtextOf(a: AgentInfo, host: String?): String? =
    listOfNotNull(a.spaceName, a.harness, host).filter { it.isNotBlank() }.distinct().joinToString(" · ").ifEmpty { null }

// ---- Overview of active agents ----

const val OVERVIEW_LINES = 5
private val ACTIVE_ORDER = listOf("blocked", "error", "working")

data class Overview(val title: String, val lines: List<String>, val more: Int) {
    val sig get() = (listOf(title) + lines + more.toString()).joinToString("\u0000")
}

// One line per active agent, blocked first, then error, then working; the
// newest change first within a state. null: no active agent.
fun overviewOf(agents: Collection<AgentInfo>): Overview? {
    val active = agents.filter { it.state in ACTIVE_ORDER }
        .sortedWith(compareBy<AgentInfo> { ACTIVE_ORDER.indexOf(it.state) }.thenByDescending { it.changedMs ?: 0 })
    if (active.isEmpty()) return null
    val n = { s: String -> active.count { it.state == s } }
    val parts = listOfNotNull(
        n("blocked").takeIf { it > 0 }?.let { "$it need${if (it == 1) "s" else ""} you" },
        n("error").takeIf { it > 0 }?.let { "$it failed" },
        n("working").takeIf { it > 0 }?.let { "$it working" },
    )
    val title = if (parts.size == 1 && n("working") == active.size) "${active.size} agent${if (active.size == 1) "" else "s"} working" else parts.joinToString(" · ")
    val lines = active.take(OVERVIEW_LINES).map(::overviewLine)
    return Overview(title, lines, active.size - lines.size)
}

fun overviewLine(a: AgentInfo): String {
    val what = when (a.state) {
        "blocked" -> a.msg?.takeIf { it.isNotBlank() } ?: "waiting at a ${a.kind ?: "dialog"}"
        "error" -> a.msg?.takeIf { it.isNotBlank() } ?: "stopped with an error"
        else -> a.msg?.takeIf { it.isNotBlank() } ?: "working"
    }
    val mark = when (a.state) { "blocked" -> "Needs you"; "error" -> "Failed"; else -> "Working" }
    return "${a.name} · $mark: $what".take(120)
}

// ---- Actions ----

// Android shows three action buttons. Reply is always one of them.
sealed interface Act {
    data class Answer(val index: Int, val label: String) : Act
    data object Reply : Act
    data object Open : Act
    data object Stop : Act
    data object OpenPr : Act
    data object View : Act
}

const val MAX_QUICK = 2

// blocked at one single-choice question: up to two answers, then Reply;
// blocked otherwise: Reply, Open, Stop; error: Open, Reply;
// done: Open PR (when there is one), View, Reply.
fun actionPlan(state: String, quick: List<String>?, hasPr: Boolean): List<Act> = when (state) {
    "blocked" -> if (!quick.isNullOrEmpty()) quick.take(MAX_QUICK).mapIndexed { j, l -> Act.Answer(j, l) } + Act.Reply
        else listOf(Act.Reply, Act.Open, Act.Stop)
    "done" -> listOfNotNull(Act.OpenPr.takeIf { hasPr }, Act.View, Act.Reply)
    else -> listOf(Act.Open, Act.Reply)
}

// After an inline reply failed: what the notification says.
fun replyFailureTitle(f: Failure): String = if (f == Failure.Retry) "Reply queued" else "Reply kept in app"
