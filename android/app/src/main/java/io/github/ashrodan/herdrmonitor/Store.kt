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
)

enum class Link { Connecting, Online, Offline }

class Store(private val server: () -> String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _live = MutableStateFlow(Live())
    val live: StateFlow<Live> = _live.asStateFlow()
    private var job: Job? = null
    @Volatile private var generation = 0

    val api get() = Api(server())

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
    fun restart() { stop(); _live.value = Live(); start() }

    fun refreshPrs(fresh: Boolean = false) {
        scope.launch {
            try {
                val prs = Json.overview(api.get("/api/overview" + if (fresh) "?fresh" else "", 60000))
                _live.update { it.copy(prs = prs) }
            } catch (_: Exception) { /* keep the last list */ }
        }
    }

    private fun onEvent(e: SseEvent) {
        val o = try { JSONObject(e.data) } catch (_: Exception) { return }
        when (e.event) {
            "snapshot" -> _live.update {
                it.copy(link = Link.Online, error = null, hcConnected = o.optBoolean("connected", true),
                    terminals = Json.terminals(o.optJSONArray("terminals")).associateBy { t -> t.address },
                    spaces = Json.spaces(o.optJSONArray("spaces")))
            }
            "terminal" -> { val t = Json.terminal(o); _live.update { it.copy(link = Link.Online, terminals = it.terminals + (t.address to t)) } }
            "removed" -> { val a = o.optString("address"); _live.update { it.copy(terminals = it.terminals - a) } }
            "layout" -> _live.update { it.copy(spaces = Json.spaces(o.optJSONArray("spaces"))) }
            "connection" -> _live.update { it.copy(hcConnected = o.optBoolean("connected")) }
        }
    }
}
