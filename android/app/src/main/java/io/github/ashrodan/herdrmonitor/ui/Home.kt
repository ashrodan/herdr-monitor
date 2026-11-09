package io.github.ashrodan.herdrmonitor.ui

import android.content.ClipData
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ashrodan.herdrmonitor.Bucket
import io.github.ashrodan.herdrmonitor.Digest
import io.github.ashrodan.herdrmonitor.Link
import io.github.ashrodan.herdrmonitor.Live
import io.github.ashrodan.herdrmonitor.Route
import io.github.ashrodan.herdrmonitor.SentMark
import io.github.ashrodan.herdrmonitor.Session
import io.github.ashrodan.herdrmonitor.Status
import io.github.ashrodan.herdrmonitor.app
import io.github.ashrodan.herdrmonitor.awaitingReview
import io.github.ashrodan.herdrmonitor.buildSessions
import io.github.ashrodan.herdrmonitor.counts
import io.github.ashrodan.herdrmonitor.digest
import io.github.ashrodan.herdrmonitor.matches
import io.github.ashrodan.herdrmonitor.shape
import io.github.ashrodan.herdrmonitor.shortHost
import io.github.ashrodan.herdrmonitor.ui.components.HostAvatar
import io.github.ashrodan.herdrmonitor.ui.components.IdleRow
import io.github.ashrodan.herdrmonitor.ui.components.SectionHeader
import io.github.ashrodan.herdrmonitor.ui.components.SessionCard
import io.github.ashrodan.herdrmonitor.ui.components.SparkleIcon
import io.github.ashrodan.herdrmonitor.ui.components.StatusFilterRow
import io.github.ashrodan.herdrmonitor.ui.components.notificationSettings
import io.github.ashrodan.herdrmonitor.ui.theme.Corner
import io.github.ashrodan.herdrmonitor.ui.theme.LocalStatusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

enum class HomeTab { Sessions, Prs, Spaces }

