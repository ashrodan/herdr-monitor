package io.github.ashrodan.herdrmonitor.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.ashrodan.herdrmonitor.Move
import io.github.ashrodan.herdrmonitor.Place
import kotlin.math.abs
import kotlin.math.roundToInt

// A pane the joystick can go to: its terminal and the name to show.
data class StickTarget(val address: String, val label: String)

private val ARROW = mapOf(Move.Left to "←", Move.Right to "→", Move.Up to "↑", Move.Down to "↓")

// The direction of a drag, once it is past `commit` px; null before that.
fun moveOf(drag: Offset, commit: Float): Move? = when {
    drag.getDistance() < commit -> null
    abs(drag.x) >= abs(drag.y) -> if (drag.x > 0) Move.Right else Move.Left
    else -> if (drag.y > 0) Move.Down else Move.Up
}

// The joystick: the action button of a session. Its face is the map of the
// tab's panes, yours filled. Push it left, right, up or down and let go: the
// session in the pane on that side opens (left and right go on into the tab
// before or after). An arrow is bright where a pane is. While you push, a
// label names the pane you go to. A tap opens the space map.
@Composable
fun Joystick(place: Place?, address: String, targets: Map<Move, StickTarget>, onMove: (String) -> Unit, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val density = LocalDensity.current
    val reach = with(density) { 20.dp.toPx() }
    val commit = with(density) { 14.dp.toPx() }
    var drag by remember { mutableStateOf(Offset.Zero) }
    val aim = moveOf(drag, commit)
    val now by rememberUpdatedState(targets)
    val go by rememberUpdatedState(onMove)
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)
    val where = place?.let { "${it.space.name}, tab ${it.tab.name}. " } ?: ""
    val ways = Move.entries.mapNotNull { m -> targets[m]?.let { "${m.name.lowercase()}: ${it.label}" } }
    val label = "Pane joystick. $where" + (if (ways.isEmpty()) "No other pane." else "Push " + ways.joinToString(", ") + ".") + " Tap for the space map."

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        // Where the push goes; "no pane" when that side is empty.
        if (aim != null) Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.padding(end = 10.dp).widthIn(max = 220.dp).testTag("joystick-aim")) {
            Text(ARROW[aim] + "  " + (targets[aim]?.label ?: "no pane"), Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(64.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .pointerInput(Unit) {
                var last: Move? = null
                detectDragGestures(
                    onDragStart = { drag = Offset.Zero; last = null },
                    onDragCancel = { drag = Offset.Zero },
                    onDragEnd = {
                        val m = moveOf(drag, commit)
                        drag = Offset.Zero
                        val t = m?.let { now[it] }
                        if (t != null) { view.performHapticFeedback(HapticFeedbackConstants.CONFIRM); go(t.address) }
                        else if (m != null) view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                    },
                ) { change, by ->
                    change.consume()
                    val next = drag + by
                    val d = next.getDistance()
                    drag = if (d > reach) next * (reach / d) else next
                    val m = moveOf(drag, commit)
                    if (m != last) { last = m; if (m != null && now[m] != null) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
                }
            }
            .clickable(onClickLabel = "Open the space map", role = Role.Button, onClick = onTap)
            .semantics {
                contentDescription = label
                customActions = Move.entries.mapNotNull { m ->
                    targets[m]?.let { t -> CustomAccessibilityAction("Go ${m.name.lowercase()} to ${t.label}") { onMove(t.address); true } }
                }
            }
            .testTag("joystick-$address"),
            contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val a = 4.dp.toPx()
                val inset = 6.dp.toPx()
                val cx = size.width / 2
                val cy = size.height / 2
                fun arrow(m: Move) {
                    val tip = when (m) {
                        Move.Left -> Offset(inset, cy); Move.Right -> Offset(size.width - inset, cy)
                        Move.Up -> Offset(cx, inset); Move.Down -> Offset(cx, size.height - inset)
                    }
                    val k = if (m == aim) 1.5f else 1f
                    val p = Path().apply {
                        moveTo(tip.x, tip.y)
                        when (m) {
                            Move.Left -> { lineTo(tip.x + a * k, tip.y - a * k); lineTo(tip.x + a * k, tip.y + a * k) }
                            Move.Right -> { lineTo(tip.x - a * k, tip.y - a * k); lineTo(tip.x - a * k, tip.y + a * k) }
                            Move.Up -> { lineTo(tip.x - a * k, tip.y + a * k); lineTo(tip.x + a * k, tip.y + a * k) }
                            Move.Down -> { lineTo(tip.x - a * k, tip.y - a * k); lineTo(tip.x + a * k, tip.y - a * k) }
                        }
                        close()
                    }
                    drawPath(p, if (targets[m] != null) on else off)
                }
                Move.entries.forEach(::arrow)
            }
            // The stick's head: the panes of this tab, moved by the push.
            WhereAmIIcon(place, address, Modifier.offset { IntOffset(drag.x.roundToInt(), drag.y.roundToInt()) }, size = 26.dp)
        }
    }
}
