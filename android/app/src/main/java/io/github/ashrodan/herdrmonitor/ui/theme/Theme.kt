package io.github.ashrodan.herdrmonitor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.ashrodan.herdrmonitor.R

// The design brief's Foundations: a teal seed, dark by default. The dark
// values are the mock's; the light values come from the same seed
// (Material Theme Builder, teal).

enum class ThemePref { Dark, Light, System;
    companion object { fun of(s: String?) = entries.firstOrNull { it.name == s } ?: Dark }
}

val HerdrDarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF82D5C8), onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005047), onPrimaryContainer = Color(0xFF9EF2E4),
    inversePrimary = Color(0xFF006A60),
    secondary = Color(0xFFB1CCC6), onSecondary = Color(0xFF1C3531),
    secondaryContainer = Color(0xFF324B47), onSecondaryContainer = Color(0xFFCCE8E2),
    tertiary = Color(0xFFADCAE6), onTertiary = Color(0xFF153349),
    tertiaryContainer = Color(0xFF2D4961), onTertiaryContainer = Color(0xFFCCE5FF),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFFFDAD5),
    background = Color(0xFF111414), onBackground = Color(0xFFE0E3E2),
    surface = Color(0xFF111414), onSurface = Color(0xFFE0E3E2),
    surfaceVariant = Color(0xFF3F4947), onSurfaceVariant = Color(0xFFBFC9C6),
    surfaceTint = Color(0xFF82D5C8),
    inverseSurface = Color(0xFFE0E3E2), inverseOnSurface = Color(0xFF2D3130),
    outline = Color(0xFF899390), outlineVariant = Color(0xFF3F4947),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF373A3A), surfaceDim = Color(0xFF111414),
    surfaceContainerLowest = Color(0xFF0C0F0F), surfaceContainerLow = Color(0xFF191C1C),
    surfaceContainer = Color(0xFF1D2020), surfaceContainerHigh = Color(0xFF272B2A),
    surfaceContainerHighest = Color(0xFF323535),
)

val HerdrLightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF006A60), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9EF2E4), onPrimaryContainer = Color(0xFF00201C),
    inversePrimary = Color(0xFF82D5C8),
    secondary = Color(0xFF4A635F), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E2), onSecondaryContainer = Color(0xFF051F1C),
    tertiary = Color(0xFF456179), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCCE5FF), onTertiaryContainer = Color(0xFF001E31),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF4FBF8), onBackground = Color(0xFF171D1C),
    surface = Color(0xFFF4FBF8), onSurface = Color(0xFF171D1C),
    surfaceVariant = Color(0xFFDAE5E1), onSurfaceVariant = Color(0xFF3F4947),
    surfaceTint = Color(0xFF006A60),
    inverseSurface = Color(0xFF2B3230), inverseOnSurface = Color(0xFFECF2EF),
    outline = Color(0xFF6F7977), outlineVariant = Color(0xFFBEC9C6),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF4FBF8), surfaceDim = Color(0xFFD5DBD9),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFEFF5F2),
    surfaceContainer = Color(0xFFE9EFEC), surfaceContainerHigh = Color(0xFFE3EAE7),
    surfaceContainerHighest = Color(0xFFDDE4E1),
)

// One colour per status, everywhere. These never come from the dynamic
// (wallpaper) scheme. Working uses primary and Idle uses onSurfaceVariant.
@Immutable
data class StatusColors(
    val waiting: Color, val waitingContainer: Color, val onWaitingContainer: Color,
    val failed: Color, val failedContainer: Color, val onFailedContainer: Color,
    val ready: Color, val readyContainer: Color, val onReadyContainer: Color,
    // The text and icon colour on a filled badge.
    val onWaiting: Color, val onFailed: Color, val onReady: Color,
)

