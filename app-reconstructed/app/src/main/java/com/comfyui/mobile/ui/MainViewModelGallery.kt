package com.comfyui.mobile.ui

import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.viewModelScope
import com.comfyui.mobile.GalleryDownloadNotification
import com.comfyui.mobile.data.ComfyRepository
import com.comfyui.mobile.model.LogType
import com.comfyui.mobile.model.MediaOutput
import com.comfyui.mobile.model.MediaType
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

object MainViewModelGallery {

    fun loadGalleryCategories(vm: MainViewModel): List<GalleryCategory> {
        val raw = vm.prefs.getString("gallery_categories_v1", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<GalleryCategory>>() {}.type
            vm.gson.fromJson<List<GalleryCategory>>(raw, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun loadGalleryCategoryAssignments(vm: MainViewModel): Map<String, String> {
        val raw = vm.prefs.getString("gallery_category_assignments_v1", null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            vm.gson.fromJson<Map<String, String>>(raw, type) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun persistGalleryCategoryAssignments(vm: MainViewModel, map: Map<String, String>) {
        vm.prefs.edit().putString("gallery_category_assignments_v1", vm.gson.toJson(map)).apply()
    }

    fun createGalleryCategory(vm: MainViewModel, name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        if (vm._galleryCategories.value.any { it.name.equals(trimmed, ignoreCase = true) }) {
            vm.showToast("该分类已存在")
            return
        }
        val next = vm._galleryCategories.value + GalleryCategory(UUID.randomUUID().toString(), trimmed.take(30))
        vm.persistGalleryCategories(next)
        vm._galleryCategories.value = next
    }

    fun renameGalleryCategory(vm: MainViewModel, id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        val next = vm._galleryCategories.value.map { c ->
            if (c.id == id) c.copy(name = trimmed.take(30)) else c
        }
        vm.persistGalleryCategories(next)
        vm._galleryCategories.value = next
    }

    fun deleteGalleryCategory(vm: MainViewModel, id: String) {
        val nextCategories = vm._galleryCategories.value.filterNot { it.id == id }
        val nextAssignments = vm._galleryCategoryAssignments.value.filterValues { it != id }
        vm.persistGalleryCategories(nextCategories)
        persistGalleryCategoryAssignments(vm, nextAssignments)
        vm._galleryCategories.value = nextCategories
        vm._galleryCategoryAssignments.value = nextAssignments
    }

    fun assignMediaToGalleryCategory(vm: MainViewModel, keys: Set<String>, categoryId: String) {
        if (keys.isEmpty()) return
        if (vm._galleryCategories.value.none { it.id == categoryId }) return
        val merged = vm._galleryCategoryAssignments.value.toMutableMap()
        for (k in keys) merged[k] = categoryId
        val next = merged.toMap()
        persistGalleryCategoryAssignments(vm, next)
        vm._galleryCategoryAssignments.value = next
    }

    fun unassignMediaFromGalleryCategory(vm: MainViewModel, keys: Set<String>) {
        if (keys.isEmpty()) return
        val merged = vm._galleryCategoryAssignments.value.toMutableMap()
        for (k in keys) merged.remove(k)
        val next = merged.toMap()
        persistGalleryCategoryAssignments(vm, next)
        vm._galleryCategoryAssignments.value = next
    }

    fun assignMediaToGalleryCategoryAndSave(vm: MainViewModel, mediaList: List<MediaOutput>, categoryId: String) {
        if (mediaList.isEmpty()) return
        if (vm._galleryCategories.value.none { it.id == categoryId }) return
        val keys = mediaList.map { vm.mediaKey(it) }.toSet()
        assignMediaToGalleryCategory(vm, keys, categoryId)
        val remoteList = mediaList.filter { it.localUri == null && it.type != "local" }
        if (remoteList.isNotEmpty()) {
            saveBatchMediaToAlbum(vm, vm.getApplication(), remoteList)
        } else {
            vm.showToast("已归入该分类")
        }
    }

    fun refreshLocalCategoryFiles(vm: MainViewModel) {
        // Kept for backward compatibility
    }

    fun localMediaBaseDir(vm: MainViewModel): File {
        val app = vm.getApplication<Application>()
        return app.getExternalFilesDir(null) ?: app.filesDir
    }

    fun cleanFileName(filename: String, mediaType: MediaType): String {
        val raw = filename.trim().ifBlank { "ComfyUI_${System.currentTimeMillis()}" }
        val ext = when (mediaType) {
            MediaType.IMAGE -> ".png"
            MediaType.VIDEO -> ".mp4"
            MediaType.AUDIO -> ".mp3"
            MediaType.TEXT -> ".txt"
            MediaType.FILE -> ".bin"
        }
        val withExt = if (raw.contains(".")) raw else (raw + ext)
        return withExt.replace(Regex("""[\\/:*?"<>|]"""), "_")
    }

    fun mimeTypeForMediaType(mediaType: MediaType, filename: String): String {
        val lower = filename.lowercase()
        return when (mediaType) {
            MediaType.IMAGE -> when {
                lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
                lower.endsWith(".webp") -> "image/webp"
                lower.endsWith(".gif") -> "image/gif"
                else -> "image/png"
            }
            MediaType.VIDEO -> when {
                lower.endsWith(".webm") -> "video/webm"
                lower.endsWith(".mov") -> "video/quicktime"
                lower.endsWith(".mkv") -> "video/x-matroska"
                else -> "video/mp4"
            }
            MediaType.AUDIO -> when {
                lower.endsWith(".wav") -> "audio/wav"
                lower.endsWith(".flac") -> "audio/flac"
                lower.endsWith(".ogg") -> "audio/ogg"
                lower.endsWith(".m4a") -> "audio/mp4"
                else -> "audio/mpeg"
            }
            MediaType.TEXT -> "text/plain"
            MediaType.FILE -> genericMimeType(lower)
        }
    }

    /** MIME type of a generic output file from its extension (application/octet-stream when unknown). */
    fun genericMimeType(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "application/octet-stream"
        return when (ext) {
            "zip" -> "application/zip"
            "json" -> "application/json"
            "txt", "log" -> "text/plain"
            "csv" -> "text/csv"
            "safetensors", "ckpt", "pt", "pth", "bin", "gguf" -> "application/octet-stream"
            else -> android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    /**
     * Media type of a downloaded cloud output: images / videos / audio as media, everything else
     * (zip, txt, json, safetensors, unknown) as a generic [MediaType.FILE].
     */
    fun outputMediaType(name: String): MediaType = when (val t = mediaTypeFromFileName(name)) {
        MediaType.IMAGE, MediaType.VIDEO, MediaType.AUDIO -> t
        else -> MediaType.FILE
    }

    fun mediaTypeFromFileName(name: String): MediaType? {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp") || lower.endsWith(".gif") -> MediaType.IMAGE
            lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mov") || lower.endsWith(".mkv") -> MediaType.VIDEO
            lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".flac") || lower.endsWith(".ogg") || lower.endsWith(".m4a") -> MediaType.AUDIO
            lower.endsWith(".txt") || lower.endsWith(".json") || lower.endsWith(".log") -> MediaType.TEXT
            else -> null
        }
    }

    suspend fun saveMediaToMediaStore(
        context: Context,
        media: MediaOutput,
        repository: ComfyRepository
    ): Uri? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = cleanFileName(media.filename, media.mediaType)
        val mime = mimeTypeForMediaType(media.mediaType, name)

        val (collection, relativePath) = when (media.mediaType) {
            MediaType.IMAGE -> Pair(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                "${Environment.DIRECTORY_PICTURES}/ComfyUI"
            )
            MediaType.VIDEO -> Pair(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                "${Environment.DIRECTORY_MOVIES}/ComfyUI"
            )
            MediaType.AUDIO -> Pair(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                "${Environment.DIRECTORY_MUSIC}/ComfyUI"
            )
            // Text and generic files (zip, json, safetensors, ...): public Download/ComfyUI.
            MediaType.TEXT, MediaType.FILE -> Pair(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${Environment.DIRECTORY_DOWNLOADS}/ComfyUI"
            )
        }

        val tempFile = if (media.localUri == null) {
            val tmp = File(context.cacheDir, "save_tmp_${System.currentTimeMillis()}_$name")
            val ok = repository.downloadViewToFile(media.filename, media.subfolder, media.type, tmp)
            if (!ok || !tmp.exists() || tmp.length() <= 0L) {
                tmp.delete()
                return@withContext null
            }
            tmp
        } else null

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        var insertedUri: Uri? = null
        try {
            insertedUri = resolver.insert(collection, values) ?: return@withContext null
            resolver.openOutputStream(insertedUri)?.use { outStream ->
                if (tempFile != null) {
                    tempFile.inputStream().use { inStream -> inStream.copyTo(outStream) }
                } else {
                    val localUri = Uri.parse(media.localUri!!)
                    if (localUri.scheme == "file") {
                        File(localUri.path ?: "").inputStream().use { inStream -> inStream.copyTo(outStream) }
                    } else {
                        resolver.openInputStream(localUri)?.use { inStream -> inStream.copyTo(outStream) }
                            ?: throw java.io.IOException("Cannot open input stream for $localUri")
                    }
                }
            } ?: throw java.io.IOException("Cannot open output stream for $insertedUri")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(insertedUri, values, null, null)
            }
            insertedUri
        } catch (e: Exception) {
            e.printStackTrace()
            if (insertedUri != null) {
                try {
                    resolver.delete(insertedUri, null, null)
                } catch (_: Exception) {}
            }
            null
        } finally {
            tempFile?.delete()
        }
    }

    fun saveMediaToAlbum(
        vm: MainViewModel,
        context: Context,
        media: MediaOutput,
        silent: Boolean = false,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        vm.viewModelScope.launch(Dispatchers.IO) {
            val appCtx = context.applicationContext
            val notificationId = GalleryDownloadNotification.nextNotificationId()
            if (!silent) {
                withContext(Dispatchers.Main) {
                    vm.showToast("正在保存…")
                }
                GalleryDownloadNotification.notifyProgress(appCtx, notificationId, media.filename)
            }
            val savedUri = saveMediaToMediaStore(appCtx, media, vm.repository)
            withContext(Dispatchers.Main) {
                if (savedUri != null) {
                    if (!silent) {
                        val mime = mimeTypeForMediaType(media.mediaType, media.filename)
                        GalleryDownloadNotification.notifySuccess(appCtx, notificationId, media.filename, savedUri, mime)
                        vm.showToast("已保存")
                        vm.addLog("保存成功: ${media.filename}", LogType.SUCCESS)
                    }
                    refreshLocalMedia(vm, appCtx)
                    onComplete?.invoke(true)
                } else {
                    if (!silent) {
                        GalleryDownloadNotification.notifyFailure(appCtx, notificationId, media.filename)
                        vm.showToast("保存失败: ${media.filename}")
                        vm.addLog("保存失败: ${media.filename}", LogType.ERROR)
                    }
                    onComplete?.invoke(false)
                }
            }
        }
    }

    fun saveBatchMediaToAlbum(
        vm: MainViewModel,
        context: Context,
        mediaList: List<MediaOutput>,
        onComplete: (() -> Unit)? = null
    ) {
        if (mediaList.isEmpty()) {
            onComplete?.invoke()
            return
        }
        vm.viewModelScope.launch(Dispatchers.IO) {
            val appCtx = context.applicationContext
            val notificationId = GalleryDownloadNotification.nextNotificationId()
            if (mediaList.isNotEmpty()) {
                GalleryDownloadNotification.notifyBatchProgress(
                    appCtx,
                    notificationId,
                    total = mediaList.size,
                    current = 0,
                    statusText = "正在保存 ${mediaList.size} 个文件到相册..."
    )
            }
            withContext(Dispatchers.Main) {
                vm.showToast("正在保存 ${mediaList.size} 个…")
            }
            val semaphore = Semaphore(3)
            var successCount = 0
            var processedCount = 0
            val jobs = mediaList.map { media ->
                async {
                    semaphore.withPermit {
                        val uri = saveMediaToMediaStore(appCtx, media, vm.repository)
                        synchronized(mediaList) {
                            processedCount++
                            if (uri != null) {
                                successCount++
                            }
                            GalleryDownloadNotification.notifyBatchProgress(
                                appCtx,
                                notificationId,
                                total = mediaList.size,
                                current = processedCount,
                                statusText = "已完成 $processedCount/${mediaList.size} 个文件"
    )
                        }
                    }
                }
            }
            jobs.forEach { it.await() }
            refreshLocalMedia(vm, appCtx)
            GalleryDownloadNotification.notifyBatchSuccess(
                appCtx,
                notificationId,
                successCount = successCount,
                total = mediaList.size
    )
            withContext(Dispatchers.Main) {
                if (successCount == mediaList.size) {
                    vm.showToast("已保存 $successCount 个")
                } else {
                    vm.showToast("已保存 $successCount/${mediaList.size} 个")
                }
                vm.addLog("批量保存完成: 成功 $successCount / 总计 ${mediaList.size}", LogType.SUCCESS)
                onComplete?.invoke()
            }
        }
    }

    fun downloadMedia(vm: MainViewModel, context: Context, media: MediaOutput): Long {
        saveMediaToAlbum(vm, context, media)
        return 0L
    }

    fun setGallerySource(vm: MainViewModel, source: String) {
        val next = if (source == MainViewModel.GALLERY_LOCAL) MainViewModel.GALLERY_LOCAL else MainViewModel.GALLERY_SERVER
        vm._gallerySource.value = next
        vm.prefs.edit().putString("gallery_source", next).apply()
    }

    fun setLocalSaveTreeUri(vm: MainViewModel, uriString: String?, displayName: String?) {
        // Kept for backward compatibility
    }

    fun clearLocalSaveTreeUri(vm: MainViewModel) {
        // Kept for backward compatibility
    }

    fun defaultSavedDir(context: Context): File {
        val rootDir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(rootDir, "ComfyUI_Saved")
    }

    fun defaultSavedDir(vm: MainViewModel): File = defaultSavedDir(vm.getApplication())

    fun refreshLocalMedia(vm: MainViewModel, context: Context) {
        vm.viewModelScope.launch(Dispatchers.IO) {
            val list = scanSystemAlbumMedia(context)
            vm._localMediaList.value = list
        }
    }

    fun scanSystemAlbumMedia(context: Context): List<MediaOutput> {
        val resolver = context.contentResolver
        val out = mutableListOf<MediaOutput>()

        // 1. Scan Images in Pictures/ComfyUI
        try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.SIZE
            )
            val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            } else null
            val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf("Pictures/ComfyUI%")
            } else null
            val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "image_$id.png"
                    val dateMod = cursor.getLong(dateCol) * 1000L
                    val size = cursor.getLong(sizeCol)
                    if (size > 0L) {
                        val contentUri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            id
                        )
                        out.add(
                            MediaOutput(
                                filename = name,
                                subfolder = "",
                                type = "local",
                                mediaType = MediaType.IMAGE,
                                timestamp = dateMod,
                                localUri = contentUri.toString()
    )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Scan Videos in Movies/ComfyUI
        try {
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DATE_MODIFIED,
                MediaStore.Video.Media.MIME_TYPE,
                MediaStore.Video.Media.SIZE
            )
            val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
            } else null
            val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf("Movies/ComfyUI%")
            } else null
            val sortOrder = "${MediaStore.Video.Media.DATE_MODIFIED} DESC"

            resolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "video_$id.mp4"
                    val dateMod = cursor.getLong(dateCol) * 1000L
                    val size = cursor.getLong(sizeCol)
                    if (size > 0L) {
                        val contentUri = ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            id
                        )
                        out.add(
                            MediaOutput(
                                filename = name,
                                subfolder = "",
                                type = "local",
                                mediaType = MediaType.VIDEO,
                                timestamp = dateMod,
                                localUri = contentUri.toString()
    )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 3. Optional: collect any legacy files in defaultSavedDir if they exist
        try {
            val legacyDir = defaultSavedDir(context)
            if (legacyDir.exists()) {
                val legacyFiles = collectFileMedia(legacyDir)
                out.addAll(legacyFiles)
            }
        } catch (_: Exception) {}

        return out.sortedByDescending { it.timestamp }
    }

    fun collectFileMedia(dir: File): List<MediaOutput> {
        if (!dir.exists()) return emptyList()
        val out = mutableListOf<MediaOutput>()
        dir.walkTopDown().forEach { f ->
            if (!f.isFile) return@forEach
            val type = mediaTypeFromFileName(f.name) ?: return@forEach
            out.add(
                MediaOutput(
                    filename = f.name,
                    subfolder = "",
                    type = "local",
                    mediaType = type,
                    timestamp = f.lastModified(),
                    localUri = Uri.fromFile(f).toString()
    )
            )
        }
        return out
    }

    fun deleteLocalMedia(vm: MainViewModel, context: Context, mediaList: List<MediaOutput>) {
        vm.viewModelScope.launch(Dispatchers.IO) {
            var deletedCount = 0
            val keysToUnassign = mediaList.map { vm.mediaKey(it) }.toSet()
            unassignMediaFromGalleryCategory(vm, keysToUnassign)

            mediaList.forEach { media ->
                val uriStr = media.localUri ?: return@forEach
                try {
                    val uri = Uri.parse(uriStr)
                    if (uri.scheme == "file") {
                        if (File(uri.path ?: "").delete()) deletedCount++
                    } else {
                        val rows = context.contentResolver.delete(uri, null, null)
                        if (rows > 0) deletedCount++
                    }
                } catch (_: Exception) {
                }
            }
            val list = scanSystemAlbumMedia(context)
            withContext(Dispatchers.Main) {
                vm._localMediaList.value = list
                vm.showToast("已删除 $deletedCount 个文件")
            }
        }
    }
}
