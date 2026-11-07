package io.github.ashrodan.herdrmonitor.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ashrodan.herdrmonitor.Link
import io.github.ashrodan.herdrmonitor.Live
import io.github.ashrodan.herdrmonitor.OverviewPr
import io.github.ashrodan.herdrmonitor.Route
import io.github.ashrodan.herdrmonitor.Space
import io.github.ashrodan.herdrmonitor.Terminal
import io.github.ashrodan.herdrmonitor.age
import io.github.ashrodan.herdrmonitor.app
import io.github.ashrodan.herdrmonitor.appUrl
import io.github.ashrodan.herdrmonitor.displayName
import io.github.ashrodan.herdrmonitor.groupAgents
import io.github.ashrodan.herdrmonitor.isAgent
import io.github.ashrodan.herdrmonitor.isApp
import io.github.ashrodan.herdrmonitor.mark
import io.github.ashrodan.herdrmonitor.shortPath
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
        color = if (live.link == Link.Offline) Palette.error else Palette.mutedForeground, style = MaterialTheme.typography.bodySmall)
}

// The rows' ages tick.
@Composable
fun rememberNow(): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    return now
}

fun openUrl(c: Context, url: String) = runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

private val SECTIONS = listOf("Spaces", "Agents", "Overview")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(live: Live, go: (Route) -> Unit) {
    val ctx = LocalContext.current
    val now = rememberNow()
    var section by rememberSaveable { mutableStateOf("Spaces") }
    var picking by remember { mutableStateOf(false) }
    val server = ctx.app.prefs.server

    Scaffold(containerColor = Palette.background, topBar = {
        TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.background),
            title = { ServerChip(server, live.link) { picking = true } },
            actions = { IconButton(onClick = { go(Route.Settings) }) { Icon(Icons.Default.Settings, "Settings") } })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            ConnectionLine(live)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                SECTIONS.forEachIndexed { i, s ->
                    SegmentedButton(selected = section == s, onClick = { section = s }, shape = SegmentedButtonDefaults.itemShape(i, SECTIONS.size),
                        colors = SegmentedButtonDefaults.colors(activeContainerColor = Palette.accent, activeContentColor = Palette.foreground,
                            inactiveContainerColor = Palette.background, inactiveContentColor = Palette.mutedForeground, activeBorderColor = Palette.border, inactiveBorderColor = Palette.border),
                        icon = {}) { Text(s) }
                }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 6.dp, end = 6.dp, bottom = 24.dp)) {
                when (section) {
                    "Spaces" -> spacesList(ctx, live, go)
                    "Agents" -> agentsList(ctx, live, now, go)
                    else -> overview(ctx, live, now, go)
                }
            }
        }
    }
    if (picking) ServerSheet { picking = false }
}

@Composable
private fun SectionTitle(text: String, count: Int? = null) {
    Row(Modifier.padding(start = 12.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Palette.mutedForeground, letterSpacing = 0.6.sp)
        if (count != null) Text("  $count", fontSize = 11.sp, color = Palette.mutedForeground)
    }
}

// web Sidebar.tsx SpacesList: avatar (a branch mark for a worktree, indented
// under its repo), name, status dot, branch, ↓behind. A virtual space is not
// a button.
private fun LazyListScope.spacesList(ctx: Context, live: Live, go: (Route) -> Unit) {
    val seen = ctx.app.prefs.localSeen
    if (live.spaces.isEmpty()) { item { Empty(if (live.link == Link.Online) "No spaces." else "Connecting to Herdr…") }; return }
    items(live.spaces, key = { "s-${it.id}" }) { s -> SpaceRow(s, spaceState(s, live.terminals, seen)) { go(Route.SpaceR(s.id)) } }
    val apps = live.terminals.values.filter { it.isApp() && !it.finished }
    if (apps.isNotEmpty()) {
        item { SectionTitle("Apps", apps.size) }
        items(apps, key = { "app-${it.address}" }) { t -> AppRow(ctx, t, displayName(t, live.spaces)) }
    }
}

