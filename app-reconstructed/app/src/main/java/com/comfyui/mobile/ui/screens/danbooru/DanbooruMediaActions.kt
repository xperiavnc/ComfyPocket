package com.comfyui.mobile.ui.screens.danbooru

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.comfyui.mobile.danbooru.DanbooruException
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Publish only a complete file. A failed/cancelled copy removes its pending album row. */
internal suspend fun saveDanbooruMedia(
    context: Context, file: File, postId: Long, extension: String, mimeType: String, video: Boolean
): Uri = withContext(Dispatchers.IO) {
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "danbooru_$postId.$extension")
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        put(MediaStore.MediaColumns.RELATIVE_PATH, if (video) "Movies/ComfyPocket" else "Pictures/ComfyPocket")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val uri = resolver.insert(collection, values) ?: throw DanbooruException("相册创建失败，请检查存储空间")
    try {
        val coroutine = currentCoroutineContext()
        resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                coroutine.ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
            }
        } } ?: throw DanbooruException("相册写入失败，请重试")
        coroutine.ensureActive()
        if (resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) <= 0)
            throw DanbooruException("相册尚未完成保存，请重试")
        uri
    } catch (e: Exception) {
        runCatching { resolver.delete(uri, null, null) }
        throw e
    }
}
