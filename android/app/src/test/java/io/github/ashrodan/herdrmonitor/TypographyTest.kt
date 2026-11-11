package io.github.ashrodan.herdrmonitor

import androidx.compose.ui.unit.TextUnit
import io.github.ashrodan.herdrmonitor.ui.theme.HerdrTypography
import org.junit.Assert.assertTrue
import org.junit.Test

// A text field's label blends one style into another (bodyLarge into
// bodySmall). Compose cannot blend em with sp: a mixed theme crashed the
// New session sheet on a phone. Every size and line height is in sp.
class TypographyTest {
    @Test fun everyStyleUsesSpOnly() {
        val t = HerdrTypography
        val styles = mapOf(
            "displayLarge" to t.displayLarge, "displayMedium" to t.displayMedium, "displaySmall" to t.displaySmall,
            "headlineLarge" to t.headlineLarge, "headlineMedium" to t.headlineMedium, "headlineSmall" to t.headlineSmall,
            "titleLarge" to t.titleLarge, "titleMedium" to t.titleMedium, "titleSmall" to t.titleSmall,
            "bodyLarge" to t.bodyLarge, "bodyMedium" to t.bodyMedium, "bodySmall" to t.bodySmall,
            "labelLarge" to t.labelLarge, "labelMedium" to t.labelMedium, "labelSmall" to t.labelSmall,
        )
        fun ok(u: TextUnit) = u == TextUnit.Unspecified || u.isSp
        for ((name, s) in styles) {
            assertTrue("$name fontSize ${s.fontSize}", ok(s.fontSize))
            assertTrue("$name lineHeight ${s.lineHeight}", ok(s.lineHeight))
        }
    }
}
