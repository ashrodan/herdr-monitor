package io.github.ashrodan.herdrmonitor.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ashrodan.herdrmonitor.R
import io.github.ashrodan.herdrmonitor.harnessMark
import io.github.ashrodan.herdrmonitor.harnessName

// The mark of the harness that runs in a terminal (the agent hc reports:
// claude-code, codex, omp, pi). The drawables say where each mark is from.
@DrawableRes
fun harnessIcon(agent: String?): Int? = when (agent) {
    "claude-code", "claude" -> R.drawable.ic_harness_claude
    "codex" -> R.drawable.ic_harness_codex
    "omp" -> R.drawable.ic_harness_omp
    "pi" -> R.drawable.ic_harness_pi
    else -> null
}

// The harness mark, or its letter in a small box when there is no mark.
// decorative: the name is said next to it, so TalkBack skips the mark.
@Composable
fun HarnessIcon(agent: String?, size: Dp = 18.dp, modifier: Modifier = Modifier, decorative: Boolean = true) {
    val res = harnessIcon(agent)
    if (res != null) {
        Image(painterResource(res), if (decorative) null else harnessName(agent), modifier.size(size))
    } else {
        Box(modifier.size(size).clip(RoundedCornerShape(size / 3)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Text(harnessMark(agent), fontSize = (size.value * 0.55f).sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}
