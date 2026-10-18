package com.comfyui.mobile.danbooru

import android.content.Context
import coil.ImageLoader
import coil.decode.ImageDecoderDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.comfyui.mobile.BuildConfig
import com.comfyui.mobile.data.MediaFileCache
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient

interface DanbooruService {
    val settings: DanbooruSettings
    val repository: DanbooruDataSource
    val initialError: String? get() = null
    suspend fun save(settings: DanbooruSettings)
    suspend fun test(settings: DanbooruSettings): DanbooruIdentity?
    suspend fun clearCache()
}

/** One owner per application, not per route/composition/Activity. No relaxed TLS client is reused. */
class DanbooruRuntime private constructor(context: Context) : DanbooruService {
    private val app = context.applicationContext
    private val store = DanbooruSettingsStore(app)
    private val directory = File(app.cacheDir, "danbooru")
    private val queue = DanbooruApiQueue()
    private val lock = Mutex()
    private val userId = AtomicLong(0)
    private val loaded = runCatching { store.load() }
    override val initialError = if (loaded.isFailure) "画廊配置读取失败，请重新检查并保存设置" else null
    private data class Clients(
        val settings: DanbooruSettings, val api: OkHttpClient, val media: OkHttpClient,
        val video: OkHttpClient, val repository: DanbooruRepository
    )
    private fun agent() = "ComfyPocket/${BuildConfig.VERSION_NAME} (Danbooru${userId.get().takeIf { it > 0 }?.let { "; user #$it" }.orEmpty()})"
    private fun clients(settings: DanbooruSettings): Clients {
        val api = DanbooruHttp.client(settings, api = true, ::agent)
        val media = DanbooruHttp.client(settings, api = false, ::agent)
        // Streaming can legitimately last longer than the 60s image-call timeout.
        // Share the exact proxy/interceptors/dispatcher/pool; keep the 30s read timeout.
        val video = media.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).build()
        return Clients(settings, api, media, video,
            DanbooruRepository(api, queue, File(directory, "pages/${settings.namespace()}")))
    }
    @Volatile private var current = clients(loaded.getOrDefault(DanbooruSettings()))
    override val settings get() = current.settings
    override val repository: DanbooruDataSource get() = current.repository
    val mediaCalls = Call.Factory { request -> current.media.newCall(request) }
    val videoCalls = Call.Factory { request -> current.video.newCall(request) }
    val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(app).callFactory { mediaCalls }
            .components { add(ImageDecoderDecoder.Factory()) }
            .memoryCache { MemoryCache.Builder(app).maxSizePercent(0.08).build() }
            .diskCache { DiskCache.Builder().directory(File(directory, "images")).maxSizeBytes(128L * 1024 * 1024).build() }
            .respectCacheHeaders(false).crossfade(false).build()
    }

    override suspend fun save(settings: DanbooruSettings) = lock.withLock {
        val validated = settings.validated()
        withContext(Dispatchers.IO) {
            val next = clients(validated)
            try { store.save(validated) } catch (e: Exception) { close(next); throw e }
            val old = current
            current = next
            userId.set(0)
            close(old)
            if (old.settings.namespace() != validated.namespace()) {
                // Only our online cache and temporary shares. Never system albums or ComfyUI images.
                clearOwnedCache()
            }
        }
    }
    override suspend fun test(settings: DanbooruSettings): DanbooruIdentity? {
        val snapshot = settings.validated()
        val temporary = withContext(Dispatchers.IO) { clients(snapshot) }
        try {
            val result = temporary.repository.verify(snapshot)
            if (snapshot == current.settings) userId.set(result?.id ?: 0)
            return result
        } finally { withContext(Dispatchers.IO) { close(temporary) } }
    }
    override suspend fun clearCache() = lock.withLock { withContext(Dispatchers.IO) {
        current.api.dispatcher.cancelAll()
        current.media.dispatcher.cancelAll()
        clearOwnedCache()
    } }
    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    private fun clearOwnedCache() {
        imageLoader.memoryCache?.clear()
        imageLoader.diskCache?.clear()
        File(directory, "pages").deleteRecursively()
        File(app.cacheDir, "share_cache/danbooru").deleteRecursively()
    }
    private fun close(clients: Clients) {
        listOf(clients.api, clients.media).forEach {
            it.dispatcher.cancelAll()
            it.connectionPool.evictAll()
            it.dispatcher.executorService.shutdown()
        }
    }
    suspend fun original(post: DanbooruPost): File {
        val snapshot = current
        val url = post.fileUrl ?: throw DanbooruException("此帖子没有可用原图地址；Tag 仍可复制")
        if (post.isVideo) throw DanbooruException("视频可在详情播放；保存或分享原始视频请打开原帖")
        if (!post.isImage) throw DanbooruException("此动态格式支持封面和标签，原始文件请打开原帖")
        val key = post.mediaKey(snapshot.settings.namespace(), "original", url)
        val ext = post.extension.lowercase().takeIf { it in setOf("jpg", "jpeg", "png", "webp", "gif", "avif") }
            ?: throw DanbooruException("原图格式尚未支持，请打开原帖")
        val file = try {
            MediaFileCache.originalForShare(app, url, "danbooru_${post.id}.$ext", snapshot.media,
                imageLoader, key, "share_cache/danbooru",
                httpError = { DanbooruException(DanbooruHttp.statusMessage(it), it) })
        } catch (e: java.io.IOException) { throw DanbooruHttp.networkException(e) }
        withContext(Dispatchers.IO) {
            var bytes = file.length()
            val expiration = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            file.parentFile?.listFiles()?.filter { it != file }?.sortedByDescending { it.lastModified() }?.forEach {
                bytes += it.length()
                if (it.lastModified() < expiration || bytes > 128L * 1024 * 1024) it.delete()
            }
        }
        return file
    }
    companion object {
        @Volatile private var instance: DanbooruRuntime? = null
        fun get(context: Context): DanbooruRuntime = instance ?: synchronized(this) {
            instance ?: DanbooruRuntime(context).also { instance = it }
        }
    }
}
