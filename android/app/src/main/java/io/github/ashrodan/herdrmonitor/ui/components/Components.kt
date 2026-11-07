package io.github.ashrodan.herdrmonitor.ui.components

import android.provider.Settings
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.heightIn
import io.github.ashrodan.herdrmonitor.ui.reducedMotion
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ashrodan.herdrmonitor.Bucket
import io.github.ashrodan.herdrmonitor.Counts
import io.github.ashrodan.herdrmonitor.Part
import io.github.ashrodan.herdrmonitor.Session
import io.github.ashrodan.herdrmonitor.Status
import io.github.ashrodan.herdrmonitor.age
import io.github.ashrodan.herdrmonitor.duration
import io.github.ashrodan.herdrmonitor.quickOptions
import io.github.ashrodan.herdrmonitor.ui.parseColor
import io.github.ashrodan.herdrmonitor.ui.theme.Corner
import io.github.ashrodan.herdrmonitor.ui.theme.LocalStatusColors
import io.github.ashrodan.herdrmonitor.ui.theme.Mono
import io.github.ashrodan.herdrmonitor.ui.theme.cardGroupShape

// ---- Icons the core icon set does not have ----

// A four-point sparkle (Ask Herdr).
val SparkleIcon: ImageVector = materialIcon("Sparkle") {
    materialPath {
        moveTo(12f, 2f); curveTo(12.6f, 7.4f, 16.6f, 11.4f, 22f, 12f)
        curveTo(16.6f, 12.6f, 12.6f, 16.6f, 12f, 22f); curveTo(11.4f, 16.6f, 7.4f, 12.6f, 2f, 12f)
        curveTo(7.4f, 11.4f, 11.4f, 7.4f, 12f, 2f); close()
    }
}

// A clock (stale chip).
val ClockIcon: ImageVector = materialIcon("Clock") {
    materialPath {
        // Ring: outer circle minus inner circle (even-odd by direction).
        moveTo(12f, 2f); arcToRelative(10f, 10f, 0f, true, true, 0f, 20f); arcToRelative(10f, 10f, 0f, true, true, 0f, -20f); close()
        moveTo(12f, 4f); arcToRelative(8f, 8f, 0f, true, false, 0f, 16f); arcToRelative(8f, 8f, 0f, true, false, 0f, -16f); close()
        // Hands.
        moveTo(11f, 7f); lineTo(13f, 7f); lineTo(13f, 11.4f); lineTo(16.2f, 13.3f); lineTo(15.2f, 15f); lineTo(11f, 12.6f); close()
    }
}

// A light tick (filter chips): SEGMENT_TICK from Android 14, else a clock tick.
fun tick(view: android.view.View) {
    view.performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 34) android.view.HapticFeedbackConstants.SEGMENT_TICK else android.view.HapticFeedbackConstants.CLOCK_TICK)
}

// ---- Status ----

fun statusWord(s: Status) = when (s) {
    Status.Waiting -> "Needs you"; Status.Failed -> "Failed"; Status.Working -> "Working"; Status.Done -> "Done"; Status.Ready -> "Ready"; Status.Idle -> "Idle"
}

