package com.comfyui.mobile.ui.components

import com.comfyui.mobile.ui.designsystem.PocketIcons

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.comfyui.mobile.model.EditableNode
import com.comfyui.mobile.model.MediaOutput
import com.comfyui.mobile.model.MediaType
import com.comfyui.mobile.ui.MainViewModelGallery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.comfyui.mobile.ui.designsystem.PocketButton as Button
import com.comfyui.mobile.ui.designsystem.PocketButtonDefaults as ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text






import androidx.compose.material3.MaterialTheme

/** Video by name / Uri (mp4 / webm / mov / mkv / m4v). */
fun isVideoName(name: String?): Boolean {
    val lower = name?.substringBefore('?')?.lowercase() ?: return false
    return lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mov") || lower.endsWith(".mkv") || lower.endsWith(".m4v")
}

/** Sniffs a local file's header: true for MP4/MOV (ftyp) and WebM/MKV (EBML), whatever its extension. */
fun isVideoFile(file: java.io.File?): Boolean {
    if (file == null || !file.isFile) return false
    return try {
        val head = ByteArray(12)
        val n = file.inputStream().use { it.read(head) }
        if (n < 12) return false
        val ftyp = head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() && head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte()
        val ebml = head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() && head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte()
        ftyp || ebml
    } catch (_: Exception) {
        false
    }
}

/**
 * Coil request for a thumbnail. GIF / animated WebP play through the app ImageLoader's animated decoders;
 * [isVideo] shows the frame at [frameMillis] (forced video decoder: works for files without a video extension).
 */
fun thumbnailRequest(context: Context, data: Any?, isVideo: Boolean, frameMillis: Long = 1000L): ImageRequest {
    // Decode at thumbnail resolution — full-res SD/video frames in grids causes scroll jank.
    val b = ImageRequest.Builder(context)
        .data(data)
        .crossfade(true)
        .size(512)
    if (isVideo) {
        b.videoFrameMillis(frameMillis)
        b.decoderFactory(VideoFrameDecoder.Factory())
    }
    return b.build()
}

/** Image / GIF / video thumbnail; videos get a play overlay. */
@Composable
fun MediaThumbnail(
    data: Any?,
    isVideo: Boolean,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    frameMillis: Long = 1000L,
    playIconSize: Dp = 28.dp
    ) {
    val context = LocalContext.current
    val request = remember(data, isVideo, frameMillis) { thumbnailRequest(context, data, isVideo, frameMillis) }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AsyncImage(
            model = request,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = contentScale
    )
        if (isVideo) {
            Icon(
                PocketIcons.Play,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.88f),
                modifier = Modifier.size(playIconSize)
    )
        }
    }
}

/** Content Uri usable by other apps for a local output (content:// as is, file:// through the FileProvider). */
fun shareableUri(context: Context, localUri: String?): Uri? {
    val raw = localUri?.takeIf { it.isNotBlank() } ?: return null
    val uri = Uri.parse(raw)
    return when (uri.scheme) {
        "content" -> uri
        "file" -> {
            val f = java.io.File(uri.path ?: return null)
            if (!f.exists()) return null
            runCatching { FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f) }.getOrNull()
        }
        else -> null
    }
}

private fun outputMime(media: MediaOutput): String = when (media.mediaType) {
    MediaType.FILE, MediaType.TEXT -> MainViewModelGallery.genericMimeType(media.filename)
    else -> MainViewModelGallery.mimeTypeForMediaType(media.mediaType, media.filename)
}

