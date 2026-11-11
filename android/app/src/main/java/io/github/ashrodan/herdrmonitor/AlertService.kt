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
    // The open question of each blocked agent (for the widget's answer
    // button).
    private val asks = ConcurrentHashMap<String, Part.Ask>()
    // Herdr itself runs on the Mac (the stream's "connected").
    @Volatile private var herdrUp = true
    private lateinit var prefs: Prefs
    // The server the records above are for.
    private var server: String? = null
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
        // A health alert left from before: a recovery must still clear it.
        if (getSystemService(android.app.NotificationManager::class.java).activeNotifications.any { it.id == Notifier.ID_HEALTH }) healthKey = "?"
        app.health.value = Health.Connecting
        // The agent summary is the foreground service's notification: one
        // routine notification, not two.
        val v = summaryNow()
        lastSummary = v.sig
        val n = Notifier.summary(this, v)
        // The specialUse type exists from Android 14; before that a plain
        // foreground service has no time limit.
        if (Build.VERSION.SDK_INT >= 34) startForeground(Notifier.ID_SUMMARY, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(Notifier.ID_SUMMARY, n)
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(net)
        scope.launch { while (isActive) { loadPrs(); delay(300_000) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!prefs.alerts) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_RECONNECT || link?.isActive != true) reconnect()
        else if (intent?.action == ACTION_REFRESH) refreshSummary()
        return START_STICKY
    }

    override fun onDestroy() {
        generation++
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(net) }
        scope.cancel()
        connected = false
        Widget.save(this, widgetFor(false))
        // Alerts off: nothing to warn about.
        setHealth(Health.Off, notify = false)
        super.onDestroy()
    }

    // The health alert on screen (its cause key), so a repeated failure
    // posts nothing new and a recovery cancels it.
    @Volatile private var healthKey: String? = null

    // One place for the link's health: the app state, the Connection
    // notification, and the health alert.
    private fun setHealth(h: Health, notify: Boolean = true) {
        app.health.value = h
        if (notify) refreshSummary()
        when (val step = planHealth(healthKey, h, System.currentTimeMillis())) {
            null -> {}
            is HealthStep.Post -> { healthKey = step.alert.key; Notifier.postHealth(this, step.alert) }
            HealthStep.Cancel -> { healthKey = null; Notifier.cancelHealth(this) }
        }
    }

    // What the summary shows, from the stream only (no transcript reads).
    private fun summaryNow(): SummaryView {
        val now = agents.values.map { it.copy(state = it.stateOf(prefs.localSeen)) }
        return summaryView(summaryOf(now, prefs.quiet), app.health.value, shortHost(prefs.server), herdrUp)
    }

    // The last summary posted. A new one only when what it shows changes:
    // not for heartbeats or an unchanged snapshot, so a summary the user
    // swiped away (Android 14+) comes back only with news.
    @Volatile private var lastSummary: String? = null

    private fun refreshSummary() {
        val v = summaryNow()
        if (v.sig == lastSummary) return
        lastSummary = v.sig
        Notifier.postSummary(this, v)
    }

    // The summary and the counts, from what the stream said.
    private fun refreshOverview() {
        refreshSummary()
        val now = agents.values.map { it.copy(state = it.stateOf(prefs.localSeen)) }
        app.alertCounts.value = AlertCounts(
            needsYou = now.count { it.state == "blocked" }, working = now.count { it.state == "working" },
            failed = now.count { it.state == "error" }, finished = now.count { it.state == "done" },
            lastEventMs = System.currentTimeMillis())
    }

    // The network changed, the server changed, or the link dropped: start a
    // new link at once (the old one ends at its next line).
    private fun reconnect() {
        // registerDefaultNetworkCallback calls onAvailable right away: skip
        // that one when a link is already starting.
        if (firstNetwork) { firstNetwork = false; if (link?.isActive == true) return }
        val srv = prefs.server
        if (server != null && server != srv) forgetServer()
        server = srv
        val gen = ++generation
        link?.cancel()
        link = scope.launch {
            val backoff = Backoff()
            // The first failure since the link last worked: an outage
            // alerts only after OFFLINE_ALERT_MS.
            var failingSince = 0L
            setHealth(Health.Connecting)
            while (isActive && gen == generation) {
                val api = Api(prefs.server)
                var code: Int? = null
                var reason = "the link closed"
                try {
                    // After a reconnect the old read can still hand over
                    // one line: only this link's generation counts.
                    api.stream("/api/agents/stream", SseParser(onEvent = { if (gen == generation) onEvent(it) }, onComment = { if (gen == generation) heard() }),
                        onOpen = { backoff.reset(); failingSince = 0L }, isCancelled = { gen != generation })
                } catch (e: Exception) {
                    // 404: the server runs code from before the agent stream.
                    // Retrying cannot fix that; a server update can.
                    code = (e as? HttpError)?.code
                    reason = e.message?.take(120) ?: e.javaClass.simpleName
                }
                if (gen != generation) return@launch
                connected = false
                val now = System.currentTimeMillis()
                if (failingSince == 0L) failingSince = now
                val wait = backoff.next()
                setHealth(healthOfFailure(shortHost(prefs.server), code, reason, failingSince, now + wait))
                pushWidget()
                delay(wait)
            }
        }
    }

    // The server changed: what the service knows of the old one's agents
    // goes, and so do their notifications. The new server's posted record
    // matches the screen again (nothing of it is on screen).
    private fun forgetServer() {
        agents.clear()
        asks.clear()
        app.serviceStates.clear()
        Notifier.cancelAllAgents(this)
        prefs.posted = emptyMap()
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
                herdrUp = o.optBoolean("connected", true)
                // A valid snapshot: the link works (this clears a health alert).
                setHealth(Health.Connected(shortHost(prefs.server), System.currentTimeMillis()), notify = false)
                val list = Json.agents(o.optJSONArray("agents"))
                val since = prefs.listenedMs.takeIf { it > 0 } ?: System.currentTimeMillis()
                agents.clear()
                list.forEach { agents[it.address] = it }
                val addresses = prefs.posted.keys + agents.keys
                for (a in addresses) apply(planAlert(a, prefs.posted[a], agents[a], prefs.localSeen, since))
                app.serviceStates.clear()
                list.forEach { app.serviceStates[it.address] = it.state }
                refreshOverview()
                // The link is up: send replies that waited in the outbox.
                app.flushOutbox()
            }
            "agent" -> {
                val a = Json.agent(o)
                agents[a.address] = a
                app.serviceStates[a.address] = a.state
                if (a.state != "blocked") asks.remove(a.address)
                apply(planAlert(a.address, prefs.posted[a.address], a, prefs.localSeen))
                refreshOverview()
            }
            "removed" -> {
                val a = o.optString("address")
                agents.remove(a)
                app.serviceStates.remove(a)
                asks.remove(a)
                apply(planAlert(a, prefs.posted[a], null))
                refreshOverview()
            }
            "connection" -> { herdrUp = o.optBoolean("connected"); refreshSummary() }
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
                // Only Needs you posts (planAlert plans nothing else).
                if (mayAlert(action.state, prefs.quiet)) scope.launch { post(action.agent, action.state) }
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
            if (ask != null) {
                asks[a.address] = ask
                Notifier.postAgent(this, agents[a.address] ?: a, state, ask, quiet = true)
                pushWidget()
                return
            }
        }
    }

    private fun widgetFor(connected: Boolean) =
        widgetData(connected, shortHost(prefs.server), agents.values, prs, asks, prefs.localSeen, System.currentTimeMillis())

    private fun loadPrs() {
        prs = runCatching { Json.overview(Api(prefs.server).get("/api/overview", 60000)) }.getOrDefault(prs)
        pushWidget()
    }

    // At most one widget update a second.
    private fun pushWidget() {
        if (widgetJob?.isActive == true) return
        widgetJob = scope.launch {
            delay(1000)
            Widget.save(this@AlertService, widgetFor(connected))
        }
    }

    companion object {
        const val ACTION_RECONNECT = "io.github.ashrodan.herdrmonitor.RECONNECT"
        // Draw the summary again (Quiet agents changed, or a Done was seen).
        const val ACTION_REFRESH = "io.github.ashrodan.herdrmonitor.REFRESH"

        fun start(c: Context, reconnect: Boolean = false) = start(c, if (reconnect) ACTION_RECONNECT else null)

        fun start(c: Context, action: String?) {
            if (!c.app.prefs.alerts) return
            val i = Intent(c, AlertService::class.java)
            if (action != null) i.action = action
            // Android can refuse (a background start, a restricted app): say
            // so in the Alerts screen and with a health alert, not silently.
            try { c.startForegroundService(i) } catch (e: Exception) {
                val why = e.message?.take(120) ?: e.javaClass.simpleName
                android.util.Log.w("HerdrAlerts", "alert service did not start: $why")
                val h = Health.NotRunning(why)
                c.app.health.value = h
                healthAlert(h, System.currentTimeMillis())?.let { runCatching { Notifier.postHealth(c, it) } }
            }
        }

        fun stop(c: Context) { c.stopService(Intent(c, AlertService::class.java)) }
    }
}

fun hostOf(url: String) = runCatching { java.net.URI(url).host }.getOrNull() ?: url
