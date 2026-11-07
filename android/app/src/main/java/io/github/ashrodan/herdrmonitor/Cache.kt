package io.github.ashrodan.herdrmonitor

import java.io.File

// The last copy of what the server sent, per server, so a cold start shows
// the last-known sessions and PRs at once (marked as stale) instead of an
// empty screen until the link is up. Files in the app's cache dir: Android
// may delete them, and the app works without them.
class Cache(private val dir: File, private val now: () -> Long = System::currentTimeMillis) {

    // One folder name per server: the URL with every other character as "_".
    fun slot(server: String): String = server.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "default" }

    private fun file(server: String, name: String) = File(File(dir, slot(server)), "$name.txt")

    // The text and when it was written, or null (none, unreadable, or older
    // than maxAgeMs).
    fun read(server: String, name: String, maxAgeMs: Long = MAX_AGE_MS): Pair<Long, String>? {
        val f = file(server, name)
        return try {
            val text = f.readText()
            val nl = text.indexOf('\n')
            if (nl <= 0) return null
            val at = text.substring(0, nl).toLongOrNull() ?: return null
            if (now() - at > maxAgeMs) null else at to text.substring(nl + 1)
        } catch (_: Exception) { null }
    }

    // Write to a temp file, then rename: a reader never sees half a file.
    fun write(server: String, name: String, text: String) {
        try {
            val f = file(server, name)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText("${now()}\n$text")
            if (!tmp.renameTo(f)) tmp.delete()
        } catch (_: Exception) { /* a cache: skip */ }
    }

    companion object {
        // Older than a day is more wrong than useful.
        const val MAX_AGE_MS = 24 * 60 * 60 * 1000L
        const val SNAPSHOT = "snapshot"
        const val OVERVIEW = "overview"
        const val DIGESTS = "digests"
    }
}

// The last `size` transcripts read (server + address -> Chat), least
// recently used out first. In memory only: a transcript can be large.
class ChatCache(private val size: Int) {
    private val map = object : LinkedHashMap<String, Chat>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Chat>?) = this.size > this@ChatCache.size
    }
    @Synchronized fun get(server: String, address: String): Chat? = map["$server\u0000$address"]
    @Synchronized fun put(server: String, address: String, chat: Chat) { map["$server\u0000$address"] = chat }
}