// Home: an inbox of agents (design brief, Home). Filter chips, then Needs
// you, Working, Done and Ready to review as segmented card groups, then one
// Idle row. A drawer with the host, views and spaces; a bottom bar (a rail
// on wider screens) with Sessions, PRs and Spaces.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(live: Live, go: (Route) -> Unit, selected: String? = null) {
    val ctx = LocalContext.current
    val app = ctx.app
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(HomeTab.Sessions) }
    var filter by rememberSaveable { mutableStateOf<Bucket?>(null) }
    val asked by app.homeFilter.collectAsStateWithLifecycle()
    LaunchedEffect(asked) {
        asked?.let { tab = HomeTab.Sessions; filter = it; app.homeFilter.value = null }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var searchActive by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun closeSearch() {
        searchActive = false
        focus.clearFocus(force = true)
        keyboard?.hide()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { closeSearch() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { closeSearch() }
    var picking by remember { mutableStateOf(false) }
    var newSession by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Session?>(null) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val now = rememberNow()
    val digests by app.digests.collectAsState()
    val sent by app.sent.collectAsState()
    val pins by app.pins.collectAsState()
    val minute = now / 60_000
    val sessions = remember(live.terminals, live.spaces, live.prs, digests, sent, pins, minute) {
        buildSessions(live.terminals.values, live.spaces, live.prs, app.prefs.localSeen, app.prefs.opened, app.prefs.unreadSince,
            digests, pins, sent, System.currentTimeMillis())
    }
    val counts = remember(sessions) { counts(sessions) }
    val online = live.link == Link.Online
    DigestLoader(sessions)

    // Back closes, in order: the drawer (ModalNavigationDrawer does that),
    // a query, a filter (the Idle list), another tab.
    BackHandler(enabled = query.isNotEmpty() || filter != null || tab != HomeTab.Sessions) {
        when { query.isNotEmpty() -> query = ""; filter != null -> filter = null; else -> tab = HomeTab.Sessions }
    }

    fun answer(s: Session, j: Int, label: String) {
        val ask = s.ask ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { app.store.api.answer(s.address, ask.shape(), 0, j) } }
                .onSuccess {
                    app.sent.value = app.sent.value + (s.address to SentMark(label, System.currentTimeMillis()))
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                }
                .onFailure {
                    view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                    snack.showSnackbar("Not sent: ${it.message}")
                }
        }
    }
    fun stop(s: Session) = scope.launch {
        runCatching { withContext(Dispatchers.IO) { app.store.api.interrupt(s.address) } }
            .onSuccess { snack.showSnackbar("Stop key sent to ${s.name}.") }
            .onFailure { view.performHapticFeedback(HapticFeedbackConstants.REJECT); snack.showSnackbar("Not sent: ${it.message}") }
    }

    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        HerdrDrawer(live, sessions, counts.all, online, onView = { b, t ->
            tab = t; filter = b; scope.launch { drawer.close() }
        }, onSpace = { id -> scope.launch { drawer.close() }; go(Route.SpaceR(id)) },
            onHost = { scope.launch { drawer.close() }; picking = true },
            onSettings = { scope.launch { drawer.close() }; go(Route.Settings) },
            onNotifications = { scope.launch { drawer.close() }; go(Route.Alerts) })
    }) {
        NavigationSuiteScaffold(layoutType = navSuiteType(), navigationSuiteItems = {
            item(selected = tab == HomeTab.Sessions, onClick = { tab = HomeTab.Sessions; filter = null },
                icon = { NavIcon(HerdrGlyph.Sheep, counts.needsYou, waiting = true) }, label = { Text("Sessions") })
            item(selected = tab == HomeTab.Prs, onClick = { tab = HomeTab.Prs },
                icon = { NavIcon(Icons.Default.CheckCircle, awaitingReview(live.prs), waiting = false) }, label = { Text("PRs") })
            item(selected = tab == HomeTab.Spaces, onClick = { tab = HomeTab.Spaces },
                icon = { HerdrIcon(HerdrGlyph.Paddock, null) }, label = { Text("Spaces") })
        }) {
            val list = rememberLazyListState()
            var fabExpanded by remember { mutableStateOf(true) }
            val collapse = remember {
                object : NestedScrollConnection {
                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                        if (available.y < -4) fabExpanded = false else if (available.y > 4) fabExpanded = true
                        return Offset.Zero
                    }
                }
            }
            Scaffold(
                contentWindowInsets = WindowInsets(0),
                topBar = {
                    HomeTopBar(query, { query = it }, searchActive, { searchActive = true; tab = HomeTab.Sessions }, onDone = { closeSearch() },
                        onMenu = { scope.launch { drawer.open() } }, host = shortHost(app.prefs.server),
                        online = online, onHost = { picking = true })
                },
                snackbarHost = { SnackbarHost(snack) },
                floatingActionButton = {
                    if (tab == HomeTab.Sessions) ExtendedFloatingActionButton(
                        onClick = { newSession = true }, expanded = fabExpanded,
                        icon = { Icon(Icons.Default.Add, null) }, text = { Text("New session") },
                        shape = RoundedCornerShape(Corner.fab),
                        containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                },
            ) { pad ->
                Column(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding())) {
                    ConnectionLine(live)
                    when (tab) {
                        HomeTab.Sessions -> {
                            StatusFilterRow(counts, filter) { filter = it }
                            Spacer(Modifier.height(4.dp))
                            val shown = remember(sessions, query) { sessions.filter { matches(it, query) } }
                            // A list keeps its first visible item in place, so a
                            // section that arrives above it (Needs you, after the
                            // transcripts load) would sit off screen. At the top,
                            // stay at the top.
                            val firstKey = shown.firstOrNull { filter == null || it.bucket == filter }?.address
                            LaunchedEffect(firstKey, filter) {
                                if (list.firstVisibleItemIndex <= 3) list.scrollToItem(0)
                            }
                            LazyColumn(Modifier.fillMaxSize().nestedScroll(collapse), state = list,
                                contentPadding = PaddingValues(bottom = 96.dp)) {
                                sessionsList(shown, filter, now, live, selected, searching = query.isNotBlank(),
                                    open = { go(Route.Agent(it.address)) }, menu = { menuFor = it },
                                    answer = ::answer, pin = { app.togglePin(it.address) }, stop = { stop(it) },
                                    showIdle = { filter = Bucket.Idle })
                            }
                        }
                        HomeTab.Prs -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
                            if (!live.prsLoaded) item { SkeletonRows(label = "Loading pull requests") }
                            else if (live.prs.isEmpty()) item { Text("No open pull requests.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            items(live.prs.size, key = { "pr-${live.prs[it].pr.url}" }) { i ->
                                val p = live.prs[i]
                                PrRow(p) { openUrl(ctx, p.pr.url) }
                            }
                        }
                        HomeTab.Spaces -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 6.dp, end = 6.dp, bottom = 24.dp)) {
                            spacesList(ctx, live, go)
                        }
                    }
                }
            }
        }
    }
    if (picking) ServerSheet { picking = false }
    if (newSession) NewSessionSheet(live, onDismiss = { newSession = false }, onStarted = { addr -> newSession = false; go(Route.Agent(addr)) })
    menuFor?.let { s -> SessionMenu(s, onDismiss = { menuFor = null }, pin = { app.togglePin(s.address) }, stop = { stop(s) },
        terminal = { go(Route.Agent(s.address, terminal = true)) }) }
}

@Composable
private fun NavIcon(glyph: HerdrGlyph, count: Int, waiting: Boolean) = NavIcon({ HerdrIcon(glyph, null) }, count, waiting)