/** Opens a downloaded output with another app (ACTION_VIEW, chooser). */
fun openOutputFile(context: Context, media: MediaOutput) {
    val uri = shareableUri(context, media.localUri)
    if (uri == null) {
        Toast.makeText(context, "文件尚未下载到本机", Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, outputMime(media))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(intent, "打开 ${media.filename}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "没有可以打开此文件的应用", Toast.LENGTH_SHORT).show()
    } catch (_: Exception) {
        Toast.makeText(context, "无法打开此文件", Toast.LENGTH_SHORT).show()
    }
}

/** Shares a downloaded output (ACTION_SEND, chooser). */
fun shareOutputFile(context: Context, media: MediaOutput) {
    val uri = shareableUri(context, media.localUri)
    if (uri == null) {
        Toast.makeText(context, "文件尚未下载到本机", Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = outputMime(media)
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(intent, "分享 ${media.filename}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        Toast.makeText(context, "无法分享此文件", Toast.LENGTH_SHORT).show()
    }
}

private fun readSize(context: Context, localUri: String?): Long? {
    val raw = localUri?.takeIf { it.isNotBlank() } ?: return null
    val uri = Uri.parse(raw)
    return try {
        when (uri.scheme) {
            "file" -> java.io.File(uri.path ?: return null).takeIf { it.exists() }?.length()
            "content" -> context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}

fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024L -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/**
 * Result tile of a generic output file (zip, json, safetensors, ...): icon, name, size.
 * Tap = open with another app; long-press = share.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileOutputCard(
    media: MediaOutput,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
    val context = LocalContext.current
    val size by produceState<Long?>(null, media.localUri) {
        value = withContext(Dispatchers.IO) { readSize(context, media.localUri) }
    }
    Box(
        modifier = modifier.combinedClickable(
            onClick = { openOutputFile(context, media) },
            onLongClick = { shareOutputFile(context, media) }
    ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
    ) {
            Icon(PocketIcons.File, contentDescription = null, tint = textColor, modifier = Modifier.size(34.dp))
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = media.filename.substringAfterLast('.', "").uppercase().take(10).ifBlank { "FILE" },
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1
    )
            Text(
                text = media.filename,
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
    )
            val s = size
            Text(
                text = when {
                    media.localUri.isNullOrBlank() -> "未下载"
                    s != null -> formatFileSize(s)
                    else -> ""
                },
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.7f),
                maxLines = 1
    )
        }
    }
}

/** Full-screen page of a generic output file (in the result viewer): name, size, open / share. */
@Composable
fun FileOutputPage(media: MediaOutput) {
    val context = LocalContext.current
    val size by produceState<Long?>(null, media.localUri) {
        value = withContext(Dispatchers.IO) { readSize(context, media.localUri) }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
        Icon(PocketIcons.File, contentDescription = null, tint = Color.White, modifier = Modifier.size(96.dp))
        Spacer(modifier = Modifier.height(16.dp))
        Text(media.filename, color = Color.White, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
        size?.let {
            Spacer(modifier = Modifier.height(6.dp))
            Text(formatFileSize(it), color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { openOutputFile(context, media) }, colors = ButtonDefaults.buttonColorsPrimary()) {
                Text("打开")
            }
            Button(onClick = { shareOutputFile(context, media) }) {
                Icon(PocketIcons.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("分享")
            }
        }
    }
}

/** A result tile for any output: image / GIF / video thumbnail, audio / text label, or a file card. */
@Composable
fun ResultMediaTile(
    media: MediaOutput,
    url: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    onClick: () -> Unit
    ) {
    when (media.mediaType) {
        MediaType.FILE -> FileOutputCard(media = media, modifier = modifier, textColor = textColor)
        MediaType.IMAGE, MediaType.VIDEO -> {
            val isVideo = media.mediaType == MediaType.VIDEO
            Box(
                modifier = modifier.combinedClickableCompat(onClick),
                contentAlignment = Alignment.Center
    ) {
                MediaThumbnail(data = url, isVideo = isVideo, modifier = Modifier.fillMaxSize())
            }
        }
        MediaType.AUDIO -> Box(modifier = modifier.combinedClickableCompat(onClick), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
                Icon(PocketIcons.Music, contentDescription = null, tint = textColor, modifier = Modifier.size(28.dp))
                Spacer(modifier = Modifier.height(4.dp))
                Text(media.filename, style = MaterialTheme.typography.labelSmall, color = textColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        MediaType.TEXT -> Box(modifier = modifier.combinedClickableCompat(onClick), contentAlignment = Alignment.Center) {
            Text(media.filename, style = MaterialTheme.typography.bodySmall, color = textColor, maxLines = 2, modifier = Modifier.padding(8.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier = this.combinedClickable(onClick = onClick)

/**
 * Input node for an image / video / audio file (shared by the Control and AI app screens):
 * 64dp preview (image, animated GIF, video first frame with play overlay, audio icon), optional file name,
 * and the upload button.
 */
@Composable
fun MediaInputRow(
    node: EditableNode.ImageInput,
    previewUrl: String,
    showFileName: Boolean,
    uploadText: String,
    onUpload: () -> Unit,
    modifier: Modifier = Modifier
    ) {
    val kind = mediaInputKind(node.classType, node.key, node.filename)
    val canPreview = previewUrl.isNotBlank() && kind != MediaInputKind.AUDIO
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center
    ) {
            if (canPreview) {
                MediaThumbnail(
                    data = previewUrl,
                    isVideo = kind == MediaInputKind.VIDEO,
                    modifier = Modifier.fillMaxSize(),
                    frameMillis = 800L,
                    playIconSize = 24.dp
    )
            } else {
                Icon(
                    imageVector = if (kind == MediaInputKind.AUDIO) PocketIcons.Music else PocketIcons.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
    )
            }
        }
        if (showFileName) {
            Text(
                text = node.filename.ifBlank { "未选择文件" },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = if (node.filename.isBlank()) Color.Gray else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
    )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
        Button(onClick = onUpload, colors = ButtonDefaults.buttonColorsPrimary()) {
            Text(uploadText)
        }
    }
}

enum class MediaInputKind { IMAGE, VIDEO, AUDIO }

/** Kind of a loader input from its class name / input key / current value. */
fun mediaInputKind(classType: String, key: String, filename: String?): MediaInputKind {
    val cls = classType.lowercase()
    val k = key.lowercase()
    return when {
        cls == "loadaudio" || k == "audio" || cls.contains("loadaudio") -> MediaInputKind.AUDIO
        cls == "vhs_loadvideo" || cls == "loadvideo" || k == "video" || cls.contains("video") || isVideoName(filename) -> MediaInputKind.VIDEO
        else -> MediaInputKind.IMAGE
    }
}

/** MIME filter for the picker of a loader input. */
fun mediaInputMime(classType: String, key: String, filename: String?): String = when (mediaInputKind(classType, key, filename)) {
    MediaInputKind.AUDIO -> "audio/*"
    MediaInputKind.VIDEO -> "video/*"
    MediaInputKind.IMAGE -> "image/*"
}

private val LOADER_CLASS = Regex("(?i)load\\s*_?(image|video|audio)|vhs_load|loadimagemask")
private val LOADER_KEYS = setOf("image", "video", "audio", "upload")

/** Third-party loader string input (class matches LOADER_CLASS and key is image/video/audio/upload): gets an upload button. */
fun isThirdPartyLoaderInput(classType: String, key: String): Boolean =
    key.lowercase() in LOADER_KEYS && LOADER_CLASS.containsMatchIn(classType)

/**
 * Request for an AI app cover file: GIF / animated WebP animate (app ImageLoader decoders); a video saved under
 * any name (older RH imports stored every cover as .jpg) is detected by its header and shows its first frame.
 */
fun coverRequest(context: Context, file: java.io.File, cacheKey: String, crossfade: Boolean = true): ImageRequest {
    val b = ImageRequest.Builder(context)
        .data(file)
        .memoryCacheKey(cacheKey)
        .diskCacheKey(cacheKey)
        .crossfade(crossfade)
        .size(512)
    if (isVideoFile(file)) {
        b.videoFrameMillis(1000L)
        b.decoderFactory(VideoFrameDecoder.Factory())
    }
    return b.build()
}
