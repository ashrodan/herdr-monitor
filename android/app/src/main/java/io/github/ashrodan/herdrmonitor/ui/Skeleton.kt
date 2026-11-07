package io.github.ashrodan.herdrmonitor.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.ashrodan.herdrmonitor.ui.theme.Corner

// Placeholders in the shape of what loads, so the screen does not jump
// when it comes. One soft pulse; still when animations are off. The alpha
// is read in the draw phase (graphicsLayer), so the pulse never recomposes.

@Composable
private fun pulse(): () -> Float {
    if (reducedMotion()) return { 0.6f }
    val a by rememberInfiniteTransition(label = "skeleton").animateFloat(0.4f, 0.8f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    return { a }
}

@Composable
private fun Bone(width: Dp?, height: Dp, alpha: () -> Float, circle: Boolean = false) {
    val m = if (width == null) Modifier.fillMaxWidth() else Modifier.width(width)
    Box(m.height(height).graphicsLayer { this.alpha = alpha() }
        .clip(if (circle) CircleShape else RoundedCornerShape(6.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHighest))
}

// A session card's shape: avatar, name, task, two lines of last words.
@Composable
fun SkeletonCard(modifier: Modifier = Modifier) {
    val a = pulse()
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(Corner.groupOuter)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp)) {
        Box(Modifier.size(40.dp)) { Bone(40.dp, 40.dp, a, circle = true) }
        Spacer(Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Bone(160.dp, 16.dp, a)
            Bone(120.dp, 12.dp, a)
            Bone(null, 12.dp, a)
            Bone(220.dp, 12.dp, a)
        }
    }
}

// Home while it connects with nothing cached.
@Composable
fun SkeletonSessions() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).semantics { contentDescription = "Loading sessions" },
        verticalArrangement = Arrangement.spacedBy(Corner.groupGap)) {
        repeat(4) { SkeletonCard() }
    }
}

// A PR row's shape, for the PRs tab while /api/overview runs.
@Composable
fun SkeletonRows(count: Int = 4, label: String) {
    val a = pulse()
    Column(Modifier.fillMaxWidth().padding(8.dp).semantics { contentDescription = label }, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        repeat(count) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Bone(200.dp, 14.dp, a)
                Bone(null, 12.dp, a)
            }
        }
    }
}

// A transcript's shape: prose, a user bubble on the right, prose.
@Composable
fun SkeletonChat() {
    val a = pulse()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp).semantics { contentDescription = "Loading the conversation" },
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Bone(90.dp, 12.dp, a)
        Bone(null, 14.dp, a); Bone(null, 14.dp, a); Bone(180.dp, 14.dp, a)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Bone(200.dp, 40.dp, a) }
        Spacer(Modifier.height(10.dp))
        Bone(90.dp, 12.dp, a)
        Bone(null, 14.dp, a); Bone(240.dp, 14.dp, a)
    }
}
