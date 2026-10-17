package com.comfyui.mobile.data

import android.content.Context
import coil.Coil
import coil.annotation.ExperimentalCoilApi
import coil.disk.DiskCache
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import okio.buffer
import okio.sink
import okio.source

/**
 * Shares the encoded original from Coil, not a resized/decoded preview bitmap.
 * A share-cache miss also warms Coil's existing LRU; no separate permanent image store.
 */
internal object MediaFileCache {
    private val locks = List(32) { Mutex() }

    suspend fun originalForShare(context: Context, url: String, filename: String, calls: Call.Factory): File =
        withContext(Dispatchers.IO) {
            // Use the same full URL key as Coil, including server, folder and query.
            val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
                .joinToString("") { "%02x".format(it) }
            locks[(url.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
                val directory = File(context.cacheDir, "share_cache").apply { mkdirs() }
                val dest = File(directory, "remote_${digest}_${filename.takeLast(120)}")
                if (dest.isFile && dest.length() > 0L) {
                    dest.setLastModified(System.currentTimeMillis())
                    return@withLock dest
                }
                val temp = File.createTempFile("share_", ".part", directory)
                try {
                    val cache = Coil.imageLoader(context).diskCache
                    if (!copyCachedOriginal(cache, url, temp)) {
                        calls.newCall(Request.Builder().url(url).build()).execute().use { response ->
                            if (!response.isSuccessful) throw IOException("Image download HTTP ${response.code}")
                            val body = response.body ?: throw IOException("Empty image response")
                            body.byteStream().use { input -> temp.outputStream().use { input.copyTo(it) } }
                        }
                        currentCoroutineContext().ensureActive()
                        if (temp.length() <= 0L) throw IOException("Empty image file")
                        storeOriginal(cache, url, temp)
                    }
                    currentCoroutineContext().ensureActive()
                    if (!temp.renameTo(dest)) throw IOException("Share file rename failed")
                    dest
                } finally {
                    temp.delete()
                }
            }
        }

    @OptIn(ExperimentalCoilApi::class)
    private fun copyCachedOriginal(cache: DiskCache?, key: String, dest: File): Boolean {
        if (cache == null) return false
        return try {
            cache.openSnapshot(key)?.use { snapshot ->
                cache.fileSystem.source(snapshot.data).use { input ->
                    dest.sink().buffer().use { it.writeAll(input) }
                }
                dest.length() > 0L
            } ?: false
        } catch (_: IOException) {
            false
        }
    }

    @OptIn(ExperimentalCoilApi::class)
    private fun storeOriginal(cache: DiskCache?, key: String, original: File) {
        if (cache == null) return
        // Cache I/O is best effort: a full cache/storage failure must not discard the share file.
        var editor: DiskCache.Editor? = null
        try {
            editor = cache.openEditor(key) ?: return
            // Coil 2 treats an empty metadata file as a manually inserted original.
            cache.fileSystem.write(editor.metadata) {}
            cache.fileSystem.write(editor.data) { original.source().use { writeAll(it) } }
            editor.commit()
        } catch (_: Exception) {
            runCatching { editor?.abort() }
        }
    }
}
