package io.github.ashrodan.herdrmonitor.ui

import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ashrodan.herdrmonitor.ui.theme.Mono
import io.github.ashrodan.herdrmonitor.Link
import io.github.ashrodan.herdrmonitor.Machine
import io.github.ashrodan.herdrmonitor.machineTerminals
import io.github.ashrodan.herdrmonitor.Live
import io.github.ashrodan.herdrmonitor.OverviewPr
import io.github.ashrodan.herdrmonitor.Route
import io.github.ashrodan.herdrmonitor.Space
import io.github.ashrodan.herdrmonitor.Terminal
import io.github.ashrodan.herdrmonitor.age
import io.github.ashrodan.herdrmonitor.app
import io.github.ashrodan.herdrmonitor.appUrl
import io.github.ashrodan.herdrmonitor.agentAddresses
import io.github.ashrodan.herdrmonitor.displayName
import io.github.ashrodan.herdrmonitor.groupAgents
import io.github.ashrodan.herdrmonitor.isAgent
import io.github.ashrodan.herdrmonitor.isApp
import io.github.ashrodan.herdrmonitor.mark
import io.github.ashrodan.herdrmonitor.shortPath
import io.github.ashrodan.herdrmonitor.spaceState
import io.github.ashrodan.herdrmonitor.stateOf
import kotlinx.coroutines.delay

// The link state, in one line. "Connecting…" waits 1 s, so a quick
// connect does not push the screen down and back up. With cached sessions
// on screen, it says how old they are.
@Composable
fun ConnectionLine(live: Live) {
    var late by remember { mutableStateOf(false) }
    LaunchedEffect(live.link) { late = false; if (live.link == Link.Connecting) { delay(1_000); late = true } }
    val from = live.cachedAt?.let { " Showing sessions from ${clockOf(it)}." } ?: ""
    val text = when {
        live.link == Link.Connecting -> if (late || live.cachedAt != null) "Connecting…$from" else null
        live.link == Link.Offline -> "No link to the server" + (live.error?.let { ": $it" } ?: "") + "." + from
        !live.hcConnected -> "Connected, but Herdr (hc) is not running on the Mac"
        else -> null
    } ?: return
    Text(text, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
        color = if (live.link == Link.Offline) Palette.error else Palette.mutedForeground, style = MaterialTheme.typography.bodySmall)
}

// "9:31" today, else "Nov 6, 9:31".
fun clockOf(ms: Long): String {
    val z = java.time.ZoneId.systemDefault()
    val t = java.time.Instant.ofEpochMilli(ms).atZone(z)
    val today = java.time.LocalDate.now(z)
    val hm = java.time.format.DateTimeFormatter.ofPattern("H:mm")
    return if (t.toLocalDate() == today) t.format(hm) else t.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, H:mm"))
}

// The rows' ages tick.
@Composable
fun rememberNow(): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    return now
}

