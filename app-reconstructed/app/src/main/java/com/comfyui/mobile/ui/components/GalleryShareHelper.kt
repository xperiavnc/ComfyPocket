package com.comfyui.mobile.ui.components

import android.content.ClipData
import android.content.Intent
import androidx.core.content.FileProvider
import com.comfyui.mobile.data.AppModule
import com.comfyui.mobile.model.MediaOutput
import com.comfyui.mobile.model.MediaType
import com.comfyui.mobile.ui.MediaPreviewHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.comfyui.mobile.data.MediaFileCache
import kotlinx.coroutines.CancellationException
import java.io.File

private val globalShareClient: okhttp3.Call.Factory by lazy { AppModule.provideRepository().hostAwareCallFactory }

fun mimeTypeForMedia(media: MediaOutput): String {
    return when (media.mediaType) {
        MediaType.IMAGE -> "image/*"
        MediaType.VIDEO -> "video/*"
        MediaType.AUDIO -> "audio/*"
        MediaType.TEXT -> "text/plain"
        MediaType.FILE -> com.comfyui.mobile.ui.MainViewModelGallery.genericMimeType(media.filename)
    }
}

fun ensureFilenameForMedia(media: MediaOutput): String {
    val base = media.filename.trim().ifBlank { "ComfyUI_${System.currentTimeMillis()}" }
    if (base.contains(".")) return base
    val ext = when (media.mediaType) {
        MediaType.IMAGE -> ".png"
        MediaType.VIDEO -> ".mp4"
        MediaType.AUDIO -> ".mp3"
        MediaType.TEXT -> ".txt"
        MediaType.FILE -> ".bin"
    }
    return base + ext
}

suspend fun prepareShareUri(
    context: android.content.Context,
    viewModel: MediaPreviewHost,
    media: MediaOutput,
    shareClient: okhttp3.Call.Factory = globalShareClient
): android.net.Uri? = withContext(Dispatchers.IO) {
    try {
        val safeName = ensureFilenameForMedia(media).replace(Regex("""[\\/:*?"<>|]"""), "_")
        val shareDir = File(context.cacheDir, "share_cache").apply { mkdirs() }
        val localUriStr = media.localUri
        if (!localUriStr.isNullOrBlank()) {
            val parsed = android.net.Uri.parse(localUriStr)
            if (parsed.scheme == "file") {
                val file = File(parsed.path ?: "")
                if (file.exists() && file.length() > 0) {
                    try {
                        return@withContext FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        )
                    } catch (_: Exception) {
                        val dest = File(shareDir, "share_${System.currentTimeMillis()}_$safeName")
                        file.copyTo(dest, overwrite = true)
                        return@withContext FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            dest
                        )
                    }
                }
            } else if (parsed.scheme == "content") {
                val dest = File(shareDir, "share_${System.currentTimeMillis()}_$safeName")
                val stream = context.contentResolver.openInputStream(parsed) ?: return@withContext null
                stream.use { input ->
                    dest.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                return@withContext FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    dest
                )
            }
        }

        // Reuse the exact encoded original already loaded by Agent/gallery.
        val dest = MediaFileCache.originalForShare(context, viewModel.previewUrl(media), safeName, shareClient)
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            dest
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

fun cleanShareCache(context: android.content.Context, forceAll: Boolean = false) {
    try {
        val shareDir = File(context.cacheDir, "share_cache")
        if (!shareDir.exists()) return
        val expireTime = if (forceAll) Long.MAX_VALUE else (System.currentTimeMillis() - 30 * 60 * 1000L)
        shareDir.listFiles()?.forEach { file ->
            if (forceAll || file.lastModified() < expireTime) {
                runCatching { file.delete() }
            }
        }
    } catch (_: Exception) {}
}

fun performShareMedia(
    context: android.content.Context,
    viewModel: MediaPreviewHost,
    targets: List<MediaOutput>,
    scope: kotlinx.coroutines.CoroutineScope,
    onBusyChange: ((Boolean) -> Unit)? = null,
    onSuccess: (() -> Unit)? = null,
    shareClient: okhttp3.Call.Factory = globalShareClient
) {
    if (targets.isEmpty()) return
    scope.launch {
        onBusyChange?.invoke(true)
        try {
            cleanShareCache(context)
            val uris = ArrayList<android.net.Uri>()
            for (media in targets) {
                val uri = prepareShareUri(context, viewModel, media, shareClient)
                if (uri != null) {
                    uris.add(uri)
                }
            }
            if (uris.isEmpty()) {
                viewModel.showToast("未能获取分享文件")
                return@launch
            }

            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = mimeTypeForMedia(targets[0])
                    putExtra(Intent.EXTRA_STREAM, uris[0])
                    clipData = ClipData.newUri(context.contentResolver, "shared_file", uris[0])
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    val allImages = targets.all { it.mediaType == MediaType.IMAGE }
                    type = if (allImages) "image/*" else "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    val cd = ClipData.newUri(context.contentResolver, "shared_file", uris[0])
                    for (i in 1 until uris.size) {
                        cd.addItem(ClipData.Item(uris[i]))
                    }
                    clipData = cd
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }

            val chooser = Intent.createChooser(intent, "转发文件").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            onSuccess?.invoke()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            viewModel.showToast("分享失败")
        } finally {
            onBusyChange?.invoke(false)
        }
    }
}
