package com.comfyui.mobile.ui.screens.modelbrowser

import com.comfyui.mobile.network.RhResourceItem
import com.comfyui.mobile.network.RhResourcePageData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class ModelBrowserQuery(
    val resourceType: String,
    val keyword: String = "",
    val baseModel: String? = null
)

internal data class ModelBrowserPageState(
    val query: ModelBrowserQuery? = null,
    val records: List<RhResourceItem> = emptyList(),
    val page: Int = 0,
    val total: Int = 0,
    val hasNext: Boolean = false,
    val loading: Boolean = false,
    val error: Throwable? = null
)

/**
 * One dialog/server session. Pagination uses the submitted query, not an unsubmitted
 * search-field draft. A newer search owns the state even if the old transport ignores
 * cancellation. Errors are retryable without dropping the already loaded pages.
 */
internal class ModelBrowserPager(
    private val scope: CoroutineScope,
    private val fetch: suspend (ModelBrowserQuery, Int, Boolean) -> Result<RhResourcePageData>
) {
    companion object { const val PAGE_SIZE = 24 }

    private data class Request(val query: ModelBrowserQuery, val page: Int, val refresh: Boolean)
    private val mutableState = MutableStateFlow(ModelBrowserPageState())
    val state = mutableState.asStateFlow()
    private var generation = 0
    private var job: Job? = null
    private var lastRequest: Request? = null

    fun search(query: ModelBrowserQuery, refresh: Boolean = false) =
        request(Request(query, 1, refresh))

    fun refresh() {
        state.value.query?.let { search(it, refresh = true) }
    }

    fun loadNext() {
        val current = state.value
        if (!current.loading && current.hasNext && current.error == null) {
            current.query?.let { request(Request(it, current.page + 1, false)) }
        }
    }

    fun retry() {
        if (!state.value.loading) lastRequest?.let(::request)
    }

    fun close() {
        generation++
        job?.cancel()
    }

    private fun request(request: Request) {
        val sequence = ++generation
        job?.cancel()
        lastRequest = request
        val previous = state.value.takeIf { it.query == request.query } ?: ModelBrowserPageState()
        mutableState.value = previous.copy(query = request.query, loading = true, error = null)
        job = scope.launch {
            val result = try {
                fetch(request.query, request.page, request.refresh)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
            if (sequence != generation) return@launch
            result.fold(onSuccess = { data ->
                val incoming = data.records.orEmpty()
                val records = (if (request.page == 1) incoming else previous.records + incoming)
                    .distinctBy { it.effectiveId }
                val hasNext = data.hasNext || request.page < data.pages ||
                    (incoming.isNotEmpty() && records.size < data.total) ||
                    (incoming.size >= PAGE_SIZE && data.total <= 0)
                // An inconsistent/repeated page must not trigger an endless auto-load loop.
                val stalled = request.page > 1 && hasNext && records.size == previous.records.size
                mutableState.value = ModelBrowserPageState(
                    query = request.query,
                    records = records,
                    page = request.page,
                    total = maxOf(data.total, records.size),
                    hasNext = hasNext && !stalled,
                    error = if (stalled) IllegalStateException("服务器未返回新的模型，请刷新后重试") else null
                )
            }, onFailure = { error ->
                if (error is CancellationException) throw error
                mutableState.value = previous.copy(query = request.query, loading = false, error = error)
            })
        }
    }
}
