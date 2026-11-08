package io.github.ashrodan.herdrmonitor.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ashrodan.herdrmonitor.Live
import io.github.ashrodan.herdrmonitor.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val HARNESSES = listOf("claude" to "Claude Code", "omp" to "omp")
const val PROMPT_MAX = 4000

// New session: pick a space and a harness, type the task (a ticket key can
// go in the text), Start. The server starts the agent in the space's
// folder (POST /api/sessions) and the app opens it.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewSessionSheet(live: Live, onDismiss: () -> Unit, initialSpace: String? = null, onStarted: (String) -> Unit) {
    val app = LocalContext.current.app
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val spaces = remember(live.spaces) { live.spaces.filter { !it.virtual } }
    var space by rememberSaveable { mutableStateOf(initialSpace?.takeIf { id -> spaces.any { it.id == id } } ?: spaces.firstOrNull()?.id) }
    var harness by rememberSaveable { mutableStateOf("claude") }
    var prompt by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("New session", style = MaterialTheme.typography.titleLarge)
            Text("Space", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (spaces.isEmpty()) Text("No spaces yet. Open one in Herdr first.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                items(spaces, key = { it.id }) { s ->
                    Row(Modifier.fillMaxWidth().selectable(selected = space == s.id, role = Role.RadioButton) { space = s.id }
                        .padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = space == s.id, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.size(12.dp).clip(RoundedCornerShape(4.dp)).background(parseColor(s.color) ?: MaterialTheme.colorScheme.outline))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            s.branch?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                        }
                    }
                }
            }
            Text("Harness", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                HARNESSES.forEachIndexed { i, (id, label) ->
                    SegmentedButton(selected = harness == id, onClick = { harness = id }, shape = SegmentedButtonDefaults.itemShape(i, HARNESSES.size)) { Text(label) }
                }
            }
            OutlinedTextField(prompt, { if (it.length <= PROMPT_MAX) prompt = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp).testTag("new-prompt"),
                label = { Text("Task") }, placeholder = { Text("What should it do? A ticket key can go here too.") },
                supportingText = { Text("${prompt.length} / $PROMPT_MAX") })
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Button(
                onClick = {
                    val sp = space ?: return@Button
                    busy = true; error = null
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { app.store.api.startSession(sp, harness, prompt.trim()) } }
                            .onSuccess {
                                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                // It runs either way; say so when Herdr has no tab for it.
                                it.placeError?.let { why -> android.widget.Toast.makeText(app, "Started, but not in a Herdr tab: $why", android.widget.Toast.LENGTH_LONG).show() }
                                onStarted(it.address)
                            }
                            .onFailure { view.performHapticFeedback(HapticFeedbackConstants.REJECT); error = "Not started: ${it.message}" }
                        busy = false
                    }
                },
                enabled = !busy && space != null && prompt.isNotBlank(),
                modifier = Modifier.fillMaxWidth().testTag("new-start"),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Start")
            }
        }
    }
}
