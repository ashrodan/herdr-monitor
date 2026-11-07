package io.github.ashrodan.herdrmonitor

import org.json.JSONArray
import org.json.JSONObject

// Server JSON -> the model (Model.kt). Uses Android's org.json; the rules
// that matter live in Model.kt, where unit tests reach them.

data class HerdrInfo(val uiVersion: String?, val uiRunning: Boolean, val tuiVersion: String?, val tuiRunning: Boolean, val hc: String?) {
    // Two lines, one per product: they run side by side.
    val appLine get() = "Herdr app: " + (uiVersion?.let { "$it, " + if (uiRunning) "running" else "not running" } ?: "not installed")
    val tuiLine get() = "Herdr TUI: " + (tuiVersion?.let { "$it, " + if (tuiRunning) "running" else "not running" } ?: "not installed")
}

// One herdr-monitor server. older: it has no /api/info, so no versions.
data class ServerInfo(val url: String, val name: String, val os: String?, val self: Boolean, val herdr: HerdrInfo?, val older: Boolean = false, val host: String? = null)

data class Commit(val sha: String, val author: String, val date: String, val subject: String, val local: Boolean)
data class Commits(val repo: String?, val reason: String?, val branch: String?, val unpushed: Int?, val dirty: Int?, val pr: Pr?, val commits: List<Commit>)
data class CommitFile(val file: String, val additions: Int?, val deletions: Int?)
data class CommitDetail(val sha: String, val author: String, val date: String, val message: String, val files: List<CommitFile>)
data class Check(val name: String, val bucket: String, val url: String?)
data class PrStatus(val repo: String?, val reason: String?, val branch: String?, val pr: Pr?, val readiness: String?, val reasons: List<String>, val checks: List<Check>)

object Json {
    fun obj(vararg pairs: Pair<String, Any?>): String = JSONObject().apply { for ((k, v) in pairs) put(k, v ?: JSONObject.NULL) }.toString()

    fun errorOf(text: String): String? = try { JSONObject(text).optStringOrNull("error") } catch (e: Exception) { null }

    fun answerBody(shape: List<QuestionShape>, q: Int, option: Int, action: String?): String {
        val qs = JSONArray()
        for (s in shape) qs.put(JSONObject().put("question", s.question).put("options", s.options).put("multi", s.multi))
        val o = JSONObject().put("questions", qs)
        if (action != null) o.put("action", action) else o.put("q", q).put("option", option)
        return o.toString()
    }

    fun keysBody(keys: List<String>): String = JSONObject().put("keys", JSONArray(keys)).toString()

