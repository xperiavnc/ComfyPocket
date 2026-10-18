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
    val detail: LocalModel? = null, val detailLoading: Boolean = false,
    val tasks: ModelTasks = ModelTasks(), val error: String? = null, val notice: String? = null
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
    private var contextKey = ""
    private var negotiated = false
    private val cacheWrites = Mutex()

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
        mutable.value = ModelCenterState(identity = identity, serverLabel = label, connected = connected)
        snapshot = ModelCacheSnapshot()
        loadJob = viewModelScope.launch {
            val stored = cacheWrites.withLock {
                withContext(Dispatchers.IO) { LocalModelCache(cacheDir, identity).load() }
            }
            if (token != generation) return@launch
            snapshot = stored
            mutable.value = mutable.value.copy(
                restored = true,
                query = stored.query, compact = stored.compact, scrollIndex = stored.scrollIndex,
                scrollOffset = stored.scrollOffset, cacheAt = stored.savedAt,
                capabilities = stored.capabilities, page = LocalModelCache.page(stored, stored.query, 1))
            if (connected && repository != null) refresh()
        }
    }

    fun query(value: LocalModelQuery) {
        if (value == state.value.query) return
        queryEpoch++
        mutable.value = state.value.copy(query = value, scrollIndex = 0, scrollOffset = 0)
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
        loadJob?.cancel()
        val token = generation
        val epoch = ++queryEpoch
        val repo = repository
        val query = state.value.query
        val nextPage = if (append) state.value.page.page + 1 else 1
        if (!state.value.connected || repo == null) {
            val cached = LocalModelCache.page(snapshot, query, nextPage)
            mutable.value = state.value.copy(
                fromCache = true, loading = false, error = null,
                page = if (append) cached.copy(items = state.value.page.items + cached.items) else cached)
            return
        }
        mutable.value = state.value.copy(loading = true, error = null)
        loadJob = viewModelScope.launch {
            try {
                val cap = repo.capabilities()
                val result = repo.models(query, nextPage)
                check(result.schemaVersion == 1 && result.serverId == cap.serverId) { "模型中心服务器身份不一致，请重新连接" }
                if (generation != token || epoch != queryEpoch) return@launch
                negotiated = true
                if (snapshot.bridgeServerId != cap.serverId) snapshot = ModelCacheSnapshot(query = query, compact = state.value.compact)
                val merged = (snapshot.items + result.items).associateBy { it.id }.values.toList()
                snapshot = snapshot.copy(bridgeServerId = cap.serverId, capabilities = cap, items = merged,
                    savedAt = System.currentTimeMillis(), lastSync = result.lastSync, query = query)
                val visible = if (append) result.copy(
                    items = (state.value.page.items + result.items).distinctBy { it.id }) else result
                mutable.value = state.value.copy(page = visible, capabilities = cap, loading = false,
                    fromCache = false, cacheAt = snapshot.savedAt, error = null)
                persist()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                if (generation != token || epoch != queryEpoch) return@launch
                val cached = LocalModelCache.page(snapshot, query, 1)
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
        mutable.value = state.value.copy(detail = existing, detailLoading = state.value.connected, notice = null)
        val repo = repository ?: return
        if (!state.value.connected || !negotiated) {
            mutable.value = state.value.copy(detailLoading = false)
            return
        }
        viewModelScope.launch {
            try {
                val item = repo.detail(id)
                if (token == generation && epoch == detailEpoch) updateDetail(item)
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
        mutable.value = state.value.copy(detail = item, detailLoading = false,
            page = state.value.page.copy(items = state.value.page.items.map { if (it.id == item.id) item else it }))
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

    fun scan(operation: String) = action { repo ->
        val scan = repo.scan(operation)
        if (repo === repository) {
            mutable.value = state.value.copy(tasks = state.value.tasks.copy(scan = scan))
            refresh()
        }
    }

    fun enrich(ids: List<String>, refresh: Boolean = false) = action { repo ->
        val result = repo.enrich(ids, refresh)
        if (repo === repository) {
            mutable.value = state.value.copy(notice = if (result.errors.isEmpty())
                "已处理 ${result.tasks.size} 项资料请求 · 使用电脑端 Key / 代理"
            else result.errors.joinToString("\n") { it.message })
            pollTasks()
        }
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

    fun preview(item: LocalModel, index: Int = 0): String? = repository?.preview(item, index)

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
