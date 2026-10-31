package com.comfyui.mobile.modelcenter

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.comfyui.mobile.data.AppModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ModelCenterState(
    val identity: String = "", val serverLabel: String = "", val connected: Boolean = false,
    val restored: Boolean = false,
    val loading: Boolean = false, val fromCache: Boolean = true, val cacheAt: Long = 0,
    val capabilities: ModelCapabilities? = null, val page: ModelPage = ModelPage(),
    val query: LocalModelQuery = LocalModelQuery(), val compact: Boolean = false,
    val scrollIndex: Int = 0, val scrollOffset: Int = 0,
    val detail: LocalModel? = null, val detailLoading: Boolean = false, val fileBusy: Boolean = false,
    val tasks: ModelTasks = ModelTasks(), val error: String? = null, val notice: String? = null,
    val sourceScope: String = "", val sourceTransport: String = "",
    val sourceTasks: Map<String, ModelSourceTask> = emptyMap()
)

class ModelCenterViewModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(ModelCenterState())
    val state = mutable.asStateFlow()
    private val cacheDir = java.io.File(application.filesDir, "local-model-cache-v1")
    private var snapshot = ModelCacheSnapshot()
    private var repository: LocalModelRepository? = null
    private var generation = 0L
    private var queryEpoch = 0L
    private var detailEpoch = 0L
    private var loadJob: Job? = null
    private var loadingQuery: LocalModelQuery? = null
    private var contextKey = ""
    private var negotiated = false
    private val cacheWrites = Mutex()
    private val fileWrites = Mutex()
    private var phoneSource: LocalModelSource? = null
    private val sourceLoader = ModelSourceLoader(viewModelScope,
        onStatus = { hash, task ->
            mutable.value = state.value.copy(sourceTasks = state.value.sourceTasks + (hash to task))
        },
        onResult = { hash, provider ->
            if (provider.cacheScope == state.value.sourceScope) {
                snapshot = snapshot.copy(phoneSources = snapshot.phoneSources + (hash to provider),
                    savedAt = System.currentTimeMillis())
                publishSources()
                persist()
            }
        })

    /** The same phone transport as the online market, regardless of its optional Bridge-list mode. */
    fun source(value: LocalModelSource) {
        val namespace = value.sourceScope
        if (phoneSource === value && state.value.sourceScope == namespace &&
            state.value.sourceTransport == value.transportScope) return
        phoneSource = value
        snapshot = snapshot.copy(phoneSources = snapshot.phoneSources.filterValues { it.cacheScope == namespace })
        mutable.value = state.value.copy(sourceScope = namespace, sourceTransport = value.transportScope, sourceTasks = emptyMap())
        bindSources()
        publishSources()
        if (state.value.restored) persist()
    }

    private fun bindSources() = sourceLoader.bind("$contextKey|$generation|${snapshot.bridgeServerId}", phoneSource)
    private fun resolved(item: LocalModel) = item.withPhoneSource(snapshot.phoneSources, state.value.sourceScope)
    private fun cachedPage(query: LocalModelQuery, page: Int) =
        LocalModelCache.page(snapshot.withPhoneSources(state.value.sourceScope), query, page)

    private fun publishSources() {
        fun raw(item: LocalModel): LocalModel = snapshot.items.firstOrNull { it.id == item.id } ?: item
        mutable.value = state.value.copy(
            page = state.value.page.copy(items = state.value.page.items.map { resolved(raw(it)) }),
            detail = state.value.detail?.let { resolved(raw(it)) })
    }

    fun requestSource(id: String, refresh: Boolean = false): Boolean {
        val item = snapshot.items.firstOrNull { it.id == id }
            ?: state.value.page.items.firstOrNull { it.id == id }
            ?: state.value.detail?.takeIf { it.id == id } ?: return false
        val hash = modelHash(item.sha256) ?: return false
        val saved = snapshot.phoneSources[hash]
        if (!refresh && saved?.cacheScope == state.value.sourceScope && saved.modelDescriptionFetched &&
            System.currentTimeMillis() - saved.fetchedAt < 24 * 60 * 60_000L) return false
        return sourceLoader.request(hash, refresh)
    }

    fun context(identity: String, label: String, baseUrl: String?, connected: Boolean) {
        val key = "$identity|$baseUrl|$connected"
        if (key == contextKey) return
        contextKey = key
        generation++
        queryEpoch++
        detailEpoch++
        negotiated = false
        loadJob?.cancel()
        val token = generation
        repository = baseUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.let {
            LocalModelRepository(cacheDir, identity, it, AppModule.provideRepository().hostAwareCallFactory)
        }
        // Immediately remove the previous server's data, before any disk/network suspension.
        mutable.value = ModelCenterState(identity = identity, serverLabel = label, connected = connected,
            sourceScope = phoneSource?.sourceScope.orEmpty(), sourceTransport = phoneSource?.transportScope.orEmpty())
        snapshot = ModelCacheSnapshot()
        bindSources()
        loadJob = viewModelScope.launch {
            val stored = cacheWrites.withLock {
                withContext(Dispatchers.IO) { LocalModelCache(cacheDir, identity).load() }
            }
            if (token != generation) return@launch
            snapshot = stored.copy(phoneSources = stored.phoneSources.filterValues {
                state.value.sourceScope.isBlank() || it.cacheScope == state.value.sourceScope
            })
            bindSources()
            mutable.value = mutable.value.copy(
                restored = true,
                query = stored.query, compact = stored.compact, scrollIndex = stored.scrollIndex,
                scrollOffset = stored.scrollOffset, cacheAt = stored.savedAt,
                capabilities = stored.capabilities, page = cachedPage(stored.query, 1))
            if (connected && repository != null) refresh()
        }
    }

    fun query(value: LocalModelQuery) {
        if (value == state.value.query) return
        queryEpoch++
        // A selected chip must never label rows/counts from the previous query, even while a slow
        // phone request is still in flight. Show this query's cached rows at once (stale-while-
        // revalidate) so switching to e.g. LoRA does not wait on the network to show anything.
        mutable.value = state.value.copy(query = value, scrollIndex = 0, scrollOffset = 0,
            page = cachedPage(value, 1))
        snapshot = snapshot.copy(query = value, scrollIndex = 0, scrollOffset = 0)
        persist()
        refresh()
    }

    fun compact() {
        mutable.value = state.value.copy(compact = !state.value.compact)
        snapshot = snapshot.copy(compact = state.value.compact)
        persist()
    }

    fun scroll(index: Int, offset: Int) {
        if (state.value.scrollIndex == index && state.value.scrollOffset == offset) return
        mutable.value = state.value.copy(scrollIndex = index, scrollOffset = offset)
        snapshot = snapshot.copy(scrollIndex = index, scrollOffset = offset)
        persist()
    }

    private fun persist() {
        val identity = state.value.identity
        val copy = snapshot
        viewModelScope.launch {
            // Captured identity: a late disk save cannot write into the next server.
            cacheWrites.withLock {
                withContext(Dispatchers.IO) { runCatching { LocalModelCache(cacheDir, identity).save(copy) } }
            }
        }
    }

    fun refresh(append: Boolean = false) {
        val query = state.value.query
        // Task polling can run faster than a model-list request. Join the current
        // query instead of cancelling it forever (also preserves a pending next page).
        if (state.value.loading && loadJob?.isActive == true && loadingQuery == query) return
        loadJob?.cancel()
        val token = generation
        val epoch = ++queryEpoch
        val repo = repository
        val nextPage = if (append) state.value.page.page + 1 else 1
        if (!state.value.connected || repo == null) {
            val cached = cachedPage(query, nextPage)
            mutable.value = state.value.copy(
                fromCache = true, loading = false, error = null,
                page = if (append) cached.copy(items = state.value.page.items + cached.items) else cached)
            return
        }
        loadingQuery = query
        mutable.value = state.value.copy(loading = true, error = null)
        loadJob = viewModelScope.launch {
            try {
                val cap = repo.capabilities()
                val result = repo.models(query, nextPage)
                check(result.schemaVersion == 1 && result.serverId == cap.serverId) { "模型中心服务器身份不一致，请重新连接" }
                check(query.category.isBlank() || result.items.all { it.category == query.category }) {
                    "电脑返回的模型分类与当前筛选不一致，请更新 Bridge 后重试"
                }
                if (generation != token || epoch != queryEpoch) return@launch
                negotiated = true
                if (snapshot.bridgeServerId != cap.serverId) {
                    snapshot = ModelCacheSnapshot(bridgeServerId = cap.serverId, query = query, compact = state.value.compact)
                    mutable.value = state.value.copy(sourceTasks = emptyMap(), detail = null, detailLoading = false)
                    bindSources()
                }
                val merged = (snapshot.items + result.items).associateBy { it.id }.values.toList()
                snapshot = snapshot.copy(bridgeServerId = cap.serverId, capabilities = cap, items = merged,
                    savedAt = System.currentTimeMillis(), lastSync = result.lastSync, query = query)
                val resolvedResult = result.copy(items = result.items.map(::resolved))
                val visible = if (append) resolvedResult.copy(
                    items = (state.value.page.items + resolvedResult.items).distinctBy { it.id }) else resolvedResult
                mutable.value = state.value.copy(page = visible, capabilities = cap, loading = false,
                    fromCache = false, cacheAt = snapshot.savedAt, error = null,
                    detail = state.value.detail?.let { detail ->
                        result.items.firstOrNull { it.id == detail.id }?.let(::resolved) ?: detail
                    })
                persist()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                if (generation != token || epoch != queryEpoch) return@launch
                val cached = cachedPage(query, 1)
                mutable.value = state.value.copy(page = cached, loading = false, fromCache = true,
                    error = error.message ?: "读取模型列表失败，已保留缓存")
            }
        }
    }

    fun openDetail(id: String?) {
        val epoch = ++detailEpoch
        val token = generation
        if (id == null) {
            mutable.value = state.value.copy(detail = null, detailLoading = false)
            return
        }
        val existing = state.value.page.items.firstOrNull { it.id == id } ?: snapshot.items.firstOrNull { it.id == id }
        mutable.value = state.value.copy(detail = existing?.let(::resolved),
            detailLoading = state.value.connected, notice = null)
        requestSource(id)
        val repo = repository ?: run {
            mutable.value = state.value.copy(detailLoading = false)
            return
        }
        if (!state.value.connected || !negotiated) {
            mutable.value = state.value.copy(detailLoading = false)
            return
        }
        viewModelScope.launch {
            try {
                val item = repo.detail(id)
                if (token == generation && epoch == detailEpoch) {
                    updateDetail(item)
                    requestSource(id)
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                if (token == generation && epoch == detailEpoch) mutable.value = state.value.copy(
                    detailLoading = false, notice = error.message ?: "详情读取失败，显示缓存")
            }
        }
    }

    private fun updateDetail(item: LocalModel) {
        snapshot = snapshot.copy(items = (snapshot.items.filterNot { it.id == item.id } + item),
            savedAt = System.currentTimeMillis())
        val visible = resolved(item)
        mutable.value = state.value.copy(detail = visible, detailLoading = false,
            page = state.value.page.copy(items = state.value.page.items.map { if (it.id == item.id) visible else it }))
        persist()
    }

    suspend fun save(annotation: ModelAnnotation, undo: Boolean = false): Result<Unit> {
        val repo = repository ?: return Result.failure(IllegalStateException("请连接电脑后保存标注"))
        val item = state.value.detail ?: return Result.failure(IllegalStateException("请先选择模型"))
        if (!state.value.connected || !negotiated || state.value.fromCache)
            return Result.failure(IllegalStateException("当前为离线缓存，请连接并刷新后保存"))
        val token = generation
        val epoch = detailEpoch
        return try {
            val result = repo.annotate(item, annotation, if (undo) item.revision else null)
            if (token == generation && epoch == detailEpoch) {
                updateDetail(result)
                mutable.value = state.value.copy(notice = if (undo) "标注已撤销并保存" else "标注已保存到电脑")
            }
            Result.success(Unit)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) { Result.failure(error) }
    }

    private fun fileRepository(): LocalModelRepository {
        check(state.value.connected && negotiated && !state.value.fromCache) { "请连接电脑并刷新模型库后管理文件" }
        check(state.value.capabilities?.fileManagement == true) { "请更新电脑 Bridge 至 2.2.5 或更新版本并重启 ComfyUI" }
        return checkNotNull(repository) { "请连接电脑后管理文件" }
    }

    suspend fun directories(id: String, rootId: String, path: String): Result<ModelDirectories> {
        val token = generation
        return try {
            val repo = fileRepository()
            val result = repo.directories(id, rootId, path)
            check(token == generation && result.serverId == state.value.capabilities?.serverId) {
                "电脑连接已切换，请重新选择文件夹"
            }
            Result.success(result)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) { Result.failure(error) }
    }

    suspend fun manageFile(item: LocalModel, request: ModelFileWrite): Result<ModelFileResult> {
        if (!fileWrites.tryLock()) return Result.failure(IllegalStateException("正在处理模型文件，请稍候"))
        val token = generation
        try {
            val repo = fileRepository()
            check(state.value.detail?.id == item.id && item.serverId == state.value.capabilities?.serverId &&
                item.present && item.mtimeNs != null) {
                "请重新打开模型详情，确认服务器文件状态"
            }
            check(request.expectedPath == item.relativePath && request.expectedSize == item.size &&
                request.expectedMtimeNs == item.mtimeNs) { "文件状态已变化，请刷新后重试" }
            val serverId = state.value.capabilities?.serverId
            mutable.value = state.value.copy(fileBusy = true)
            val result = repo.fileAction(item.id, request)
            check(result.serverId == serverId && result.previousId == item.id && result.action == request.action &&
                (request.action == "delete" || result.item?.serverId == serverId)) { "服务器文件操作响应不一致，请刷新确认" }
            if (token != generation) {
                // The old server still performed the requested operation. Repair only
                // that server's disk cache; never publish it into the active profile.
                cacheWrites.withLock {
                    withContext(Dispatchers.IO) {
                        val previous = repo.cache.load()
                        repo.cache.save(previous.copy(items = previous.items.filterNot {
                            it.id == item.id || it.id == result.item?.id
                        } + listOfNotNull(result.item), savedAt = System.currentTimeMillis()))
                    }
                }
                error("操作已提交到原电脑；连接已切换，请在原电脑刷新确认")
            }
            // Do not publish any rename/delete before the server has committed the real files.
            loadJob?.cancel()
            queryEpoch++
            detailEpoch++
            val replacement = result.item
            snapshot = snapshot.copy(items = snapshot.items.filterNot { it.id == item.id ||
                it.id == replacement?.id } + listOfNotNull(replacement), savedAt = System.currentTimeMillis())
            mutable.value = state.value.copy(
                loading = false, detailLoading = false, fileBusy = false, notice = result.feedback,
                detail = if (state.value.detail?.id == item.id) replacement?.let(::resolved) else state.value.detail,
                page = state.value.page.copy(items = state.value.page.items.filterNot { it.id == item.id }))
            persist()
            refresh()
            return Result.success(result)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) { return Result.failure(error)
        } finally {
            if (token == generation) mutable.value = state.value.copy(fileBusy = false)
            fileWrites.unlock()
        }
    }

    fun scan(operation: String) = action { repo ->
        val scan = repo.scan(operation)
        if (repo === repository) {
            mutable.value = state.value.copy(tasks = state.value.tasks.copy(scan = scan))
            refresh()
        }
    }

    fun enrich(ids: List<String>, refresh: Boolean = false) {
        val queued = ids.distinct().count { requestSource(it, refresh) }
        mutable.value = state.value.copy(notice = if (queued > 0)
            "手机直连补全 $queued 项 · 使用手机 HTTP 代理与 Civitai Key，结果保存在手机"
        else "已有资料 / 正在查询；缺少 SHA-256 的文件请先连接电脑同步指纹，可在详情中重试")
    }

    fun taskAction(id: String, operation: String) = action { repo ->
        repo.taskAction(id, operation)
        if (repo === repository) pollTasks()
    }

    private fun action(block: suspend (LocalModelRepository) -> Unit) {
        val repo = repository ?: return
        val token = generation
        if (!state.value.connected || !negotiated) {
            mutable.value = state.value.copy(notice = "请连接支持模型中心 v1 的电脑 Bridge 后操作")
            return
        }
        viewModelScope.launch {
            try {
                // Recheck immediately before dispatch. Results also stay profile-scoped.
                if (token != generation) return@launch
                block(repo)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                if (token == generation) mutable.value = state.value.copy(notice = error.message ?: "操作失败")
            }
        }
    }

    suspend fun pollTasks() {
        val repo = repository ?: return
        val token = generation
        if (!state.value.connected || !negotiated) return
        try {
            val tasks = repo.tasks()
            if (token == generation) mutable.value = state.value.copy(tasks = tasks)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { /* Explicit refresh/action exposes errors; polling retains last known state. */ }
    }

    fun preview(item: LocalModel, index: Int = 0): String? {
        val remote = remoteModelPreview(item, index)
        // Only an actual sidecar file needs the computer. Provider images never use its proxy.
        if (item.preview?.source == "local" && state.value.connected) return repository?.preview(item, index) ?: remote
        return remote ?: if (item.preview?.source == "local") repository?.preview(item, index) else null
    }

    suspend fun installation(modelId: Long, versionId: Long, fileId: Long): String {
        val repo = repository ?: return "安装状态待连接电脑确认"
        val token = generation
        if (!state.value.connected) return "安装状态待连接电脑确认（缓存不作已安装确认）"
        return try {
            repo.capabilities()
            val result = repo.installation(modelId, versionId, fileId)
            if (token != generation) return "服务器已切换，安装状态待重新确认"
            when (result.status) {
                "exact-file" -> "当前电脑已安装此文件 · 版本 / 文件 ID 与已校验哈希一致"
                "other-version" -> "当前电脑已有此模型的其他版本 / 文件"
                else -> "索引中尚未确认安装此文件（不是按名称猜测）"
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { "安装状态未确认，请更新电脑 Bridge 并同步模型库" }
    }
}
