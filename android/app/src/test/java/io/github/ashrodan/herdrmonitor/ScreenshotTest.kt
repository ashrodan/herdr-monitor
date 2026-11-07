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
import io.github.ashrodan.herdrmonitor.ui.components.QuestionBlock
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
}
