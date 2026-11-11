package io.github.ashrodan.herdrmonitor.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ashrodan.herdrmonitor.Live
import io.github.ashrodan.herdrmonitor.Machine
import io.github.ashrodan.herdrmonitor.Route
import io.github.ashrodan.herdrmonitor.Servers
import io.github.ashrodan.herdrmonitor.app
import io.github.ashrodan.herdrmonitor.displayName
import io.github.ashrodan.herdrmonitor.isAgent
import io.github.ashrodan.herdrmonitor.machineLine
import io.github.ashrodan.herdrmonitor.machineTerminals
import io.github.ashrodan.herdrmonitor.mark
import io.github.ashrodan.herdrmonitor.shortHost
import io.github.ashrodan.herdrmonitor.shortPath
import kotlinx.coroutines.delay

// The machines of the tailnet, read from the server in use and read again
// each minute. Empty while it loads, and with an older server.
@Composable
fun rememberMachines(server: String): List<Machine> {
    var machines by remember(server) { mutableStateOf<List<Machine>>(emptyList()) }
    LaunchedEffect(server) { while (true) { machines = Servers.machines(server).ifEmpty { machines }; delay(60_000) } }
    return machines
}

// One machine: a dot (green: the phone can work there; grey: it cannot),
// the short name, the system, and what the phone can do with it.
@Composable
fun MachineRow(m: Machine, via: String, onClick: (() -> Unit)? = null, action: String? = null) {
    val usable = m.self || m.linked || m.server != null
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).let { if (onClick != null) it.clickable(onClick = onClick) else it }
        .padding(horizontal = 12.dp, vertical = 8.dp).testTag("machine-${m.short}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            Dot(when { usable -> Palette.done; m.online -> Palette.idle; else -> Palette.border }, 8.dp)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.short, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    color = if (m.online) Palette.foreground else Palette.mutedForeground)
                m.os?.let { Text("  $it", fontSize = 12.sp, color = Palette.mutedForeground, maxLines = 1) }
            }
            Text(machineLine(m, via), fontSize = 12.sp, color = Palette.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (action != null) Text(action, fontSize = 12.sp, color = Palette.working)
    }
}

// The Machines section of the Spaces tab: every machine of the tailnet. A
// machine that the server reaches through hc shows its terminals below it,
// and a tap opens one through the server (no server on that machine). A
// machine with its own herdr-monitor has "Use", which changes the server.
fun LazyListScope.machinesList(ctx: Context, live: Live, machines: List<Machine>, now: Long, go: (Route) -> Unit) {
    if (machines.isEmpty()) return
    val via = shortHost(ctx.app.prefs.server)
    val seen = ctx.app.prefs.localSeen
    item(key = "machines-title") { MachinesTitle(machines.size) }
    for (m in machines) {
        val other = m.server?.takeIf { !m.self }
        item(key = "machine-${m.name}") {
            MachineRow(m, via, onClick = other?.let { url -> { useServer(ctx, url) } }, action = other?.let { "Use" })
        }
        if (m.self) continue
        items(machineTerminals(m, live.terminals.values), key = { "mt-${it.address}" }) { t ->
            Box(Modifier.padding(start = 20.dp)) {
                TerminalRow(t, displayName(t, live.spaces), t.title ?: shortPath(t.cwd), t.mark(seen), now,
                    tag = if (t.isAgent()) null else "shell") { go(Route.Agent(t.address, terminal = !t.isAgent())) }
            }
        }
    }
}

@Composable
private fun MachinesTitle(count: Int) {
    Row(Modifier.padding(start = 12.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("MACHINES", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Palette.mutedForeground, letterSpacing = 0.6.sp)
        Text("  $count", fontSize = 11.sp, color = Palette.mutedForeground)
    }
}
