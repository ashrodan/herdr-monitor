package io.github.ashrodan.herdrmonitor

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

// "host:7400" or "https://host:7400/" -> "https://host:7400". null when it
// is not an http(s) URL with a host.
fun normalizeServer(input: String): String? {
    var s = input.trim().trimEnd('/')
    if (s.isEmpty()) return null
    if (!s.contains("://")) s = "https://$s"
    val u = try { URI(s) } catch (e: Exception) { return null }
    if (u.scheme !in setOf("http", "https") || u.host.isNullOrEmpty()) return null
    val port = if (u.port == -1) "" else ":${u.port}"
    return "${u.scheme}://${u.host}$port"
}

// The Origin the server's sameOrigin check wants: scheme://host[:port],
// equal to the URL we call. The port is left out when it is the default.
fun originOf(url: String): String {
    val u = URI(url)
    val default = (u.scheme == "https" && u.port == 443) || (u.scheme == "http" && u.port == 80)
    val port = if (u.port == -1 || default) "" else ":${u.port}"
    return "${u.scheme}://${u.host}$port"
}

fun pathPart(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

class HttpError(val code: Int, message: String) : IOException(message)

// Blocking HTTP calls against one server. Call them off the main thread.
class Api(val base: String) {

    private fun open(path: String, method: String, timeoutMs: Int = 15000): HttpURLConnection {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10000
        c.readTimeout = timeoutMs
        c.setRequestProperty("Accept", "application/json")
        return c
    }

    private fun HttpURLConnection.body(): String {
        val ok = responseCode in 200..299
        val stream = if (ok) inputStream else (errorStream ?: inputStream)
        val text = stream.bufferedReader().use { it.readText() }
        if (!ok) throw HttpError(responseCode, Json.errorOf(text) ?: "HTTP $responseCode")
        return text
    }

    fun get(path: String, timeoutMs: Int = 15000): String {
        val c = open(path, "GET", timeoutMs)
        try { return c.body() } finally { c.disconnect() }
    }

    fun post(path: String, json: String = "{}"): String = postBytes(path, json.toByteArray(), "application/json")

    fun postBytes(path: String, bytes: ByteArray, type: String): String {
        val c = open(path, "POST", 30000)
        c.doOutput = true
        c.setRequestProperty("Content-Type", type)
        c.setRequestProperty("Origin", originOf(base))
        c.setFixedLengthStreamingMode(bytes.size)
        try {
            c.outputStream.use { it.write(bytes) }
            return c.body()
        } finally { c.disconnect() }
    }

    // Hold an SSE stream open and feed its lines to the parser until it
    // ends or the thread is interrupted. readTimeout catches a dead link:
    // the server pings every 25 s.
    fun stream(path: String, parser: SseParser, onOpen: () -> Unit = {}, isCancelled: () -> Boolean = { false }) {
        val c = open(path, "GET", 70000)
        c.setRequestProperty("Accept", "text/event-stream")
        try {
            if (c.responseCode != 200) throw HttpError(c.responseCode, "HTTP ${c.responseCode}")
            onOpen()
            BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { r ->
                while (!isCancelled()) {
                    val line = r.readLine() ?: break
                    parser.line(line)
                }
            }
        } finally { c.disconnect() }
    }

    // ---- The routes ----
    fun terminalPath(addr: String, rest: String) = "/api/terminals/${pathPart(addr)}/$rest"

    fun send(addr: String, text: String, force: Boolean) =
        post(terminalPath(addr, "send"), Json.obj("text" to text, "force" to force))

    fun answer(addr: String, shape: List<QuestionShape>, q: Int, option: Int) =
        post(terminalPath(addr, "answer"), Json.answerBody(shape, q, option, null))

    fun submit(addr: String, shape: List<QuestionShape>) =
        post(terminalPath(addr, "answer"), Json.answerBody(shape, 0, 0, "submit"))

    fun interrupt(addr: String) = post(terminalPath(addr, "interrupt"))

    fun keys(addr: String, keys: List<String>) = post(terminalPath(addr, "keys"), Json.keysBody(keys))

    fun chat(addr: String, version: String?, timeoutMs: Int = 30000): Chat? {
        val q = if (version != null) "?v=${pathPart(version)}" else ""
        return Json.chat(get(terminalPath(addr, "chat") + q, timeoutMs))
    }

    fun read(addr: String, lines: Int) = Json.readText(get(terminalPath(addr, "read") + "?lines=$lines"))

    // Start one agent in a space (POST /api/sessions). Returns its address.
    fun startSession(space: String, harness: String, prompt: String): String =
        Json.started(post("/api/sessions", Json.obj("space" to space, "harness" to harness, "prompt" to prompt)))

    fun upload(addr: String, bytes: ByteArray, type: String) = Json.uploadPath(postBytes(terminalPath(addr, "upload"), bytes, type))
}
