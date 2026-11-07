package io.github.ashrodan.herdrmonitor

// Pure logic for the native surfaces (notifications, tiles, widget), so it
// is unit tested.

// The three alert channels of the brief.
enum class Channel { NeedsYou, Running, Finished }

// blocked and error need you (as on Home); done is finished; working runs.
fun channelFor(state: String): Channel? = when (state) {
    "blocked", "error" -> Channel.NeedsYou
    "done" -> Channel.Finished
    "working" -> Channel.Running
    else -> null
}

// Quiet agents on: only the Needs you channel alerts.
fun mayAlert(state: String, quiet: Boolean): Boolean {
    val ch = channelFor(state) ?: return false
    return !quiet || ch == Channel.NeedsYou
}

// A Running notification only for a pinned session that works, at most 3
// at once, and none with Quiet agents. One per working agent flooded a
// slow device (the emulator's system_server was killed), and a shade of
// progress bars is noise.
const val MAX_RUNNING = 3
fun wantsRunning(state: String, quiet: Boolean, pinned: Boolean): Boolean = state == "working" && !quiet && pinned

// The four stages of the running notification.
val STAGES = listOf("Plan", "Edit", "Verify", "PR")

private val VERIFY = Regex("\\b(test|tests|lint|check|build|assemble\\w*|gradlew|pytest|jest|vitest|tsc|cargo (test|build|check)|go (test|build|vet))\\b", RegexOption.IGNORE_CASE)
private val PR = Regex("\\bgh pr (create|ready|edit)\\b|\\bgit push\\b", RegexOption.IGNORE_CASE)
private val EDIT_TOOL = Regex("edit|write|patch|apply", RegexOption.IGNORE_CASE)
private val SHELL_TOOL = Regex("bash|shell|exec|run|terminal", RegexOption.IGNORE_CASE)

// A heuristic from the tool calls of the current run (since your last
// message): the server reports no plan steps. Plan until a file edit; Edit
// after it; Verify when a test, build or lint command ran after the last
// edit; PR after a push or `gh pr create`. Index into STAGES.
fun progressStage(messages: List<Message>): Int {
    val start = messages.indexOfLast { it.role == "user" } + 1
    var edited = false
    var verified = false
    var pr = false
    for (m in messages.drop(start)) for (p in m.parts) {
        if (p !is Part.Tool) continue
        when {
            SHELL_TOOL.containsMatchIn(p.name) && PR.containsMatchIn(p.summary) -> pr = true
            EDIT_TOOL.containsMatchIn(p.name) -> { edited = true; verified = false }
            SHELL_TOOL.containsMatchIn(p.name) && VERIFY.containsMatchIn(p.summary) && edited -> verified = true
        }
    }
    return when { pr -> 3; verified -> 2; edited -> 1; else -> 0 }
}

// The current step: the running tool and its command, else the last tool.
fun currentStep(messages: List<Message>): String? {
    val tool = messages.asReversed().asSequence().flatMap { it.parts.asReversed().asSequence() }.filterIsInstance<Part.Tool>().firstOrNull()
        ?: return null
    return listOf(tool.name, tool.summary).filter { it.isNotBlank() }.joinToString(": ").take(160)
}

// The Herdr tile's subtitle: "2 need you" when any, else "4 working".
fun tileSubtitle(d: WidgetData?): String = when {
    d == null -> "Open the app"
    !d.connected -> "No link"
    d.needsYou > 0 -> "${d.needsYou} need${if (d.needsYou == 1) "s" else ""} you"
    else -> "${d.working} working"
}

// The finished line: the agent's message, else a failure or a done line.
fun finishedLine(a: AgentInfo, state: String, pr: Pr?): String {
    val base = a.msg?.takeIf { it.isNotBlank() } ?: if (state == "error") "Stopped with an error" else "Finished its turn"
    return if (pr != null && state == "done") "$base · PR #${pr.number}" else base
}
