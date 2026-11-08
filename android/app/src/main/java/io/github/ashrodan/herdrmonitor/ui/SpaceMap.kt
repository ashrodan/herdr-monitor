package io.github.ashrodan.herdrmonitor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.ashrodan.herdrmonitor.Live
import io.github.ashrodan.herdrmonitor.Place
import io.github.ashrodan.herdrmonitor.Space
import io.github.ashrodan.herdrmonitor.SpaceTab
import io.github.ashrodan.herdrmonitor.Terminal
import io.github.ashrodan.herdrmonitor.Worktree
import io.github.ashrodan.herdrmonitor.app
import io.github.ashrodan.herdrmonitor.displayName
import io.github.ashrodan.herdrmonitor.harnessName
import io.github.ashrodan.herdrmonitor.panes
import io.github.ashrodan.herdrmonitor.placeOf
import io.github.ashrodan.herdrmonitor.spaceFamily
import io.github.ashrodan.herdrmonitor.stateOf
import io.github.ashrodan.herdrmonitor.worktreesWithoutSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Where you are, as an icon: the panes of this terminal's tab in miniature
// with yours filled, and under them one dot per tab with yours filled. A
// terminal that is in no tab shows one filled pane and no dots.
@Composable
fun WhereAmIIcon(place: Place?, address: String, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val here = MaterialTheme.colorScheme.primary
    val other = MaterialTheme.colorScheme.onSurfaceVariant
    val rects = place?.tab?.panes().orEmpty()
    val tabs = place?.space?.tabs?.size ?: 0
    Canvas(modifier.size(size)) {
        val dotRow = if (tabs > 1) this.size.height * 0.2f else 0f
        val gap = 1.5.dp.toPx()
        val area = Size(this.size.width, this.size.height - dotRow)
        val corner = CornerRadius(2.dp.toPx())
        val list = rects.ifEmpty { listOf(io.github.ashrodan.herdrmonitor.PaneRect(address, 0f, 0f, 1f, 1f)) }
        for (r in list) {
            val tl = Offset(r.x * area.width + gap / 2, r.y * area.height + gap / 2)
            val sz = Size((r.w * area.width - gap).coerceAtLeast(1f), (r.h * area.height - gap).coerceAtLeast(1f))
            if (r.terminal == address) drawRoundRect(here, tl, sz, corner)
            else drawRoundRect(other, tl + Offset(0.6f, 0.6f), Size(sz.width - 1.2f, sz.height - 1.2f), corner, style = Stroke(1.2.dp.toPx()))
        }
        if (tabs > 1) {
            val n = tabs.coerceAtMost(5)
            val current = (place?.tabIndex ?: 0).coerceAtMost(n - 1)
            val r = 1.6.dp.toPx()
            val step = r * 3.2f
            val start = this.size.width / 2 - step * (n - 1) / 2
            for (i in 0 until n) drawCircle(if (i == current) here else other.copy(alpha = 0.5f), r, Offset(start + i * step, this.size.height - r))
        }
    }
}

