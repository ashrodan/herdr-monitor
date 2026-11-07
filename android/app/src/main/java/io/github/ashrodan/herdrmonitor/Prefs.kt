package io.github.ashrodan.herdrmonitor

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

const val DEFAULT_SERVER = "https://ashleys-macbook-pro-2.tail5265d4.ts.net:7400"

class Prefs(context: Context) {
    private val p: SharedPreferences = context.applicationContext.getSharedPreferences("herdr", Context.MODE_PRIVATE)

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

    var alerts: Boolean
        get() = p.getBoolean("alerts", true)
        set(v) = p.edit().putBoolean("alerts", v).apply()

    // When the alert service last heard from the server: on a reconnect it
    // alerts only for changes after this time.
    var listenedMs: Long
        get() = p.getLong("listened_ms", 0)
        set(v) = p.edit().putLong("listened_ms", v).apply()

    // "Done" agents opened on this phone (address -> ms), like the web UI's seen map.
    var localSeen: Map<String, Long>
        get() = readMap("seen").mapValues { (it.value as? Number)?.toLong() ?: 0L }
        set(v) = writeMap("seen", v)

    fun markSeen(address: String) { localSeen = localSeen + (address to System.currentTimeMillis()) }

    // The alert state of each notification on screen (address -> state).
    var posted: Map<String, String>
        get() = readMap("posted").mapValues { it.value.toString() }
        set(v) = writeMap("posted", v)

    // The widget's data, as the service last wrote it.
    var widgetJson: String?
        get() = p.getString("widget", null)
        set(v) = p.edit().putString("widget", v).apply()

    private fun readMap(key: String): Map<String, Any> = try {
        val o = JSONObject(p.getString(key, "{}")!!)
        o.keys().asSequence().associateWith { o.get(it) }
    } catch (e: Exception) { emptyMap() }

    private fun writeMap(key: String, m: Map<String, Any>) = p.edit().putString(key, JSONObject(m).toString()).apply()
}
