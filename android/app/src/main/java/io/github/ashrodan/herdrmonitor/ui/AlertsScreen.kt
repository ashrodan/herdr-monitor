package io.github.ashrodan.herdrmonitor.ui

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ashrodan.herdrmonitor.AlertService
import io.github.ashrodan.herdrmonitor.Health
import io.github.ashrodan.herdrmonitor.Notifier
import io.github.ashrodan.herdrmonitor.age
import io.github.ashrodan.herdrmonitor.app
import io.github.ashrodan.herdrmonitor.healthLine
import io.github.ashrodan.herdrmonitor.mayNotify
import io.github.ashrodan.herdrmonitor.setQuiet
import io.github.ashrodan.herdrmonitor.shortHost

// What the alert path looks like now, without logs: the link's health, the
// permission, each channel's importance, battery mode and the counts the
// service last saw. A local test alert, and Retry (it reconnects; it never
// restarts the server).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.app.prefs
    val health by ctx.app.health.collectAsStateWithLifecycle()
    val counts by ctx.app.alertCounts.collectAsStateWithLifecycle()
    var alerts by remember { mutableStateOf(prefs.alerts) }
    var quiet by remember { mutableStateOf(prefs.quiet) }
    var note by remember { mutableStateOf<String?>(null) }
    // System settings change outside the app: read them again on return.
    var round by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { round++; onPauseOrDispose {} }

    val nm = ctx.getSystemService(NotificationManager::class.java)
    val pm = ctx.getSystemService(PowerManager::class.java)
    val allowed = remember(round) { ctx.mayNotify() && nm.areNotificationsEnabled() }
    val unrestricted = remember(round) { pm.isIgnoringBatteryOptimizations(ctx.packageName) }
    val importance = remember(round) { CHANNELS.associate { (id, _) -> id to nm.getNotificationChannel(id)?.importance } }

    Scaffold(containerColor = Palette.background, topBar = {
        TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.background), title = { Text("Alerts") },
            navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()).verticalScroll(rememberScrollState())
            .padding(bottom = pad.calculateBottomPadding() + 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {

            val ok = health is Health.Connected
            Fact("Stream", if (!alerts) "Off" else healthLine(health), if (!alerts) null else if (ok) Palette.done else if (health == Health.Connecting) null else Palette.error,
                Modifier.testTag("alerts-stream"))
            Fact("Server", shortHost(prefs.server))
            Fact("Last event", if (counts.lastEventMs > 0) age(counts.lastEventMs) + " ago" else "None yet")
            Fact("Agents", "${counts.needsYou} need you · ${counts.working} working · ${counts.failed} failed · ${counts.finished} finished")

            HorizontalDivider(color = Palette.border)
            Toggle("Background alerts", "One link to the server, a silent overview of active agents, and the widget.", alerts) { on ->
                alerts = on; prefs.alerts = on
                if (on) AlertService.start(ctx) else AlertService.stop(ctx)
            }
            Toggle("Quiet agents", "Only Needs you and alert-health notifications. No overview, Running or Finished.", quiet) { on ->
                quiet = on; setQuiet(ctx, on)
            }

            HorizontalDivider(color = Palette.border)
            Fact("Notifications", if (allowed) "Allowed" else "Blocked", if (allowed) Palette.done else Palette.error,
                onClick = if (allowed) null else ({ appNotificationSettings(ctx) }))
            Fact("Battery", if (unrestricted) "Unrestricted" else "Restricted: Android may pause the link", if (unrestricted) Palette.done else Palette.blocked,
                onClick = if (unrestricted) null else ({ batterySettings(ctx) }))
            for ((id, name) in CHANNELS) {
                val imp = importance[id]
                val off = imp == null || imp == NotificationManager.IMPORTANCE_NONE
                Fact("Channel: $name", importanceLabel(imp), if (off) Palette.error else null, Modifier.testTag("channel-$id"),
                    onClick = { channelSettings(ctx, id) })
            }

            HorizontalDivider(color = Palette.border)
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    Notifier.channels(ctx)
                    Notifier.postTest(ctx)
                    note = if (allowed) "Test alert posted on Needs you. No agent was contacted." else "Notifications are blocked: allow them first."
                }, Modifier.fillMaxWidth().testTag("alerts-test")) { Text("Send test alert") }
                OutlinedButton(onClick = {
                    if (!prefs.alerts) note = "Turn on background alerts first."
                    else { AlertService.start(ctx, reconnect = true); note = "Reconnecting. This does not restart the server." }
                }, Modifier.fillMaxWidth()) { Text("Retry connection") }
                OutlinedButton(onClick = { appNotificationSettings(ctx) }, Modifier.fillMaxWidth()) { Text("Android notification settings") }
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.mutedForeground) }
            }
        }
    }
}

private val CHANNELS = listOf(Notifier.CH_NEEDS to "Needs you", Notifier.CH_RUNNING to "Running", Notifier.CH_FINISHED to "Finished", Notifier.CH_HEALTH to "Alert health")

fun importanceLabel(i: Int?): String = when (i) {
    null -> "Missing"
    NotificationManager.IMPORTANCE_NONE -> "Off"
    NotificationManager.IMPORTANCE_MIN -> "Minimum"
    NotificationManager.IMPORTANCE_LOW -> "Silent"
    NotificationManager.IMPORTANCE_DEFAULT -> "Sound"
    NotificationManager.IMPORTANCE_HIGH, NotificationManager.IMPORTANCE_MAX -> "Pop up and sound"
    else -> "Importance $i"
}

@Composable
private fun Fact(label: String, value: String, tint: Color? = null, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Palette.mutedForeground)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = tint ?: Palette.foreground)
        }
        if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Palette.mutedForeground)
    }
}

@Composable
private fun Toggle(title: String, text: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodySmall, color = Palette.mutedForeground)
        }
        Switch(on, set)
    }
}

private fun start(c: Context, i: Intent) = runCatching { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

private fun appNotificationSettings(c: Context) =
    start(c, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName))

private fun channelSettings(c: Context, id: String) = start(c, Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
    .putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, id))

// The list of apps and their battery mode (no direct request: Settings has
// that button, and Play policy frowns on asking from every screen).
private fun batterySettings(c: Context) = start(c, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
