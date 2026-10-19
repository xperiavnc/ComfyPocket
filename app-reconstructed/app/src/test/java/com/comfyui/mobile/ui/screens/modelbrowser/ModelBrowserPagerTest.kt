package com.comfyui.mobile.ui.screens.modelbrowser

import com.comfyui.mobile.network.RhResourceItem
import com.comfyui.mobile.network.RhResourcePageData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ModelBrowserPagerTest {
    private val unet = ModelBrowserQuery("UNET")
    private fun rows(vararg ids: Int) = ids.map {
        RhResourceItem(id = "$it", resourceName = "model-$it", nodeModelName = "folder\\model-$it.safetensors")
    }
    private fun page(vararg ids: Int, total: Int = ids.size, more: Boolean = false, pages: Int = 1) =
        Result.success(RhResourcePageData(rows(*ids), total = total, hasNext = more, pages = pages))

    @Test fun allThreePagesRemainAvailable() = runTest {
        val requests = mutableListOf<Int>()
        val pager = ModelBrowserPager(this) { _, number, _ ->
            requests += number
            val start = (number - 1) * 24
            Result.success(RhResourcePageData(rows(*(start until minOf(start + 24, 51)).toList().toIntArray()),
                total = 51, hasNext = number < 3, pages = 3))
        }
        pager.search(unet)
        runCurrent()
        repeat(2) { pager.loadNext(); runCurrent() }
        assertEquals(listOf(1, 2, 3), requests)
        assertEquals(51, pager.state.value.records.size)
        assertFalse(pager.state.value.hasNext)
    }

    @Test fun shortPagesUseExplicitPageCount() = runTest {
        val pager = ModelBrowserPager(this) { _, number, _ -> page(number, total = 3, pages = 3) }
        pager.search(unet); runCurrent()
        repeat(2) { assertTrue(pager.state.value.hasNext); pager.loadNext(); runCurrent() }
        assertEquals(3, pager.state.value.records.size)
        assertFalse(pager.state.value.hasNext)
    }

    @Test fun shortPagesWithOnlyTotalStillContinue() = runTest {
        val pager = ModelBrowserPager(this) { _, number, _ -> page(number, total = 3) }
        pager.search(unet); runCurrent()
        repeat(2) { assertTrue(pager.state.value.hasNext); pager.loadNext(); runCurrent() }
        assertEquals(3, pager.state.value.records.size)
        assertFalse(pager.state.value.hasNext)
    }

    @Test fun appendUsesSubmittedQueryAndNeverRefreshesCache() = runTest {
        val requests = mutableListOf<Triple<ModelBrowserQuery, Int, Boolean>>()
        val query = unet.copy(keyword = "Anima", baseModel = "Anima")
        val pager = ModelBrowserPager(this) { q, p, r ->
            requests += Triple(q, p, r)
            page(p, total = 2, more = p == 1)
        }
        pager.search(query, refresh = true); runCurrent()
        pager.loadNext(); runCurrent()
        assertEquals(listOf(Triple(query, 1, true), Triple(query, 2, false)), requests)
        assertEquals("folder\\model-2.safetensors", pager.state.value.records.last().effectiveModelName)
    }

    @Test fun loadingNextTwiceDoesNotSkipPage() = runTest {
        val gate = CompletableDeferred<Unit>()
        val requests = mutableListOf<Int>()
        val pager = ModelBrowserPager(this) { _, p, _ ->
            requests += p
            if (p == 2) gate.await()
            page(p, total = 2, more = p == 1)
        }
        pager.search(unet); runCurrent()
        pager.loadNext(); pager.loadNext(); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals(listOf(1, 2), requests)
    }

    @Test fun failedAppendPreservesRowsAndExplicitRetryUsesSamePage() = runTest {
        var fail = true
        val requests = mutableListOf<Int>()
        val pager = ModelBrowserPager(this) { _, p, _ ->
            requests += p
            if (p == 2 && fail) Result.failure(java.io.IOException("offline"))
            else page(p, total = 2, more = p == 1)
        }
        pager.search(unet); runCurrent()
        pager.loadNext(); runCurrent()
        assertEquals(1, pager.state.value.records.size)
        assertNotNull(pager.state.value.error)
        pager.loadNext(); runCurrent()
        assertEquals(listOf(1, 2), requests) // no automatic retry storm
        fail = false
        pager.retry(); runCurrent()
        assertEquals(listOf(1, 2, 2), requests)
        assertEquals(2, pager.state.value.records.size)
        assertNull(pager.state.value.error)
    }

    @Test fun refreshFailureKeepsExistingModelsAndCanRetry() = runTest {
        var fail = false
        val pager = ModelBrowserPager(this) { _, _, _ ->
            if (fail) Result.failure(java.io.IOException("offline")) else page(1, 2)
        }
        pager.search(unet); runCurrent()
        fail = true
        pager.refresh(); runCurrent()
        assertEquals(2, pager.state.value.records.size)
        assertFalse(pager.state.value.loading)
        fail = false
        pager.retry(); runCurrent()
        assertNull(pager.state.value.error)
    }

    @Test fun refreshReplacesSnapshotIncludingRemovals() = runTest {
        var refreshed = false
        val pager = ModelBrowserPager(this) { _, _, refresh ->
            refreshed = refresh
            if (refresh) page(2, 3) else page(1, 2)
        }
        pager.search(unet); runCurrent()
        pager.refresh(); runCurrent()
        assertTrue(refreshed)
        assertEquals(listOf("2", "3"), pager.state.value.records.map { it.effectiveId })
    }

    @Test fun oldSearchIgnoringCancellationCannotOverwriteNewType() = runTest {
        val gate = CompletableDeferred<Unit>()
        val pager = ModelBrowserPager(this) { q, _, _ ->
            if (q.resourceType == "UNET") withContext(NonCancellable) { gate.await() }
            if (q.resourceType == "UNET") page(1) else page(2)
        }
        pager.search(unet); runCurrent()
        pager.search(ModelBrowserQuery("LORA")); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals("LORA", pager.state.value.query!!.resourceType)
        assertEquals(listOf("2"), pager.state.value.records.map { it.effectiveId })
    }

    @Test fun newQueryClearsOldRowsImmediatelyAndFailureDoesNotRestoreThem() = runTest {
        val pager = ModelBrowserPager(this) { q, _, _ ->
            if (q.resourceType == "UNET") page(1) else Result.failure(java.io.IOException("offline"))
        }
        pager.search(unet); runCurrent()
        pager.search(ModelBrowserQuery("LORA"))
        assertTrue(pager.state.value.records.isEmpty())
        runCurrent()
        assertTrue(pager.state.value.records.isEmpty())
    }

    @Test fun repeatedPageStopsAutoloadAndExposesRetryableError() = runTest {
        val pager = ModelBrowserPager(this) { _, _, _ -> page(1, more = true, total = 10) }
        pager.search(unet); runCurrent()
        pager.loadNext(); runCurrent()
        assertEquals(1, pager.state.value.records.size)
        assertFalse(pager.state.value.hasNext)
        assertNotNull(pager.state.value.error)
    }

    @Test fun overlappingPagesAreDeduplicatedWithoutLosingNewModels() = runTest {
        val pager = ModelBrowserPager(this) { _, p, _ ->
            if (p == 1) page(1, 2, more = true, total = 3) else page(2, 3)
        }
        pager.search(unet); runCurrent()
        pager.loadNext(); runCurrent()
        assertEquals(listOf("1", "2", "3"), pager.state.value.records.map { it.effectiveId })
    }

    @Test fun closedServerSessionDiscardsLateReply() = runTest {
        val gate = CompletableDeferred<Unit>()
        val pager = ModelBrowserPager(this) { _, _, _ ->
            withContext(NonCancellable) { gate.await() }
            page(1)
        }
        pager.search(unet); runCurrent()
        pager.close()
        gate.complete(Unit); runCurrent()
        assertTrue(pager.state.value.records.isEmpty())
    }
}
