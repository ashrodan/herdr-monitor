package io.github.ashrodan.herdrmonitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.ashrodan.herdrmonitor.Notifier

@Composable
fun HerdrTheme(content: @Composable () -> Unit) {
    val c = LocalContext.current
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(c) else dynamicLightColorScheme(c), content = content)
}

fun stateColor(state: String?) = Color(Notifier.colorOf(state ?: "idle"))

fun parseColor(hex: String?): Color? = try {
    hex?.let { Color(android.graphics.Color.parseColor(it)) }
} catch (e: Exception) { null }

@Composable
fun Dot(color: Color, size: Dp = 9.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

val STATE_LABEL = mapOf("blocked" to "Needs you", "error" to "Error", "done" to "Done", "working" to "Working", "idle" to "Idle", "finished" to "Finished")

// Just enough Markdown for chat: # headings, **bold**, `code`. Code fences are split
// out by the caller.
fun inlineMarkdown(text: String, codeBg: Color): AnnotatedString = buildAnnotatedString {
    text.split('\n').forEachIndexed { i, raw ->
        if (i > 0) append('\n')
        // "## Title": the words, bold.
        val heading = Regex("^#{1,6}\\s+(.*)").find(raw)?.groupValues?.get(1)
        if (heading != null) { pushStyle(SpanStyle(fontWeight = FontWeight.Bold)); inline(heading, codeBg); pop() }
        else inline(raw, codeBg)
    }
}

private fun AnnotatedString.Builder.inline(text: String, codeBg: Color) {
    val re = Regex("""\*\*(.+?)\*\*|`([^`]+)`""")
    var at = 0
    for (m in re.findAll(text)) {
        append(text.substring(at, m.range.first))
        val bold = m.groups[1]?.value
        if (bold != null) {
            pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold)); append(bold); pop()
        } else {
            pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)); append(m.groups[2]!!.value); pop()
        }
        at = m.range.last + 1
    }
    append(text.substring(at))
}

// Text and ``` fenced code, in order.
fun splitFences(text: String): List<Pair<Boolean, String>> {
    val out = mutableListOf<Pair<Boolean, String>>()
    val parts = text.split("```")
    parts.forEachIndexed { i, p ->
        val code = i % 2 == 1
        val body = if (code) p.substringAfter('\n', p).trimEnd() else p.trim('\n')
        if (body.isNotBlank()) out += code to body
    }
    return out
}
