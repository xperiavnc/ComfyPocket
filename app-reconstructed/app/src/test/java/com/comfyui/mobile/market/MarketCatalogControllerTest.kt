package com.comfyui.mobile.market

import com.comfyui.mobile.model.CivitaiModelItem
import com.comfyui.mobile.model.CivitaiSearchPageData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MarketCatalogControllerTest {
    private fun result(vararg ids: Long, cursor: String? = null, totalPages: Int = 1) =
        MarketSearchResult(CivitaiSearchPageData(
            items = ids.map { CivitaiModelItem(id = it, name = "model-$it") },
            nextCursor = cursor, totalPages = totalPages
        ))

    @Test fun emptyPageWithCursorStillAllowsBrowsingTheNextPage() = runTest {
        val catalog = MarketCatalogController(this) { _, page, _, _ ->
            if (page == 1) result(cursor = "next") else result(2)
        }
        catalog.load(); advanceUntilIdle()
        assertTrue(catalog.state.value.items.isEmpty())
        assertNull(catalog.state.value.error)
        assertTrue(catalog.state.value.hasNextPage)
        catalog.load(reset = false); advanceUntilIdle()
        assertEquals(listOf(2L), catalog.state.value.items.map { it.id })
        assertEquals(2, catalog.state.value.loadedPage)
    }

    @Test fun cursorCycleStopsBrowsingEvenWithLegacyTotalPages() = runTest {
        var calls = 0
        val catalog = MarketCatalogController(this) { _, _, _, _ ->
            calls++
            result(calls.toLong(), cursor = if (calls == 2) "B" else "A", totalPages = 100)
        }
        repeat(3) {
            catalog.load(reset = it == 0); advanceUntilIdle()
        }
        assertFalse(catalog.state.value.hasNextPage)
        catalog.load(reset = false); advanceUntilIdle()
        assertEquals(3, calls)
    }

    @Test fun refreshResetsCursorHistoryAndCanBrowseAgain() = runTest {
        val catalog = MarketCatalogController(this) { _, _, _, _ -> result(1, cursor = "same") }
        catalog.load(); advanceUntilIdle()
        catalog.load(reset = false); advanceUntilIdle()
        assertFalse(catalog.state.value.hasNextPage)
        catalog.load(forceRefresh = true); advanceUntilIdle()
        assertTrue(catalog.state.value.hasNextPage)
    }

    @Test fun firstLoadDoesNotRequireAnyComfyServerAndEnsureLoadedDoesNotReload() = runTest {
        var calls = 0
        val catalog = MarketCatalogController(this) { _, _, _, _ -> calls++; result(1) }
        catalog.ensureLoaded()
        advanceUntilIdle()
        catalog.ensureLoaded()
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(listOf(1L), catalog.state.value.items.map { it.id })
    }

    @Test fun aNewSearchCancelsTheOldRequestAndOldResultsDoNotWin() = runTest {
        val pending = CompletableDeferred<MarketSearchResult>()
        var cancelled = false
        val catalog = MarketCatalogController(this) { query, _, _, _ ->
            if (query.keyword == "old") try { pending.await() }
            catch (_: CancellationException) { cancelled = true; result(99) }
            else result(2)
        }
        catalog.keyword("old")
        catalog.load()
        runCurrent()
        catalog.keyword("new")
        catalog.load()
        advanceUntilIdle()
        assertTrue(cancelled)
        assertEquals(listOf(2L), catalog.state.value.items.map { it.id })
        assertFalse(catalog.state.value.loading)
    }

    @Test fun paginationDeduplicatesAndUsesTheSubmittedQueryNotDraftInput() = runTest {
        val queries = mutableListOf<String>()
        val pages = mutableListOf<Int>()
        val cursors = mutableListOf<String?>()
        val catalog = MarketCatalogController(this) { query, page, cursor, _ ->
            queries += query.keyword; pages += page; cursors += cursor
            if (page == 1) result(1, 2, cursor = "opaque|cursor") else result(2, 3)
        }
        catalog.keyword("submitted")
        catalog.load()
        advanceUntilIdle()
        catalog.keyword("not submitted yet")
        catalog.load(reset = false)
        advanceUntilIdle()
        assertEquals(listOf("submitted", "submitted"), queries)
        assertEquals(listOf(1, 2), pages)
        assertEquals(listOf(null, "opaque|cursor"), cursors)
        assertEquals(listOf(1L, 2L, 3L), catalog.state.value.items.map { it.id })
        assertEquals("not submitted yet", catalog.state.value.keyword)
    }

    @Test fun duplicateLoadMoreClicksProduceOnlyOneRequest() = runTest {
        val pending = CompletableDeferred<MarketSearchResult>()
        var calls = 0
        val catalog = MarketCatalogController(this) { _, page, _, _ ->
            calls++
            if (page == 1) result(1, cursor = "next") else pending.await()
        }
        catalog.load(); advanceUntilIdle()
        catalog.load(reset = false); runCurrent()
        catalog.load(reset = false); runCurrent()
        assertEquals(2, calls)
        pending.complete(result(2)); advanceUntilIdle()
    }

    @Test fun repeatingCursorAndTotalPagesDoNotLoopKeywordSearch() = runTest {
        val catalog = MarketCatalogController(this) { _, _, _, _ -> result(1, cursor = "same", totalPages = 100) }
        catalog.keyword("search"); catalog.load(); advanceUntilIdle()
        catalog.load(reset = false); advanceUntilIdle()
        assertFalse(catalog.state.value.hasNextPage)
    }

    @Test fun keywordSearchWithoutACursorStopsEvenWithLegacyTotalPages() = runTest {
        val catalog = MarketCatalogController(this) { _, _, _, _ -> result(1, totalPages = 100) }
        catalog.keyword("search"); catalog.load(); advanceUntilIdle()
        assertFalse(catalog.state.value.hasNextPage)
    }

    @Test fun pageFailureRetainsCardsAndRetryUsesTheSamePageAndCursor() = runTest {
        val requests = mutableListOf<Pair<Int, String?>>()
        var failed = false
        val catalog = MarketCatalogController(this) { _, page, cursor, _ ->
            requests += page to cursor
            if (page == 1) result(1, cursor = "page-two")
            else if (!failed) { failed = true; throw MarketAccessException("connection failed") }
            else result(2)
        }
        catalog.load(); advanceUntilIdle()
        catalog.load(reset = false); advanceUntilIdle()
        assertEquals(listOf(1L), catalog.state.value.items.map { it.id })
        assertNotNull(catalog.state.value.error)
        catalog.retry(); advanceUntilIdle()
        assertEquals(listOf(1 to null, 2 to "page-two", 2 to "page-two"), requests)
        assertEquals(listOf(1L, 2L), catalog.state.value.items.map { it.id })
    }

    @Test fun switchingFiltersClearsOldCardsWhileLoading() = runTest {
        val pending = CompletableDeferred<MarketSearchResult>()
        val catalog = MarketCatalogController(this) { query, _, _, _ ->
            if (query.baseModel == "全部") result(1) else pending.await()
        }
        catalog.load(); advanceUntilIdle()
        catalog.filter(baseModel = "SDXL"); runCurrent()
        assertTrue(catalog.state.value.items.isEmpty())
        pending.complete(result(2)); advanceUntilIdle()
        assertEquals("SDXL", catalog.state.value.baseModel)
    }

    @Test fun changingAccountInvalidatesCardsAndPagination() = runTest {
        val catalog = MarketCatalogController(this) { _, _, _, _ -> result(1, cursor = "next") }
        catalog.load(); advanceUntilIdle()
        catalog.invalidate()
        assertTrue(catalog.state.value.items.isEmpty())
        assertFalse(catalog.state.value.hasNextPage)
        assertFalse(catalog.state.value.loading)
    }

    @Test fun permissionFailureIsNotHiddenBehindOldCards() = runTest {
        var deny = false
        val catalog = MarketCatalogController(this) { _, _, _, _ ->
            if (deny) throw MarketAccessException("permission denied", 403) else result(1)
        }
        catalog.load(); advanceUntilIdle()
        deny = true
        catalog.load(forceRefresh = true); advanceUntilIdle()
        assertTrue(catalog.state.value.items.isEmpty())
        assertEquals("permission denied", catalog.state.value.error)
    }

    @Test fun cachedResultsAreLabelledAndRefreshBypassesTheCache() = runTest {
        val refreshFlags = mutableListOf<Boolean>()
        val catalog = MarketCatalogController(this) { _, _, _, refresh ->
            refreshFlags += refresh
            result(1).copy(fromCache = !refresh)
        }
        catalog.load(); advanceUntilIdle()
        assertNotNull(catalog.state.value.notice)
        catalog.load(forceRefresh = true); advanceUntilIdle()
        assertNull(catalog.state.value.notice)
        assertEquals(listOf(false, true), refreshFlags)
    }

    @Test fun anEmptyResponseHasAUsefulMessage() = runTest {
        val catalog = MarketCatalogController(this) { _, _, _, _ -> result() }
        catalog.load(); advanceUntilIdle()
        assertTrue(catalog.state.value.error.orEmpty().contains("未找到"))
        assertFalse(catalog.state.value.loading)
    }

    @Test fun authorSortAndPeriodAreSubmittedTogetherAndRetainedForPagination() = runTest {
        val requests = mutableListOf<MarketQuery>()
        val catalog = MarketCatalogController(this) { query, page, _, _ ->
            requests += query
            if (page == 1) result(1, cursor = "author-next") else result(2)
        }
        catalog.author(" creator ")
        catalog.ordering(MarketSort.LIKES, MarketPeriod.MONTH)
        advanceUntilIdle()
        catalog.author("unsent-draft")
        catalog.load(reset = false); advanceUntilIdle()
        assertEquals(2, requests.size)
        assertEquals(requests[0], requests[1])
        assertEquals("creator", requests[0].author)
        assertEquals(MarketSort.LIKES, requests[0].sort)
        assertEquals(MarketPeriod.MONTH, requests[0].period)
        assertNull(catalog.state.value.notice)
    }

    @Test fun textSearchOrderingMergesPagesAndExplainsItsLoadedOnlyBoundary() = runTest {
        val catalog = MarketCatalogController(this) { _, page, _, _ ->
            val items = if (page == 1) listOf(
                CivitaiModelItem(id = 1, name = "first", downloadCount = 10),
                CivitaiModelItem(id = 2, name = "second", downloadCount = 90))
            else listOf(CivitaiModelItem(id = 3, name = "third", downloadCount = 50))
            MarketSearchResult(CivitaiSearchPageData(items = items,
                nextCursor = if (page == 1) "more" else null))
        }
        catalog.keyword("sample"); catalog.ordering(MarketSort.DOWNLOADS)
        advanceUntilIdle()
        assertEquals(listOf(2L, 1L), catalog.state.value.items.map { it.id })
        assertTrue(catalog.state.value.notice.orEmpty().contains("仅覆盖已加载结果"))
        catalog.load(reset = false); advanceUntilIdle()
        assertEquals(listOf(2L, 3L, 1L), catalog.state.value.items.map { it.id })
        assertFalse(catalog.state.value.hasNextPage)
    }

    @Test fun loadedTextMatchesCanBeOrderedByDateAndLikesWithoutChangingDefaultRelevance() = runTest {
        val catalog = MarketCatalogController(this) { _, _, _, _ ->
            MarketSearchResult(CivitaiSearchPageData(items = listOf(
                CivitaiModelItem(id = 1, name = "a", thumbsUpCount = 30, publishedAt = "2026-10-01"),
                CivitaiModelItem(id = 2, name = "b", thumbsUpCount = 10, publishedAt = "2026-10-17"))))
        }
        catalog.keyword("sample"); catalog.ordering(MarketSort.NEWEST); advanceUntilIdle()
        assertEquals(listOf(2L, 1L), catalog.state.value.items.map { it.id })
        catalog.ordering(MarketSort.LIKES); advanceUntilIdle()
        assertEquals(listOf(1L, 2L), catalog.state.value.items.map { it.id })
        catalog.ordering(MarketSort.DEFAULT); advanceUntilIdle()
        assertEquals(listOf(1L, 2L), catalog.state.value.items.map { it.id })
        assertNull(catalog.state.value.notice)
    }

    @Test fun modelIdLookupClearsStaleCategoryAndAuthorButPreservesContentSettingsInRepository() = runTest {
        val requests = mutableListOf<MarketQuery>()
        val catalog = MarketCatalogController(this) { query, _, _, _ ->
            requests += query
            result(123)
        }
        catalog.filter(type = "LORA", baseModel = "SDXL"); advanceUntilIdle()
        catalog.author("wrong-author")
        catalog.keyword("https://civitai.com/models/123/sample")
        catalog.load(); advanceUntilIdle()
        assertEquals("ALL", requests.last().type)
        assertEquals("全部", requests.last().baseModel)
        assertEquals("", requests.last().author)
        assertEquals(listOf(123L), catalog.state.value.items.map { it.id })
    }

    @Test fun clearFiltersRetainsKeywordAndOrderingAndStartsAtFirstPage() = runTest {
        val requests = mutableListOf<Triple<MarketQuery, Int, String?>>()
        val catalog = MarketCatalogController(this) { query, page, cursor, _ ->
            requests += Triple(query, page, cursor); result(1, cursor = "next")
        }
        catalog.keyword("keep-name")
        catalog.filter(type = "LORA", baseModel = "SDXL"); advanceUntilIdle()
        catalog.author("author")
        catalog.ordering(MarketSort.LIKES, MarketPeriod.YEAR); advanceUntilIdle()
        catalog.clearFilters(); advanceUntilIdle()
        val (query, page, cursor) = requests.last()
        assertEquals(MarketQuery("keep-name", sort = MarketSort.LIKES, period = MarketPeriod.YEAR), query)
        assertEquals(1, page)
        assertNull(cursor)
    }
}
