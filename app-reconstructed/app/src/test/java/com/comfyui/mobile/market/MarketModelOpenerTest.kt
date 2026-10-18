package com.comfyui.mobile.market

import com.comfyui.mobile.model.CivitaiModelItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MarketModelOpenerTest {
    @Test fun redLinkPreservesExactModelAndVersionAndCanBeConsumedOnce() = runTest {
        val references = mutableListOf<CivitaiModelReference>()
        val opener = MarketModelOpener(backgroundScope) {
            references += it; MarketDetailResult(CivitaiModelItem(id = it.modelId))
        }
        opener.open("https://civitai.red/models/2779381/sample?modelVersionId=3130085"); runCurrent()
        assertEquals(listOf(CivitaiModelReference(2779381, 3130085)), references)
        assertEquals(2779381L, opener.state.value.model?.id)
        assertEquals(3130085L, opener.state.value.versionId)
        opener.consume()
        assertNull(opener.state.value.model)
    }

    @Test fun invalidLinksStayLocalAndExplainAcceptedInput() = runTest {
        var requests = 0
        val opener = MarketModelOpener(backgroundScope) { requests++; error("unexpected") }
        opener.open("https://other.invalid/models/1"); runCurrent()
        assertEquals(0, requests)
        assertTrue(opener.state.value.error!!.contains("模型编号"))
        assertFalse(opener.state.value.loading)
    }

    @Test fun newLookupCancelsOldOneAndLateResultNeverReopensTheOldModel() = runTest {
        val pending = CompletableDeferred<MarketDetailResult>()
        val opener = MarketModelOpener(backgroundScope) {
            if (it.modelId == 1L) try { pending.await() }
                catch (_: CancellationException) { MarketDetailResult(CivitaiModelItem(id = 1)) }
            else MarketDetailResult(CivitaiModelItem(id = it.modelId))
        }
        opener.open("1"); runCurrent()
        assertTrue(opener.state.value.loading)
        opener.open("2"); runCurrent()
        assertEquals(2L, opener.state.value.model?.id)
    }

    @Test fun permissionFailureClearsPreviousResultAndKeepsItsMessage() = runTest {
        val opener = MarketModelOpener(backgroundScope) {
            if (it.modelId == 2L) throw MarketAccessException("permission fixture", 403)
            MarketDetailResult(CivitaiModelItem(id = it.modelId))
        }
        opener.open("1"); runCurrent()
        opener.open("2"); runCurrent()
        assertNull(opener.state.value.model)
        assertEquals("permission fixture", opener.state.value.error)
        assertFalse(opener.state.value.loading)
    }
}
