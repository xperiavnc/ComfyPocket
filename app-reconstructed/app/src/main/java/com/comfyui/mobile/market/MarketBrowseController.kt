package com.comfyui.mobile.market

import com.comfyui.mobile.model.CivitaiContentChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Independent requests/cursors/retries; ordinary records win duplicate IDs, never their ratings. */
class MarketBrowseController(
    scope: CoroutineScope,
    includeMature: Boolean,
    search: suspend (MarketQuery, Int, String?, Boolean) -> MarketSearchResult
) {
    private val enabled = MutableStateFlow(includeMature)
    private val standard = MarketCatalogController(scope) { query, page, cursor, refresh ->
        search(query.copy(channel = CivitaiContentChannel.STANDARD), page, cursor, refresh)
    }
    private val mature = MarketCatalogController(scope) { query, page, cursor, refresh ->
        search(query.copy(channel = CivitaiContentChannel.MATURE), page, cursor, refresh)
    }
    val state = combine(standard.state, mature.state, enabled) { normal, adult, showAdult ->
        val ordinaryIds = normal.items.mapTo(hashSetOf()) { it.id }
        val extra = adult.copy(items = adult.items.filterNot { it.id in ordinaryIds })
        val sections = listOf(MarketCatalogSection(CivitaiContentChannel.STANDARD, normal)) +
            if (showAdult) listOf(MarketCatalogSection(CivitaiContentChannel.MATURE, extra)) else emptyList()
        normal.copy(
            items = sections.flatMap { it.state.items },
            loading = sections.any { it.state.loading },
            hasNextPage = sections.any { it.state.hasNextPage },
            loadedPage = sections.sumOf { it.state.loadedPage },
            error = if (showAdult) null else normal.error,
            notice = if (showAdult) null else normal.notice,
            sections = sections, matureEnabled = showAdult
        )
    }.stateIn(scope, SharingStarted.Eagerly, MarketCatalogState(matureEnabled = includeMature))

    fun keyword(value: String) { standard.keyword(value); mature.keyword(value) }
    fun author(value: String) { standard.author(value); mature.author(value) }
    fun filter(type: String = standard.state.value.type, baseModel: String = standard.state.value.baseModel) {
        standard.filter(type, baseModel)
        mature.filter(type, baseModel, reload = enabled.value)
    }
    fun ordering(sort: MarketSort = standard.state.value.sort, period: MarketPeriod = standard.state.value.period) {
        standard.ordering(sort, period)
        mature.ordering(sort, period, reload = enabled.value)
    }
    fun clearFilters() { standard.clearFilters(); mature.clearFilters(reload = enabled.value) }
    fun ensureLoaded() { standard.ensureLoaded(); if (enabled.value) mature.ensureLoaded() }
    fun invalidate(includeMature: Boolean = enabled.value) {
        enabled.value = includeMature
        standard.invalidate()
        mature.invalidate()
    }
    fun load(reset: Boolean = true, forceRefresh: Boolean = false) {
        standard.load(reset, forceRefresh)
        if (enabled.value) mature.load(reset, forceRefresh)
    }
    fun loadMore(channel: CivitaiContentChannel) {
        if (channel == CivitaiContentChannel.STANDARD) standard.load(reset = false)
        else if (enabled.value) mature.load(reset = false)
    }
    fun retry(channel: CivitaiContentChannel? = null) {
        if (channel == null || channel == CivitaiContentChannel.STANDARD) standard.retry()
        if (enabled.value && (channel == null || channel == CivitaiContentChannel.MATURE)) mature.retry()
    }
}