    private fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k) || !has(k)) null else optString(k)
    private fun JSONObject.optLongOrNull(k: String): Long? = if (isNull(k) || !has(k)) null else optLong(k)
    private fun JSONObject.optIntOrNull(k: String): Int? = if (isNull(k) || !has(k)) null else optInt(k)
    private inline fun <T> JSONArray?.mapObj(f: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it)?.let(f) }
    }
    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }

    fun terminal(o: JSONObject): Terminal {
        val st = o.optJSONObject("status")
        return Terminal(
            address = o.getString("address"),
            name = Glyphs.label(o.optStringOrNull("name")),
            agent = o.optString("agent", "?"),
            cwd = o.optStringOrNull("cwd"),
            state = o.optString("state", "live"),
            title = Glyphs.label(o.optStringOrNull("title")),
            where = Glyphs.label(o.optStringOrNull("where")),
            reported = o.optBoolean("reported"),
            status = st?.optStringOrNull("state"),
            kind = st?.optStringOrNull("kind"),
            msg = Glyphs.label(st?.optStringOrNull("msg")),
            exitCode = o.optIntOrNull("exit_code"),
            parent = o.optStringOrNull("parent"),
            space = o.optStringOrNull("space"),
            tab = o.optStringOrNull("tab"),
            changedMs = o.optLongOrNull("changed_ms"),
            seenMs = o.optLongOrNull("seen_ms"),
            listen = o.optJSONObject("listen")?.let { l -> Listen(l.optString("scheme", "http"), l.optInt("port"), l.optStringOrNull("host")) }?.takeIf { it.port > 0 },
        )
    }

    fun spaces(a: JSONArray?): List<Space> = a.mapObj { s ->
        Space(
            id = s.getString("id"), name = Glyphs.label(s.optString("name"))!!, color = s.optStringOrNull("color"),
            repo = s.optStringOrNull("repo"), branch = s.optStringOrNull("branch"),
            worktree = s.optBoolean("worktree"), virtual = s.optBoolean("virtual"),
            parent = s.optStringOrNull("parent"), behind = s.optInt("behind"),
            tabs = s.optJSONArray("tabs").mapObj { t -> SpaceTab(t.getString("id"), Glyphs.label(t.optString("name"))!!, t.optBoolean("named"), t.optJSONArray("terminals").strings()) },
        )
    }

    fun terminals(a: JSONArray?): List<Terminal> = a.mapObj(::terminal)

    fun agent(o: JSONObject) = AgentInfo(
        address = o.getString("address"), name = Glyphs.label(o.optString("name", o.getString("address")))!!, state = o.optString("state", "idle"),
        kind = o.optStringOrNull("kind"), msg = Glyphs.label(o.optStringOrNull("msg")),
        changedMs = o.optLongOrNull("changed_ms"), seenMs = o.optLongOrNull("seen_ms"),
    )

    fun agents(a: JSONArray?) = a.mapObj(::agent)

    fun pr(o: JSONObject?): Pr? = o?.let {
        Pr(it.optInt("number"), it.optString("title"), it.optString("url"), it.optBoolean("isDraft"),
            it.optString("headRefName"), it.optStringOrNull("reviewDecision"), it.optStringOrNull("updatedAt"))
    }

    fun overview(text: String): List<OverviewPr> = JSONObject(text).optJSONArray("prs").mapObj { p ->
        val counts = p.optJSONObject("counts")
        OverviewPr(p.optString("space"), p.optString("spaceName"), p.optStringOrNull("color"), p.optStringOrNull("branch"),
            pr(p.optJSONObject("pr")) ?: return@mapObj null, p.optJSONObject("readiness")?.optStringOrNull("state"),
            counts?.optInt("fail") ?: 0, counts?.optInt("pending") ?: 0)
    }.filterNotNull()

    fun herdr(o: JSONObject?): HerdrInfo? = o?.let {
        val ui = it.optJSONObject("ui"); val tui = it.optJSONObject("tui")
        HerdrInfo(ui?.optStringOrNull("version"), ui?.optBoolean("running") ?: false, tui?.optStringOrNull("version"), tui?.optBoolean("running") ?: false, it.optStringOrNull("hc"))
    }

    fun info(text: String): HerdrInfo? = herdr(JSONObject(text).optJSONObject("herdr"))
    fun infoHost(text: String): String? = JSONObject(text).optStringOrNull("host")
    fun isHealth(text: String): Boolean = try { JSONObject(text).let { it.optBoolean("ok") && it.has("socket") && it.has("terminals") } } catch (e: Exception) { false }

    fun discover(text: String): List<ServerInfo> = JSONObject(text).optJSONArray("servers").mapObj {
        ServerInfo(it.getString("url"), it.optString("name"), it.optStringOrNull("os"), it.optBoolean("self"), herdr(it.optJSONObject("herdr")), it.optBoolean("older"))
    }

    fun started(text: String): String = JSONObject(text).getString("address")

    fun readText(text: String): String = Glyphs.mono(JSONObject(text).optString("text"))
    fun uploadPath(text: String): String = JSONObject(text).optString("path")

    // null when the server says the version did not change.
    fun chat(text: String): Chat? {
        val o = JSONObject(text)
        if (o.optBoolean("unchanged")) return null
        return Chat(
            available = o.optBoolean("available"), reason = o.optStringOrNull("reason"), version = o.optStringOrNull("version"),
            truncated = o.optBoolean("truncated"),
            messages = o.optJSONArray("messages").mapObj { m ->
                Message(m.optString("id"), m.optString("role"), m.optStringOrNull("at"), m.optJSONArray("parts").mapObj(::part).filterNotNull())
            },
        )
    }

    // A result is {text, error, truncated} (lib/transcript.js).
    private fun result(o: JSONObject): ToolResult? {
        if (!o.has("result") || o.isNull("result")) return null
        val r = o.opt("result")
        return if (r is JSONObject) ToolResult(Glyphs.mono(r.optString("text")), r.optBoolean("error"), r.optBoolean("truncated"))
        else ToolResult(r.toString(), false, false)
    }

    private fun part(p: JSONObject): Part? = when (p.optString("kind")) {
        "text" -> Part.Text(Glyphs.prose(p.optString("text")))
        "thinking" -> Part.Thinking(Glyphs.prose(p.optString("text")))
        "tool" -> Part.Tool(p.optString("name"), Glyphs.label(p.optString("summary"))!!, result(p))
        "question" -> Part.Ask(p.optString("id"), p.optJSONArray("questions").mapObj { q ->
            Question(Glyphs.label(q.optString("header"))!!, Glyphs.prose(q.optString("question")), q.optBoolean("multi"),
                q.optJSONArray("options").mapObj { Option(Glyphs.label(it.optString("label"))!!, Glyphs.label(it.optString("description"))!!) })
        }, answered = result(p) != null, answer = result(p)?.text)
        else -> null
    }

    fun commits(text: String): Commits {
        val o = JSONObject(text)
        return Commits(o.optStringOrNull("repo"), o.optStringOrNull("reason") ?: o.optStringOrNull("error"), o.optStringOrNull("branch"),
            o.optIntOrNull("unpushed"), o.optIntOrNull("dirty"), pr(o.optJSONObject("pr")),
            o.optJSONArray("commits").mapObj { Commit(it.optString("sha"), it.optString("author"), it.optString("date"), it.optString("subject"), it.optBoolean("local")) })
    }

    fun commitDetail(text: String): CommitDetail {
        val o = JSONObject(text)
        return CommitDetail(o.optString("sha"), o.optString("author"), o.optString("date"), o.optString("message"),
            o.optJSONArray("files").mapObj { CommitFile(it.optString("file"), it.optIntOrNull("additions"), it.optIntOrNull("deletions")) })
    }

    fun prStatus(text: String): PrStatus {
        val o = JSONObject(text)
        val rd = o.optJSONObject("readiness")
        return PrStatus(o.optStringOrNull("repo"), o.optStringOrNull("reason") ?: o.optStringOrNull("error"), o.optStringOrNull("branch"),
            pr(o.optJSONObject("pr")), rd?.optStringOrNull("state"), rd?.optJSONArray("reasons").strings(),
            o.optJSONArray("checks").mapObj { Check(it.optString("name"), it.optString("bucket"), it.optStringOrNull("url")) })
    }
}
