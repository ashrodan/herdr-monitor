package io.github.ashrodan.herdrmonitor

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import io.github.ashrodan.herdrmonitor.ui.theme.HerdrDarkScheme
import io.github.ashrodan.herdrmonitor.ui.theme.HerdrLightScheme
import io.github.ashrodan.herdrmonitor.ui.theme.StatusColors
import io.github.ashrodan.herdrmonitor.ui.theme.StatusDark
import io.github.ashrodan.herdrmonitor.ui.theme.StatusLight
import org.junit.Assert.assertTrue
import org.junit.Test

// The brief's quality bar: all text at least 4.5:1 against its actual
// surface, status text on tinted cards included, light scheme checked
// separately.
class ContrastTest {
    private fun ratio(fg: Color, bg: Color): Double {
        val a = fg.compositeOver(bg).luminance() + 0.05
        val b = bg.luminance() + 0.05
        return maxOf(a, b).toDouble() / minOf(a, b)
    }

    private fun pairs(cs: ColorScheme, st: StatusColors) = listOf(
        Triple("onSurface on surface", cs.onSurface, cs.surface),
        Triple("onSurfaceVariant on surface", cs.onSurfaceVariant, cs.surface),
        Triple("onSurfaceVariant on surfaceContainerLow (cards)", cs.onSurfaceVariant, cs.surfaceContainerLow),
        Triple("onSurfaceVariant on surfaceContainerHigh (fields)", cs.onSurfaceVariant, cs.surfaceContainerHigh),
        Triple("onSecondaryContainer on secondaryContainer (pills)", cs.onSecondaryContainer, cs.secondaryContainer),
        Triple("onPrimaryContainer on primaryContainer (your bubble)", cs.onPrimaryContainer, cs.primaryContainer),
        Triple("onPrimary on primary (Send)", cs.onPrimary, cs.primary),
        Triple("onErrorContainer on errorContainer (Stop)", cs.onErrorContainer, cs.errorContainer),
        Triple("onWaitingContainer on waitingContainer (Needs you card)", st.onWaitingContainer, st.waitingContainer),
        Triple("onWaiting on waiting (first answer)", st.onWaiting, st.waiting),
        Triple("onFailed on failed", st.onFailed, st.failed),
        Triple("onReady on ready", st.onReady, st.ready),
        Triple("waiting count on surface (chip)", st.waiting, cs.surface),
        Triple("ready text on surfaceContainerLow (PR chip)", st.ready, cs.surfaceContainerLow),
        Triple("failed text on surfaceContainerLow (checks chip)", st.failed, cs.surfaceContainerLow),
        Triple("waiting text on surfaceContainerLow (stale chip)", st.waiting, cs.surfaceContainerLow),
        Triple("primary on surfaceContainerLow (sent chip)", cs.primary, cs.surfaceContainerLow),
    )

    private fun check(name: String, cs: ColorScheme, st: StatusColors) {
        val bad = pairs(cs, st).map { (n, f, b) -> n to ratio(f, b) }.filter { it.second < 4.5 }
        assertTrue("$name below 4.5:1: " + bad.joinToString { "${it.first} ${"%.2f".format(it.second)}" }, bad.isEmpty())
    }

    @Test fun darkScheme() = check("dark", HerdrDarkScheme, StatusDark)
    @Test fun lightScheme() = check("light", HerdrLightScheme, StatusLight)
}