// One badge per status: shape, colour and icon, and a content description.
// Idle has no badge.
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StatusBadge(status: Status, size: Dp = 20.dp, ring: Color = Color.Transparent) {
    val c = LocalStatusColors.current
    val label = statusWord(status)
    val mod = Modifier.size(size).semantics { contentDescription = label }
    when (status) {
        Status.Idle -> Unit
        Status.Working -> Box(mod.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest).border(2.dp, ring, CircleShape),
            contentAlignment = Alignment.Center) {
            // Animations off: a still dot instead of the morphing indicator.
            if (reducedMotion()) Box(Modifier.size(size * 0.45f).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            else LoadingIndicator(Modifier.size(size), color = MaterialTheme.colorScheme.primary)
        }
        Status.Waiting -> Box(mod.clip(CircleShape).background(c.waiting).border(2.dp, ring, CircleShape), contentAlignment = Alignment.Center) {
            Text("?", color = c.onWaiting, fontSize = (size.value * 0.6f).sp, fontWeight = FontWeight.Black)
        }
        Status.Failed -> Box(mod.clip(RoundedCornerShape(6.dp)).background(c.failed).border(2.dp, ring, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
            Text("!", color = c.onFailed, fontSize = (size.value * 0.62f).sp, fontWeight = FontWeight.Black)
        }
        Status.Done, Status.Ready -> Box(mod.clip(CircleShape).background(c.ready).border(2.dp, ring, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Check, null, Modifier.size(size * 0.7f), tint = c.onReady)
        }
    }
}

// 40dp monogram on the space colour, with the status badge bottom-right,
// ringed in the card colour.
@Composable
fun SessionAvatar(letter: String, color: String?, status: Status, cardColor: Color) {
    val bg = parseColor(color) ?: MaterialTheme.colorScheme.primaryContainer
    Box(Modifier.size(44.dp)) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(bg.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
            Text(letter, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Box(Modifier.align(Alignment.BottomEnd)) { StatusBadge(status, 20.dp, ring = cardColor) }
    }
}

// ---- Chips and headers ----

@Composable
fun SectionHeader(title: String, count: Int, waiting: Boolean = false) {
    val c = LocalStatusColors.current
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = if (waiting) c.waiting else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text("$count", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// All, Needs you, Working, Done, Ready, Idle. A chip with a count of 0 is
// hidden, except All. Only the Needs you count is coloured.
@Composable
fun StatusFilterRow(counts: Counts, selected: Bucket?, onSelect: (Bucket?) -> Unit) {
    val view = LocalView.current
    val c = LocalStatusColors.current
    val chips = listOf<Pair<Bucket?, String>>(null to "All", Bucket.NeedsYou to "Needs you", Bucket.Working to "Working",
        Bucket.Done to "Done", Bucket.Ready to "Ready", Bucket.Idle to "Idle")
        .filter { (b, _) -> b == null || counts.of(b) > 0 }
    // A FlowRow, not a scrolling row: at a large font size the chips wrap
    // to a second line instead of going off screen.
    FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((b, label) in chips) {
            FilterChip(
                selected = selected == b, onClick = { tick(view); onSelect(b) },
                label = {
                    Text(label)
                    if (b != null) {
                        Spacer(Modifier.width(6.dp))
                        Text("${counts.of(b)}", fontWeight = FontWeight.Bold, color = if (b == Bucket.NeedsYou) c.waiting else Color.Unspecified)
                    }
                },
                shape = RoundedCornerShape(Corner.chip),
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer),
            )
        }
    }
}

enum class MetaKind { Branch, Pr, Checks, Stale, Sent }

@Composable
fun MetaChip(kind: MetaKind, label: String, onClick: (() -> Unit)? = null) {
    val c = LocalStatusColors.current
    val (fg, border) = when (kind) {
        MetaKind.Checks -> c.failed to c.failed.copy(alpha = 0.5f)
        MetaKind.Stale -> c.waiting to c.waiting.copy(alpha = 0.5f)
        MetaKind.Pr -> c.ready to c.ready.copy(alpha = 0.5f)
        MetaKind.Sent -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        MetaKind.Branch -> MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.outlineVariant
    }
    val shape = RoundedCornerShape(Corner.chip)
    Row(Modifier.let { if (onClick != null) it.minimumInteractiveComponentSize() else it }.heightIn(min = 28.dp).clip(shape).border(1.dp, border, shape)
        .let { if (onClick != null) it.combinedClickable(onClick = onClick) else it }.padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (kind == MetaKind.Stale) { Icon(ClockIcon, null, Modifier.size(14.dp), tint = fg); Spacer(Modifier.width(5.dp)) }
        Text(label, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontFamily = if (kind == MetaKind.Branch) Mono else null, fontSize = if (kind == MetaKind.Branch) 11.5.sp else 12.5.sp)
    }
}

// The question in full, then up to three answers and Reply. Used on the
// Needs you card (and in the transcript in Phase 3).
@Composable
fun QuestionBlock(ask: Part.Ask, onAnswer: (Int, String) -> Unit, onReply: () -> Unit) {
    val c = LocalStatusColors.current
    val q = ask.questions.firstOrNull() ?: return
    val options = ask.quickOptions(3)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(q.question, style = MaterialTheme.typography.bodyMedium, color = c.onWaitingContainer)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options?.forEachIndexed { j, label ->
                if (j == 0) Button(onClick = { onAnswer(j, label) }, Modifier.height(34.dp).minimumInteractiveComponentSize(),
                    colors = ButtonDefaults.buttonColors(containerColor = c.waiting, contentColor = c.onWaiting),
                    contentPadding = PaddingValues(horizontal = 14.dp)) { Text(label, maxLines = 1) }
                else OutlinedButton(onClick = { onAnswer(j, label) }, Modifier.height(34.dp).minimumInteractiveComponentSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = c.onWaitingContainer)) { Text(label, maxLines = 1) }
            }
            OutlinedButton(onClick = onReply, Modifier.height(34.dp).minimumInteractiveComponentSize(), contentPadding = PaddingValues(horizontal = 14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = c.onWaitingContainer)) { Text("Reply") }
        }
    }
}

// ---- The session card ----

