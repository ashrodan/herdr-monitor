package io.github.ashrodan.herdrmonitor.ui

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ashrodan.herdrmonitor.userTexts
import io.github.ashrodan.herdrmonitor.Link
import io.github.ashrodan.herdrmonitor.OutItem
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.content.ClipData
import android.view.HapticFeedbackConstants
import androidx.compose.ui.platform.LocalView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.github.ashrodan.herdrmonitor.Item
import io.github.ashrodan.herdrmonitor.clock
import io.github.ashrodan.herdrmonitor.dedupName
import io.github.ashrodan.herdrmonitor.harnessMark
import io.github.ashrodan.herdrmonitor.harnessName
import io.github.ashrodan.herdrmonitor.runningTool
import io.github.ashrodan.herdrmonitor.toolCounts
import io.github.ashrodan.herdrmonitor.ticketOf
import io.github.ashrodan.herdrmonitor.transcriptItems
import io.github.ashrodan.herdrmonitor.whereOf
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ashrodan.herdrmonitor.ui.theme.Mono
import io.github.ashrodan.herdrmonitor.Api
import io.github.ashrodan.herdrmonitor.Chat
import io.github.ashrodan.herdrmonitor.CommitDetail
import io.github.ashrodan.herdrmonitor.Commits
import io.github.ashrodan.herdrmonitor.Json
import io.github.ashrodan.herdrmonitor.Live
import io.github.ashrodan.herdrmonitor.Message
import io.github.ashrodan.herdrmonitor.Notifier
import io.github.ashrodan.herdrmonitor.Part
import io.github.ashrodan.herdrmonitor.PrStatus
import io.github.ashrodan.herdrmonitor.age
import io.github.ashrodan.herdrmonitor.app
import io.github.ashrodan.herdrmonitor.displayName
import io.github.ashrodan.herdrmonitor.lastUnansweredAsk
import io.github.ashrodan.herdrmonitor.shape
import io.github.ashrodan.herdrmonitor.shortPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private suspend fun <T> io(block: () -> T): Result<T> = withContext(Dispatchers.IO) { runCatching(block) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(live: Live, address: String, back: () -> Unit, initialTab: Int = 0, showBack: Boolean = true) {
    val ctx = LocalContext.current
    val api = remember(live) { ctx.app.store.api }
    val port = remember(api) { ApiPort(api) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val outboxAll by ctx.app.outbox.items.collectAsStateWithLifecycle()
    val queued = outboxAll.filter { it.address == address }
    val t = live.terminals[address]
    val now = rememberNow()
    var tab by remember { mutableIntStateOf(initialTab) }
    var menu by remember { mutableStateOf(false) }

    // Commits load once here: the Changes tab shows them, and its pill
    // shows the changed-file count.
    var commits by remember(address) { mutableStateOf<Commits?>(null) }
    var commitsErr by remember(address) { mutableStateOf<String?>(null) }
    val loadCommits = { fresh: Boolean ->
        scope.launch {
            io { Json.commits(api.get(api.terminalPath(address, "commits") + if (fresh) "?fresh" else "", 30000)) }
                .onSuccess { commits = it; commitsErr = null }.onFailure { commitsErr = it.message }
        }
    }
    LaunchedEffect(address, api.base) { loadCommits(false) }

    // Opening a finished agent counts as seeing it (as in the web UI), and
    // its alert goes.
    LaunchedEffect(address, t?.status) {
        if (t?.status == "done") ctx.app.prefs.markSeen(address)
        Notifier.cancelAgent(ctx, address)
        ctx.app.prefs.markOpened(address)
    }

    val blocked = t?.status == "blocked"
    val working = t?.status == "working"
    val rawState = if (t?.finished == true) "finished" else t?.status ?: "idle"
    val where = t?.let { whereOf(it, live.spaces) }
    val name = t?.let { dedupName(displayName(it, live.spaces)) } ?: address
    val branch = where?.space?.branch ?: commits?.branch
    val pr = where?.space?.let { s -> live.prs.firstOrNull { it.space == s.id } }?.pr ?: commits?.pr
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = {
        Column(Modifier.statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showBack) IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                else Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // "Working · MNT-1 · omp · 8:06": the time is how long the
                    // agent has been in this state.
                    val bits = listOfNotNull(
                        STATE_WORD[rawState] ?: rawState,
                        ticketOf(name) ?: where?.space?.name?.let { ticketOf(it) ?: it }
                            ?: t?.where?.substringBefore(" / ")?.let { ticketOf(it) ?: it.takeIf(String::isNotBlank) },
                        t?.agent?.let { harnessName(it) },
                        t?.changedMs?.let { clock(now - it) },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(stateColor(rawState), 9.dp)
                        Text("  " + bits.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = {
                    val body = listOfNotNull(name, branch, pr?.url).joinToString("\n")
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body), null))
                }) { Icon(Icons.Default.Share, "Share") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        if (t != null && (working || blocked) && live.link == Link.Online) DropdownMenuItem({ Text("Stop") }, {
                            menu = false
                            scope.launch { io { api.interrupt(address) } }
                        })
                        DropdownMenuItem({ Text("Open terminal") }, { menu = false; tab = 1 })
                        branch?.let { b ->
                            DropdownMenuItem({ Text("Copy branch") }, {
                                menu = false
                                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("branch", b))) }
                            })
                        }
                    }
                }
            }
            if (t != null) PillTabs(tab, { tab = it }, changes = commits?.dirty ?: 0)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }, bottomBar = {
        // Offline, an agent the app has not heard of yet (a cold start) still
        // takes replies: they wait in the outbox.
        val offline = live.link != Link.Online
        if (t != null && !t.finished) Composer(port, ctx.app.outbox, address, online = !offline, blocked, t.kind, working = working, harness = t.agent)
        else if (t == null && offline) Composer(port, ctx.app.outbox, address, online = false, blocked = false, kind = null, working = false)
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            ConnectionLine(live)
            if (t == null) {
                if (live.link != Link.Online) NoChat("No link to the server. The chat loads when the link is back.", queued)
                else Text("This terminal is gone.", Modifier.padding(16.dp))
                return@Column
            }
            when (tab) {
                0 -> ChatTab(api, address, blocked, working, t.agent, queued)
                1 -> TextTab(api, address)
                2 -> CommitsTab(api, address, commits, commitsErr) { loadCommits(true) }
                else -> StatusTab(api, address)
            }
        }
    }
}

