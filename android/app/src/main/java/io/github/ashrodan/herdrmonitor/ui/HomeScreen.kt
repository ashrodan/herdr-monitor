package io.github.ashrodan.herdrmonitor.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ashrodan.herdrmonitor.Link
import io.github.ashrodan.herdrmonitor.Live
import io.github.ashrodan.herdrmonitor.OverviewPr
import io.github.ashrodan.herdrmonitor.Route
import io.github.ashrodan.herdrmonitor.Terminal
import io.github.ashrodan.herdrmonitor.age
import io.github.ashrodan.herdrmonitor.app
import io.github.ashrodan.herdrmonitor.displayName
import io.github.ashrodan.herdrmonitor.groupAgents
import io.github.ashrodan.herdrmonitor.isAgent
import io.github.ashrodan.herdrmonitor.spaceState
import io.github.ashrodan.herdrmonitor.stateOf
import kotlinx.coroutines.delay

@Composable
fun ConnectionLine(live: Live) {
    val text = when {
        live.link == Link.Connecting -> "Connecting…"
        live.link == Link.Offline -> "No link to the server" + (live.error?.let { ": $it" } ?: "")
        !live.hcConnected -> "Connected, but Herdr (hc) is not running on the Mac"
        else -> null
    } ?: return
    Text(text, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        color = if (live.link == Link.Offline) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall)
}

// The rows' ages tick once a minute.
@Composable
fun rememberNow(): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    return now
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(live: Live, go: (Route) -> Unit) {
    val ctx = LocalContext.current
    val seen = ctx.app.prefs.localSeen
    val now = rememberNow()
    val groups = groupAgents(live.terminals.values, seen)
    val count = { s: String -> groups.find { it.state == s }?.rows?.size ?: 0 }
    val open = remember { mutableStateMapOf("idle" to false) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Herdr") }, actions = {
            IconButton(onClick = { go(Route.Settings) }) { Icon(Icons.Default.Settings, "Settings") }
        })
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { ConnectionLine(live) }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tile("Waiting", count("blocked") + count("error"), "blocked", Modifier.weight(1f))
                    Tile("Done", count("done"), "done", Modifier.weight(1f))
                    Tile("Working", count("working"), "working", Modifier.weight(1f))
                    Tile("PRs", live.prs.size, "idle", Modifier.weight(1f))
                }
            }
            for (g in groups) {
                val collapsed = open[g.state] == false
                item(key = "h-${g.state}") {
                    Header("${g.label} · ${g.rows.size}", collapsed) { open[g.state] = collapsed }
                }
                if (!collapsed) items(g.rows, key = { "a-${it.address}" }) { t ->
                    AgentRow(t, displayName(t, live.spaces), g.state, now) { go(Route.Agent(t.address)) }
                }
            }
            if (live.spaces.isNotEmpty()) {
                item { Header("Spaces · ${live.spaces.size}", false) {} }
                items(live.spaces, key = { "s-${it.id}" }) { s ->
                    val st = spaceState(s, live.terminals, seen)
                    Row(Modifier.fillMaxWidth().clickable { go(Route.SpaceR(s.id)) }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Dot(parseColor(s.color) ?: MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            s.branch?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                        }
                        if (st != null) Dot(stateColor(st), 8.dp)
                    }
                }
            }
            if (live.prs.isNotEmpty()) {
                item { Header("Open PRs · ${live.prs.size}", false) {} }
                items(live.prs, key = { "p-${it.pr.url}" }) { p -> PrRow(p) { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.pr.url))) } }
            }
        }
    }
}

@Composable
fun Tile(label: String, n: Int, state: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(10.dp)) {
            Text("$n", style = MaterialTheme.typography.headlineSmall, color = if (n > 0) stateColor(state) else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun Header(text: String, collapsed: Boolean, toggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = toggle).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        Icon(if (collapsed) Icons.Default.KeyboardArrowRight else Icons.Default.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun AgentRow(t: Terminal, name: String, state: String, now: Long, indent: Int = 0, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = (16 + indent * 16).dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Dot(stateColor(state))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = t.msg?.takeIf { it.isNotBlank() } ?: t.title ?: t.agent
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text(age(t.changedMs, now), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun PrRow(p: OverviewPr, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Dot(when { p.fail > 0 -> stateColor("error"); p.pending > 0 -> stateColor("blocked"); else -> parseColor(p.color) ?: MaterialTheme.colorScheme.outline })
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("#${p.pr.number} ${p.pr.title}", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            val st = listOfNotNull(p.spaceName, p.pr.headRefName.takeIf { it.isNotBlank() }, if (p.pr.isDraft) "draft" else p.pr.reviewDecision?.lowercase()?.replace('_', ' '))
            Text(st.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceScreen(live: Live, id: String, go: (Route) -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    val seen = ctx.app.prefs.localSeen
    val now = rememberNow()
    val s = live.spaces.find { it.id == id }
    Scaffold(topBar = {
        TopAppBar(title = { Text(s?.name ?: "Space", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            item { ConnectionLine(live) }
            if (s == null) { item { Text("This space is gone.", Modifier.padding(16.dp)) }; return@LazyColumn }
            s.repo?.let { item { Text(listOfNotNull(it, s.branch).joinToString(" · "), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            for (tab in s.tabs) {
                item(key = "t-${tab.id}") { Header(tab.name.ifBlank { "Tab" }, false) {} }
                tabRows(tab.terminals.mapNotNull { live.terminals[it] }, live, seen, now, go)
            }
        }
    }
}

// A tab's terminals: agents first, each with the terminals it started.
private fun LazyListScope.tabRows(ts: List<Terminal>, live: Live, seen: Map<String, Long>, now: Long, go: (Route) -> Unit) {
    val by = ts.associateBy { it.address }
    val kids = ts.filter { it.parent != null && by.containsKey(it.parent) }.groupBy { it.parent!! }
    val roots = ts.filter { it.parent == null || !by.containsKey(it.parent) }.sortedBy { if (it.isAgent()) 0 else 1 }
    val rows = mutableListOf<Pair<Terminal, Int>>()
    fun walk(t: Terminal, d: Int) { rows += t to d; kids[t.address]?.forEach { walk(it, d + 1) } }
    roots.forEach { walk(it, 0) }
    items(rows, key = { "r-${it.first.address}" }) { (t, d) ->
        val state = if (t.finished) "finished" else if (t.status != null) t.stateOf(seen) else "idle"
        AgentRow(t, displayName(t, live.spaces).let { n -> if (t.status == null) "${t.agent} · ${t.title ?: ""}".trimEnd(' ', '·') else n }, state, now, d) { go(Route.Agent(t.address)) }
    }
    item { HorizontalDivider(Modifier.padding(top = 4.dp)) }
}
