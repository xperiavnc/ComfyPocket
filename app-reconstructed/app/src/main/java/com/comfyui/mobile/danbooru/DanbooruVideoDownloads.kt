package com.comfyui.mobile.danbooru

import android.content.Context
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response

data class DanbooruVideoFile(val file: File, val extension: String) {
    val mimeType: String get() = DanbooruMediaSource("", "", extension).videoMimeType()
}

/** Encoded video cache, separate from Coil's image LRU. Never buffer a whole video in memory. */
internal object DanbooruVideoDownloads {
    private val locks = List(16) { Mutex() }

    suspend fun get(
        context: Context, post: DanbooruPost, calls: Call.Factory, namespace: String,
        onProgress: (Long, Long?) -> Unit = { _, _ -> }
    ): DanbooruVideoFile = withContext(Dispatchers.IO) {
        val source = post.videoDownloadSource() ?: throw DanbooruException("此帖子没有可下载的视频地址")
        val key = post.mediaKey(namespace, source.representation, source.url)
        locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            val directory = File(context.cacheDir, "share_cache/danbooru/video").apply { mkdirs() }
            val dest = File(directory, "danbooru_${post.id}_${danbooruHash(key)}.${source.extension}")
            if (dest.isFile && dest.length() > 0) {
                dest.setLastModified(System.currentTimeMillis())
                onProgress(dest.length(), dest.length())
                return@withLock DanbooruVideoFile(dest, source.extension)
            }
            val temp = File.createTempFile("download_", ".part", directory)
            try {
                transfer(calls, source.url, temp, onProgress)
                currentCoroutineContext().ensureActive()
                if (!temp.renameTo(dest)) throw IOException("视频缓存写入失败，请检查存储空间")
                trim(directory, dest)
                DanbooruVideoFile(dest, source.extension)
            } finally { temp.delete() }
        }
    }

    private suspend fun transfer(
        calls: Call.Factory, url: String, target: File, onProgress: (Long, Long?) -> Unit
    ): Unit = suspendCancellableCoroutine { continuation ->
        val call = calls.newCall(Request.Builder().url(url).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(DanbooruHttp.networkException(e))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!response.isSuccessful) throw DanbooruException(DanbooruHttp.statusMessage(response.code), response.code)
                        if (response.code == 206 || response.header("Content-Range") != null)
                            throw DanbooruException("视频返回了不完整的分段文件，请重试下载")
                        val body = response.body ?: throw DanbooruException("视频响应为空，请重试")
                        val type = body.contentType()
                        if (type?.type == "text" || type?.subtype?.contains("json", true) == true)
                            throw DanbooruException("服务器未返回视频文件，请检查帖子或网络设置")
                        val total = body.contentLength().takeIf { it >= 0 }
                        var received = 0L
                        var lastUpdate = 0L
                        onProgress(0, total)
                        body.byteStream().use { input -> target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                if (!continuation.isActive) return
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                received += read
                                val now = System.nanoTime()
                                if (now - lastUpdate >= 100_000_000) {
                                    lastUpdate = now
                                    onProgress(received, total)
                                }
                            }
                        } }
                        if (received == 0L || (total != null && total != received))
                            throw DanbooruException("视频文件尚未完整下载，请重试")
                        onProgress(received, total ?: received)
                        if (continuation.isActive) continuation.resume(Unit)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(
                            if (e is DanbooruException) e else if (e is IOException) DanbooruHttp.networkException(e) else e)
                    } finally {
                        if (continuation.isCancelled) target.delete()
                    }
                }
            }
        })
    }

    private fun trim(directory: File, keep: File) {
        val expiration = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        var retained = keep.length()
        directory.listFiles()?.filter { it.isFile && it != keep }
            ?.sortedByDescending { it.lastModified() }?.forEach { file ->
                if (file.extension == "part") {
                    if (file.lastModified() < expiration) file.delete()
                } else {
                    retained += file.length()
                    if (file.lastModified() < expiration || retained > 512L * 1024 * 1024) file.delete()
                }
            }
    }
}
