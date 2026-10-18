package com.comfyui.mobile.market

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.request.ImageRequest
import com.comfyui.mobile.data.ComfyRepository
import com.comfyui.mobile.diagnostics.NavigationDiagnostics
import com.comfyui.mobile.model.CivitaiModelItem
import com.comfyui.mobile.model.CivitaiSearchPageData
import com.comfyui.mobile.model.CivitaiVersionItem
import com.comfyui.mobile.modelcenter.LocalModelSource
import com.comfyui.mobile.modelcenter.ModelProvider
import com.comfyui.mobile.modelcenter.ModelSourceException
import com.comfyui.mobile.modelcenter.ModelSourceResult
import com.comfyui.mobile.modelcenter.modelHash
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.CompletableFuture

data class MarketDetailResult(val model: CivitaiModelItem, val warning: String? = null)

class CivitaiRepository(
    context: Context,
    initialSettings: CivitaiSettings,
    private val bridge: ComfyRepository
) : LocalModelSource {
    private val app = context.applicationContext
    private val gson = Gson()
    private val cache = MarketDiskCache(app.cacheDir.resolve("civitai_metadata"))
    @Volatile private var settings = initialSettings
    @Volatile private var client = CivitaiHttp.client(initialSettings)
    private val resourceLock = Any()
    private var closed = false
    private var pendingCleanup: CompletableFuture<Boolean>? = null
    private var finalCleanup: CompletableFuture<Boolean>? = null
    private var bridgeIdentity = ""
    private var bridgeAvailable = false

    val imageLoader: ImageLoader = ImageLoader.Builder(app)
        // Resolve the current proxy client per request, not once via Coil's lazy initializer.
        .callFactory(okhttp3.Call.Factory { request -> activeClient().newCall(request) })
        .diskCache { sharedImageCache(app) }
        .crossfade(false)
        .build()

    fun updateSettings(value: CivitaiSettings): Unit = synchronized(resourceLock) {
        check(!closed) { "集市已关闭，请重新进入后再修改设置" }
        val previous = client
        client = CivitaiHttp.client(value)
        settings = value
        pendingCleanup = MarketResourceCleanup.release(previous, previous = pendingCleanup)
        imageLoader.memoryCache?.clear()
    }

    private fun activeClient(): OkHttpClient = synchronized(resourceLock) {
        check(!closed) { "集市已关闭，请重新进入后重试" }
        client
    }

    fun bridgeContext(identity: String, available: Boolean): Boolean {
        val changed = identity != bridgeIdentity || available != bridgeAvailable
        bridgeIdentity = identity
        bridgeAvailable = available
        return changed
    }

    fun imageRequest(url: String): ImageRequest = ImageRequest.Builder(app).data(url)
        .memoryCacheKey("$sourceScope:$url")
        .diskCacheKey("$sourceScope:$url")
        .build()

    override val sourceScope: String
        get() = settings.copy(mode = MarketConnectionMode.DIRECT).cacheNamespace()
    override val transportScope: String
        get() = settings.let { config ->
            marketHash(listOf(sourceScope, config.proxyEnabled.toString(), config.proxyAddress,
                config.proxyUsername, config.proxyPassword).joinToString("\u0000"))
        }

    /** External metadata always uses the phone's own client/proxy/key, even with a Bridge catalog. */
    override suspend fun fetchSource(sha256: String, refresh: Boolean): ModelSourceResult =
        withContext(Dispatchers.IO) {
            val hash = modelHash(sha256) ?: throw ModelSourceException("请先同步完整的模型 SHA-256")
            val namespace = sourceScope
            val http = activeClient()
            try {
                val matched = cached("$namespace|local-hash-v1|$hash", ModelProvider::class.java, refresh) {
                    CivitaiLocalModelJson.match(CivitaiHttp.text(http,
                        CivitaiHttp.request(CivitaiJson.endpoint("model-versions/by-hash/$hash"))), hash)
                }
                var provider = matched.value
                var warning = matched.warning
                try {
                    val model = cached("$namespace|model|${provider.modelId}",
                        CivitaiModelItem::class.java, refresh) {
                        CivitaiJson.model(CivitaiHttp.text(http,
                            CivitaiHttp.request(CivitaiJson.endpoint("models/${provider.modelId}")))).also {
                            check(it.id == provider.modelId) { "Civitai 返回了不同的模型" }
                        }
                    }
                    provider = provider.copy(name = model.value.name, author = model.value.creator,
                        modelDescription = model.value.description, modelDescriptionFetched = true)
                    warning = model.warning ?: warning
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (error: Exception) {
                    // An independently failing introduction must not discard the exact version/cover.
                    warning = "版本与预览已匹配；模型简介暂未加载，点击重试。" +
                        if (error is MarketAccessException) " ${error.message}" else ""
                }
                ModelSourceResult(provider.copy(fetchedAt = System.currentTimeMillis(),
                    fetchedBy = "phone", cacheScope = namespace), warning)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                throw ModelSourceException(
                    if (error is MarketAccessException) error.message.orEmpty()
                    else "来源未返回与本地 SHA-256 相同的有效文件，请重试",
                    (error as? MarketAccessException)?.status)
            }
        }

    suspend fun search(
        query: MarketQuery, page: Int, cursor: String?, forceRefresh: Boolean
    ): MarketSearchResult = withContext(Dispatchers.IO) {
        val config = settings
        val identity = bridgeIdentity
        // v2 invalidates old early-access-only pages and the former Checkpoint-only type mapping.
        val key = "${config.cacheNamespace(identity)}|search-v2|${gson.toJson(query)}|$page|$cursor"
        if (config.mode == MarketConnectionMode.BRIDGE) {
            check(bridgeAvailable) { "请先连接 ComfyUI 电脑，或在集市设置中切换为手机直连" }
            val result = cached(key, CivitaiSearchPageData::class.java, forceRefresh) {
                val value = bridge.searchCivitai(
                    query.keyword, query.type, query.baseModel.takeUnless { it == "全部" },
                    page, cursor, 20
                )
                currentCoroutineContext().ensureActive()
                value.getOrThrow()
            }
            return@withContext MarketSearchResult(
                MarketModelFilters.page(result.value, query), result.fromCache, result.warning
            )
        }
        val http = activeClient()
        val result = cached(key, CivitaiSearchPageData::class.java, forceRefresh) {
            CivitaiJson.page(CivitaiHttp.text(http,
                CivitaiHttp.request(CivitaiJson.searchUrl(query, page, cursor, config))), page)
        }
        MarketSearchResult(MarketModelFilters.page(result.value, query), result.fromCache, result.warning)
    }

    suspend fun details(
        original: CivitaiModelItem, versionId: Long?, forceRefresh: Boolean = false
    ): MarketDetailResult = withContext(Dispatchers.IO) {
        val config = settings.copy(mode = MarketConnectionMode.DIRECT)
        val namespace = config.cacheNamespace()
        val http = activeClient()
        val model = cached("$namespace|model|${original.id}",
            CivitaiModelItem::class.java, forceRefresh) {
            CivitaiJson.model(CivitaiHttp.text(http,
                CivitaiHttp.request(CivitaiJson.endpoint("models/${original.id}")))).also {
                check(it.id == original.id) { "Civitai 返回了不同的模型，请重试" }
            }
        }
        val selected = model.value.versions.firstOrNull { it.id == versionId }
            ?: model.value.versions.firstOrNull()
            ?: return@withContext MarketDetailResult(model.value, model.warning)
        var warning = model.warning
        val fullVersion = try {
            val result = cached("$namespace|version|${selected.id}",
                CivitaiVersionItem::class.java, forceRefresh) {
                var version = CivitaiJson.version(CivitaiHttp.text(http,
                    CivitaiHttp.request(CivitaiJson.endpoint("model-versions/${selected.id}"))))
                check(version.id == selected.id) { "Civitai 返回了不同的版本，请重试" }
                if (version.images.none { it.prompt.isNotBlank() || it.negativePrompt.isNotBlank() }) {
                    val examplesUrl = CivitaiJson.endpoint("images").newBuilder()
                        .addQueryParameter("modelVersionId", selected.id.toString())
                        .addQueryParameter("limit", "8")
                        .addQueryParameter("type", "image")
                        .addQueryParameter("withMeta", "true")
                        .addQueryParameter("nsfw", config.includeMatureContent.toString()).build()
                    try {
                        val examples = CivitaiJson.examples(CivitaiHttp.text(http,
                            CivitaiHttp.request(examplesUrl)))
                        version = version.copy(images =
                            (version.images + examples).distinctBy { it.url })
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        warning = "简介已加载；示例提示词加载失败，可点击重试"
                    }
                }
                version
            }
            warning = result.warning ?: warning
            result.value
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            warning = error.message ?: "版本详情加载失败，可点击重试"
            selected
        }
        MarketDetailResult(
            model.value.copy(versions = model.value.versions.map {
                if (it.id == fullVersion.id) fullVersion else it
            }), warning ?: if (model.fromCache) "正在显示本地缓存的详情" else null
        )
    }

    suspend fun testConnection(value: CivitaiSettings): String = withContext(Dispatchers.IO) {
        val config = value.validated()
        val temporaryClient = CivitaiHttp.client(config)
        try {
            val url = if (config.apiKey.isNotBlank()) CivitaiJson.endpoint("me")
            else CivitaiJson.endpoint("models").newBuilder().addQueryParameter("limit", "1")
                .addQueryParameter("nsfw", "false").build()
            val response = CivitaiHttp.text(temporaryClient, CivitaiHttp.request(url))
            // Successful HTML (for example a captive portal) is not a working API connection.
            com.google.gson.JsonParser.parseString(response).asJsonObject
            if (config.apiKey.isNotBlank()) "直连成功，Civitai Key 验证通过"
            else "直连成功，当前为匿名访问"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: MarketAccessException) {
            throw error
        } catch (_: Exception) {
            throw MarketAccessException("连接测试失败：响应不是有效的 Civitai 数据")
        } finally {
            temporaryClient.dispatcher.cancelAll()
            temporaryClient.connectionPool.evictAll()
            temporaryClient.dispatcher.executorService.shutdown()
        }
    }

    suspend fun downloadTasks() = bridge.getCivitaiDownloadTasks()

    suspend fun taskAction(action: String, downloadId: String) =
        bridge.civitaiTaskAction(action, downloadId)

    private data class Cached<T>(val value: T, val fromCache: Boolean, val warning: String? = null)

    private suspend fun <T : Any> cached(
        key: String, type: Class<T>, forceRefresh: Boolean, fetch: suspend () -> T
    ): Cached<T> {
        val entry = cache.get(key)
        val saved = entry?.let { runCatching { gson.fromJson(it.body, type) }.getOrNull() }
        if (!forceRefresh && entry != null && saved != null &&
            entry.fresh(System.currentTimeMillis(), 5 * 60_000L)) {
            return Cached(saved, true)
        }
        try {
            val value = fetch()
            currentCoroutineContext().ensureActive()
            cache.put(key, gson.toJson(value))
            return Cached(value, false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // A denied/deleted resource is not an offline condition: do not hide it behind stale data.
            val permissionDenied = error is MarketAccessException && error.status in setOf(401, 403, 404)
            if (saved != null && !permissionDenied) return Cached(
                saved, true, "网络访问失败，正在显示本地缓存；可检查代理或稍后刷新"
            )
            if (error is MarketAccessException || settings.mode == MarketConnectionMode.BRIDGE) throw error
            throw MarketAccessException("Civitai 数据解析失败，请刷新重试")
        }
    }

    /** Idempotent and non-blocking on main; the result includes all retired clients. */
    fun close(): CompletableFuture<Boolean> = synchronized(resourceLock) {
        finalCleanup ?: run {
            closed = true
            NavigationDiagnostics.record(NavigationDiagnostics.Event.MARKET_REPOSITORY_CLOSING)
            MarketResourceCleanup.release(
                client, imageLoader, pendingCleanup, closingRepository = true
            ).also { finalCleanup = it }
        }
    }

    companion object {
        @Volatile private var sharedCache: DiskCache? = null

        private fun sharedImageCache(context: Context): DiskCache =
            sharedCache ?: synchronized(this) {
                sharedCache ?: DiskCache.Builder()
                    .directory(context.cacheDir.resolve("civitai_images"))
                    .maxSizeBytes(96L * 1024 * 1024).build()
                    .also { sharedCache = it }
            }
    }
}