@Composable
private fun SpaceRow(s: Space, state: String?, onClick: () -> Unit) {
    val m = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).let { if (s.virtual) it else it.clickable(onClick = onClick) }
        .padding(start = if (s.parent != null) 22.dp else 12.dp, end = 12.dp, top = 9.dp, bottom = 9.dp)
    Row(m, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (s.parent != null) Text("⎇", color = Palette.mutedForeground, fontSize = 14.sp, modifier = Modifier.widthIn(min = 14.dp))
        else SpaceAvatar(s)
        Text(s.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (s.virtual) null else FontWeight.Medium, color = if (s.virtual) Palette.mutedForeground else Palette.foreground)
        if (state != null) StatusMark(state, 8.dp)
        s.branch?.let { Text(it, Modifier.widthIn(max = 140.dp), fontSize = 12.sp, color = Palette.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        if (s.behind > 0) Text("↓${s.behind}", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Palette.error)
    }
}

// web AgentsList (by state).
private fun LazyListScope.agentsList(ctx: Context, live: Live, now: Long, go: (Route) -> Unit) {
    val seen = ctx.app.prefs.localSeen
    val groups = groupAgents(live.terminals.values, seen)
    if (groups.isEmpty()) { item { Empty(if (live.link == Link.Online) "No agents running." else "Connecting to Herdr…") }; return }
    for (g in groups) {
        item(key = "h-${g.state}") { SectionTitle(g.label, g.rows.size) }
        items(g.rows, key = { "a-${it.address}" }) { t ->
            TerminalRow(t, displayName(t, live.spaces), t.msg?.takeIf { it.isNotBlank() } ?: t.title, t.stateOf(seen), now) { go(Route.Agent(t.address)) }
        }
    }
}

// The Overview: the counts, then what needs you, then open PRs and apps.
private fun LazyListScope.overview(ctx: Context, live: Live, now: Long, go: (Route) -> Unit) {
    val seen = ctx.app.prefs.localSeen
    val groups = groupAgents(live.terminals.values, seen).associateBy { it.state }
    val waiting = (groups["blocked"]?.rows.orEmpty() + groups["error"]?.rows.orEmpty())
    val done = groups["done"]?.rows.orEmpty()
    item {
        Row(Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile("Waiting", waiting.size, "blocked", Modifier.weight(1f))
            Tile("Done", done.size, "done", Modifier.weight(1f))
            Tile("Working", groups["working"]?.rows?.size ?: 0, "working", Modifier.weight(1f))
            Tile("PRs", live.prs.size, "pr", Modifier.weight(1f))
        }
    }
    if (waiting.isNotEmpty()) {
        item { SectionTitle("Waiting", waiting.size) }
        items(waiting, key = { "w-${it.address}" }) { t -> TerminalRow(t, displayName(t, live.spaces), t.msg ?: t.title, t.stateOf(seen), now) { go(Route.Agent(t.address)) } }
    }
    if (done.isNotEmpty()) {
        item { SectionTitle("Done", done.size) }
        items(done, key = { "d-${it.address}" }) { t -> TerminalRow(t, displayName(t, live.spaces), t.msg ?: t.title, "done", now) { go(Route.Agent(t.address)) } }
    }
    if (live.prs.isNotEmpty()) {
        item { SectionTitle("Open PRs", live.prs.size) }
        items(live.prs, key = { "p-${it.pr.url}" }) { p -> PrRow(p) { openUrl(ctx, p.pr.url) } }
    }
}

@Composable
fun Tile(label: String, n: Int, state: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(Palette.card).border(1.dp, Palette.border, RoundedCornerShape(10.dp)).padding(10.dp)) {
        Text("$n", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = if (n > 0 && state != "pr") stateColor(state) else Palette.foreground)
        Text(label, fontSize = 11.sp, color = Palette.mutedForeground)
    }
}

@Composable
private fun Empty(text: String) = Text(text, Modifier.padding(horizontal = 16.dp, vertical = 24.dp), color = Palette.mutedForeground, style = MaterialTheme.typography.bodyMedium)

