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
import androidx.compose.foundation.background
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
fun AgentScreen(live: Live, address: String, back: () -> Unit) {
    val ctx = LocalContext.current
    val api = remember(live) { ctx.app.store.api }
    val port = remember(api) { ApiPort(api) }
    val outboxAll by ctx.app.outbox.items.collectAsStateWithLifecycle()
    val queued = outboxAll.filter { it.address == address }
    val t = live.terminals[address]
    val now = rememberNow()
    var tab by remember { mutableIntStateOf(0) }

    // Opening a finished agent counts as seeing it (as in the web UI), and
    // its alert goes.
    LaunchedEffect(address, t?.status) {
        if (t?.status == "done") ctx.app.prefs.markSeen(address)
        Notifier.cancelAgent(ctx, address)
    }

    val blocked = t?.status == "blocked"
    val rawState = if (t?.finished == true) "finished" else t?.status ?: "idle"
    Scaffold(topBar = {
        TopAppBar(
            title = {
                Column {
                    Text(t?.let { displayName(it, live.spaces) } ?: address, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(stateColor(rawState), 7.dp)
                        Text("  ${STATE_LABEL[rawState] ?: rawState} · ${t?.agent ?: ""} · ${age(t?.changedMs, now)}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            },
            navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }, bottomBar = {
        // Offline, an agent the app has not heard of yet (a cold start) still
        // takes replies: they wait in the outbox.
        val offline = live.link != Link.Online
        if (t != null && !t.finished) Composer(port, ctx.app.outbox, address, online = !offline, blocked, t.kind, working = t.status == "working")
        else if (t == null && offline) Composer(port, ctx.app.outbox, address, online = false, blocked = false, kind = null, working = false)
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            ConnectionLine(live)
            if (t == null) {
                if (live.link != Link.Online) NoChat("No link to the server. The chat loads when the link is back.", queued)
                else Text("This terminal is gone.", Modifier.padding(16.dp))
                return@Column
            }
            TabRow(selectedTabIndex = tab) {
                listOf("Chat", "Text", "Commits", "Status").forEachIndexed { i, s -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(s) }) }
            }
            when (tab) {
                0 -> ChatTab(api, address, blocked, working = t.status == "working", queued)
                1 -> TextTab(api, address)
                2 -> CommitsTab(api, address)
                else -> StatusTab(api, address)
            }
            t.cwd?.let { Text(shortPath(it), Modifier.padding(horizontal = 12.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        }
    }
}

// ---- Chat ----

@Composable
private fun ChatTab(api: Api, address: String, blocked: Boolean, working: Boolean, queued: List<OutItem>) {
    val ctx = LocalContext.current
    var chat by remember(address) { mutableStateOf<Chat?>(null) }
    var err by remember(address) { mutableStateOf<String?>(null) }
    LaunchedEffect(address, api.base) {
        while (true) {
            io { api.chat(address, chat?.version) }
                .onSuccess { c -> if (c != null) { chat = c; ctx.app.outbox.dropDelivered(address, userTexts(c.messages)) }; err = null }
                .onFailure { err = it.message }
            delay(if (working || blocked) 2500 else 6000)
        }
    }
    val c = chat
    when {
        c == null && err != null -> NoChat("Could not read the transcript: $err", queued, error = true)
        c == null -> NoChat("Loading the conversation…", queued)
        !c.available -> NoChat("${c.reason ?: "No transcript."} Use Text.", queued)
        else -> {
            val list = rememberLazyListState()
            val waitingOn = if (blocked) lastUnansweredAsk(c.messages) else null
            LaunchedEffect(c.messages.size, queued.size) { list.layoutInfo.totalItemsCount.takeIf { it > 0 }?.let { list.scrollToItem(it - 1) } }
            LazyColumn(Modifier.fillMaxSize(), state = list, verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                if (c.truncated) item { Text("Older messages are not shown (long transcript).", style = MaterialTheme.typography.labelSmall) }
                if (c.messages.isEmpty()) item { Text("No messages yet.") }
                items(c.messages, key = { it.id.ifEmpty { it.hashCode().toString() } }) { m -> MessageView(m, api, address, waitingOn) }
                if (working) item { Text("Working…", color = stateColor("working"), style = MaterialTheme.typography.labelMedium) }
                if (queued.isNotEmpty()) item(key = "outbox") { QueuedReplies(queued) { ctx.app.outbox.remove(it.id) } }
            }
        }
    }
}

@Composable
private fun MessageView(m: Message, api: Api, address: String, waitingOn: Part.Ask?) {
    when (m.role) {
        // Your prompt: a bubble on the right, at most ~85% wide, Markdown
        // with single line breaks kept.
        "user" -> BoxWithConstraints(Modifier.fillMaxWidth()) {
            val w = maxWidth * 0.85f
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(shape = RoundedCornerShape(16.dp, 16.dp, 6.dp, 16.dp), color = Palette.secondary, modifier = Modifier.widthIn(max = w)) {
                    SelectionContainer { MarkdownView(m.parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text }, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), keepBreaks = true) }
                }
            }
        }
        "system" -> Text(m.parts.filterIsInstance<Part.Text>().joinToString(" ") { it.text }, Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.labelSmall, color = Palette.mutedForeground, textAlign = TextAlign.Center)
        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Runs of tool calls fold into one card.
            val blocks = mutableListOf<Any>()
            for (p in m.parts) {
                val last = blocks.lastOrNull()
                if (p is Part.Tool && last is MutableList<*>) @Suppress("UNCHECKED_CAST") (last as MutableList<Part.Tool>).add(p)
                else if (p is Part.Tool) blocks.add(mutableListOf(p)) else blocks.add(p)
            }
            for (b in blocks) when (b) {
                is MutableList<*> -> @Suppress("UNCHECKED_CAST") ToolGroup(b as List<Part.Tool>)
                is Part.Ask -> AskCard(b, api, address, live = b === waitingOn)
                is Part.Thinking -> Thinking(b.text)
                is Part.Text -> SelectionContainer { MarkdownView(b.text) }
            }
        }
    }
}

@Composable
private fun Thinking(text: String) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.clickable { open = !open }) {
        Text((if (open) "▾  " else "▸  ") + "Thinking", fontSize = 12.sp, color = Palette.mutedForeground)
        if (open) Text(text, Modifier.padding(start = 18.dp, top = 4.dp), fontSize = 12.sp, fontStyle = FontStyle.Italic, color = Palette.mutedForeground)
    }
}

