package com.comfyui.mobile.ui.material

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.comfyui.mobile.danbooru.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reuses the exact production original-byte cache, never re-encodes a preview bitmap. */
internal suspend fun materialOriginalAction(context: Context, runtime: DanbooruRuntime, post: DanbooruPost, share: Boolean): String {
    val file = runtime.original(post)
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(post.extension.lowercase()) ?: "application/octet-stream"
    if (share) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Danbooru 原图", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "分享原图"))
        return "已打开原图分享"
    }
    withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "danbooru_${post.id}.${post.extension.lowercase()}")
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ComfyPocket")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw DanbooruException("相册创建失败")
        try {
            resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                ?: throw DanbooruException("相册写入失败")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
    }
    return "原图已保存到 Pictures/ComfyPocket"
}