val StatusDark = StatusColors(
    waiting = Color(0xFFF4BF5F), waitingContainer = Color(0xFF4A3600), onWaitingContainer = Color(0xFFFFDEA8),
    failed = Color(0xFFFFB4A9), failedContainer = Color(0xFF8C1D18), onFailedContainer = Color(0xFFFFDAD5),
    ready = Color(0xFF94D68E), readyContainer = Color(0xFF1C4F20), onReadyContainer = Color(0xFFB0F3A8),
    onWaiting = Color(0xFF2A1D00), onFailed = Color(0xFF410002), onReady = Color(0xFF00390A),
)

val StatusLight = StatusColors(
    waiting = Color(0xFF7C5800), waitingContainer = Color(0xFFFFDEA8), onWaitingContainer = Color(0xFF271900),
    failed = Color(0xFFBA1A1A), failedContainer = Color(0xFFFFDAD6), onFailedContainer = Color(0xFF410002),
    ready = Color(0xFF2A6A28), readyContainer = Color(0xFFB0F3A8), onReadyContainer = Color(0xFF002204),
    onWaiting = Color(0xFFFFFFFF), onFailed = Color(0xFFFFFFFF), onReady = Color(0xFFFFFFFF),
)

val LocalStatusColors = staticCompositionLocalOf { StatusDark }

// System UI font for everything; JetBrains Mono only for commands, paths,
// branch names, diffs and the terminal.
val Mono = FontFamily(Font(R.font.jetbrains_mono_regular, FontWeight.Normal), Font(R.font.jetbrains_mono_bold, FontWeight.Bold))

private val base = Typography()
val HerdrTypography = base.copy(
    titleLarge = base.titleLarge.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    titleMedium = base.titleMedium.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    bodyLarge = base.bodyLarge.copy(fontSize = 15.5.sp, lineHeight = 1.5.em),
    bodyMedium = base.bodyMedium.copy(fontSize = 14.5.sp, lineHeight = 20.sp),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

val CodeStyle = TextStyle(fontFamily = Mono, fontSize = 12.sp, lineHeight = 17.sp)
val CodeSmallStyle = TextStyle(fontFamily = Mono, fontSize = 11.5.sp, lineHeight = 16.sp)

// Chips 8dp (small), FAB 16dp (large). Pills and the composer are fully round.
val HerdrShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

object Corner {
    val groupOuter: Dp = 22.dp
    val groupInner: Dp = 6.dp
    val groupGap: Dp = 3.dp
    val chip: Dp = 8.dp
    val fab: Dp = 16.dp
}

// A card in a segmented group: 22dp on the group's outer corners, 6dp
// inside. A pressed card uses 22dp on every corner.
fun cardGroupShape(index: Int, count: Int, pressed: Boolean = false): RoundedCornerShape {
    val top = if (pressed || index == 0) Corner.groupOuter else Corner.groupInner
    val bottom = if (pressed || index == count - 1) Corner.groupOuter else Corner.groupInner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

object Spacing {
    val grid: Dp = 8.dp
    val cardGutter: Dp = 12.dp
    val headerGutter: Dp = 24.dp
}

fun isDark(pref: ThemePref, system: Boolean) = when (pref) {
    ThemePref.Dark -> true
    ThemePref.Light -> false
    ThemePref.System -> system
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HerdrTheme(
    pref: ThemePref = ThemePref.Dark,
    wallpaperColours: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = isDark(pref, isSystemInDarkTheme())
    val ctx = LocalContext.current
    val scheme = when {
        wallpaperColours ->  // minSdk is 31: dynamic colour is always there
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> HerdrDarkScheme
        else -> HerdrLightScheme
    }
    CompositionLocalProvider(LocalStatusColors provides if (dark) StatusDark else StatusLight) {
        MaterialExpressiveTheme(
            colorScheme = scheme,
            motionScheme = MotionScheme.expressive(),
            typography = HerdrTypography,
            shapes = HerdrShapes,
            content = content,
        )
    }
}
