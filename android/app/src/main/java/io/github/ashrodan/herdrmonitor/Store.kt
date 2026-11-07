package io.github.ashrodan.herdrmonitor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

// What the app's screens show: every terminal and space, live from
// /api/events (the same feed the web UI uses), plus open PRs.
data class Live(
    val link: Link = Link.Connecting,
    val hcConnected: Boolean = false,
    val terminals: Map<String, Terminal> = emptyMap(),
    val spaces: List<Space> = emptyList(),
    val prs: List<OverviewPr> = emptyList(),
    val error: String? = null,
    // Set while terminals and spaces come from the cache (when it was
    // written): the screens show them as stale. The first snapshot clears it.
    val cachedAt: Long? = null,
    // false until a PR list (live or cached) is in: "No open pull requests"
    // must not show while /api/overview still runs (it can take a minute).
    val prsLoaded: Boolean = false,
)

enum class Link { Connecting, Online, Offline }

class Store(private val server: () -> String, private val cache: Cache? = null) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _live = MutableStateFlow(Live())
    val live: StateFlow<Live> = _live.asStateFlow()
    private var job: Job? = null
    @Volatile private var generation = 0

    val api get() = Api(server())

    init { loadCache() }

    // Fill an empty Live from the cache. Live data always wins: a snapshot
    // or a PR list that came in first is kept.
    private fun loadCache() {
        val c = cache ?: return
        val srv = server()
        scope.launch {
            val snap = c.read(srv, Cache.SNAPSHOT)?.let { (at, text) -> runCatching { at to JSONObject(text) }.getOrNull() }
            val prs = c.read(srv, Cache.OVERVIEW)?.let { (_, text) -> runCatching { Json.overview(text) }.getOrNull() }
            if (server() != srv) return@launch
            _live.update {
                var l = it
                if (snap != null && l.terminals.isEmpty() && l.link != Link.Online)
                    l = l.copy(cachedAt = snap.first, terminals = Json.terminals(snap.second.optJSONArray("terminals")).associateBy { t -> t.address },
                        spaces = Json.spaces(snap.second.optJSONArray("spaces")))
                if (prs != null && !l.prsLoaded) l = l.copy(prs = prs, prsLoaded = true)
                l
            }
        }
    }

    fun start() {
        if (job?.isActive == true) return
        val gen = ++generation
        job = scope.launch {
            val backoff = Backoff()
            launch { while (isActive) { refreshPrs(); delay(120_000) } }
            while (isActive && gen == generation) {
                try {
                    api.stream("/api/events", SseParser({ onEvent(it) }), onOpen = { backoff.reset() }, isCancelled = { !isActive || gen != generation })
                } catch (e: Exception) {
                    _live.update { it.copy(link = Link.Offline, error = e.message ?: e.javaClass.simpleName) }
                }
                if (!isActive || gen != generation) break
                _live.update { it.copy(link = Link.Offline) }
                delay(backoff.next())
            }
        }
    }

    fun stop() { generation++; job?.cancel(); job = null }

    // A new server, or the network came back: connect again now.
    fun restart() { stop(); _live.value = Live(); loadCache(); start() }

    fun refreshPrs(fresh: Boolean = false) {
        scope.launch {
            try {
                val srv = server()
                val text = Api(srv).get("/api/overview" + if (fresh) "?fresh" else "", 60000)
                val prs = Json.overview(text)
                _live.update { it.copy(prs = prs, prsLoaded = true) }
                cache?.write(srv, Cache.OVERVIEW, text)
            } catch (_: Exception) { /* keep the last list */ }
        }
    }

    private fun onEvent(e: SseEvent) {
        val o = try { JSONObject(e.data) } catch (_: Exception) { return }
        when (e.event) {
            "snapshot" -> {
                _live.update {
                    it.copy(link = Link.Online, error = null, hcConnected = o.optBoolean("connected", true), cachedAt = null,
                        terminals = Json.terminals(o.optJSONArray("terminals")).associateBy { t -> t.address },
                        spaces = Json.spaces(o.optJSONArray("spaces")))
                }
                // This runs on the stream's IO thread, so the write is off the main thread.
                cache?.write(server(), Cache.SNAPSHOT, e.data)
            }
            // Skip an event that changes nothing the app shows (a spinner in
            // the title): each new Live redraws every screen.
            "terminal" -> { val t = Json.terminal(o); _live.update { if (it.link == Link.Online && it.terminals[t.address] == t) it else it.copy(link = Link.Online, terminals = it.terminals + (t.address to t)) } }
            "removed" -> { val a = o.optString("address"); _live.update { it.copy(terminals = it.terminals - a) } }
            "layout" -> _live.update { it.copy(spaces = Json.spaces(o.optJSONArray("spaces"))) }
            "connection" -> _live.update { it.copy(hcConnected = o.optBoolean("connected")) }
        }
    }
}
