package com.comfyui.mobile.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.webkit.MimeTypeMap
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent previews of cloud input uploads: creates downscaled lightweight thumbnails
 * (in filesDir/upload_previews) and maps the returned upload name ("api/xxx.png") to that copy,
 * so input thumbnails survive an app restart without retaining massive original videos/images.
 * [target] is the in-memory name -> preview Uri map read by MainViewModel.getViewUrl.
 */
internal class UploadPreviewStore(
    private val app: Application,
    private val target: ConcurrentHashMap<String, String>
    ) {
    private val gson = Gson()
    private val dir: File get() = File(app.filesDir, "upload_previews")
    private val indexFile: File get() = File(dir, "index.json")
    /** upload name -> absolute path of the local copy (insertion order = age). */
    private val index = LinkedHashMap<String, String>()
    @Volatile
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            val raw = runCatching { indexFile.takeIf { it.exists() }?.readText() }.getOrNull() ?: return
            val type = object : TypeToken<LinkedHashMap<String, String>>() {}.type
            val parsed: LinkedHashMap<String, String> = runCatching { gson.fromJson<LinkedHashMap<String, String>>(raw, type) }.getOrNull() ?: return
            for ((name, path) in parsed) {
                val f = File(path)
                if (!f.exists()) continue
                index[name] = path
                target.putIfAbsent(name, Uri.fromFile(f).toString())
            }

            // Prune excess old entries if above MAX_ENTRIES
            var changed = false
            while (index.size > MAX_ENTRIES) {
                val oldest = index.entries.first()
                index.remove(oldest.key)
                target.remove(oldest.key)
                runCatching { File(oldest.value).delete() }
                changed = true
            }
            if (changed) {
                runCatching { indexFile.writeText(gson.toJson(index)) }
            }

            // Clean up orphan files in directory not referenced in index
            runCatching {
                dir.listFiles()?.forEach { f ->
                    if (f.name != "index.json" && !index.values.contains(f.absolutePath)) {
                        f.delete()
                    }
                }
            }
        }
    }

    /** Saves a downscaled preview of [source] for [uploadName]; returns the preview Uri string. */
    fun put(uploadName: String, source: File): String {
        ensureLoaded()
        synchronized(this) {
            val d = dir.apply { mkdirs() }
            val dest = File(d, "p_${System.currentTimeMillis()}_${Integer.toHexString(uploadName.hashCode())}.jpg")
            saveThumbnail(source, dest)
            index.remove(uploadName)?.let { old -> if (old != dest.absolutePath) runCatching { File(old).delete() } }
            index[uploadName] = dest.absolutePath
            // Keep the store bounded (oldest previews are dropped).
            while (index.size > MAX_ENTRIES) {
                val oldest = index.entries.first()
                index.remove(oldest.key)
                target.remove(oldest.key)
                runCatching { File(oldest.value).delete() }
            }
            runCatching { indexFile.writeText(gson.toJson(index)) }
            val uri = Uri.fromFile(dest).toString()
            target[uploadName] = uri
            return uri
        }
    }

    /** Clears all stored upload previews to free internal storage. */
    fun clear() {
        synchronized(this) {
            target.clear()
            index.clear()
            runCatching { dir.deleteRecursively() }
        }
    }

    private fun saveThumbnail(source: File, dest: File) {
        val nameLower = source.name.lowercase()
        val isVideo = nameLower.endsWith(".mp4") || nameLower.endsWith(".mov") || nameLower.endsWith(".mkv") || nameLower.endsWith(".webm")
        if (isVideo) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(source.absolutePath)
                val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (frame != null) {
                    val scaled = scaleBitmap(frame, 720)
                    dest.outputStream().use { out ->
                        scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
                    }
                    if (scaled != frame) frame.recycle()
                    scaled.recycle()
                    return
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { retriever.release() }
            }
        }

        // Try decoding as image with downsampling
        try {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.absolutePath, boundsOptions)
            val w = boundsOptions.outWidth
            val h = boundsOptions.outHeight
            if (w > 0 && h > 0) {
                var sampleSize = 1
                val maxDim = maxOf(w, h)
                while (maxDim / sampleSize > 1080) {
                    sampleSize *= 2
                }
                val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                val bmp = BitmapFactory.decodeFile(source.absolutePath, decodeOptions)
                if (bmp != null) {
                    val scaled = scaleBitmap(bmp, 720)
                    dest.outputStream().use { out ->
                        scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
                    }
                    if (scaled != bmp) bmp.recycle()
                    scaled.recycle()
                    return
                }
            }
        } catch (_: Throwable) {}

        // Fallback: if not an image/video or decode failed, copy directly
        source.copyTo(dest, overwrite = true)
    }

    private fun scaleBitmap(bmp: Bitmap, maxDimension: Int): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val maxSide = maxOf(w, h)
        if (maxSide <= maxDimension) return bmp
        val scale = maxDimension.toFloat() / maxSide
        val dstW = (w * scale).toInt().coerceAtLeast(1)
        val dstH = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, dstW, dstH, true)
    }

    companion object {
        private const val MAX_ENTRIES = 60

        /** The picked file's real name (sanitized); falls back to a MIME-derived extension only when unknown. */
        fun uploadFileName(displayName: String?, mimeType: String?): String {
            val cleaned = displayName?.trim()?.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_")?.takeIf { it.isNotBlank() && it != "." && it != ".." }
            val mimeExt = mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
                ?: when {
                    mimeType?.startsWith("video") == true -> "mp4"
                    mimeType?.startsWith("audio") == true -> "mp3"
                    mimeType?.startsWith("image") == true -> "png"
                    else -> null
                }
            if (cleaned != null) {
                val hasExt = cleaned.substringAfterLast('.', "").let { it.isNotEmpty() && it.length <= 8 && it != cleaned }
                return if (hasExt || mimeExt == null) cleaned else "$cleaned.$mimeExt"
            }
            return "upload_${System.currentTimeMillis()}" + (mimeExt?.let { ".$it" } ?: "")
        }
    }
}
