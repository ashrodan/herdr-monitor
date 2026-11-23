package io.github.ashrodan.herdrmonitor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

// Every herdr-monitor the phone can reach. An Android app cannot read
// Tailscale's list of machines, so: ask the saved server and the default
// one for /api/discover (with the ports of both URLs), and ask both
// directly too. No port scan.
object Servers {
    // One server, asked directly: /api/info, else /api/health (an older
    // server has no /api/info).
    fun probe(url: String): ServerInfo? {
        val api = Api(url)
        runCatching { api.get("/api/info", 6000) }.getOrNull()?.let { text ->
            val host = Json.infoHost(text)
            val self = host != null && host.equals(runCatching { java.net.URI(url).host }.getOrNull(), ignoreCase = true)
            return ServerInfo(url, runCatching { java.net.URI(url).host }.getOrNull() ?: url, null, self, Json.info(text), false, host)
        }
        runCatching { api.get("/api/health", 6000) }.getOrNull()?.takeIf(Json::isHealth)?.let {
            return ServerInfo(url, runCatching { java.net.URI(url).host }.getOrNull() ?: url, null, false, null, older = true)
        }
        return null
    }

    suspend fun find(saved: String, fallback: String = DEFAULT_SERVER): List<ServerInfo> = withContext(Dispatchers.IO) {
        val bases = listOf(saved, fallback).distinct()
        val ports = bases.map(::portOf).filter { it > 0 }.distinct().joinToString(",")
        coroutineScope {
            val listed = bases.map { b -> async { runCatching { Json.discover(Api(b).get("/api/discover?ports=$ports", 30000)) }.getOrDefault(emptyList()) } }
            val direct = bases.map { b -> async { probe(b) } }
            merge(listed.awaitAll().flatten() + direct.awaitAll().filterNotNull())
        }
    }

    // One row per URL. A row with versions wins over one without; the
    // server's own "self" flag wins over a direct probe's guess.
    fun merge(all: List<ServerInfo>): List<ServerInfo> =
        all.groupBy { normalizeServer(it.url) ?: it.url }.map { (url, rows) ->
            val best = rows.maxBy { (if (it.herdr != null) 2 else 0) + (if (it.os != null) 1 else 0) }
            best.copy(url = url, self = rows.any { it.self }, os = rows.firstNotNullOfOrNull { it.os })
        }.sortedWith(compareBy({ !it.self }, { it.name }, { portOf(it.url) }))

    // Every machine of the tailnet, from the server in use. An older server
    // has no /api/machines: an empty list then.
    suspend fun machines(server: String): List<Machine> = machineList(server).machines

    // The same, and whether the server is too old to list machines (a 404:
    // it runs code from before /api/machines), so a screen can say so.
    suspend fun machineList(server: String): MachineList = withContext(Dispatchers.IO) {
        try { MachineList(Json.machines(Api(server).get("/api/machines", 20000))) }
        catch (e: HttpError) { MachineList(emptyList(), tooOld = e.code == 404) }
        catch (e: Exception) { MachineList(emptyList()) }
    }

    // A server the phone can move to: it answers /api/health and its Herdr
    // runs (connected). A server whose Herdr is off shows nothing useful.
    fun healthy(url: String): Boolean = runCatching {
        val text = Api(url).get("/api/health", 6000)
        Json.isHealth(text) && org.json.JSONObject(text).optBoolean("connected")
    }.getOrDefault(false)
}