// The space map: this space and its worktrees, each with its tabs drawn as
// their panes (harness mark, name, state), and "New tab" to start an agent
// there. Worktrees of the repo that have no space in Herdr are listed under.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceMapSheet(live: Live, address: String, onDismiss: () -> Unit, onOpen: ((String) -> Unit)?, onSpace: ((String) -> Unit)? = null) {
    val ctx = LocalContext.current
    val t = live.terminals[address]
    val place = remember(t, address, live.spaces) { placeOf(t, address, live.spaces) }
    val family = remember(place, t, live.spaces) { spaceFamily(live.spaces, place?.space?.id ?: t?.space) }
    var worktrees by remember { mutableStateOf<List<Worktree>>(emptyList()) }
    var newTabIn by remember { mutableStateOf<String?>(null) }
    val head = family.firstOrNull()
    LaunchedEffect(head?.id) {
        val id = head?.id ?: return@LaunchedEffect
        worktrees = runCatching { withContext(Dispatchers.IO) { ctx.app.store.api.worktrees(id) } }.getOrDefault(emptyList())
    }
    val loose = remember(worktrees, family) { worktreesWithoutSpace(worktrees, family) }
    val seen = ctx.app.prefs.localSeen

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().testTag("space-map"), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Text(head?.name ?: "Not in a space", style = MaterialTheme.typography.titleLarge)
                    val sub = if (place == null) "This terminal is in no Herdr tab." else
                        "You are in ${place.space.name} · tab ${place.tab.name} (${place.tabIndex + 1} of ${place.space.tabs.size})"
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(family, key = { it.id }) { s ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // A space in Herdr opens; a repo with no space of its own does not.
                    SpaceLine(s, here = s.id == place?.space?.id, onClick = onSpace?.takeIf { !s.virtual }?.let { go -> { onDismiss(); go(s.id) } })
                    LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(s.tabs, key = { it.id }) { tab ->
                            TabCard(tab, live, address, seen, onOpen = onOpen?.let { open -> { a: String -> onDismiss(); open(a) } })
                        }
                        if (!s.virtual) item(key = "new") { NewTabCard(s) { newTabIn = s.id } }
                    }
                }
            }
            if (loose.isNotEmpty()) {
                item {
                    Text("Worktrees with no space in Herdr", Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(loose, key = { it.path }) { w ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                        Text(w.name, style = MaterialTheme.typography.bodyMedium)
                        Text(w.branch ?: "detached", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    newTabIn?.let { id ->
        NewSessionSheet(live, onDismiss = { newTabIn = null }, initialSpace = id, onStarted = { addr -> newTabIn = null; onDismiss(); onOpen?.invoke(addr) })
    }
}

@Composable
private fun SpaceLine(s: Space, here: Boolean, onClick: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClickLabel = "Open the space", onClick = onClick) else it }
        .padding(horizontal = 24.dp, vertical = 6.dp).testTag("space-line-${s.id}"), verticalAlignment = Alignment.CenterVertically) {
        if (s.worktree) Spacer(Modifier.width(12.dp))
        SpaceAvatar(s, 20.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(s.name + if (s.worktree) "  · worktree" else "", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            s.branch?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (here) Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Text("You are here", Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.padding(start = 4.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// One tab: its name, then its panes where Herdr draws them.
@Composable
fun TabCard(tab: SpaceTab, live: Live, current: String, seen: Map<String, Long>, onOpen: ((String) -> Unit)?) {
    val hereTab = current in tab.terminals
    OutlinedCard(Modifier.width(196.dp).testTag("tab-${tab.id}"),
        border = BorderStroke(if (hereTab) 2.dp else 1.dp, if (hereTab) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // The tab's name opens its first session that still runs.
            val first = tab.terminals.firstOrNull { it in live.terminals }
            Text("Tab ${tab.name}", Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                .let { m -> if (onOpen != null && first != null) m.clickable(onClickLabel = "Open the tab") { onOpen(first) } else m }
                .padding(vertical = 4.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BoxWithConstraints(Modifier.fillMaxWidth().height(112.dp)) {
                val W = maxWidth
                val H = maxHeight
                for (r in tab.panes()) {
                    val t = live.terminals[r.terminal]
                    PaneCell(t, r.terminal, displayName(t, live), state = t?.stateOf(seen), here = r.terminal == current,
                        modifier = Modifier.offset(W * r.x, H * r.y).size(W * r.w, H * r.h).padding(2.dp),
                        onClick = onOpen?.takeIf { t != null }?.let { { it(r.terminal) } })
                }
            }
        }
    }
}

private fun displayName(t: Terminal?, live: Live): String = if (t == null) "ended" else displayName(t, live.spaces).let {
    // The space name says nothing inside its own map: show the harness.
    if (t.name == null) harnessName(t.agent) else it
}

@Composable
private fun PaneCell(t: Terminal?, address: String, label: String, state: String?, here: Boolean, modifier: Modifier, onClick: (() -> Unit)?) {
    val shape = RoundedCornerShape(6.dp)
    val bg = if (here) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    Box(modifier) {
        Column(Modifier.fillMaxSize().background(bg, shape)
            .border(if (here) 2.dp else 0.dp, if (here) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(4.dp)
            .semantics(mergeDescendants = true) { contentDescription = label + (if (here) ", you are here" else "") }
            .testTag("pane-$address"),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HarnessIcon(t?.agent, 14.dp)
                Spacer(Modifier.weight(1f))
                StatusMark(state, 8.dp)
            }
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (here) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun NewTabCard(s: Space, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.width(120.dp).height(144.dp).testTag("new-tab-${s.id}")) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.Add, null)
            Text("New tab", style = MaterialTheme.typography.labelMedium)
            Text("an agent here", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
