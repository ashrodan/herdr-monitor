package io.github.ashrodan.herdrmonitor

// Pure logic for the native surfaces (notifications, tiles, widget), so it
// is unit tested.

// The notification channels: an agent that needs you alerts on its own;
// everything else (working, done) is only in the one agent summary.
enum class Channel { NeedsYou, Summary }

// blocked and error need you (as on Home); working and done go to the
// summary only.
fun channelFor(state: String): Channel? = when (state) {
    "blocked", "error" -> Channel.NeedsYou
    "done", "working" -> Channel.Summary
    else -> null
}

// Only Needs you posts its own notification, with Quiet agents on or off.
fun mayAlert(state: String, @Suppress("UNUSED_PARAMETER") quiet: Boolean): Boolean = channelFor(state) == Channel.NeedsYou

// The Herdr tile's subtitle: "2 need you" when any, else "4 working".
fun tileSubtitle(d: WidgetData?): String = when {
    d == null -> "Open the app"
    !d.connected -> "No link"
    d.needsYou > 0 -> "${d.needsYou} need${if (d.needsYou == 1) "s" else ""} you"
    else -> "${d.working} working"
}
