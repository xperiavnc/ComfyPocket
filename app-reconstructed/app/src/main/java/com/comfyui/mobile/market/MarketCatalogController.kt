package com.comfyui.mobile.market

import com.comfyui.mobile.model.CivitaiModelItem
import com.comfyui.mobile.model.CivitaiSearchPageData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MarketSearchResult(
    val page: CivitaiSearchPageData,
    val fromCache: Boolean = false,
    val warning: String? = null
)

data class MarketCatalogState(
    val keyword: String = "",
    val type: String = "LORA",
    val baseModel: String = "全部",
    val items: List<CivitaiModelItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val hasNextPage: Boolean = false
)

/** Android-free state holder: one active search, isolated generations, stable de-duplicated IDs. */
class MarketCatalogController(
    private val scope: CoroutineScope,
    private val search: suspend (MarketQuery, Int, String?, Boolean) -> MarketSearchResult
) {
    private val mutableState = MutableStateFlow(MarketCatalogState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    private var loaded = false
    private var activeQuery = MarketQuery()
    private var page = 0
    private var nextPage: Int? = null
    private var cursor: String? = null
    private var lastAttemptReset = true
    private var lastForceRefresh = false

    fun keyword(value: String) {
        mutableState.value = mutableState.value.copy(keyword = value)
    }

    fun filter(type: String = state.value.type, baseModel: String = state.value.baseModel) {
        mutableState.value = state.value.copy(type = type, baseModel = baseModel)
        load(reset = true)
    }

    fun ensureLoaded() {
        if (!loaded && job?.isActive != true) load(reset = true)
    }

    fun retry() = load(reset = lastAttemptReset, forceRefresh = lastForceRefresh)

    fun invalidate() {
        generation++
        job?.cancel()
        loaded = false
        page = 0
        cursor = null
        nextPage = null
        mutableState.value = state.value.copy(
            items = emptyList(), loading = false, error = null, notice = null, hasNextPage = false
        )
    }

    fun load(reset: Boolean = true, forceRefresh: Boolean = false) {
        if (!reset && (state.value.loading || !state.value.hasNextPage)) return
        lastAttemptReset = reset
        lastForceRefresh = forceRefresh
        if (reset) {
            job?.cancel()
            activeQuery = MarketQuery(state.value.keyword.trim(), state.value.type, state.value.baseModel)
        }
        val requestGeneration = ++generation
        val targetQuery = activeQuery
        val targetPage = if (reset) 1 else nextPage ?: (page + 1)
        val targetCursor = if (reset) null else cursor
        // Don't leave old-filter cards visible under new filter chips while a new search runs.
        mutableState.value = state.value.copy(
            loading = true, error = null, notice = null,
            items = if (reset && targetQuery != lastSuccessfulQuery) emptyList() else state.value.items,
            hasNextPage = if (reset) false else state.value.hasNextPage
        )
        job = scope.launch {
            try {
                val result = search(targetQuery, targetPage, targetCursor, forceRefresh)
                if (requestGeneration != generation) return@launch
                val data = result.page
                val items = ((if (reset) emptyList() else state.value.items) + data.items).distinctBy { it.id }
                page = targetPage
                nextPage = data.nextPage
                // Stop a looping cursor, even if the upstream accidentally repeats it.
                cursor = data.nextCursor?.takeIf { it.isNotBlank() && (reset || it != targetCursor) }
                loaded = true
                lastSuccessfulQuery = targetQuery
                val more = cursor != null || (targetQuery.keyword.isEmpty() &&
                    (nextPage != null || targetPage < data.totalPages))
                mutableState.value = state.value.copy(
                    items = items, loading = false, hasNextPage = more,
                    error = if (items.isEmpty()) "未找到相关模型，请尝试更换关键词" else null,
                    notice = result.warning ?: if (result.fromCache) "正在显示本地缓存，刷新可获取最新内容" else null
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (requestGeneration == generation) {
                    val denied = error is MarketAccessException && error.status in setOf(401, 403, 404)
                    mutableState.value = state.value.copy(
                        loading = false, error = error.message ?: "集市加载失败，请重试",
                        items = if (denied) emptyList() else state.value.items
                    )
                }
            }
        }
    }

    private var lastSuccessfulQuery: MarketQuery? = null
}