// A card in a segmented group. Needs you is the only tinted card. Bold
// name and task with a dot after the time: new output since you last
// opened it. The press morphs the corners to 22dp.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionCard(
    s: Session,
    index: Int,
    count: Int,
    now: Long,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onAnswer: (Int, String) -> Unit,
    onPin: () -> Unit,
    onStop: () -> Unit,
    selected: Boolean = false,
) {
    val sc = LocalStatusColors.current
    val tinted = s.status == Status.Waiting && s.sent == null
    val bg = if (tinted) sc.waitingContainer else MaterialTheme.colorScheme.surfaceContainerLow
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduced = reducedMotion()
    val corner by animateDpAsState(if (pressed && !reduced) Corner.groupOuter else 0.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "corner")
    val base = cardGroupShape(index, count)
    val shape = if (corner > 0.dp) RoundedCornerShape(maxOf(corner, Corner.groupInner)) else base
    val a11y = buildString {
        append(s.name); append(", "); append(statusWord(s.status).lowercase())
        s.task?.let { append(", "); append(it) }
        s.timeMs?.let { append(", "); append(age(it, now).let { a -> if (a == "<1m") "now" else a }) }
    }
    // Two panes: the open session has a primary outline.
    Surface(color = bg, shape = shape, border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth()
        .combinedClickable(interactionSource = interaction, indication = null, onClick = onOpen, onLongClick = onLongPress)
        .semantics(mergeDescendants = s.ask == null) {
            contentDescription = a11y
            this.selected = selected
            customActions = listOfNotNull(
                CustomAccessibilityAction(if (s.pinned) "Unpin" else "Pin") { onPin(); true },
                if (s.status == Status.Working || s.status == Status.Waiting) CustomAccessibilityAction("Stop") { onStop(); true } else null,
            ) + (s.ask?.quickOptions(3)?.mapIndexed { j, l -> CustomAccessibilityAction("Answer $l") { onAnswer(j, l); true } } ?: emptyList())
        }) {
        Row(Modifier.padding(start = 12.dp, end = 14.dp, top = 14.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SessionAvatar(s.letter, s.spaceColor, s.status, bg)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val weight = if (s.unread) FontWeight.Bold else FontWeight.Normal
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (s.pinned) Text("📌 ", fontSize = 12.sp)
                    Text(s.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = weight,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (tinted) sc.onWaitingContainer else MaterialTheme.colorScheme.onSurface)
                    Text(age(s.timeMs, now).let { if (it == "<1m") "now" else it }, style = MaterialTheme.typography.labelMedium,
                        color = if (tinted) sc.onWaitingContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (s.unread) Box(Modifier.padding(start = 6.dp).size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                }
                s.task?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = weight, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (tinted) sc.onWaitingContainer else MaterialTheme.colorScheme.onSurface)
                }
                if (s.ask != null && s.sent == null) {
                    Spacer(Modifier.height(4.dp))
                    QuestionBlock(s.ask, onAnswer, onReply = onOpen)
                } else s.snippet?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val chips = buildList {
                    s.sent?.let { add(Triple(MetaKind.Sent, "Sent: $it", null)) }
                    s.staleForMs?.let { add(Triple(MetaKind.Stale, "No output for ${duration(it)}", null)) }
                    s.pr?.let { p ->
                        if (p.fail > 0) add(Triple(MetaKind.Checks, "${p.fail} failing", p.pr.url))
                        add(Triple(MetaKind.Pr, "#${p.pr.number}" + when {
                            p.pr.isDraft -> " draft"
                            p.readiness == "ready" -> " ready"
                            p.pending > 0 -> " checks running"
                            else -> " open"
                        }, p.pr.url))
                    }
                    s.branch?.let { add(Triple(MetaKind.Branch, it, null)) }
                }
                if (chips.isNotEmpty()) {
                    val ctx = LocalContext.current
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((k, label, url) in chips) MetaChip(k, label, url?.let { u -> { openUrl(ctx, u) } })
                    }
                }
            }
        }
    }
}

fun openUrl(ctx: android.content.Context, url: String) {
    runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

// One collapsed row for idle sessions; it opens the Idle list.
@Composable
fun IdleRow(count: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Corner.groupOuter)
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 12.dp).clip(shape)
        .background(MaterialTheme.colorScheme.surfaceContainerLowest).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
        .combinedClickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Idle sessions", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("$count", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// The host in the top bar: initials and a connection dot.
@Composable
fun HostAvatar(label: String, online: Boolean, onClick: () -> Unit) {
    val c = LocalStatusColors.current
    Box(Modifier.size(48.dp).combinedClickable(onClick = onClick).semantics { contentDescription = "Server $label, ${if (online) "online" else "offline"}" },
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Text(label.take(3), color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Box(Modifier.align(Alignment.BottomEnd).offset((-3).dp, (-3).dp).size(12.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface).padding(2.dp).clip(CircleShape)
            .background(if (online) c.ready else MaterialTheme.colorScheme.outline))
    }
}

// Notification settings for this app.
fun notificationSettings(ctx: android.content.Context) = runCatching {
    ctx.startActivity(android.content.Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
}