private val STATE_WORD = mapOf("blocked" to "Needs you", "error" to "Failed", "done" to "Ready", "working" to "Working", "idle" to "Idle", "finished" to "Finished")
private val TABS = listOf("Chat", "Terminal", "Changes", "Status")

// Pill tabs: the selected one on secondaryContainer. Changes shows the
// changed-file count when there is one.
@Composable
private fun PillTabs(selected: Int, onSelect: (Int) -> Unit, changes: Int) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 10.dp)
        .selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TABS.forEachIndexed { i, label ->
            val on = i == selected
            // 32dp pill, 48dp touch target.
            Row(Modifier.selectable(on, role = Role.Tab) { onSelect(i) }.minimumInteractiveComponentSize().heightIn(min = 32.dp).clip(RoundedCornerShape(16.dp))
                .background(if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelLarge,
                    color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                if (i == 2 && changes > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text("$changes", Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(horizontal = 6.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

// ---- Chat: prose first, activity folded ----

@Composable
private fun ChatTab(api: Api, address: String, blocked: Boolean, working: Boolean, agent: String, queued: List<OutItem>) {
    val ctx = LocalContext.current
    // The last transcript read for this agent shows at once; the loop
    // below brings it up to date.
    var chat by remember(address) { mutableStateOf(ctx.app.chats.get(api.base, address)) }
    var err by remember(address) { mutableStateOf<String?>(null) }
    LaunchedEffect(address, api.base) {
        while (true) {
            io { api.chat(address, chat?.version) }
                .onSuccess { c -> if (c != null) { chat = c; ctx.app.chats.put(api.base, address, c); ctx.app.outbox.dropDelivered(address, userTexts(c.messages)) }; err = null }
                .onFailure { err = it.message }
            delay(if (working || blocked) 2500 else 6000)
        }
    }
    val c = chat
    when {
        c == null && err != null -> NoChat("Could not read the transcript: $err", queued, error = true)
        c == null -> if (queued.isEmpty()) SkeletonChat() else NoChat("Loading the conversation…", queued)
        !c.available -> NoChat("${c.reason ?: "No transcript."} Use Terminal.", queued)
        else -> {
            val list = rememberLazyListState()
            // The first transcript folds here (no blank frame); later ones
            // fold off the main thread, and the list keeps the old items
            // until the new ones are ready. The question to answer comes from
            // the same transcript as the items (AskCard compares identity).
            var shown by remember(address) { mutableStateOf(c to transcriptItems(c.messages)) }
            LaunchedEffect(c) {
                if (shown.first !== c) shown = withContext(Dispatchers.Default) { c to transcriptItems(c.messages) }
            }
            val items = shown.second
            val waitingOn = if (blocked) remember(shown) { lastUnansweredAsk(shown.first.messages) } else null
            val running = if (working) runningTool(items) else null
            LaunchedEffect(items.size, queued.size, working) { list.layoutInfo.totalItemsCount.takeIf { it > 0 }?.let { list.scrollToItem(it - 1) } }
            LazyColumn(Modifier.fillMaxSize(), state = list, verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                if (c.truncated) item { SystemChip("Older messages are not shown (long transcript).") }
                if (items.isEmpty()) item { SystemChip("No messages yet.") }
                items(items, key = { it.key }) { i ->
                    when (i) {
                        is Item.Prose -> Prose(i, agent)
                        is Item.User -> UserBubble(i.text)
                        is Item.System -> SystemChip(i.text)
                        is Item.Activity -> ActivityCard(i)
                        is Item.Question -> AskCard(i.ask, api, address, live = i.ask === waitingOn)
                    }
                }
                if (working) item(key = "live") { LiveStep(running) }
                if (queued.isNotEmpty()) item(key = "outbox") { QueuedReplies(queued) { ctx.app.outbox.remove(it.id) } }
            }
        }
    }
}

@Composable
private fun Prose(p: Item.Prose, agent: String) {
    Column {
        if (p.who) Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(18.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Text(harnessMark(agent), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(6.dp))
            Text(harnessName(agent), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SelectionContainer { MarkdownView(p.text) }
    }
}

// Your text: a bubble on the right, at most ~85% wide.
@Composable
private fun UserBubble(text: String) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val w = maxWidth * 0.85f
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp), color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.widthIn(max = w)) {
                SelectionContainer { MarkdownView(text, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), keepBreaks = true) }
            }
        }
    }
}

// A system line (a background job that finished, and so on): a centred chip.
@Composable
private fun SystemChip(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(text, Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 12.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

// One run of thinking and tool calls. Closed: the tool counts ("6 bash ·
// 1 read") and how many failed. Open: one step per tool or thought; a tap
// on a tool shows its output. No durations: the transcript has no times.
@Composable
internal fun ActivityCard(a: Item.Activity) {
    var open by rememberSaveable(a.key) { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    val tools = a.tools
    Column(Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainerLowest)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = if (open) "Fold" else "Unfold") { open = !open }
            .padding(horizontal = 12.dp, vertical = 10.dp).testTag("activity-${a.key}"), verticalAlignment = Alignment.CenterVertically) {
            Text(if (tools.isEmpty()) "Thinking" else toolCounts(tools), Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (a.failed > 0) Text("  · ${a.failed} failed", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, maxLines = 1)
            Spacer(Modifier.weight(0.0001f))
            val turn by animateFloatAsState(if (open) 180f else 0f, if (reducedMotion()) snap() else spring(), label = "chev")
            Icon(Icons.Default.KeyboardArrowDown, if (open) "Fold" else "Unfold", Modifier.size(20.dp).rotate(turn), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.padding(vertical = 6.dp)) {
                for (s in a.steps) when (s) {
                    is Part.Tool -> ToolStep(s)
                    is Part.Thinking -> Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                        StepIcon("thinking", false)
                        Text(s.text, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun ToolStep(t: Part.Tool) {
    var open by remember { mutableStateOf(false) }
    val failed = t.result?.error == true
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().let { if (t.result != null) it.heightIn(min = 48.dp) else it }
            .clickable(enabled = t.result != null, onClickLabel = "Show output") { open = !open }.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            StepIcon(t.name, failed)
            Text(buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)) { append(t.name) }
                if (t.summary.isNotBlank()) { append("  "); withStyle(SpanStyle(fontFamily = Mono)) { append(t.summary) } }
            }, Modifier.weight(1f).padding(start = 8.dp), fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        val r = t.result
        if (open && r != null) {
            Text(r.text.ifEmpty { "(no output)" } + if (r.truncated) "\n… (cut)" else "",
                Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(max = 300.dp).clip(RoundedCornerShape(8.dp)).background(Palette.code)
                    .verticalScroll(rememberScrollState()).padding(10.dp),
                fontFamily = Mono, fontSize = 11.sp, lineHeight = 14.sp, color = Palette.foreground)
        }
    }
}

// A small icon per kind of step, from the core icon set; a shell is ">_".
@Composable
private fun StepIcon(name: String, failed: Boolean) {
    val tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val n = name.lowercase()
    val icon = when {
        failed -> Icons.Default.Warning
        n == "thinking" -> Icons.Default.Info
        n.contains("grep") || n.contains("glob") || n.contains("search") || n.contains("find") -> Icons.Default.Search
        n.contains("edit") || n.contains("write") || n.contains("patch") -> Icons.Default.Edit
        n.contains("read") || n.contains("fetch") || n.contains("ls") -> Icons.AutoMirrored.Filled.List
        n.contains("bash") || n.contains("shell") || n.contains("exec") || n.contains("run") -> null
        else -> Icons.Default.Build
    }
    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
        if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = tint)
        else Text(">_", fontFamily = Mono, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = tint)
    }
}

// The live step, last while the agent works: a dot, "Running <tool>" and
// its command. No timer: there is no start time to count from.
@Composable
private fun LiveStep(tool: Part.Tool?) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
        .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Dot(MaterialTheme.colorScheme.primary, 12.dp)
        Column(Modifier.padding(start = 12.dp)) {
            Text(if (tool != null) "Running ${tool.name}" else "Working", style = MaterialTheme.typography.titleSmall)
            if (tool != null && tool.summary.isNotBlank()) Text(tool.summary, fontFamily = Mono, fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// A question the agent asked. While it waits on it (live), tap an option:
// the server presses the keys in the form. One single-choice question: a
// tap answers it; otherwise each tap fills the form and Submit sends it.
@Composable
private fun AskCard(q: Part.Ask, api: Api, address: String, live: Boolean) {
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val picked = remember(q.id) { mutableStateListOf<String>() }
    var note by remember(q.id) { mutableStateOf<String?>(null) }
    var busy by remember(q.id) { mutableStateOf(false) }
    val needsSubmit = q.questions.size > 1 || q.questions.any { it.multi }
    val border = if (live) stateColor("blocked") else MaterialTheme.colorScheme.outlineVariant
    fun act(body: () -> String, ok: String, after: () -> Unit = {}) {
        if (busy) return
        busy = true; note = null
        scope.launch {
            io(body).onSuccess { view.performHapticFeedback(HapticFeedbackConstants.CONFIRM); after(); note = ok }.onFailure {
                view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                note = if (it.message == "the question form is not on screen") "The form is not on screen. Use Text to check." else "Not sent: ${it.message}"
            }
            busy = false
        }
    }
    Column(Modifier.fillMaxWidth().border(1.dp, border, RoundedCornerShape(12.dp)).padding(12.dp)) {
        q.questions.forEachIndexed { i, x ->
            if (i > 0) Spacer(Modifier.height(10.dp))
            Text((x.header.ifBlank { "Question" }) + if (x.multi) " · pick any" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(x.question, fontWeight = FontWeight.Medium)
            x.options.forEachIndexed { j, o ->
                val k = "$i.$j"
                val on = k in picked
                OutlinedButton(onClick = {
                    if (!live) return@OutlinedButton
                    act({ api.answer(address, q.shape(), i, j) }, if (needsSubmit) "Picked. Submit when ready." else "Answer sent.") {
                        if (x.multi) { if (on) picked.remove(k) else picked.add(k) } else { picked.removeAll { it.startsWith("$i.") }; picked.add(k) }
                    }
                }, enabled = live && !busy, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Column(Modifier.fillMaxWidth()) {
                        Text((if (on) "✓ " else "${j + 1}. ") + o.label, fontWeight = FontWeight.Medium)
                        if (o.description.isNotBlank()) Text(o.description, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (live && needsSubmit) Button(onClick = { act({ api.submit(address, q.shape()) }, "Submitted.") }, enabled = !busy, modifier = Modifier.padding(top = 8.dp)) { Text("Submit") }
        if (q.answered) Text("Answered: " + (q.answer ?: "").lineSequence().firstOrNull().orEmpty().take(200), fontSize = 12.sp, color = Palette.mutedForeground, modifier = Modifier.padding(top = 6.dp))
        else if (live && note == null) Text(if (needsSubmit) "Tap your answers, then Submit. To write your own, type it below." else "Tap an answer. To write your own, type it below.",
            fontSize = 12.sp, color = Palette.blocked, modifier = Modifier.padding(top = 6.dp))
        note?.let { Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp)) }
    }
}

// ---- Text: the screen and its history, refreshed every 2 s ----

@Composable
private fun TextTab(api: Api, address: String) {
    var text by remember(address) { mutableStateOf<String?>(null) }
    var err by remember(address) { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    LaunchedEffect(address, api.base) {
        while (true) {
            io { api.read(address, 300) }.onSuccess { text = it; err = null }.onFailure { err = it.message }
            delay(2000)
        }
    }
    LaunchedEffect(text) { scroll.scrollTo(scroll.maxValue) }
    Box(Modifier.fillMaxSize()) {
        when {
            text == null && err != null -> Text("Could not read the screen: $err", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            text == null -> Text("Reading the screen…", Modifier.padding(16.dp))
            else -> SelectionContainer {
                Text(text!!.trimEnd(), Modifier.fillMaxSize().verticalScroll(scroll).horizontalScroll(rememberScrollState()).padding(8.dp),
                    fontFamily = Mono, fontSize = 10.sp, lineHeight = 12.sp, softWrap = false)
            }
        }
    }
}

// ---- Commits ----

@Composable
private fun CommitsTab(api: Api, address: String, data: Commits?, err: String?, refresh: () -> Unit) {
    val scope = rememberCoroutineScope()
    var detail by remember(address) { mutableStateOf<CommitDetail?>(null) }
    val load = { _: Boolean -> refresh() }
    val d = data
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d?.let { listOfNotNull(it.repo, it.branch).joinToString(" · ") } ?: "", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                IconButton(onClick = { load(true) }) { Icon(Icons.Default.Refresh, "Refresh") }
            }
            d?.let {
                val bits = listOfNotNull(it.unpushed?.takeIf { n -> n > 0 }?.let { n -> "$n not pushed" }, it.dirty?.takeIf { n -> n > 0 }?.let { n -> "$n changed files" })
                if (bits.isNotEmpty()) Text(bits.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = stateColor("blocked"))
                it.pr?.let { pr -> Text("PR #${pr.number}: ${pr.title}", style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (err != null) item { Text("Could not read commits: $err", color = MaterialTheme.colorScheme.error) }
        if (d?.reason != null) item { Text(d.reason) }
        items(d?.commits ?: emptyList(), key = { it.sha }) { c ->
            Column(Modifier.fillMaxWidth().clickable {
                scope.launch { io { Json.commitDetail(api.get(api.terminalPath(address, "commits/${c.sha}"))) }.onSuccess { detail = it } }
            }.padding(vertical = 6.dp)) {
                Text(c.subject, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text("${c.sha.take(7)} · ${c.author} · ${c.date.take(10)}" + if (c.local) " · not pushed" else "",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val dd = detail
                if (dd != null && dd.sha.startsWith(c.sha.take(7))) {
                    Text(dd.message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                    for (f in dd.files) Text("${f.file}  +${f.additions ?: "?"} −${f.deletions ?: "?"}", fontFamily = Mono, fontSize = 11.sp)
                }
            }
        }
    }
}

// ---- Status: the branch's PR, its checks and whether it can merge ----

@Composable
private fun StatusTab(api: Api, address: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var data by remember(address) { mutableStateOf<PrStatus?>(null) }
    var err by remember(address) { mutableStateOf<String?>(null) }
    val load = { fresh: Boolean ->
        scope.launch {
            io { Json.prStatus(api.get(api.terminalPath(address, "status") + if (fresh) "?fresh" else "", 30000)) }
                .onSuccess { data = it; err = null }.onFailure { err = it.message }
        }
    }
    LaunchedEffect(address) { load(false) }
    val d = data
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d?.branch ?: "", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                IconButton(onClick = { load(true) }) { Icon(Icons.Default.Refresh, "Refresh") }
            }
        }
        if (err != null) item { Text("Could not read the status: $err", color = MaterialTheme.colorScheme.error) }
        if (d != null && d.pr == null) item { Text(d.reason ?: "No PR for this branch.") }
        d?.pr?.let { pr ->
            item {
                Column(Modifier.fillMaxWidth().clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pr.url))) }) {
                    Text("#${pr.number} ${pr.title}", fontWeight = FontWeight.Medium)
                    Text(listOfNotNull(if (pr.isDraft) "draft" else null, d.readiness, pr.reviewDecision?.lowercase()?.replace('_', ' ')).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = if (d.readiness == "ready") stateColor("done") else MaterialTheme.colorScheme.onSurfaceVariant)
                    for (r in d.reasons) Text("• $r", style = MaterialTheme.typography.bodySmall)
                }
            }
            items(d.checks) { c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(enabled = c.url != null) { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(c.url))) },
                    verticalAlignment = Alignment.CenterVertically) {
                    Dot(stateColor(when (c.bucket) { "pass" -> "done"; "fail" -> "error"; "pending" -> "blocked"; else -> "idle" }), 8.dp)
                    Text("  ${c.name}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// No transcript to show (offline, loading, or none): the note, then any
// replies waiting in the outbox.
@Composable
private fun NoChat(text: String, queued: List<OutItem>, error: Boolean = false) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        if (queued.isNotEmpty()) QueuedReplies(queued) { ctx.app.outbox.remove(it.id) }
    }
}
