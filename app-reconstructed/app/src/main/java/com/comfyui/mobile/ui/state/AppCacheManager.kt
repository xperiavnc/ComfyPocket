package com.comfyui.mobile.ui.state

import android.content.Context
import com.comfyui.mobile.ui.UploadPreviewStore
import com.comfyui.mobile.ui.components.cleanShareCache
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Cache I/O only. The host owns coroutine scheduling and completion callbacks. */
internal class AppCacheManager(
    private val context: Context,
    private val uploadPreviewStore: UploadPreviewStore
) {
    private val _cacheSizeText = MutableStateFlow("计算中...")
    val sizeText = _cacheSizeText.asStateFlow()

    private fun computeAppCacheBytes(): Long {
        val app = context
        var total = 0L
        fun addDirSize(dir: File?) {
            if (dir == null || !dir.exists()) return
            val queue = ArrayDeque<File>()
            queue.add(dir)
            while (queue.isNotEmpty()) {
                val curr = queue.removeFirst()
                val children = curr.listFiles() ?: continue
                for (child in children) {
                    if (child.isDirectory) queue.add(child)
                    else total += child.length()
                }
            }
        }
        addDirSize(app.cacheDir)
        addDirSize(app.externalCacheDir)
        addDirSize(File(app.filesDir, "upload_previews"))
        return total
    }

    fun updateCacheSize() {
        val bytes = computeAppCacheBytes()
        _cacheSizeText.value = formatFileSize(bytes)
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
        return String.format(Locale.getDefault(), "%.1f %s", value, units[digitGroups])
    }

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    fun clear(includeUploadPreviews: Boolean) {
        val app = context
        try {
            val loader = coil.Coil.imageLoader(app)
            loader.memoryCache?.clear()
            loader.diskCache?.clear()
        } catch (_: Throwable) {}

        fun clearDirContents(dir: File?) {
            if (dir == null || !dir.exists()) return
            dir.listFiles()?.forEach { file ->
                runCatching { file.deleteRecursively() }
            }
        }
        clearDirContents(app.cacheDir)
        clearDirContents(app.externalCacheDir)

        if (includeUploadPreviews) {
            uploadPreviewStore.clear()
        }

        updateCacheSize()
    }

    fun pruneOrphans() {
        // 1. 清除超过 30 分钟未处理的分享临时文件
        cleanShareCache(context, forceAll = false)
        // 2. 清理 cacheDir 下超时的上传/下载/日志残余
        try {
            val now = System.currentTimeMillis()
            val tenMinAgo = now - 10 * 60 * 1000L
            val oneDayAgo = now - 24 * 60 * 60 * 1000L
            context.cacheDir.listFiles()?.forEach { f ->
                val name = f.name
                when {
                    name.startsWith("upload_") && f.lastModified() < tenMinAgo -> runCatching { f.deleteRecursively() }
                    (name.startsWith("save_tmp_") || name.startsWith("rh_dl_")) && f.lastModified() < tenMinAgo -> runCatching { f.delete() }
                    (name.startsWith("comfy_log_") || name.startsWith("ComfyUI_Backup_") || name.startsWith("ComfyController_Backup_")) && f.lastModified() < oneDayAgo -> runCatching { f.delete() }
                }
            }
        } catch (_: Throwable) {}
        // 3. 加载并淘汰超过上限的节点上传预览缩略图
        runCatching { uploadPreviewStore.ensureLoaded() }
        // 4. 刷新缓存占用统计
        runCatching { updateCacheSize() }
    }
}
