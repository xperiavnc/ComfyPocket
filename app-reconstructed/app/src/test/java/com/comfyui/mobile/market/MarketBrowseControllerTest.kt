package com.comfyui.mobile.market

import com.comfyui.mobile.model.CivitaiContentChannel
import com.comfyui.mobile.model.CivitaiContentChannel.*
import com.comfyui.mobile.model.CivitaiModelItem
import com.comfyui.mobile.model.CivitaiSearchPageData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MarketBrowseControllerTest {
    private fun result(vararg ids: Long, cursor: String? = null) = MarketSearchResult(
        CivitaiSearchPageData(items = ids.map { CivitaiModelItem(id = it) }, nextCursor = cursor))

    @Test fun enabledMatureBrowsingStillQueriesOrdinaryAndDeduplicatesWithoutChangingFlags() = runTest {
        val requests = mutableListOf<MarketQuery>()
        val catalog = MarketBrowseController(backgroundScope, true) { query, _, _, _ ->
            requests += query
            if (query.channel == STANDARD) result(2779381, 2) else result(2, 3)
        }
        catalog.author("juluoye"); catalog.filter(baseModel = "Anima"); runCurrent()
        assertEquals(setOf(STANDARD, MATURE), requests.map { it.channel }.toSet())
        assertTrue(requests.all { it.author == "juluoye" && it.baseModel == "Anima" })
        val sections = catalog.state.value.sections
        assertEquals(listOf(2779381L, 2L), sections[0].state.items.map { it.id })
        assertEquals(listOf(3L), sections[1].state.items.map { it.id })
        println("MARKET_DUAL enabled=true ordinary=[2779381,2] additional=[3] duplicate=2")
    }

    @Test fun eachSectionKeepsItsOwnCursorAndExhaustionDoesNotStopTheOther() = runTest {
        val calls = mutableListOf<Triple<CivitaiContentChannel, Int, String?>>()
        val catalog = MarketBrowseController(backgroundScope, true) { query, page, cursor, _ ->
            calls += Triple(query.channel, page, cursor)
            if (query.channel == STANDARD) result(1)
            else if (page == 1) result(1, cursor = "adult-only") else result(3)
        }
        catalog.load(); runCurrent()
        assertFalse(catalog.state.value.sections[0].state.hasNextPage)
        assertTrue(catalog.state.value.sections[1].state.hasNextPage)
        assertTrue(catalog.state.value.sections[1].state.items.isEmpty())
        catalog.loadMore(MATURE); runCurrent()
        assertEquals(Triple(MATURE, 2, "adult-only"), calls.last())
        assertEquals(3, calls.size)
        assertEquals(listOf(1L, 3L), catalog.state.value.items.map { it.id })
    }

    @Test fun sectionFailureAndRetryAreIndependentAndDoNotDiscardTheOtherSection() = runTest {
        var fail = true
        val requests = mutableListOf<CivitaiContentChannel>()
        val catalog = MarketBrowseController(backgroundScope, true) { query, _, _, _ ->
            requests += query.channel
            if (query.channel == MATURE && fail) throw MarketAccessException("retry fixture", 503)
            result(if (query.channel == STANDARD) 1 else 2)
        }
        catalog.load(); runCurrent()
        assertEquals(listOf(1L), catalog.state.value.items.map { it.id })
        assertEquals("retry fixture", catalog.state.value.sections[1].state.error)
        fail = false
        catalog.retry(MATURE); runCurrent()
        assertEquals(listOf(STANDARD, MATURE, MATURE), requests)
        assertEquals(listOf(1L, 2L), catalog.state.value.items.map { it.id })
    }

    @Test fun disabledMatureSectionDoesNotRequestEvenWhenFiltersChange() = runTest {
        val requests = mutableListOf<MarketQuery>()
        val catalog = MarketBrowseController(backgroundScope, false) { query, _, _, _ ->
            requests += query; result(1)
        }
        catalog.author("fixture"); catalog.filter(baseModel = "Anima"); runCurrent()
        catalog.ordering(MarketSort.LIKES, MarketPeriod.MONTH); runCurrent()
        assertTrue(requests.all { it.channel == STANDARD })
        catalog.invalidate(includeMature = true); catalog.ensureLoaded(); runCurrent()
        assertEquals(setOf(STANDARD, MATURE), requests.takeLast(2).map { it.channel }.toSet())
        assertTrue(requests.takeLast(2).all { it.author == "fixture" &&
            it.baseModel == "Anima" && it.sort == MarketSort.LIKES && it.period == MarketPeriod.MONTH })
    }

    @Test fun disablingCancelsInFlightAdultRequestAndLateResultsStayHidden() = runTest {
        val pending = CompletableDeferred<MarketSearchResult>()
        val catalog = MarketBrowseController(backgroundScope, true) { query, _, _, _ ->
            if (query.channel == STANDARD) result(1)
            else try { pending.await() } catch (_: CancellationException) { result(99) }
        }
        catalog.load(); runCurrent()
        catalog.invalidate(false); catalog.ensureLoaded(); runCurrent()
        assertFalse(catalog.state.value.matureEnabled)
        assertEquals(1, catalog.state.value.sections.size)
        assertEquals(listOf(1L), catalog.state.value.items.map { it.id })
    }

    @Test fun draftsDoNotChangeEitherSubmittedPaginationQueryAndRefreshRestartsBoth() = runTest {
        val calls = mutableListOf<Triple<MarketQuery, Int, Boolean>>()
        val catalog = MarketBrowseController(backgroundScope, true) { query, page, _, refresh ->
            calls += Triple(query, page, refresh)
            result(if (query.channel == STANDARD) 1 else 2, cursor = "${query.channel}-next")
        }
        catalog.keyword("submitted"); catalog.load(); runCurrent()
        catalog.keyword("draft")
        catalog.loadMore(STANDARD); catalog.loadMore(MATURE); runCurrent()
        assertTrue(calls.all { it.first.keyword == "submitted" })
        assertEquals(listOf(1, 1, 2, 2), calls.map { it.second })
        catalog.load(forceRefresh = true); runCurrent()
        assertTrue(calls.takeLast(2).all { it.first.keyword == "draft" && it.second == 1 && it.third })
    }
}
