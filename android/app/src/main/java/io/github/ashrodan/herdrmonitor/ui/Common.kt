package io.github.ashrodan.herdrmonitor.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ashrodan.herdrmonitor.Block
import io.github.ashrodan.herdrmonitor.Markdown
import io.github.ashrodan.herdrmonitor.Space
import io.github.ashrodan.herdrmonitor.ui.theme.LocalStatusColors
import io.github.ashrodan.herdrmonitor.ui.theme.Mono

// The old names, now read from the theme (ui/theme/Theme.kt) so every
// screen follows Dark, Light, System and wallpaper colours. Status colours
// come from LocalStatusColors, never from the wallpaper scheme.
object Palette {
    val background: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surface
    val foreground: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurface
    val card: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainerLow
    val secondary: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.secondaryContainer
    val muted: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainerHigh
    val mutedForeground: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant
    val accent: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainerHigh
    val border: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.outlineVariant
    val code: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainerLowest
    val working: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
    val done: Color @Composable @ReadOnlyComposable get() = LocalStatusColors.current.ready
    val blocked: Color @Composable @ReadOnlyComposable get() = LocalStatusColors.current.waiting
    val error: Color @Composable @ReadOnlyComposable get() = LocalStatusColors.current.failed
    val idle: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
@ReadOnlyComposable
fun stateColor(state: String?) = when (state) {
    "working" -> Palette.working
    "blocked" -> Palette.blocked
    "error" -> Palette.error
    "done" -> Palette.done
    else -> Palette.idle
}

fun parseColor(hex: String?): Color? = try {
    hex?.let { Color(android.graphics.Color.parseColor(it)) }
} catch (e: Exception) { null }

@Composable
fun Dot(color: Color, size: Dp = 9.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

// web/src/components/marks.tsx StatusMark: pulse = working, filled = done,
// hollow = idle, amber with a ring = blocked, red = error, faint = finished,
// null = an empty slot (keeps rows aligned).
@Composable
fun StatusMark(state: String?, size: Dp = 10.dp) {
    when (state) {
        null -> Box(Modifier.size(size))
        "working" -> {
            val a by rememberInfiniteTransition(label = "pulse").animateFloat(1f, 0.35f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
            Box(Modifier.size(size).alpha(a).clip(CircleShape).background(Palette.working))
        }
        "blocked" -> Box(Modifier.size(size + 4.dp).clip(CircleShape).background(Palette.blocked.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
            Box(Modifier.size(size).clip(CircleShape).background(Palette.blocked))
        }
        "error" -> Dot(Palette.error, size)
        "done" -> Dot(Palette.done, size)
        "finished" -> Dot(Palette.idle.copy(alpha = 0.4f), size)
        else -> Box(Modifier.size(size).border(1.5.dp, Palette.idle, CircleShape))
    }
}

// marks.tsx SpaceAvatar: the first letter on the space's colour.
@Composable
fun SpaceAvatar(s: Space, size: Dp = 24.dp) {
    val letter = (s.name.trimStart('~', '.').ifEmpty { s.name }).take(1).uppercase().ifEmpty { "~" }
    Box(Modifier.size(size).clip(RoundedCornerShape(6.dp)).background(parseColor(s.color) ?: Palette.idle), contentAlignment = Alignment.Center) {
        Text(letter, color = Palette.background, fontSize = (size.value / 2).sp, fontWeight = FontWeight.SemiBold)
    }
}

val STATE_LABEL = mapOf("blocked" to "Needs you", "error" to "Error", "done" to "Done", "working" to "Working", "idle" to "Idle", "finished" to "Finished")

// ---- Markdown (Markdown.kt parses; this draws) ----

@Composable
fun annotated(text: String): AnnotatedString {
    val codeBg = Palette.muted
    val link = Palette.working
    return remember(text, codeBg, link) { annotatedWith(text, codeBg, link) }
}

private fun annotatedWith(text: String, codeBg: Color, link: Color): AnnotatedString = buildAnnotatedString {
    for (s in Markdown.spans(text)) {
        val style = SpanStyle(
            fontWeight = if (s.bold) FontWeight.SemiBold else null,
            fontStyle = if (s.italic) FontStyle.Italic else null,
            fontFamily = if (s.code) Mono else null,
            background = if (s.code) codeBg else Color.Unspecified,
            fontSize = if (s.code) 0.9.em else androidx.compose.ui.unit.TextUnit.Unspecified,
        )
        if (s.url != null) {
            withLink(LinkAnnotation.Url(s.url, TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline)))) {
                withStyle(style) { append(s.text) }
            }
        } else withStyle(style) { append(s.text) }
    }
}

private val Double.em get() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Em)

@Composable
fun MarkdownView(text: String, modifier: Modifier = Modifier, keepBreaks: Boolean = false) {
    val body = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (b in Markdown.blocks(text, keepBreaks)) when (b) {
            is Block.Heading -> Text(annotated(b.text), fontWeight = FontWeight.SemiBold,
                style = when (b.level) { 1 -> MaterialTheme.typography.titleLarge; 2 -> MaterialTheme.typography.titleMedium; else -> MaterialTheme.typography.titleSmall })
            is Block.Para -> Text(annotated(b.text), style = body)
            is Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(Palette.border))
                Text(annotated(b.text), Modifier.padding(start = 10.dp), style = body, color = Palette.mutedForeground)
            }
            is Block.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(Palette.border))
            is Block.Code -> Text(b.text, Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.code)
                .horizontalScroll(rememberScrollState()).padding(10.dp), fontFamily = Mono, fontSize = 12.sp, lineHeight = 16.sp,
                softWrap = false, color = Palette.foreground)
            is Block.Items -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                b.items.forEachIndexed { i, item ->
                    Row {
                        Text(if (b.ordered) "${b.start + i}." else "•", Modifier.widthIn(min = 20.dp), style = body, color = Palette.mutedForeground)
                        Text(annotated(item), style = body)
                    }
                }
            }
            is Block.Table -> Column(Modifier.horizontalScroll(rememberScrollState()).border(1.dp, Palette.border, RoundedCornerShape(6.dp))) {
                val all = listOf(b.header) + b.rows
                val cols = all.maxOf { it.size }
                all.forEachIndexed { r, row ->
                    Row(Modifier.background(if (r == 0) Palette.muted else Color.Transparent)) {
                        for (c in 0 until cols) Text(annotated(row.getOrElse(c) { "" }), Modifier.width(140.dp).padding(horizontal = 8.dp, vertical = 5.dp),
                            fontSize = 13.sp, fontWeight = if (r == 0) FontWeight.SemiBold else null)
                    }
                }
            }
        }
    }
}