// web ToolGroup: one collapsed card. Closed: the tool and its summary, or
// "N tool calls · names", and how many failed. Open: one row per tool; a
// tap shows its result.
@Composable
private fun ToolGroup(tools: List<Part.Tool>) {
    var open by remember { mutableStateOf(false) }
    val failed = tools.count { it.result?.error == true }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.card.copy(alpha = 0.6f)).border(1.dp, Palette.border, RoundedCornerShape(8.dp))) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (open) "▾ " else "▸ ", fontSize = 12.sp, color = Palette.mutedForeground)
            Text("🔧 ", fontSize = 11.sp)
            val one = tools.singleOrNull()
            Text(buildAnnotatedString {
                if (one != null) { withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = Palette.foreground)) { append(one.name) }; append(" "); append(one.summary) }
                else append("${tools.size} tool calls · " + tools.map { it.name }.distinct().take(3).joinToString(", "))
            }, Modifier.weight(1f), fontSize = 12.sp, color = Palette.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (failed > 0) Text("  ⚠ $failed", fontSize = 12.sp, color = Palette.error)
            if (tools.any { it.result == null }) Text("  running", fontSize = 12.sp, color = Palette.blocked)
        }
        if (open) for (t in tools) ToolRow(t)
    }
}

@Composable
private fun ToolRow(t: Part.Tool) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = Palette.border)
        Row(Modifier.fillMaxWidth().clickable(enabled = t.result != null) { open = !open }.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(t.name, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (t.result?.error == true) Palette.error else Palette.foreground)
            Text("  " + t.summary, Modifier.weight(1f), fontSize = 11.sp, fontFamily = Mono, color = Palette.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (t.result == null) Text("running", fontSize = 12.sp, color = Palette.blocked)
        }
        val r = t.result
        if (open && r != null) {
            Text(r.text.ifEmpty { "(no output)" } + if (r.truncated) "\n… (cut)" else "",
                Modifier.fillMaxWidth().heightIn(max = 300.dp).background(Palette.code).verticalScroll(rememberScrollState()).padding(10.dp),
                fontFamily = Mono, fontSize = 11.sp, lineHeight = 14.sp, color = Palette.foreground)
        }
    }
}

// A question the agent asked. While it waits on it (live), tap an option:
// the server presses the keys in the form. One single-choice question: a
// tap answers it; otherwise each tap fills the form and Submit sends it.
@Composable
private fun AskCard(q: Part.Ask, api: Api, address: String, live: Boolean) {
    val scope = rememberCoroutineScope()
    val picked = remember(q.id) { mutableStateListOf<String>() }
    var note by remember(q.id) { mutableStateOf<String?>(null) }
    var busy by remember(q.id) { mutableStateOf(false) }
    val needsSubmit = q.questions.size > 1 || q.questions.any { it.multi }
    val border = if (live) stateColor("blocked") else MaterialTheme.colorScheme.outlineVariant
    fun act(body: () -> String, ok: String, after: () -> Unit = {}) {
        if (busy) return
        busy = true; note = null
        scope.launch {
            io(body).onSuccess { after(); note = ok }.onFailure {
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
private fun CommitsTab(api: Api, address: String) {
    val scope = rememberCoroutineScope()
    var data by remember(address) { mutableStateOf<Commits?>(null) }
    var err by remember(address) { mutableStateOf<String?>(null) }
    var detail by remember(address) { mutableStateOf<CommitDetail?>(null) }
    val load = { fresh: Boolean ->
        scope.launch {
            io { Json.commits(api.get(api.terminalPath(address, "commits") + if (fresh) "?fresh" else "", 30000)) }
                .onSuccess { data = it; err = null }.onFailure { err = it.message }
        }
    }
    LaunchedEffect(address) { load(false) }
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