@Composable
private fun NavIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, count: Int, waiting: Boolean) = NavIcon({ Icon(icon, null) }, count, waiting)

@Composable
private fun NavIcon(icon: @Composable () -> Unit, count: Int, waiting: Boolean) {
    val c = LocalStatusColors.current
    BadgedBox(badge = {
        if (count > 0) Badge(containerColor = if (waiting) c.waiting else MaterialTheme.colorScheme.primary,
            contentColor = if (waiting) c.onWaiting else MaterialTheme.colorScheme.onPrimary) { Text(if (count > 99) "99+" else "$count") }
    }) { icon() }
}

// Menu, the Ask Herdr pill (a filter on this phone), the host avatar.
@Composable
private fun HomeTopBar(
    query: String,
    onQuery: (String) -> Unit,
    searchActive: Boolean,
    onSearch: () -> Unit,
    onDone: () -> Unit,
    onMenu: () -> Unit,
    host: String,
    online: Boolean,
    onHost: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(searchActive) {
        if (searchActive) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "Open navigation") }
        val searchModifier = if (searchActive) Modifier else Modifier.clickable(onClick = onSearch)
        Row(Modifier.weight(1f).height(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(searchModifier).testTag("search-pill").padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Ask Herdr", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                if (searchActive) BasicTextField(query, onQuery, Modifier.fillMaxWidth().focusRequester(focusRequester).testTag("search-field"), singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onDone() }),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary))
                else if (query.isNotEmpty()) Text(query, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
            }
            if (query.isEmpty()) Icon(SparkleIcon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            else IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Clear, "Clear the filter") }
        }
        Spacer(Modifier.width(4.dp))
        HostAvatar(host.take(3), online, onHost)
    }
}

private fun LazyListScope.sessionsList(
    all: List<Session>, filter: Bucket?, now: Long, live: Live, selected: String?, searching: Boolean,
    open: (Session) -> Unit, menu: (Session) -> Unit, answer: (Session, Int, String) -> Unit,
    pin: (Session) -> Unit, stop: (Session) -> Unit, showIdle: () -> Unit,
) {
    // Cards from the cache are dimmed until the live list is in.
    val stale = live.cachedAt != null
    if (all.isEmpty() && searching) {
        item(key = "no-match") { SceneState(HerdrScene.AllPenned, "No session matches the search.") }
        return
    }
    if (all.isEmpty()) {
        item(key = "empty") {
            when (live.link) {
                Link.Online -> SceneState(HerdrScene.AllPenned, "No sessions.")
                Link.Offline -> SceneState(HerdrScene.Offline, "No link to the server.")
                Link.Connecting -> SkeletonSessions()
            }
        }
        return
    }
    fun group(title: String, bucket: Bucket) {
        val rows = all.filter { it.bucket == bucket }
        if (rows.isEmpty()) return
        item(key = "h-$bucket", contentType = "header") { SectionHeader(title, rows.size, waiting = bucket == Bucket.NeedsYou) }
        itemsIndexed(rows, key = { _, s -> s.address }, contentType = { _, _ -> "card" }) { i, s ->
            Box(Modifier.padding(horizontal = 12.dp).padding(top = if (i == 0) 0.dp else Corner.groupGap).animateItem()
                .graphicsLayer { alpha = if (stale) 0.6f else 1f }) {
                SessionCard(s, i, rows.size, now, onOpen = { open(s) }, onLongPress = { menu(s) },
                    onAnswer = { j, l -> answer(s, j, l) }, onPin = { pin(s) }, onStop = { stop(s) }, selected = s.address == selected)
            }
        }
    }
    when (filter) {
        null -> {
            group("Needs you", Bucket.NeedsYou)
            group("Working", Bucket.Working)
            group("Done", Bucket.Done)
            group("Ready to review", Bucket.Ready)
            // A search shows idle matches too: most sessions are idle, and a
            // match folded under the Idle row could not be opened.
            val idle = all.count { it.bucket == Bucket.Idle }
            if (searching) group("Idle", Bucket.Idle)
            else if (idle > 0) item(key = "idle-row", contentType = "idle") { IdleRow(idle, showIdle) }
        }
        Bucket.NeedsYou -> if (all.none { it.bucket == Bucket.NeedsYou }) item(key = "penned") { SceneState(HerdrScene.AllPenned, "Nobody needs you.") }
            else group("Needs you", Bucket.NeedsYou)
        Bucket.Working -> group("Working", Bucket.Working)
        Bucket.Done -> group("Done", Bucket.Done)
        Bucket.Ready -> group("Ready to review", Bucket.Ready)
        Bucket.Idle -> group("Idle", Bucket.Idle)
    }
}

