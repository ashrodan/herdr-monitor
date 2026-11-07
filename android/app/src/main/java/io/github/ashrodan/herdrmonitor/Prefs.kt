package io.github.ashrodan.herdrmonitor

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

const val DEFAULT_SERVER = "https://ashleys-macbook-pro-2.tail5265d4.ts.net:7400"

// The per-agent records (seen, posted, opened, pinned) are kept per server:
// t1 on one server is not t1 on another.
fun scopedKey(key: String, server: String) = "$key@$server"
private val SCOPED = listOf("seen", "posted", "opened", "pinned")

class Prefs(context: Context) {
    private val p: SharedPreferences = context.applicationContext.getSharedPreferences("herdr", Context.MODE_PRIVATE)

    // Records from before they were per server belong to the server in use.
    init {
        val e = p.edit()
        for (k in SCOPED) if (p.contains(k)) {
            val to = scopedKey(k, server)
            if (!p.contains(to)) when (k) {
                "pinned" -> e.putStringSet(to, p.getStringSet(k, emptySet())!!.toSet())
                else -> e.putString(to, p.getString(k, "{}"))
            }
            e.remove(k)
        }
        e.commit()
    }

    private fun scoped(key: String) = scopedKey(key, server)

    var server: String
        get() = p.getString("server", null) ?: DEFAULT_SERVER
        set(v) = p.edit().putString("server", v).apply()

    // Dark (default), Light or System; and whether to take colours from the
    // wallpaper (off by default). Status colours never change.
    var theme: String
        get() = p.getString("theme", null) ?: "Dark"
        set(v) = p.edit().putString("theme", v).apply()

    var wallpaperColours: Boolean
        get() = p.getBoolean("wallpaper_colours", false)
        set(v) = p.edit().putBoolean("wallpaper_colours", v).apply()

    // The app asked for the notification permission at least once.
    var askedNotify: Boolean
        get() = p.getBoolean("asked_notify", false)
        set(v) = p.edit().putBoolean("asked_notify", v).apply()

    var alerts: Boolean
        get() = p.getBoolean("alerts", true)
        set(v) = p.edit().putBoolean("alerts", v).apply()
    // Quiet agents (the tile, and Settings): only Needs you alerts.
    var quiet: Boolean
        get() = p.getBoolean("quiet", false)
        set(v) = p.edit().putBoolean("quiet", v).apply()

    // When the alert service last heard from the server: on a reconnect it
    // alerts only for changes after this time.
    var listenedMs: Long
        get() = p.getLong("listened_ms", 0)
        set(v) = p.edit().putLong("listened_ms", v).apply()

    // "Done" agents opened on this phone (address -> ms), like the web UI's
    // seen map. This and the records below are for the server in use.
    var localSeen: Map<String, Long>
        get() = readMap(scoped("seen")).mapValues { (it.value as? Number)?.toLong() ?: 0L }
        set(v) = writeMap(scoped("seen"), v)

    fun markSeen(address: String) { localSeen = localSeen + (address to System.currentTimeMillis()) }

    // The alert state of each notification on screen (address -> state).
    var posted: Map<String, String>
        get() = readMap(scoped("posted")).mapValues { it.value.toString() }
        set(v) = writeMap(scoped("posted"), v)

    // Replies waiting to be sent (Outbox), as JSON.
    val outboxStorage = object : OutboxStorage {
        override fun load(): String? = p.getString("outbox", null)
        // commit, not apply: the queue must be on disk before the process can die.
        override fun save(json: String) { p.edit().putString("outbox", json).commit() }
    }

    // When each agent was last opened on this phone (address -> ms): newer
    // output than that is unread.
    var opened: Map<String, Long>
        get() = readMap(scoped("opened")).mapValues { (it.value as? Number)?.toLong() ?: 0L }
        set(v) = writeMap(scoped("opened"), v)

    fun markOpened(address: String) { opened = opened + (address to System.currentTimeMillis()) }

    // Output before the app first ran does not count as unread.
    val unreadSince: Long
        get() = p.getLong("unread_since", 0L).takeIf { it > 0 } ?: System.currentTimeMillis().also { p.edit().putLong("unread_since", it).apply() }

    // Pinned sessions sit at the top of their section. Local to the phone.
    var pinned: Set<String>
        get() = p.getStringSet(scoped("pinned"), emptySet())!!.toSet()
        set(v) = p.edit().putStringSet(scoped("pinned"), v).apply()

    // The widget's data, as the service last wrote it.
    var widgetJson: String?
        get() = p.getString("widget", null)
        set(v) = p.edit().putString("widget", v).apply()

    // Home reads these maps on every rebuild of its list: decode the JSON
    // only when the stored text changed.
    private val decoded = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Map<String, Any>>>()

    private fun readMap(key: String): Map<String, Any> {
        val raw = p.getString(key, "{}")!!
        decoded[key]?.let { (r, m) -> if (r == raw) return m }
        val m: Map<String, Any> = try {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { o.get(it) }
        } catch (e: Exception) { emptyMap() }
        decoded[key] = raw to m
        return m
    }

    private fun writeMap(key: String, m: Map<String, Any>) = p.edit().putString(key, JSONObject(m).toString()).apply()
}
