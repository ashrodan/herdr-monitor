package io.github.ashrodan.herdrmonitor

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams
import com.android.resources.NightMode
import io.github.ashrodan.herdrmonitor.ui.ActivityCard
import io.github.ashrodan.herdrmonitor.ui.Composer
import io.github.ashrodan.herdrmonitor.ui.HerdrScene
import io.github.ashrodan.herdrmonitor.ui.PrRow
import io.github.ashrodan.herdrmonitor.ui.ReplyPort
import io.github.ashrodan.herdrmonitor.ui.SceneState
import io.github.ashrodan.herdrmonitor.ui.SkeletonSessions
import io.github.ashrodan.herdrmonitor.ui.TerminalRow
import io.github.ashrodan.herdrmonitor.ui.HarnessIcon
import io.github.ashrodan.herdrmonitor.ui.TabCard
import io.github.ashrodan.herdrmonitor.ui.WhereAmIIcon
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import io.github.ashrodan.herdrmonitor.ui.components.QuestionBlock
import io.github.ashrodan.herdrmonitor.ui.components.StatusFilterRow
import io.github.ashrodan.herdrmonitor.ui.theme.HerdrTheme
import io.github.ashrodan.herdrmonitor.ui.theme.ThemePref
import org.junit.Rule
import org.junit.Test

// Screenshots of the main pieces, in the dark and the light theme, on the
// JVM (Paparazzi, no device). Fake data only. Record after a deliberate
// change: ./gradlew recordPaparazziDebug; check: ./gradlew verifyPaparazziDebug.
// The images are in src/test/snapshots/images.
class ScreenshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, renderingMode = SessionParams.RenderingMode.SHRINK, maxPercentDifference = 0.1)

    private val now = 1_800_000_000_000L

    private fun both(content: @Composable () -> Unit) {
        for (pref in listOf(ThemePref.Dark, ThemePref.Light)) {
            paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_6.copy(nightMode = if (pref == ThemePref.Dark) NightMode.NIGHT else NightMode.NOTNIGHT))
            paparazzi.snapshot(pref.name.lowercase()) {
                HerdrTheme(pref) {
                    Surface(color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) { content() }
                    }
                }
            }
        }
    }

    @Test fun sessionRows() = both {
        val rows = listOf(
            Terminal("t1", agent = "claude-code", status = "blocked", kind = "permission", msg = "Allow Bash: ./gradlew lint?", changedMs = now - 60_000),
            Terminal("t2", agent = "omp", status = "working", title = "Refactor the SSE hub", changedMs = now - 5 * 60_000),
            Terminal("t3", agent = "claude-code", status = "done", msg = "PR #12 is green", changedMs = now - 3_600_000),
            Terminal("t4", agent = "codex", status = "error", msg = "gh: not logged in", changedMs = now - 86_400_000),
            Terminal("t5", agent = "zsh", changedMs = now - 120_000),
        )
        val names = listOf("android app", "server", "herdr-monitor · review", "dash-www", "shell")
        rows.zip(names).forEach { (t, n) -> TerminalRow(t, n, t.msg ?: t.title, t.status, now, tag = if (t.agent == "zsh") "shell" else null) {} }
    }

    @Test fun prRows() = both {
        val pr = { n: Int, title: String, draft: Boolean, review: String? -> Pr(n, title, "https://example.invalid/$n", draft, "feat/x$n", review, null) }
        PrRow(OverviewPr("s1", "herdr-monitor", "#4fb3a9", "feat/server-durable", pr(12, "Server: monitor/hub/overview split", false, "APPROVED"), "ready", 0, 0)) {}
        PrRow(OverviewPr("s2", "android app", "#c9a227", "feat/android-app", pr(11, "Native Android app", true, null), null, 0, 2)) {}
        PrRow(OverviewPr("s3", "dash-www", "#d06a5a", "fix/build", pr(7, "Fix the build on Node 22", false, "CHANGES_REQUESTED"), null, 1, 0)) {}
    }

    @Test fun activityAndQuestion() = both {
        ActivityCard(Item.Activity("act.1", listOf(
            Part.Tool("bash", "./gradlew lint", ToolResult("ok", false, false)),
            Part.Tool("bash", "./gradlew assembleDebug", ToolResult("BUILD FAILED", true, false)),
            Part.Tool("read", "docs/ui.md", ToolResult("", false, false)),
        )))
        QuestionBlock(Part.Ask("q1", listOf(Question("Colours", "Which accent colour?", false,
            listOf(Option("Teal", "Calm, the current one"), Option("Amber", "Warm"), Option("Red", "Loud")))), false),
            onAnswer = { _, _ -> }, onReply = {})
    }

    private class Mem : OutboxStorage {
        override fun load(): String? = null
        override fun save(json: String) {}
    }

    private class NoPort : ReplyPort {
        override suspend fun send(address: String, text: String, force: Boolean) {}
        override suspend fun keys(address: String, keys: List<String>) {}
        override suspend fun interrupt(address: String) {}
        override suspend fun upload(address: String, bytes: ByteArray, type: String) = ""
    }

    // The composer's image picker needs an owner; this one never launches.
    private val noResults = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {}
        }
    }

    @Test fun composer() = both {
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides noResults) {
            Composer(NoPort(), Outbox(Mem()), "t1", online = true, blocked = false, kind = null, working = true, harness = "claude")
        }
    }

    @Test fun emptyAndOffline() = both {
        SceneState(HerdrScene.AllPenned, "No sessions.")
        SceneState(HerdrScene.Offline, "No link to Herdr.")
    }

    @Test fun loading() = both { SkeletonSessions() }

    // The filter chips stay on one line, at a phone's width: first the counts
    // from a real phone (they wrapped before), then with Needs you as well,
    // so the row runs past the edge and scrolls.
    @Test fun filterRowOneLine() = both {
        StatusFilterRow(Counts(37, 0, 3, 4, 3, 27), selected = null) {}
        StatusFilterRow(Counts(39, 2, 3, 4, 3, 27), selected = Bucket.Working) {}
    }

    // The harness marks, and the space map: the where-am-I button (t2 of
    // three panes, tab 2 of 3) and that tab's card. Fake sessions only.
    @Test fun harnessMarksAndSpaceMap() = both {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            for (a in listOf("claude-code", "codex", "omp", "pi", "zsh")) HarnessIcon(a, 28.dp)
        }
        val tab = SpaceTab("tb2", "review", true, listOf("t1", "t2", "t3"),
            PaneSplit(true, listOf(PaneLeaf("t1", 0.5f), PaneSplit(false, listOf(PaneLeaf("t2"), PaneLeaf("t3")), 0.5f))))
        val space = Space("sp", "herdr-monitor", tabs = listOf(SpaceTab("tb1", "1", false, listOf("t9")), tab, SpaceTab("tb3", "3", false, listOf("t8"))))
        val live = Live(terminals = mapOf(
            "t1" to Terminal("t1", name = "server tests", agent = "claude-code", status = "working"),
            "t2" to Terminal("t2", name = "android app", agent = "codex", status = "blocked"),
            "t3" to Terminal("t3", agent = "omp", status = "done"),
        ), spaces = listOf(space))
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            WhereAmIIcon(placeOf(live.terminals["t2"], "t2", live.spaces), "t2", size = 48.dp)
            TabCard(tab, live, "t2", emptyMap(), onOpen = null)
        }
    }
}
