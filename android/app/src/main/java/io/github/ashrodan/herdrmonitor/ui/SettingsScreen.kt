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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import io.github.ashrodan.herdrmonitor.ui.theme.ThemePref
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedButton
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.key
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
    var url by remember { mutableStateOf(prefs.server) }
    var note by remember { mutableStateOf<String?>(null) }
    var alerts by remember { mutableStateOf(prefs.alerts) }
    var round by remember { mutableStateOf(0) }
    val tailnet = remember { onTailnet(ctx) }

    Scaffold(containerColor = Palette.background, topBar = {
        TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.background), title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()).imePadding().verticalScroll(rememberScrollState())
            .padding(top = 8.dp, bottom = pad.calculateBottomPadding() + 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (tailnet) "Tailscale: on (this phone has a tailnet address)" else "Tailscale: not detected. Turn on Tailscale to reach the Mac.",
                Modifier.padding(horizontal = 16.dp), color = if (tailnet) Palette.done else Palette.blocked, style = MaterialTheme.typography.bodySmall)

            key(round) { ServerList(onPicked = { url = prefs.server; note = "Now using ${prefs.server}" }) }

            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Or type a server URL", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("herdr-monitor URL") })
                Button(onClick = {
                    note = if (useServer(ctx, url)) { url = prefs.server; round++; "Saved." } else "Not a server URL"
                }) { Text("Use this server") }
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }

            HorizontalDivider(color = Palette.border)
            Appearance()
            HorizontalDivider(color = Palette.border)
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Alerts in the background", style = MaterialTheme.typography.titleSmall)
                    Text("Keeps one link to the server and shows a quiet \"connected\" notification. Also keeps the widget current.",
                        style = MaterialTheme.typography.bodySmall, color = Palette.mutedForeground)
                }
                Switch(alerts, { on ->
                    alerts = on; prefs.alerts = on
                    if (on) AlertService.start(ctx) else AlertService.stop(ctx)
                })
            }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!ctx.mayNotify()) OutlinedButton(onClick = { appSettings(ctx) }) { Text("Allow notifications") }
                val pm = ctx.getSystemService(PowerManager::class.java)
                if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                    Text("Android may pause the link when the phone sleeps. Let the app run unrestricted for alerts on time.",
                        style = MaterialTheme.typography.bodySmall, color = Palette.mutedForeground)
                    OutlinedButton(onClick = { askBattery(ctx) }) { Text("Run unrestricted") }
                }
            }
        }
    }
}

private fun appSettings(c: Context) = c.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName))

@SuppressLint("BatteryLife")
private fun askBattery(c: Context) = c.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${c.packageName}")))

// Dark is the default. Status colours stay the same with wallpaper colours on.
@Composable
private fun Appearance() {
    val ctx = LocalContext.current
    val look by ctx.app.look.collectAsStateWithLifecycle()
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Appearance", style = MaterialTheme.typography.titleSmall)
        val prefs = ThemePref.entries
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            prefs.forEachIndexed { i, p ->
                SegmentedButton(selected = look.pref == p, onClick = { ctx.app.setLook(look.copy(pref = p)) },
                    shape = SegmentedButtonDefaults.itemShape(i, prefs.size)) { Text(p.name) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Use wallpaper colours", style = MaterialTheme.typography.bodyLarge)
                Text("Takes the app colours from your wallpaper. Status colours do not change.",
                    style = MaterialTheme.typography.bodySmall, color = Palette.mutedForeground)
            }
            Switch(look.wallpaperColours, { ctx.app.setLook(look.copy(wallpaperColours = it)) })
        }
    }
}
