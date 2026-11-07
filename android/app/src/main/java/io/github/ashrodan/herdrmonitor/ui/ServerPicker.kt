package io.github.ashrodan.herdrmonitor.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ashrodan.herdrmonitor.AlertService
import io.github.ashrodan.herdrmonitor.Link
import io.github.ashrodan.herdrmonitor.ServerInfo
import io.github.ashrodan.herdrmonitor.Servers
import io.github.ashrodan.herdrmonitor.app
import io.github.ashrodan.herdrmonitor.normalizeServer
import io.github.ashrodan.herdrmonitor.portOf
import io.github.ashrodan.herdrmonitor.shortHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Use another server: save it, and reconnect the screens and the alerts.
fun useServer(c: Context, url: String): Boolean {
    val n = normalizeServer(url) ?: return false
    c.app.prefs.server = n
    c.app.store.restart()
    AlertService.start(c, reconnect = true)
    return true
}

// The server in use, at the top of Home: machine, port, "this Mac", and
// whether the link is up. A tap opens the picker.
@Composable
fun ServerChip(server: String, link: Link, onClick: () -> Unit) {
    var self by remember(server) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(server) { self = withContext(Dispatchers.IO) { Servers.probe(server)?.self } }
    Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).background(Palette.card).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Dot(when (link) { Link.Online -> Palette.done; Link.Connecting -> Palette.blocked; Link.Offline -> Palette.error }, 7.dp)
        Spacer(Modifier.width(7.dp))
        Text(shortHost(server), style = MaterialTheme.typography.labelLarge, maxLines = 1)
        Text(" :${portOf(server)}" + if (self == true) " · this Mac" else "", style = MaterialTheme.typography.labelMedium, color = Palette.mutedForeground, maxLines = 1)
        Text("  ▾", style = MaterialTheme.typography.labelMedium, color = Palette.mutedForeground)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.card) {
        ServerList(Modifier.padding(bottom = 16.dp), onPicked = onDismiss)
    }
}

// Every herdr-monitor the phone can reach, with the Herdr app and the Herdr
// TUI on two lines each. A tap uses that server.
@Composable
fun ServerList(modifier: Modifier = Modifier, onPicked: () -> Unit = {}) {
    val ctx = LocalContext.current
    val saved = ctx.app.prefs.server
    var servers by remember { mutableStateOf<List<ServerInfo>?>(null) }
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(round) { servers = null; servers = Servers.find(saved) }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Herdr servers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { round++ }, enabled = servers != null) { Text("Look again") }
        }
        Text("Servers on your tailnet that this phone can reach. The phone must be on Tailscale.",
            Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = Palette.mutedForeground)
        Spacer(Modifier.padding(4.dp))
        val list = servers
        when {
            list == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
            list.isEmpty() -> Text("No server answered. Turn on Tailscale, or type the URL in Settings.", Modifier.padding(16.dp), color = Palette.blocked)
            else -> Column {
                list.forEach { s ->
                    val inUse = normalizeServer(s.url) == normalizeServer(saved)
                    Column(Modifier.fillMaxWidth().clickable { if (useServer(ctx, s.url)) onPicked() }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(shortHost(s.url), fontWeight = FontWeight.SemiBold)
                            Text("  :${portOf(s.url)}", color = Palette.mutedForeground)
                            if (s.self) Text("  · this Mac", style = MaterialTheme.typography.labelMedium, color = Palette.mutedForeground)
                            Spacer(Modifier.weight(1f))
                            if (inUse) Text("in use", style = MaterialTheme.typography.labelMedium, color = Palette.done)
                        }
                        val h = s.herdr
                        if (h != null) {
                            Text(h.appLine, style = MaterialTheme.typography.bodySmall, color = if (h.uiRunning) Palette.foreground else Palette.mutedForeground)
                            Text(h.tuiLine, style = MaterialTheme.typography.bodySmall, color = if (h.tuiRunning) Palette.foreground else Palette.mutedForeground)
                        } else Text(if (s.older) "An older herdr-monitor: it does not report Herdr versions." else "Versions unknown.",
                            style = MaterialTheme.typography.bodySmall, color = Palette.mutedForeground)
                        Text(s.url, style = MaterialTheme.typography.labelSmall, color = Palette.mutedForeground)
                    }
                    HorizontalDivider(color = Palette.border)
                }
            }
        }
    }
}
