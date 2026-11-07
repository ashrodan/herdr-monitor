package io.github.ashrodan.herdrmonitor

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
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

// The home-screen widget (4x2): host name, three counts (Needs you,
// Working, PRs) and the top Needs you session with its first answer
// button. It updates when the stream pushes a change, not on a timer.
object Widget {
    private val scope = CoroutineScope(Dispatchers.Default)

    fun save(c: Context, d: WidgetData) {
        c.app.prefs.widgetJson = toJson(d)
        scope.launch { runCatching { AgentWidget().updateAll(c.applicationContext) } }
        // The Herdr tile shows the same counts.
        HerdrTile.refresh(c)
    }

    fun toJson(d: WidgetData): String = JSONObject()
        .put("connected", d.connected).put("host", d.host).put("needsYou", d.needsYou).put("working", d.working)
        .put("prs", d.prs).put("updated", d.updatedMs)
        .put("top", d.top?.let { t ->
            JSONObject().put("address", t.address).put("name", t.name).put("line", t.line).put("state", t.state)
                .put("option", t.option ?: JSONObject.NULL).put("shape", Notifier.shapeJson(t.shape))
        } ?: JSONObject.NULL).toString()

    fun fromJson(s: String?): WidgetData? = try {
        val o = JSONObject(s ?: return null)
        val top = o.optJSONObject("top")?.let { t ->
            WidgetTop(t.getString("address"), t.getString("name"), t.getString("line"), t.getString("state"),
                t.optString("option").takeIf { !t.isNull("option") }, Notifier.shapeFrom(t.optString("shape")))
        }
        WidgetData(o.getBoolean("connected"), o.getString("host"), o.getInt("needsYou"), o.getInt("working"), o.getInt("prs"), top, o.getLong("updated"))
    } catch (e: Exception) { null }
}

class AgentWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AgentWidget()
}

// The app's dark tokens (the widget is always dark, like the app default).
private val BG = ColorProvider(Color(0xFF111414))
private val CARD = ColorProvider(Color(0xFF1D2020))
private val FG = ColorProvider(Color(0xFFE0E3E2))
private val MUTED = ColorProvider(Color(0xFFBEC9C6))
private val WAITING = ColorProvider(Color(0xFFF4BF5F))
private val FAILED = ColorProvider(Color(0xFFFFB4A9))
private val PRIMARY = ColorProvider(Color(0xFF82D5C8))
private val ON_PRIMARY = ColorProvider(Color(0xFF003731))

class AgentWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = Widget.fromJson(context.app.prefs.widgetJson)
        provideContent { Content(context, data) }
    }

    @Composable
    private fun Content(c: Context, d: WidgetData?) {
        val openApp = actionStartActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Column(GlanceModifier.fillMaxSize().background(BG).cornerRadius(22.dp).padding(14.dp).clickable(openApp)) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(d?.host ?: "Herdr", GlanceModifier.defaultWeight(), maxLines = 1,
                    style = TextStyle(color = FG, fontSize = 14.sp, fontWeight = FontWeight.Medium))
                if (d != null && !d.connected) Text("No link · ${age(d.updatedMs)}", maxLines = 1, style = TextStyle(color = MUTED, fontSize = 11.sp))
            }
            Spacer(GlanceModifier.height(8.dp))
            Row(GlanceModifier.fillMaxWidth()) {
                Stat(d?.needsYou, "Needs you", if ((d?.needsYou ?: 0) > 0) WAITING else FG, GlanceModifier.defaultWeight())
                Spacer(GlanceModifier.width(6.dp))
                Stat(d?.working, "Working", FG, GlanceModifier.defaultWeight())
                Spacer(GlanceModifier.width(6.dp))
                Stat(d?.prs, "PRs", FG, GlanceModifier.defaultWeight())
            }
            Spacer(GlanceModifier.height(8.dp))
            val t = d?.top
            when {
                d == null -> Text("Open the app to connect", style = TextStyle(color = MUTED, fontSize = 12.sp))
                t == null -> Text(if (d.connected) "Nothing needs you" else "Not connected", style = TextStyle(color = MUTED, fontSize = 12.sp))
                else -> TopSession(c, t)
            }
        }
    }

    @Composable
    private fun Stat(n: Int?, label: String, color: ColorProvider, modifier: GlanceModifier) {
        Column(modifier.background(CARD).cornerRadius(14.dp).padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(n?.toString() ?: "–", style = TextStyle(color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold))
            Text(label, maxLines = 1, style = TextStyle(color = MUTED, fontSize = 11.sp))
        }
    }

    // The top Needs you session: a tap opens it; its first answer button
    // answers it (the same route as the notification button).
    @Composable
    private fun TopSession(c: Context, t: WidgetTop) {
        Row(GlanceModifier.fillMaxWidth().background(CARD).cornerRadius(14.dp).padding(10.dp)
            .clickable(actionStartActivity(openAgentIntent(c, t.address))), verticalAlignment = Alignment.CenterVertically) {
            Box(GlanceModifier.size(8.dp).cornerRadius(4.dp).background(if (t.state == "error") FAILED else WAITING)) {}
            Spacer(GlanceModifier.width(8.dp))
            Column(GlanceModifier.defaultWeight()) {
                Text(t.name, maxLines = 1, style = TextStyle(color = FG, fontSize = 13.sp, fontWeight = FontWeight.Medium))
                Text(t.line, maxLines = 2, style = TextStyle(color = MUTED, fontSize = 12.sp))
            }
            t.option?.let { o ->
                Spacer(GlanceModifier.width(8.dp))
                Text(o.take(18), GlanceModifier.background(PRIMARY).cornerRadius(16.dp).padding(horizontal = 12.dp, vertical = 6.dp)
                    .clickable(actionSendBroadcast(Notifier.answerIntent(c, t.address, t.shape, 0, o))),
                    maxLines = 1, style = TextStyle(color = ON_PRIMARY, fontSize = 13.sp, fontWeight = FontWeight.Medium))
            }
        }
    }
}
