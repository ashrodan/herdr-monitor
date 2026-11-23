package io.github.ashrodan.herdrmonitor

// More than one server (design A: hubs with failover). The phone keeps an
// ordered list of herdr-monitor servers. The first is the preferred one
// (usually the Mac). The phone holds a link to one server at a time: the one
// in use (Prefs.server). When that link fails for FAILOVER_AFTER_MS, the
// phone moves to the first healthy server in the list. While it uses a
// server that is not first, it asks the servers above it every
// RETURN_EVERY_MS and moves back to the first one that answers.
//
// Each server is its own world: the same agent has another address on
// another hub ("dai/t3" through the Mac, "t3" on dai), so the per-agent
// records stay per server (Prefs), as they do for a server the user picks.

const val FAILOVER_AFTER_MS = 60_000L
const val RETURN_EVERY_MS = 300_000L

// The server list after the user picks a server: it goes first, the rest
// keep their order.
fun pickFirst(list: List<String>, url: String): List<String> = listOf(url) + list.filter { it != url }

// Add a server to the end of the list, or take it out. The first (preferred)
// server stays: to change it, the user picks another server.
fun toggleFallback(list: List<String>, url: String): List<String> = when {
    url !in list -> list + url
    list.firstOrNull() == url -> list
    else -> list - url
}

// Leave the server in use: its link has failed since failingSince (0: it
// works) for FAILOVER_AFTER_MS, and the list has another server to try.
fun shouldFailOver(list: List<String>, inUse: String, failingSince: Long, now: Long): Boolean =
    failingSince > 0 && now - failingSince >= FAILOVER_AFTER_MS && list.any { it != inUse }

// The server to move to: the first healthy one in list order, not the one
// that fails. null: none answers, so stay (the link keeps retrying).
suspend fun nextServer(list: List<String>, inUse: String, healthy: suspend (String) -> Boolean): String? =
    list.filter { it != inUse }.firstOrNull { healthy(it) }

// The servers above the one in use: the ones to move back to. All of them
// when the server in use is not in the list (a URL typed in Settings).
fun above(list: List<String>, inUse: String): List<String> {
    val i = list.indexOf(inUse)
    return if (i < 0) list else list.take(i)
}

// The server in use is a fallback, not the preferred one.
fun onFallback(list: List<String>, inUse: String): Boolean = above(list, inUse).isNotEmpty()
