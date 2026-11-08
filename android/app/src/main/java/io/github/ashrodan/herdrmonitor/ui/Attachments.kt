package io.github.ashrodan.herdrmonitor.ui

import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

const val MAX_IMAGES = 5

enum class AttachState { Uploading, Ready, Failed }

// One image picked for a reply: its thumbnail, and the server path once it
// is uploaded (the path goes into the message).
data class Attachment(
    val uri: Uri,
    val state: AttachState = AttachState.Uploading,
    val thumb: ImageBitmap? = null,
    val path: String? = null,
    val error: String? = null,
)

// A small copy for the strip. ImageDecoder applies the photo's rotation.
fun thumbnail(bytes: ByteArray, px: Int): ImageBitmap? = runCatching {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { d, info, _ ->
        val scale = px.toFloat() / maxOf(info.size.width, info.size.height)
        if (scale < 1f) d.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }.asImageBitmap()
}.getOrNull()

// The images above the field: a tap shows one large (a failed one tries
// again), the cross takes it out of the message.
@Composable
fun AttachmentStrip(items: List<Attachment>, onOpen: (Attachment) -> Unit, onRemove: (Attachment) -> Unit) {
    LazyRow(Modifier.testTag("attachments"), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(items, key = { it.uri.toString() }) { a -> AttachmentThumb(a, onOpen, onRemove) }
    }
}

@Composable
private fun AttachmentThumb(a: Attachment, onOpen: (Attachment) -> Unit, onRemove: (Attachment) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val failed = a.state == AttachState.Failed
    val label = when (a.state) {
        AttachState.Uploading -> "Image, uploading"
        AttachState.Ready -> "Image attached. Tap to view"
        AttachState.Failed -> "Image not uploaded${a.error?.let { ": $it" } ?: ""}. Tap to try again"
    }
    Box(Modifier.size(76.dp)) {
        Box(Modifier.padding(top = 6.dp, end = 6.dp).size(70.dp).clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(if (failed) 2.dp else 1.dp, if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(enabled = a.state != AttachState.Uploading) { onOpen(a) }
            .semantics { contentDescription = label }
            .testTag("attachment"),
            contentAlignment = Alignment.Center) {
            a.thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            when (a.state) {
                AttachState.Uploading -> Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                }
                AttachState.Failed -> Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Refresh, null, tint = Color.White)
                }
                AttachState.Ready -> {}
            }
        }
        // The remove cross sits on the corner, with a 32dp target.
        Box(Modifier.align(Alignment.TopEnd).size(32.dp).clickable { onRemove(a) }
            .semantics { contentDescription = "Remove image" }.testTag("attachment-remove"),
            contentAlignment = Alignment.TopEnd) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.inverseSurface), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Close, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.inverseOnSurface)
            }
        }
    }
}

// The image at full screen width, on black. Back or the cross closes it.
@Composable
fun ImagePreview(a: Attachment, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var big by remember(a.uri) { mutableStateOf(a.thumb) }
    LaunchedEffect(a.uri) {
        withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.openInputStream(a.uri)!!.use { it.readBytes() } }.getOrNull()
        }?.let { bytes -> withContext(Dispatchers.Default) { thumbnail(bytes, 2048) }?.let { big = it } }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).testTag("image-preview")) {
            big?.let { Image(it, "Attached image", Modifier.fillMaxSize().clickable(onClick = onDismiss), contentScale = ContentScale.Fit) }
                ?: Text("No preview", Modifier.align(Alignment.Center), color = Color.White)
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                Icon(Icons.Default.Close, "Close the preview", tint = Color.White)
            }
        }
    }
}
