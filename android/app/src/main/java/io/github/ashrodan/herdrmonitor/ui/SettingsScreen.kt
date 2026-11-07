package io.github.ashrodan.herdrmonitor.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ashrodan.herdrmonitor.AlertService
import io.github.ashrodan.herdrmonitor.Api
import io.github.ashrodan.herdrmonitor.DEFAULT_SERVER
import io.github.ashrodan.herdrmonitor.HerdrInfo
import io.github.ashrodan.herdrmonitor.Json
import io.github.ashrodan.herdrmonitor.ServerInfo
import io.github.ashrodan.herdrmonitor.app
import io.github.ashrodan.herdrmonitor.isTailnetAddress
import io.github.ashrodan.herdrmonitor.mayNotify
import io.github.ashrodan.herdrmonitor.normalizeServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Is the phone on a tailnet now? A VPN network with a 100.64.0.0/10 address
// (Tailscale's range). Android does not let other apps read Tailscale's
// peer list, so finding servers goes through a server we can reach.
fun onTailnet(c: Context): Boolean {
    val cm = c.getSystemService(ConnectivityManager::class.java)
    @Suppress("DEPRECATION")
    return cm.allNetworks.any { n ->
        val caps = cm.getNetworkCapabilities(n)
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
            cm.getLinkProperties(n)?.linkAddresses?.any { isTailnetAddress(it.address.hostAddress ?: "") } == true
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.app.prefs
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(prefs.server) }
    var saved by remember { mutableStateOf(prefs.server) }
    var note by remember { mutableStateOf<String?>(null) }
    var alerts by remember { mutableStateOf(prefs.alerts) }
    var info by remember { mutableStateOf<HerdrInfo?>(null) }
    var infoErr by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<List<ServerInfo>?>(null) }
    var finding by remember { mutableStateOf(false) }
    val tailnet = remember { onTailnet(ctx) }

    fun save(u: String) {
        val n = normalizeServer(u) ?: run { note = "Not a server URL"; return }
        prefs.server = n; url = n; saved = n; note = "Saved."
        ctx.app.store.restart()
        AlertService.start(ctx, reconnect = true)
    }
    LaunchedEffect(saved) {
        info = null; infoErr = null
        withContext(Dispatchers.IO) { runCatching { Json.info(Api(saved).get("/api/info", 10000)) } }
            .onSuccess { info = it }.onFailure { infoErr = it.message }
    }
    fun find() {
        finding = true; found = null; note = null
        scope.launch {
            // Ask the saved server, and the default one if that is another.
            val bases = listOf(saved, DEFAULT_SERVER).distinct()
            val all = withContext(Dispatchers.IO) {
                bases.flatMap { b -> runCatching { Json.discover(Api(b).get("/api/discover", 30000)) }.getOrDefault(emptyList()) }
            }.distinctBy { it.url }
            found = all
            if (all.isEmpty()) note = "No server answered. Check that the phone is on Tailscale, or type the URL."
            finding = false
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (tailnet) "Tailscale: on (this phone has a tailnet address)" else "Tailscale: not detected. Turn on Tailscale to reach the Mac.",
                color = if (tailnet) stateColor("done") else stateColor("blocked"), style = MaterialTheme.typography.bodySmall)

            Text("Server", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("herdr-monitor URL") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { save(url) }) { Text("Save") }
                OutlinedButton(onClick = { find() }, enabled = !finding) { Text(if (finding) "Looking…" else "Find on tailnet") }
            }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(when {
                info != null -> info!!.describe()
                infoErr != null -> "Cannot reach $saved: $infoErr"
                else -> "Asking $saved…"
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            found?.let { list ->
                for (s in list) {
                    Column(Modifier.fillMaxWidth().clickable { save(s.url) }.padding(vertical = 6.dp)) {
                        Text(s.name + if (s.url == saved) "  (in use)" else "", fontWeight = FontWeight.Medium)
                        Text(listOfNotNull(s.os, s.herdr?.describe()).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(s.url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Alerts in the background", style = MaterialTheme.typography.titleSmall)
                    Text("Keeps one link to the server and shows a quiet \"connected\" notification. Also keeps the widget current.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(alerts, { on ->
                    alerts = on; prefs.alerts = on
                    if (on) AlertService.start(ctx) else AlertService.stop(ctx)
                })
            }
            if (!ctx.mayNotify()) {
                OutlinedButton(onClick = { appSettings(ctx) }) { Text("Allow notifications") }
            }
            val pm = ctx.getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                Text("Android may pause the link when the phone sleeps. Let the app run unrestricted for alerts on time.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { askBattery(ctx) }) { Text("Run unrestricted") }
            }
        }
    }
}

private fun appSettings(c: Context) = c.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName))

@SuppressLint("BatteryLife")
private fun askBattery(c: Context) = c.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${c.packageName}")))
