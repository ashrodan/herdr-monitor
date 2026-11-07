package io.github.ashrodan.herdrmonitor

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

// Holds one SSE link to /api/agents/stream while alerts are on, and turns
// agent changes into notifications and widget updates.
//
// Foreground service type specialUse, not dataSync: from Android 15 a
// dataSync service stops after 6 hours a day and may not start at boot.
class AlertService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var link: Job? = null
    @Volatile private var generation = 0
    private val agents = ConcurrentHashMap<String, AgentInfo>()
    private var prs: List<OverviewPr> = emptyList()
    @Volatile private var connected = false
    private var widgetJob: Job? = null
    private lateinit var prefs: Prefs
    private val net = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { reconnect() }
    }
    private var firstNetwork = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = app.prefs
        // Forget alerts that are no longer on screen (an app update or a
        // reboot clears them), so the record matches what the user sees.
        val shown = getSystemService(android.app.NotificationManager::class.java).activeNotifications
            .filter { it.id == Notifier.ID_AGENT }.mapNotNull { it.tag }.toSet()
        prefs.posted = prefs.posted.filterKeys { it in shown }
        val n = Notifier.service(this, "Herdr monitor connecting…")
        // The specialUse type exists from Android 14; before that a plain
        // foreground service has no time limit.
        if (Build.VERSION.SDK_INT >= 34) startForeground(Notifier.ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(Notifier.ID_SERVICE, n)
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(net)
        scope.launch { while (isActive) { loadPrs(); delay(300_000) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!prefs.alerts) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_RECONNECT || link?.isActive != true) reconnect()
        return START_STICKY
    }

    override fun onDestroy() {
        generation++
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(net) }
        scope.cancel()
        connected = false
        Widget.save(this, widgetData(false, agents.values, prs, prefs.localSeen, System.currentTimeMillis()))
        super.onDestroy()
    }

    private fun status(text: String) {
        getSystemService(android.app.NotificationManager::class.java).notify(Notifier.ID_SERVICE, Notifier.service(this, text))
    }

    // The network changed, the server changed, or the link dropped: start a
    // new link at once (the old one ends at its next line).
    private fun reconnect() {
        // registerDefaultNetworkCallback calls onAvailable right away: skip
        // that one when a link is already starting.
        if (firstNetwork) { firstNetwork = false; if (link?.isActive == true) return }
        val gen = ++generation
        link?.cancel()
        link = scope.launch {
            val backoff = Backoff()
            while (isActive && gen == generation) {
                val api = Api(prefs.server)
                try {
                    api.stream("/api/agents/stream", SseParser(onEvent = { onEvent(it) }, onComment = { heard() }),
                        onOpen = { backoff.reset() }, isCancelled = { gen != generation })
                } catch (_: Exception) { }
                if (gen != generation) return@launch
                connected = false
                status("Herdr monitor: no link to ${hostOf(prefs.server)}, retrying")
                pushWidget()
                delay(backoff.next())
            }
        }
    }

    private var lastHeard = 0L
    private fun heard() {
        val now = System.currentTimeMillis()
        if (now - lastHeard > 10_000) { lastHeard = now; prefs.listenedMs = now }
    }

    private fun onEvent(e: SseEvent) {
        val o = try { JSONObject(e.data) } catch (_: Exception) { return }
        when (e.event) {
            "snapshot" -> {
                connected = true
                status(if (o.optBoolean("connected", true)) "Herdr monitor connected" else "Herdr monitor connected (Herdr is not running)")
                val list = Json.agents(o.optJSONArray("agents"))
                val since = prefs.listenedMs.takeIf { it > 0 } ?: System.currentTimeMillis()
                agents.clear()
                list.forEach { agents[it.address] = it }
                val addresses = prefs.posted.keys + agents.keys
                for (a in addresses) apply(planAlert(a, prefs.posted[a], agents[a], prefs.localSeen, since))
                app.serviceStates.clear()
                list.forEach { app.serviceStates[it.address] = it.state }
                // The link is up: send replies that waited in the outbox.
                app.flushOutbox()
            }
            "agent" -> {
                val a = Json.agent(o)
                agents[a.address] = a
                app.serviceStates[a.address] = a.state
                apply(planAlert(a.address, prefs.posted[a.address], a, prefs.localSeen))
            }
            "removed" -> {
                val a = o.optString("address")
                agents.remove(a)
                app.serviceStates.remove(a)
                apply(planAlert(a, prefs.posted[a], null))
            }
            "connection" -> status(if (o.optBoolean("connected")) "Herdr monitor connected" else "Herdr monitor connected (Herdr is not running)")
        }
        heard()
        pushWidget()
    }

    private fun apply(action: AlertAction?) {
        when (action) {
            null -> {}
            is AlertAction.Cancel -> {
                Notifier.cancelAgent(this, action.address)
                prefs.posted = prefs.posted - action.address
            }
            is AlertAction.Post -> {
                prefs.posted = prefs.posted + (action.agent.address to action.state)
                scope.launch { post(action.agent, action.state) }
            }
        }
    }

    // Post at once. A blocked agent may wait at a question form: Herdr
    // reports "blocked" before the agent writes the question to its
    // transcript, so read the chat a few times (about 6 s) and, when the
    // question shows up, update the alert quietly with its options.
    private suspend fun post(a: AgentInfo, state: String) {
        Notifier.postAgent(this, a, state, null, quiet = false)
        if (state != "blocked") return
        repeat(5) { attempt ->
            if (attempt > 0) delay(1500)
            val ask = withTimeoutOrNull(5000) {
                runCatching { Api(prefs.server).chat(a.address, null)?.let { lastUnansweredAsk(it.messages) } }.getOrNull()
            }
            // It may have moved on while we read.
            if (prefs.posted[a.address] != state) return
            if (ask != null) { Notifier.postAgent(this, agents[a.address] ?: a, state, ask, quiet = true); return }
        }
    }

    private fun loadPrs() {
        prs = runCatching { Json.overview(Api(prefs.server).get("/api/overview", 60000)) }.getOrDefault(prs)
        pushWidget()
    }

    // At most one widget update a second.
    private fun pushWidget() {
        if (widgetJob?.isActive == true) return
        widgetJob = scope.launch {
            delay(1000)
            Widget.save(this@AlertService, widgetData(connected, agents.values, prs, prefs.localSeen, System.currentTimeMillis()))
        }
    }

    companion object {
        const val ACTION_RECONNECT = "io.github.ashrodan.herdrmonitor.RECONNECT"

        fun start(c: Context, reconnect: Boolean = false) {
            if (!c.app.prefs.alerts) return
            val i = Intent(c, AlertService::class.java)
            if (reconnect) i.action = ACTION_RECONNECT
            runCatching { c.startForegroundService(i) }
        }

        fun stop(c: Context) { c.stopService(Intent(c, AlertService::class.java)) }
    }
}

fun hostOf(url: String) = runCatching { java.net.URI(url).host }.getOrNull() ?: url
