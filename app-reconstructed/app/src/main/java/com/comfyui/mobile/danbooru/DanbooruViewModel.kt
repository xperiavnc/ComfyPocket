package com.comfyui.mobile.danbooru

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AccountStatus(val title: String) {
    ANONYMOUS("未登录"), SAVED("已保存，待验证"), VERIFYING("验证中"), VERIFIED("已验证"), FAILED("验证失败")
}
data class DanbooruPage(val number: Int, val request: String?, val next: String?, val posts: List<DanbooruPost>)
data class DanbooruState(
    val query: DanbooruQuery,
    val pages: List<DanbooruPage> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val appending: Boolean = false,
    val error: String? = null,
    val appendError: String? = null,
    val ended: Boolean = false,
    val boundary: Boolean = false,
    val detail: DanbooruPost? = null,
    val detailLoading: Boolean = false,
    val detailError: String? = null,
    val selected: Set<String> = emptySet(),
    val settingsOpen: Boolean = false,
    val account: AccountStatus = AccountStatus.ANONYMOUS,
    val identity: DanbooruIdentity? = null,
    val accountError: String? = null,
    val message: String? = null,
    val revision: Long = 0
) {
    val posts get() = pages.flatMap { it.posts }.distinctBy { it.id }
}

class DanbooruViewModel(
    private val saved: SavedStateHandle,
    private val service: DanbooruService
) : ViewModel() {
    val settings get() = service.settings
    private val _state = MutableStateFlow(DanbooruState(
        DanbooruQuery.resolve(saved["query"] ?: "", settings.defaultFilter,
            DanbooruSort.entries.firstOrNull { it.name == saved.get<String>("sort") } ?: DanbooruSort.LATEST,
            RankingPeriod.entries.firstOrNull { it.name == saved.get<String>("rankingPeriod") } ?: RankingPeriod.DAY,
            saved.get<String>("rankingDate")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: danbooruToday()),
        account = if (settings.loggedIn) AccountStatus.SAVED else AccountStatus.ANONYMOUS,
        accountError = service.initialError))
    val state = _state.asStateFlow()
    private var generation = 0L
    private var active = false
    private var observedSettings = settings
    private var loadJob: Job? = null
    private var detailJob: Job? = null
    // Only cursors, never post bodies or keys, go into SavedState. Eight visible page blocks.
    private val requests = (saved.get<ArrayList<String>>("pageRequests") ?: arrayListOf("_"))
        .mapIndexed { i, s -> i + 1 to s.takeUnless { it == "_" } }.toMap().toMutableMap()
    var scrollIndex: Int
        get() = saved["scrollIndex"] ?: 0
        set(value) { saved["scrollIndex"] = value }
    var scrollOffset: Int
        get() = saved["scrollOffset"] ?: 0
        set(value) { saved["scrollOffset"] = value }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (!value) {
            generation++
            loadJob?.cancel()
            detailJob?.cancel()
            update { copy(loading = false, refreshing = false, appending = false, detailLoading = false) }
        } else if (settings != observedSettings) {
            // Another UI (the opt-in Material sample) may save while this owner is paused.
            // Never resume old-account content with a newly configured client/cache scope.
            observedSettings = settings
            update { copy(account = if (settings.loggedIn) AccountStatus.SAVED else AccountStatus.ANONYMOUS,
                identity = null, accountError = null) }
            search(_state.value.query.text, settings.defaultFilter)
        } else if (_state.value.pages.isEmpty() && !_state.value.ended) {
            restore()
        } else if (hasDetail && _state.value.detail == null) {
            retryDetail()
        }
    }
    private fun restore() {
        val first = saved.get<Int>("firstPage") ?: 1
        val last = (saved.get<Int>("lastPage") ?: first).coerceAtMost(first + 7)
        val token = generation
        loadJob = viewModelScope.launch {
            update { copy(loading = true, error = null) }
            try {
                val pages = (first..last).map { number -> fetch(number, requests[number], false) }
                if (!active || token != generation) return@launch
                update { copy(pages = pages, loading = false, ended = pages.last().posts.isEmpty() || (saved["ended"] ?: false)) }
                saved.get<Long>("detailId")?.let { id -> open(pages.flatMap { it.posts }.firstOrNull { it.id == id }, id) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (token == generation) update { copy(loading = false, error = readable(e)) } }
        }
    }
    fun search(
        input: String, filter: ContentFilter = _state.value.query.filter,
        sort: DanbooruSort = _state.value.query.sort,
        period: RankingPeriod = _state.value.query.rankingPeriod,
        date: LocalDate = _state.value.query.rankingDate
    ) {
        generation++
        loadJob?.cancel()
        detailJob?.cancel()
        val query = DanbooruQuery.resolve(input, filter, sort, period, date)
        saved["query"] = query.text
        saved["sort"] = query.sort.name
        saved["rankingPeriod"] = query.rankingPeriod.name
        saved["rankingDate"] = query.rankingDate.toString()
        saved["detailId"] = null
        saved["firstPage"] = 1
        saved["lastPage"] = 1
        saved["ended"] = false
        scrollIndex = 0
        scrollOffset = 0
        requests.clear(); requests[1] = null
        saveRequests()
        update { copy(query = query, pages = emptyList(), detail = null, selected = emptySet(),
            error = null, appendError = null, boundary = false, ended = false, loading = false,
            refreshing = false, appending = false, detailLoading = false, revision = revision + 1) }
        if (active) loadNext(refresh = true)
    }
    fun refresh() {
        val current = _state.value
        if (!active || current.loading || current.refreshing) return
        if (current.pages.isEmpty()) { search(current.query.text, current.query.filter); return }
        generation++
        loadJob?.cancel()
        val token = generation
        update { copy(refreshing = true, appending = false, error = null, appendError = null) }
        loadJob = viewModelScope.launch {
            try {
                val first = fetch(1, null, true)
                if (!active || token != generation) return@launch
                requests.clear(); requests[1] = null; requests[2] = first.next
                saveRequests()
                saved["firstPage"] = 1; saved["lastPage"] = 1
                saved["ended"] = first.posts.isEmpty()
                scrollIndex = 0; scrollOffset = 0
                update { copy(pages = listOf(first), refreshing = false, ended = first.posts.isEmpty(),
                    boundary = false, error = null, appendError = null, revision = revision + 1) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (token == generation) update { copy(refreshing = false, error = readable(e)) }
            }
        }
    }
    fun filter(input: String, value: ContentFilter) = search(DanbooruQuery.withFilter(input, value), value)
    fun sort(input: String, value: DanbooruSort) = search(DanbooruQuery.withoutOrder(input), sort = value)
    fun rankingPeriod(input: String, value: RankingPeriod) =
        search(DanbooruQuery.withoutOrder(input), sort = DanbooruSort.RANKING, period = value)
    fun rankingDate(value: LocalDate) {
        val q = _state.value.query
        if (q.rankingPeriod.start(value) > q.rankingPeriod.start(danbooruToday())) return
        search(q.text, sort = DanbooruSort.RANKING, date = value)
    }

    // Ranked page numbers are mutable: never append an older cached page to a freshly
    // requested first page. Restore/previous still reuse the pages from the saved window.
    fun loadNext(refresh: Boolean = !_state.value.query.cursorPaging) {
        val current = _state.value
        if (!active || current.loading || current.refreshing || current.appending || current.ended || current.boundary) return
        val number = (current.pages.lastOrNull()?.number ?: 0) + 1
        val request = current.pages.lastOrNull()?.next
        load(number, request, previous = false, refresh = refresh)
    }
    fun retry() {
        update { copy(boundary = false, appendError = null, error = null) }
        loadNext(refresh = true)
    }
    fun previous() {
        val first = _state.value.pages.firstOrNull()?.number ?: return
        if (first <= 1 || _state.value.loading || _state.value.refreshing || _state.value.appending) return
        load(first - 1, requests[first - 1], previous = true, refresh = false)
    }
    private fun load(number: Int, request: String?, previous: Boolean, refresh: Boolean) {
        val token = generation
        val initial = _state.value.pages.isEmpty()
        update { copy(loading = initial, appending = !initial, error = null, appendError = null) }
        loadJob = viewModelScope.launch {
            try {
                val page = fetch(number, request, refresh)
                if (!active || generation != token) return@launch
                val existing = _state.value.pages
                val ids = existing.flatMap { it.posts }.map { it.id }.toSet()
                if (page.posts.isNotEmpty() && page.posts.all { it.id in ids }) {
                    throw DanbooruException("分页没有推进，已保留当前内容；可重试或刷新查询")
                }
                val pages = if (previous) (listOf(page) + existing).take(8) else (existing + page).takeLast(8)
                requests[number] = request
                requests[number + 1] = page.next
                saveRequests()
                saved["firstPage"] = pages.first().number
                saved["lastPage"] = pages.last().number
                val ended = !previous && page.posts.isEmpty()
                saved["ended"] = ended
                update { copy(pages = pages, loading = false, appending = false, ended = ended,
                    boundary = false, error = null, appendError = null) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == token) update { copy(loading = false, appending = false,
                    error = if (initial) readable(e) else null,
                    appendError = if (initial) null else readable(e),
                    boundary = (e as? DanbooruException)?.status == 410) }
            }
        }
    }
    private suspend fun fetch(number: Int, request: String?, refresh: Boolean): DanbooruPage {
        val query = _state.value.query
        val posts = service.repository.posts(query, request, refresh)
        val next = if (query.cursorPaging) posts.minOfOrNull { it.id }?.let { "b$it" } else (number + 1).toString()
        if (posts.isNotEmpty() && next == request) throw DanbooruException("分页游标没有推进，请重试")
        return DanbooruPage(number, request, next, posts)
    }
    fun open(post: DanbooruPost) = open(post, post.id)
    fun open(post: DanbooruPost?, id: Long) {
        detailJob?.cancel()
        val token = generation
        saved["detailId"] = id
        update { copy(detail = post, detailLoading = true, detailError = null, selected = emptySet()) }
        detailJob = viewModelScope.launch {
            try {
                val result = service.repository.post(id)
                if (active && generation == token && saved.get<Long>("detailId") == id) update { copy(detail = result, detailLoading = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == token) update { copy(detailLoading = false, detailError = readable(e)) } }
        }
    }
    val hasDetail get() = saved.get<Long>("detailId") != null
    fun retryDetail() { saved.get<Long>("detailId")?.let { open(_state.value.detail, it) } }
    fun select(tag: String) = update { copy(selected = if (tag in selected) selected - tag else selected + tag) }
    fun selectGroup(group: TagGroup) = update { copy(selected = selected + detail?.tags?.get(group).orEmpty()) }
    fun clearSelection() = update { copy(selected = emptySet()) }
    fun showSettings() = update { copy(settingsOpen = true) }
    fun closeSettings() = update { copy(settingsOpen = false) }
    fun back() {
        when (danbooruBack(_state.value.settingsOpen, _state.value.selected.isNotEmpty(), hasDetail)) {
            DanbooruBack.SETTINGS -> closeSettings()
            DanbooruBack.SELECTION -> clearSelection()
            DanbooruBack.DETAIL -> {
                detailJob?.cancel(); saved["detailId"] = null
                update { copy(detail = null, detailLoading = false, detailError = null) }
            }
            DanbooruBack.ROOT -> Unit
        }
    }
    suspend fun save(value: DanbooruSettings): Result<Unit> = try {
        val snapshot = value.validated()
        generation++; loadJob?.cancel(); detailJob?.cancel()
        update { copy(loading = false, refreshing = false, appending = false, detailLoading = false) }
        service.save(snapshot)
        observedSettings = settings
        update { copy(account = if (snapshot.loggedIn) AccountStatus.SAVED else AccountStatus.ANONYMOUS,
            identity = null, accountError = null, message = "Danbooru 设置已保存") }
        search(_state.value.query.text, snapshot.defaultFilter)
        Result.success(Unit)
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { Result.failure(DanbooruException(readable(e))) }
    suspend fun test(value: DanbooruSettings): String {
        val snapshot = value.validated()
        val testingSaved = snapshot == settings
        if (testingSaved) update { copy(account = AccountStatus.VERIFYING, accountError = null) }
        try {
            val identity = service.test(snapshot)
            if (testingSaved && snapshot == settings) update { copy(
                account = if (identity == null) AccountStatus.ANONYMOUS else AccountStatus.VERIFIED, identity = identity) }
            return if (identity == null) "当前输入：匿名读取成功；这不是登录验证，也未执行保存"
            else "当前输入：已验证 ${identity.name}（${identity.level}），帖子读取成功；${if (testingSaved) "已保存配置" else "尚未保存"}"
        } catch (e: CancellationException) {
            if (testingSaved && snapshot == settings) update { copy(account = AccountStatus.SAVED) }
            throw e
        } catch (e: Exception) {
            if (testingSaved && snapshot == settings) update { copy(account = AccountStatus.FAILED, accountError = readable(e)) }
            throw DanbooruException(readable(e))
        }
    }
    suspend fun logout(): Result<Unit> = save(settings.copy(username = "", apiKey = ""))
    suspend fun clearCache(): String {
        generation++; loadJob?.cancel(); detailJob?.cancel()
        service.clearCache()
        update { copy(loading = false, refreshing = false, appending = false, detailLoading = false) }
        return "Danbooru 在线缓存已清除，登录和系统相册保持不变"
    }
    fun consumeMessage() = update { copy(message = null) }
    private fun saveRequests() { saved["pageRequests"] = ArrayList((1..(requests.keys.maxOrNull() ?: 1)).map { requests[it] ?: "_" }) }
    private inline fun update(transform: DanbooruState.() -> DanbooruState) { _state.value = _state.value.transform() }
    private fun readable(e: Exception): String = when (e) {
        is DanbooruException, is IllegalArgumentException -> e.message ?: "Danbooru 操作失败，请重试"
        else -> "Danbooru 操作失败，请重试；原设置保持不变"
    }
}
