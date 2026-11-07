package io.github.ashrodan.herdrmonitor.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ashrodan.herdrmonitor.Api
import io.github.ashrodan.herdrmonitor.OutItem
import io.github.ashrodan.herdrmonitor.Outbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// What the composer needs from the server. Api is the real one; UI tests
// pass a fake.
interface ReplyPort {
    suspend fun send(address: String, text: String, force: Boolean)
    suspend fun keys(address: String, keys: List<String>)
    suspend fun interrupt(address: String)
    suspend fun upload(address: String, bytes: ByteArray, type: String): String
}

class ApiPort(private val api: Api) : ReplyPort {
    override suspend fun send(address: String, text: String, force: Boolean) { withContext(Dispatchers.IO) { api.send(address, text, force) } }
    override suspend fun keys(address: String, keys: List<String>) { withContext(Dispatchers.IO) { api.keys(address, keys) } }
    override suspend fun interrupt(address: String) { withContext(Dispatchers.IO) { api.interrupt(address) } }
    override suspend fun upload(address: String, bytes: ByteArray, type: String): String = withContext(Dispatchers.IO) { api.upload(address, bytes, type) }
}

private val DIALOG_KEYS = listOf("up" to "↑", "down" to "↓", "enter" to "⏎", "esc" to "Esc", "tab" to "Tab", "1" to "1", "2" to "2", "3" to "3")

// The reply bar. A reply goes out at once when the link is up; if the link
// is down, or the send fails, it goes to the outbox and is sent later, in
// order. Either way the field clears. Interrupts and keys are live: they
// never queue. Images upload first; an image that does not upload stays
// here to try again.
@Composable
fun Composer(
    port: ReplyPort,
    outbox: Outbox,
    address: String,
    online: Boolean,
    blocked: Boolean,
    kind: String?,
    working: Boolean,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember(address) { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    val images = remember(address) { mutableStateListOf<String>() } // uploaded paths
    val notUploaded = remember(address) { mutableStateListOf<Uri>() }
    var uploading by remember { mutableStateOf(false) }
    LaunchedEffect(note) { if (note != null && !note!!.startsWith("Not")) { delay(2500); note = null } }

    fun upload(uri: Uri) {
        uploading = true
        scope.launch {
            runCatching {
                val (type, bytes) = withContext(Dispatchers.IO) {
                    (ctx.contentResolver.getType(uri) ?: "application/octet-stream") to ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                }
                port.upload(address, bytes, type)
            }.onSuccess { images.add(it); notUploaded.remove(uri) }
                .onFailure { if (uri !in notUploaded) notUploaded.add(uri); note = "Not uploaded: ${it.message}" }
            uploading = false
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) upload(uri) }

    fun send() {
        val full = (listOf(text.trim()) + images).filter { it.isNotBlank() }.joinToString(" ")
        if (full.isBlank() || sending || uploading) return
        // At a dialog, force: the text goes to the dialog (e.g. an "Other" answer).
        val force = blocked
        fun queued(why: String?) {
            outbox.add(address, full, force, why)
            text = ""; images.clear()
            note = "Queued. It goes when the link is back."
        }
        if (!online) { queued("Offline"); return }
        sending = true
        scope.launch {
            runCatching { port.send(address, full, force) }
                .onSuccess { text = ""; images.clear(); note = "Sent." }
                .onFailure { queued(it.message ?: it.javaClass.simpleName) }
            sending = false
        }
    }
    fun keys(k: String) = scope.launch { runCatching { port.keys(address, listOf(k)) }.onFailure { note = "Not sent: ${it.message}" } }
    fun interrupt() = scope.launch { runCatching { port.interrupt(address) }.onSuccess { note = "Stop key sent." }.onFailure { note = "Not sent: ${it.message}" } }

    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(8.dp)) {
            val info = when {
                !online -> "Offline. Replies wait in the outbox."
                blocked -> "Waiting at a ${kind ?: "dialog"}. Tap an answer in Chat, press keys, or type here."
                else -> null
            }
            (note ?: info)?.let {
                Text(it, Modifier.padding(horizontal = 4.dp, vertical = 2.dp).testTag("composer-note"), style = MaterialTheme.typography.labelSmall,
                    color = if (it.startsWith("Not")) MaterialTheme.colorScheme.error else if (blocked && note == null) stateColor("blocked") else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (blocked) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for ((k, label) in DIALOG_KEYS) AssistChip(onClick = { keys(k) }, label = { Text(label) })
            }
            if (images.isNotEmpty()) Text("${images.size} image${if (images.size == 1) "" else "s"} attached · tap to remove", style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.clickable { images.clear() }.padding(4.dp))
            if (notUploaded.isNotEmpty()) Text("${notUploaded.size} image${if (notUploaded.size == 1) "" else "s"} not uploaded · tap to try again",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.clickable(enabled = !uploading) { notUploaded.toList().forEach(::upload) }.padding(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !uploading) {
                    Icon(Icons.Default.Add, "Attach an image")
                }
                OutlinedTextField(text, { text = it }, Modifier.weight(1f).testTag("composer-field"),
                    placeholder = { Text(if (blocked) "Answer…" else "Reply…") }, maxLines = 6)
                if (online && (working || blocked)) TextButton(onClick = { interrupt() }) { Text("Stop") }
                IconButton(onClick = { send() }, Modifier.testTag("composer-send"),
                    enabled = !sending && !uploading && (text.isNotBlank() || images.isNotEmpty())) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
        }
    }
}

// Replies in the outbox for this agent: your bubble, marked Queued, with
// the last error and a control to delete it before it goes.
@Composable
fun QueuedReplies(items: List<OutItem>, onDelete: (OutItem) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (item in items) QueuedBubble(item, onDelete)
    }
}

@Composable
fun QueuedBubble(item: OutItem, onDelete: (OutItem) -> Unit) {
    // One item for TalkBack and tests: "Queued", the error and the text.
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("queued-${item.id}").semantics(mergeDescendants = true) {}) {
        val w = maxWidth * 0.85f
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.widthIn(max = w)) {
                Column(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Queued" + (item.lastError?.let { " · $it" } ?: ""), Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        IconButton(onClick = { onDelete(item) }, Modifier.testTag("delete-${item.id}")) {
                            Icon(Icons.Default.Close, "Delete queued reply")
                        }
                    }
                    MarkdownView(item.text, Modifier.padding(end = 14.dp), keepBreaks = true)
                }
            }
        }
    }
}
