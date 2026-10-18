package com.comfyui.mobile.modelcenter

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelSourceLoaderTest {
    private fun hash(index: Int) = index.toString(16).padStart(64, '0')
    private fun provider(hash: String) = ModelProvider(matchedHash = hash, modelDescription = "简介",
        modelDescriptionFetched = true)
    private class Source(
        override var sourceScope: String = "account-A",
        val fetch: suspend (String, Boolean) -> ModelSourceResult
    ) : LocalModelSource {
        var route = "direct"
        override val transportScope: String get() = "$sourceScope|$route"
        override suspend fun fetchSource(sha256: String, refresh: Boolean) = fetch(sha256, refresh)
    }

    @Test fun visibleRequestsDeduplicateByHashAndNeverExceedTwoConcurrentFetches() = runTest {
        val gate = CompletableDeferred<Unit>()
        val results = mutableListOf<String>()
        var started = 0
        var active = 0
        var peak = 0
        val loader = ModelSourceLoader(this, { _, _ -> }, { h, _ -> results.add(h) }) { testScheduler.currentTime }
        loader.bind("server-A", Source { h, _ ->
            started++; active++; peak = maxOf(peak, active)
            gate.await(); active--
            ModelSourceResult(provider(h))
        })
        repeat(4) { assertTrue(loader.request(hash(it))) }
        assertFalse(loader.request(hash(0).uppercase()))
        runCurrent()
        assertEquals(2, started)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(4, results.size)
        assertEquals(2, peak)
        assertFalse(loader.request(hash(0)))
    }

    @Test fun contextSwitchRejectsLateSourceEvenWhenItIgnoresCancellation() = runTest {
        val results = mutableListOf<String>()
        val loader = ModelSourceLoader(this, { _, _ -> }, { _, p -> results.add(p.name) })
        loader.bind("A", Source { h, _ ->
            withContext(NonCancellable) { delay(1000) }
            ModelSourceResult(provider(h).copy(name = "late-A"))
        })
        loader.request(hash(1))
        runCurrent()
        loader.bind("B", Source { h, _ -> ModelSourceResult(provider(h).copy(name = "current-B")) })
        loader.request(hash(2))
        advanceUntilIdle()
        assertEquals(listOf("current-B"), results)
    }

    @Test fun failureHasCooldownAndExplicitRetryDoesNotNeedAComputer() = runTest {
        var requests = 0
        val states = mutableListOf<ModelSourceTask>()
        val loader = ModelSourceLoader(this, { _, task -> states.add(task) }, { _, _ -> }) { testScheduler.currentTime }
        loader.bind("A", Source { h, _ ->
            requests++
            if (requests == 1) throw ModelSourceException("not found", 404)
            ModelSourceResult(provider(h))
        })
        assertTrue(loader.request(hash(1)))
        advanceUntilIdle()
        assertEquals("not-found", states.last().status)
        assertFalse(loader.request(hash(1)))
        assertEquals(1, requests)
        assertTrue(loader.request(hash(1), refresh = true))
        advanceUntilIdle()
        assertEquals(2, requests)
        assertEquals("ready", states.last().status)
    }

    @Test fun incompleteIntroductionIsPublishedButRemainsRetryable() = runTest {
        var published = 0
        val states = mutableListOf<ModelSourceTask>()
        val loader = ModelSourceLoader(this, { _, task -> states.add(task) }, { _, _ -> published++ }) { testScheduler.currentTime }
        loader.bind("A", Source { h, _ ->
            ModelSourceResult(provider(h).copy(modelDescriptionFetched = false), "简介待重试")
        })
        loader.request(hash(1))
        advanceUntilIdle()
        assertEquals(1, published)
        assertEquals("partial", states.last().status)
        assertFalse(loader.request(hash(1)))
        advanceTimeBy(30_001)
        assertTrue(loader.request(hash(1)))
        advanceUntilIdle()
        assertEquals(2, published)
    }

    @Test fun changedAccountScopeClearsCooldownAndCancelsOldRequests() = runTest {
        var requests = 0
        val source = Source { h, _ -> requests++; ModelSourceResult(provider(h)) }
        val results = mutableListOf<ModelProvider>()
        val loader = ModelSourceLoader(this, { _, _ -> }, { _, p -> results.add(p) })
        loader.bind("A", source)
        loader.request(hash(1))
        advanceUntilIdle()
        source.sourceScope = "account-B"
        loader.bind("A", source)
        assertTrue(loader.request(hash(1)))
        advanceUntilIdle()
        assertEquals(2, requests)
        assertEquals(listOf("account-A", "account-B"), results.map { it.cacheScope })
    }

    @Test fun absentOrMismatchedFingerprintPublishesNoMetadata() = runTest {
        var calls = 0
        var published = 0
        val states = mutableListOf<ModelSourceTask>()
        val loader = ModelSourceLoader(this, { _, task -> states.add(task) }, { _, _ -> published++ })
        loader.bind("A", Source { _, _ -> calls++; ModelSourceResult(provider(hash(9))) })
        assertFalse(loader.request(null))
        assertFalse(loader.request("partial"))
        loader.request(hash(1))
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(0, published)
        assertEquals("error", states.last().status)
    }

    @Test fun proxyChangeResetsFailureCooldownWithoutChangingAccountCacheScope() = runTest {
        var calls = 0
        val source = Source { h, _ ->
            calls++
            if (calls == 1) throw ModelSourceException("connection failed")
            ModelSourceResult(provider(h))
        }
        val results = mutableListOf<ModelProvider>()
        val loader = ModelSourceLoader(this, { _, _ -> }, { _, p -> results.add(p) })
        loader.bind("A", source)
        loader.request(hash(1))
        advanceUntilIdle()
        assertFalse(loader.request(hash(1)))
        source.route = "new-phone-proxy"
        loader.bind("A", source)
        assertTrue(loader.request(hash(1)))
        advanceUntilIdle()
        assertEquals(2, calls)
        assertEquals("account-A", results.single().cacheScope)
    }
}
