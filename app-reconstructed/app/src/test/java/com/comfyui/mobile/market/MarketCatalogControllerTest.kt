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
}
