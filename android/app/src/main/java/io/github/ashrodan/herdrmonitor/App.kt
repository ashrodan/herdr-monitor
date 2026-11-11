package io.github.ashrodan.herdrmonitor

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import io.github.ashrodan.herdrmonitor.ui.theme.ThemePref
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class Look(val pref: ThemePref, val wallpaperColours: Boolean)

class App : Application() {
    lateinit var prefs: Prefs
    lateinit var store: Store
    lateinit var outbox: Outbox
    lateinit var cache: Cache
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Agent states the alert service last heard (address -> state). The app's
    // own Store is stale while no screen is open.
    val serviceStates = ConcurrentHashMap<String, String>()
    // What Home read from each agent's transcript, and answers sent from a
    // card (shown as "Sent: X" until the agent's next output).
    val digests = MutableStateFlow<Map<String, Digest>>(emptyMap())
    val sent = MutableStateFlow<Map<String, SentMark>>(emptyMap())
    val pins = MutableStateFlow<Set<String>>(emptySet())
    // The alert link's health and the counts the service last saw, for
    // the Alerts screen.
    val health = MutableStateFlow<Health>(Health.Off)
    val alertCounts = MutableStateFlow(AlertCounts())
    // The last few transcripts read, so reopening an agent is instant.
    val chats = ChatCache(8)

    fun togglePin(address: String) {
        val next = if (address in pins.value) pins.value - address else pins.value + address
        pins.value = next; prefs.pinned = next
    }
    // The theme the screens draw with; Settings changes it.
    // Home's filter, asked for from outside (the Herdr tile: Needs you).
    val homeFilter = MutableStateFlow<Bucket?>(null)
    val look = MutableStateFlow(Look(ThemePref.Dark, false))

    fun isBlocked(address: String): Boolean =
        (serviceStates[address] ?: store.live.value.terminals[address]?.status) == "blocked"

    // Send the outbox now (the alert service calls this when it connects).
    // Only the items typed for the server in use go, and only to it; items of
    // another server wait until that server is in use again. Outbox.flush
    // ignores a call while one runs.
    fun flushOutbox() {
        val server = prefs.server
        if (outbox.items.value.none { it.server == server && it.state == OutState.WAITING }) return
        scope.launch {
            outbox.flush(server, ::isBlocked) { item, force -> Api(item.server).send(item.address, item.text, force) }
        }
    }

    // The cards' last words survive a restart: read them back (no version,
    // so Home reads each transcript again), and save them 2 s after a change.
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun loadDigests() {
        readDigests()
        scope.launch { digests.debounce(2_000).collect { d -> cache.write(prefs.server, Cache.DIGESTS, digestsToJson(d)) } }
    }

    private fun readDigests() = scope.launch {
        val srv = prefs.server
        cache.read(srv, Cache.DIGESTS)?.let { (_, text) ->
            val saved = runCatching { digestsFromJson(text) }.getOrNull().orEmpty()
            if (prefs.server == srv) digests.update { saved + it }
        }
    }

    // Another server: its own sessions, PRs and last words.
    fun serverChanged() {
        digests.value = emptyMap()
        sent.value = emptyMap()
        // Pins, seen and opened are per server (Prefs).
        pins.value = prefs.pinned
        serviceStates.clear()
        store.restart()
        readDigests()
    }

    fun setLook(l: Look) {
        prefs.theme = l.pref.name
        prefs.wallpaperColours = l.wallpaperColours
        look.value = l
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        look.value = Look(ThemePref.of(prefs.theme), prefs.wallpaperColours)
        cache = Cache(java.io.File(cacheDir, "live"))
        store = Store({ prefs.server }, cache)
        loadDigests()
        Notifier.channels(this)
        outbox = Outbox(prefs.outboxStorage, legacyServer = prefs.server)
        pins.value = prefs.pinned
        // Flush when the app's link comes up, and every 15 s while replies wait.
        scope.launch { store.live.map { it.link }.distinctUntilChanged().collect { if (it == Link.Online) flushOutbox() } }
        scope.launch {
            while (true) {
                delay(15_000)
                flushOutbox()
            }
        }
    }
}

val Context.app get() = applicationContext as App

// herdrmonitor://agent/<addr>: opens the app on that agent.
fun agentUri(address: String): Uri = Uri.parse("herdrmonitor://agent/${pathPart(address)}")

fun openAgentIntent(context: Context, address: String?): Intent =
    Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        if (address != null) data = agentUri(address)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }

data class AlertCounts(val needsYou: Int = 0, val working: Int = 0, val failed: Int = 0, val finished: Int = 0, val lastEventMs: Long = 0)

// herdrmonitor://alerts: the in-app Alerts screen (from a health alert).
val ALERTS_URI: Uri = Uri.parse("herdrmonitor://alerts")
fun isAlertsLink(uri: Uri?) = uri?.scheme == "herdrmonitor" && uri.host == "alerts"
fun alertsIntent(context: Context): Intent = Intent(context, MainActivity::class.java).apply {
    action = Intent.ACTION_VIEW
    data = ALERTS_URI
    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
}

fun addressFrom(uri: Uri?): String? =
    if (uri?.scheme == "herdrmonitor" && uri.host == "agent") uri.pathSegments.firstOrNull() else null

// POST_NOTIFICATIONS is a runtime permission from Android 13; before that
// notifications are on unless the user turned them off.
fun Context.mayNotify(): Boolean = Build.VERSION.SDK_INT < 33 ||
    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