// Reads the transcript of each session that is not idle (up to 12), every
// 8 s while Home shows, for its last words, its question and its last
// output time. Needs you first (its answer buttons wait on this), four at a
// time, a 10 s read timeout each: one slow agent does not hold up the rest. An
// unchanged transcript costs one small request.
@Composable
private fun DigestLoader(sessions: List<Session>) {
    val app = LocalContext.current.app
    val current by rememberUpdatedState(sessions)
    LaunchedEffect(Unit) {
        val gate = Semaphore(4)
        while (true) {
            val api = app.store.api
            val targets = current.filter { it.bucket != Bucket.Idle }.sortedBy { it.bucket.ordinal }.take(12)
            coroutineScope {
                for (s in targets) launch {
                    gate.withPermit {
                        val old = app.digests.value[s.address]
                        val chat = runCatching { withContext(Dispatchers.IO) { api.chat(s.address, old?.version, timeoutMs = 10_000) } }.getOrNull()
                            ?: return@withPermit
                        if (!chat.available) return@withPermit
                        val d: Digest = withContext(Dispatchers.Default) { digest(chat) }
                        app.digests.update { it + (s.address to d) }
                    }
                }
            }
            delay(8_000)
        }
    }
}

@Composable
private fun HerdrDrawer(
    live: Live, sessions: List<Session>, total: Int, online: Boolean,
    onView: (Bucket?, HomeTab) -> Unit, onSpace: (String) -> Unit, onHost: () -> Unit,
    onSettings: () -> Unit, onNotifications: () -> Unit,
) {
    val app = LocalContext.current.app
    val c = LocalStatusColors.current
    val counts = counts(sessions)
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 12.dp).verticalScrollable()) {
            Text("Herdr", Modifier.padding(start = 16.dp, top = 20.dp, bottom = 16.dp), style = MaterialTheme.typography.titleLarge)
            Surface(onClick = onHost, color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (online) c.ready else MaterialTheme.colorScheme.outline))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(shortHost(app.prefs.server), fontWeight = FontWeight.Medium)
                        Text((if (online) "Online" else "Offline") + " · ${counts.working} running", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            DrawerRow("All sessions", total) { onView(null, HomeTab.Sessions) }
            DrawerRow("Needs you", counts.needsYou, waiting = true) { onView(Bucket.NeedsYou, HomeTab.Sessions) }
            DrawerRow("Working", counts.working) { onView(Bucket.Working, HomeTab.Sessions) }
            DrawerRow("Done", counts.done) { onView(Bucket.Done, HomeTab.Sessions) }
            DrawerRow("Ready to review", counts.ready) { onView(Bucket.Ready, HomeTab.Sessions) }
            DrawerRow("Pull requests", live.prs.size) { onView(null, HomeTab.Prs) }
            DrawerRow("Idle", counts.idle) { onView(Bucket.Idle, HomeTab.Sessions) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Text("Spaces", Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val perSpace = sessions.groupingBy { it.spaceId }.eachCount()
            for (s in live.spaces.filter { !it.virtual }) {
                NavigationDrawerItem(
                    label = { Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    icon = { Box(Modifier.size(12.dp).clip(RoundedCornerShape(4.dp)).background(parseColor(s.color) ?: MaterialTheme.colorScheme.outline)) },
                    badge = { (perSpace[s.id] ?: 0).takeIf { it > 0 }?.let { Text("$it") } },
                    selected = false, onClick = { onSpace(s.id) })
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            NavigationDrawerItem(label = { Text("Alerts") }, icon = { Icon(Icons.Default.Notifications, null) }, selected = false, onClick = onNotifications)
            NavigationDrawerItem(label = { Text("Settings") }, icon = { Icon(Icons.Default.Settings, null) }, selected = false, onClick = onSettings)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Modifier.verticalScrollable() = this.then(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()))

@Composable
private fun DrawerRow(label: String, count: Int, waiting: Boolean = false, onClick: () -> Unit) {
    val c = LocalStatusColors.current
    NavigationDrawerItem(label = { Text(label) }, selected = false, onClick = onClick,
        badge = { Text("$count", color = if (waiting && count > 0) c.waiting else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (waiting && count > 0) FontWeight.Bold else null) })
}

// Long press: Pin, Stop, Raw terminal, Copy branch.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionMenu(s: Session, onDismiss: () -> Unit, pin: () -> Unit, stop: () -> Unit, terminal: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Text(s.name, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
            MenuRow(if (s.pinned) "Unpin" else "Pin") { pin(); onDismiss() }
            if (s.status == Status.Working || s.status == Status.Waiting) MenuRow("Stop") { stop(); onDismiss() }
            MenuRow("Raw terminal") { terminal(); onDismiss() }
            s.branch?.let { b ->
                MenuRow("Copy branch  $b") {
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("branch", b))) }
                    onDismiss()
                }
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    Text(label, Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 16.dp),
        style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
