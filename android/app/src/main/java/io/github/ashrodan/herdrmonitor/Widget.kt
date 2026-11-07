package io.github.ashrodan.herdrmonitor

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// The home-screen widget, like the Summary mod: Waiting, Done (not seen),
// Working and PR counts, then the rows (waiting agents, done agents, open
// PRs). A row opens that agent (or the PR); anywhere else opens the app.
object Widget {
    private val scope = CoroutineScope(Dispatchers.Default)

    fun save(c: Context, d: WidgetData) {
        c.app.prefs.widgetJson = toJson(d)
        scope.launch { runCatching { AgentWidget().updateAll(c.applicationContext) } }
    }

    fun toJson(d: WidgetData): String = JSONObject()
        .put("connected", d.connected).put("waiting", d.waiting).put("done", d.done).put("working", d.working)
        .put("prs", d.prs).put("updated", d.updatedMs)
        .put("rows", JSONArray().apply {
            d.rows.forEach { r ->
                put(JSONObject().put("address", r.address ?: JSONObject.NULL).put("url", r.url ?: JSONObject.NULL)
                    .put("label", r.label).put("detail", r.detail).put("state", r.state))
            }
        }).toString()

    fun fromJson(s: String?): WidgetData? = try {
        val o = JSONObject(s ?: return null)
        val rows = o.getJSONArray("rows")
        WidgetData(o.getBoolean("connected"), o.getInt("waiting"), o.getInt("done"), o.getInt("working"), o.getInt("prs"),
            (0 until rows.length()).map { i ->
                rows.getJSONObject(i).let { r ->
                    WidgetRow(r.optString("address").takeIf { !r.isNull("address") }, r.optString("url").takeIf { !r.isNull("url") },
                        r.getString("label"), r.getString("detail"), r.getString("state"))
                }
            }, o.getLong("updated"))
    } catch (e: Exception) { null }
}

class AgentWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AgentWidget()
}

private val BG = ColorProvider(Color(0xFF16181D))
private val FG = ColorProvider(Color(0xFFF3F4F6))
private val MUTED = ColorProvider(Color(0xFF9CA3AF))
private fun stateColor(s: String) = ColorProvider(Color(Notifier.colorOf(if (s == "pr") "idle" else s)))

class AgentWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = Widget.fromJson(context.app.prefs.widgetJson)
        provideContent { Content(context, data) }
    }

    @Composable
    private fun Content(c: Context, d: WidgetData?) {
        val openApp = actionStartActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Column(GlanceModifier.fillMaxSize().background(BG).cornerRadius(16.dp).padding(10.dp).clickable(openApp)) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Tile(d?.waiting, "Waiting", "blocked")
                Tile(d?.done, "Done", "done")
                Tile(d?.working, "Working", "working")
                Tile(d?.prs, "PRs", "idle")
            }
            Spacer(GlanceModifier.height(6.dp))
            when {
                d == null -> Text("Open the app to connect", style = TextStyle(color = MUTED, fontSize = 12.sp))
                d.rows.isEmpty() -> Text(if (d.connected) "Nothing needs you" else "Not connected", style = TextStyle(color = MUTED, fontSize = 12.sp))
                else -> LazyColumn {
                    items(d.rows) { r -> RowView(c, r) }
                }
            }
            if (d != null && !d.connected) {
                Text("Not connected · last ${age(d.updatedMs)} ago", style = TextStyle(color = MUTED, fontSize = 10.sp))
            }
        }
    }

    @Composable
    private fun Tile(n: Int?, label: String, state: String) {
        Column(GlanceModifier.width(64.dp).padding(horizontal = 2.dp)) {
            Text(n?.toString() ?: "–", style = TextStyle(color = stateColor(state), fontSize = 18.sp, fontWeight = FontWeight.Bold))
            Text(label, style = TextStyle(color = MUTED, fontSize = 10.sp))
        }
    }

    @Composable
    private fun RowView(c: Context, r: WidgetRow) {
        val intent = if (r.url != null) Intent(Intent.ACTION_VIEW, Uri.parse(r.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        else openAgentIntent(c, r.address)
        Row(GlanceModifier.fillMaxWidth().padding(vertical = 3.dp).clickable(actionStartActivity(intent)), verticalAlignment = Alignment.CenterVertically) {
            Box(GlanceModifier.size(8.dp).cornerRadius(4.dp).background(stateColor(r.state))) {}
            Spacer(GlanceModifier.width(6.dp))
            Column {
                Text(r.label, maxLines = 1, style = TextStyle(color = FG, fontSize = 13.sp, fontWeight = FontWeight.Medium))
                if (r.detail.isNotBlank()) Text(r.detail, maxLines = 1, style = TextStyle(color = MUTED, fontSize = 11.sp))
            }
        }
    }
}