fun openUrl(c: Context, url: String) = runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

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
fun LazyListScope.spacesList(ctx: Context, live: Live, go: (Route) -> Unit) {
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

@Composable
private fun Empty(text: String) = Text(text, Modifier.padding(horizontal = 16.dp, vertical = 24.dp), color = Palette.mutedForeground, style = MaterialTheme.typography.bodyMedium)

// The program's letter (web Glyph without Herdr's icons).
@Composable
fun Glyph(agent: String) {
    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        if (harnessIcon(agent) != null) HarnessIcon(agent, 18.dp)
        else Text(agent.take(1).uppercase(), fontSize = 12.sp, color = Palette.mutedForeground)
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
            Text(url ?: "port ${t.listen?.port} · the machine's address is not known", fontSize = 12.sp, fontFamily = Mono,
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

// A space is a horizontal set of agent sessions. App servers are actions
// above the composer; shell terminals stay behind Session options.
@Composable
fun SpaceScreen(live: Live, id: String, go: (Route) -> Unit, back: () -> Unit) {
    val space = live.spaces.find { it.id == id }
    val addresses = remember(space, live.terminals) { space?.agentAddresses(live.terminals).orEmpty() }
    if (addresses.isEmpty()) {
        SpaceWithoutAgents(live, space, go, back)
        return
    }

    SessionPager(live, addresses, go, back, space = id)
}

// Sessions side by side: a swipe changes the session, and dots under the
// title show which one is on screen. space: the space they belong to, when
// they share one.
@Composable
fun SessionPager(live: Live, addresses: List<String>, go: (Route) -> Unit, back: () -> Unit, space: String? = null, rawTerminal: Boolean = false) {
    val pager = rememberPagerState(pageCount = { addresses.size })
    val scope = rememberCoroutineScope()
    LaunchedEffect(addresses) {
        if (pager.currentPage > addresses.lastIndex) pager.scrollToPage(addresses.lastIndex)
    }
    HorizontalPager(
        state = pager,
        key = { addresses[it] },
        modifier = Modifier.fillMaxSize().testTag("space-session-pager"),
    ) { page ->
        AgentScreen(
            live = live,
            address = addresses[page],
            back = back,
            pageIndex = page,
            pageCount = addresses.size,
            active = pager.currentPage == page,
            showTerminalOnOpen = rawTerminal,
            // From the map: a session of this pager is a swipe away, any
            // other opens on its own.
            open = { a -> val i = addresses.indexOf(a); if (i >= 0) scope.launch { pager.animateScrollToPage(i) } else go(Route.Agent(a)) },
            // This space is already open: only the map closes.
            openSpace = { s -> if (s != space) go(Route.SpaceR(s)) },
        )
    }
}

// A machine that the server reaches through hc, opened from the Machines
// list: its agent sessions, a swipe apart. With no agent there, its first
// terminal as a raw terminal.
@Composable
fun MachineScreen(live: Live, name: String, go: (Route) -> Unit, back: () -> Unit) {
    val all = remember(name, live.terminals) { machineTerminals(Machine(name, hcName = name), live.terminals.values) }
    val agents = remember(all) { all.filter { it.isAgent() && !it.isApp() }.map { it.address } }
    val addresses = agents.ifEmpty { all.take(1).map { it.address } }
    if (addresses.isEmpty()) {
        // Its last terminal ended, or the link is not up yet.
        Scaffold(containerColor = Palette.background) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                ConnectionLine(live)
                Empty(if (live.link == Link.Online) "No terminals on $name." else "Connecting…")
            }
        }
        return
    }
    SessionPager(live, addresses, go, back, rawTerminal = agents.isEmpty())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpaceWithoutAgents(live: Live, space: Space?, go: (Route) -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    val terminals = remember(space, live.terminals) {
        space?.tabs?.flatMap { it.terminals }?.distinct()?.mapNotNull(live.terminals::get).orEmpty()
    }
    val apps = remember(terminals) { terminals.filter { it.isApp() && !it.finished } }
    val shells = remember(terminals) { terminals.filter { !it.isApp() && !it.isAgent() && !it.finished } }
    var menu by remember { mutableStateOf(false) }
    Scaffold(containerColor = Palette.background, topBar = {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.background),
            navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (space != null) { SpaceAvatar(space); Spacer(Modifier.width(10.dp)) }
                    Column {
                        Text(space?.name ?: "Space", maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        if (space != null) Text(shortPath(space.repo) + (space.branch?.let { " · $it" } ?: "") +
                            if (space.behind > 0) " · ↓${space.behind}" else "", fontFamily = Mono, fontSize = 11.sp,
                            color = Palette.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
            actions = {
                if (shells.isNotEmpty()) Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Space options") }
                    DropdownMenu(menu, { menu = false }) {
                        shells.forEach { shell ->
                            DropdownMenuItem({ Text("Raw shell · ${shell.name ?: shell.agent}") }, {
                                menu = false
                                go(Route.Agent(shell.address, terminal = true))
                            })
                        }
                    }
                }
            },
        )
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()),
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 10.dp, bottom = pad.calculateBottomPadding() + 10.dp)) {
            item { ConnectionLine(live) }
            if (space == null) {
                item { Empty(if (live.link == Link.Online) "This space is gone." else "Connecting…") }
                return@LazyColumn
            }
            item { Empty("No agent sessions in this space.") }
            if (apps.isNotEmpty()) {
                item { SectionTitle("Running apps", apps.size) }
                items(apps, key = { "app-${it.address}" }) { app -> AppRow(ctx, app, app.name ?: "Server") }
            }
        }
    }
}
