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

// ---- The agent summary ----

// The one routine notification (it is also the foreground service's): the
// counts and up to five agents, Needs you first, then Failed, Working and
// Done. Done is only an unseen one: once seen on the Mac or this phone its
// effective state is idle, and it leaves the summary.
const val OVERVIEW_LINES = 5
private val SUMMARY_ORDER = listOf("blocked", "error", "working", "done")

data class Overview(val title: String, val lines: List<String>, val more: Int) {
    val sig get() = (listOf(title) + lines + more.toString()).joinToString("\u0000")
}

// agents: with their effective state. Quiet agents: only how many need
// you, no Working or Done lines.
fun summaryOf(agents: Collection<AgentInfo>, quiet: Boolean = false): Overview {
    val shown = agents.filter { it.state in SUMMARY_ORDER }
        .sortedWith(compareBy<AgentInfo> { SUMMARY_ORDER.indexOf(it.state) }.thenByDescending { it.changedMs ?: 0 })
    val n = { s: String -> shown.count { it.state == s } }
    if (quiet) {
        val need = n("blocked") + n("error")
        return Overview("Quiet agents · $need need${if (need == 1) "s" else ""} you", emptyList(), 0)
    }
    if (shown.isEmpty()) return Overview("No active agents", emptyList(), 0)
    val parts = listOfNotNull(
        n("blocked").takeIf { it > 0 }?.let { "$it need${if (it == 1) "s" else ""} you" },
        n("error").takeIf { it > 0 }?.let { "$it failed" },
        n("working").takeIf { it > 0 }?.let { "$it working" },
        n("done").takeIf { it > 0 }?.let { "$it done" },
    )
    val lines = shown.take(OVERVIEW_LINES).map(::overviewLine)
    return Overview(parts.joinToString(" · "), lines, shown.size - lines.size)
}

fun overviewLine(a: AgentInfo): String {
    val msg = a.msg?.takeIf { it.isNotBlank() }
    val (mark, what) = when (a.state) {
        "blocked" -> "Needs you" to (msg ?: "waiting at a ${a.kind ?: "dialog"}")
        "error" -> "Failed" to (msg ?: "stopped with an error")
        "done" -> "Done" to (msg ?: "finished its turn")
        else -> "Working" to (msg ?: "working")
    }
    return "${a.name} · $mark: $what".take(120)
}

// The summary as a notification shows it. While the link is not
// connected, the title says why (the counts may be old).
data class SummaryView(val title: String, val text: String?, val lines: List<String>, val sub: String) {
    val sig get() = (listOf(title, text ?: "", sub) + lines).joinToString("\u0000")
}

fun summaryView(ov: Overview, h: Health, host: String, herdrUp: Boolean = true): SummaryView {
    val connected = h is Health.Connected
    val title = if (connected) ov.title else healthLine(h)
    val lines = ov.lines + listOfNotNull(if (ov.more > 0) "+${ov.more} more" else null)
    val text = when {
        !connected -> ov.title
        !herdrUp -> "Herdr is not running on $host"
        else -> ov.lines.firstOrNull()
    }
    return SummaryView(title, text, lines, host)
}

// ---- Actions ----

// Android shows three action buttons. Reply is always one of them.
sealed interface Act {
    data class Answer(val index: Int, val label: String) : Act
    data object Reply : Act
    data object Open : Act
    data object Stop : Act
}

const val MAX_QUICK = 2

// Only Needs you has its own notification:
// blocked at one single-choice question: up to two answers, then Reply;
// blocked otherwise: Reply, Open, Stop; error: Open, Reply.
fun actionPlan(state: String, quick: List<String>?): List<Act> = when (state) {
    "blocked" -> if (!quick.isNullOrEmpty()) quick.take(MAX_QUICK).mapIndexed { j, l -> Act.Answer(j, l) } + Act.Reply
        else listOf(Act.Reply, Act.Open, Act.Stop)
    else -> listOf(Act.Open, Act.Reply)
}

// After an inline reply failed: what the notification says.
fun replyFailureTitle(f: Failure): String = if (f == Failure.Retry) "Reply queued" else "Reply kept in app"