// The program's letter (web Glyph without Herdr's icons).
@Composable
fun Glyph(agent: String) {
    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        Text(agent.take(1).uppercase(), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Palette.mutedForeground)
    }
}

// One terminal: glyph, name (+ a word: shell, app), a second line, age, mark.
@Composable
fun TerminalRow(t: Terminal, name: String, detail: String?, mark: String?, now: Long, tag: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Glyph(t.agent)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (tag != null) Text("  $tag", fontSize = 12.sp, color = Palette.mutedForeground, maxLines = 1)
            }
            if (!detail.isNullOrBlank()) Text(detail, fontSize = 12.sp, color = Palette.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(age(t.changedMs, now), fontSize = 12.sp, color = Palette.mutedForeground)
        StatusMark(mark)
    }
}

// A running app: a tap opens it in the phone's browser on the machine's
// tailnet name. Without a known host the row shows, but does not open.
@Composable
fun AppRow(ctx: Context, t: Terminal, name: String) {
    val url = appUrl(t, ctx.app.prefs.server)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).let { if (url != null) it.clickable { openUrl(ctx, url) } else it }
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { Text("◉", color = Palette.done, fontSize = 13.sp) }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text("  app", fontSize = 12.sp, color = Palette.mutedForeground)
            }
            Text(url ?: "port ${t.listen?.port} · the machine's address is not known", fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                color = if (url != null) Palette.working else Palette.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (url != null) Text("Open ↗", fontSize = 12.sp, color = Palette.working)
    }
}

@Composable
fun PrRow(p: OverviewPr, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            Dot(when { p.fail > 0 -> Palette.error; p.pending > 0 -> Palette.blocked; else -> parseColor(p.color) ?: Palette.idle }, 8.dp)
        }
        Column(Modifier.weight(1f)) {
            Text("#${p.pr.number} ${p.pr.title}", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            val st = listOfNotNull(p.spaceName, p.pr.headRefName.takeIf { it.isNotBlank() }, if (p.pr.isDraft) "draft" else p.pr.reviewDecision?.lowercase()?.replace('_', ' '))
            Text(st.joinToString(" · "), fontSize = 12.sp, color = Palette.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// web SpaceView: a header (avatar, name, repo path, branch, ↓behind), then
// one section per tab in Herdr's order, one row per terminal. A shell says
// "shell"; an app opens in the browser.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceScreen(live: Live, id: String, go: (Route) -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    val seen = ctx.app.prefs.localSeen
    val now = rememberNow()
    val s = live.spaces.find { it.id == id }
    Scaffold(containerColor = Palette.background, topBar = {
        TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.background),
            navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (s != null) { SpaceAvatar(s); Spacer(Modifier.width(10.dp)) }
                    Column {
                        Text(s?.name ?: "Space", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        if (s != null) Text(shortPath(s.repo) + (s.branch?.let { " · $it" } ?: "") + if (s.behind > 0) " · ↓${s.behind}" else "",
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Palette.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            })
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(10.dp)) {
            item { ConnectionLine(live) }
            if (s == null) { item { Empty(if (live.link == Link.Online) "This space is gone." else "Connecting…") }; return@LazyColumn }
            for (tab in s.tabs) {
                item(key = "t-${tab.id}") { SectionTitle(if (tab.named) tab.name else "Tab ${tab.name}") }
                item(key = "tl-${tab.id}") {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.card).border(1.dp, Palette.border, RoundedCornerShape(8.dp))) {
                        tab.terminals.mapNotNull { live.terminals[it] }.forEach { t ->
                            if (t.isApp()) AppRow(ctx, t, t.name ?: t.agent)
                            else TerminalRow(t, t.name ?: t.agent, t.msg?.takeIf { it.isNotBlank() } ?: t.title?.takeIf { it.isNotBlank() } ?: shortPath(t.cwd),
                                t.mark(seen), now, tag = if (!t.isAgent()) "shell" else null) { go(Route.Agent(t.address)) }
                        }
                    }
                }
            }
        }
    }
}
