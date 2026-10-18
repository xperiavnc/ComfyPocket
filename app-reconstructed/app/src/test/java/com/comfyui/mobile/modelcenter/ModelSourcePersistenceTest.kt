package com.comfyui.mobile.modelcenter

import android.os.Looper
import androidx.lifecycle.ViewModelStore
import com.google.gson.Gson
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class ModelSourcePersistenceTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val directory get() = File(app.filesDir, "local-model-cache-v1")
    private val hash = "a".repeat(64)
    private fun item(id: String = "file") = LocalModel(id = id, serverId = "bridge-fixture",
        name = "not-the-provider-name.safetensors", sha256 = hash, present = true,
        annotation = ModelAnnotation(alias = "我的中文名称", notes = "我的使用心得",
            trainedWords = emptyList(), fieldLocks = listOf("trainedWords")))
    private class Source(override var sourceScope: String = "account-fixture") : LocalModelSource {
        var calls = 0
        override suspend fun fetchSource(sha256: String, refresh: Boolean): ModelSourceResult {
            calls++
            return ModelSourceResult(ModelProvider(provider = "civitai", matchedHash = sha256,
                modelId = 11, versionId = 12, fileId = 13, name = "精确来源名称",
                description = "版本说明", modelDescription = "这是手机获取的模型简介", modelDescriptionFetched = true,
                trainedWords = listOf("provider_trigger"), fetchedAt = System.currentTimeMillis()))
        }
    }
    private fun await(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("ViewModel did not settle", condition())
    }
    private fun store(vm: ModelCenterViewModel) = ViewModelStore().apply { put("fixture", vm) }

    @Test fun offlinePhoneFetchIsSavedRestoredAndDoesNotOverwriteManualDataOrConfirmFilePresence() {
        val identity = "source-offline-restart"
        LocalModelCache(directory, identity).save(ModelCacheSnapshot(
            bridgeServerId = "bridge-fixture", items = listOf(item())))
        val source = Source()
        val vm = ModelCenterViewModel(app)
        val holder = store(vm)
        try {
            vm.source(source)
            vm.context(identity, "离线电脑", null, false)
            await { vm.state.value.restored }
            vm.openDetail("file")
            await { vm.state.value.detail?.provider?.modelDescriptionFetched == true }
            assertEquals("这是手机获取的模型简介", vm.state.value.detail!!.provider!!.modelDescription)
            assertEquals("我的中文名称", vm.state.value.detail!!.displayName)
            assertEquals("我的使用心得", vm.state.value.detail!!.annotation.notes)
            assertTrue("Explicitly locked empty trigger words must remain empty", vm.state.value.detail!!.triggerWords.isEmpty())
            assertTrue(vm.state.value.fromCache)
            await { LocalModelCache(directory, identity).load().phoneSources[hash]?.modelDescriptionFetched == true }
            val saved = LocalModelCache(directory, identity).load()
            assertNull("Raw computer index and phone overlay must stay separate", saved.items.single().provider)
        } finally { holder.clear() }
        val restarted = ModelCenterViewModel(app)
        val restartedHolder = store(restarted)
        try {
            restarted.source(source)
            restarted.context(identity, "离线电脑", null, false)
            await { restarted.state.value.restored }
            assertEquals("这是手机获取的模型简介", restarted.state.value.page.items.single().provider!!.modelDescription)
            restarted.openDetail("file")
            assertEquals("Fresh saved metadata should not be refetched on every detail open", 1, source.calls)
            println("MODEL_SOURCE_PERSIST description restored; manual alias/notes/empty-word-lock preserved; computer state remains cached")
        } finally { restartedHolder.clear() }
    }

    @Test fun computerRefreshAndDetailDoNotErasePhoneIntroductionButChangedFileHashDoes() {
        MockWebServer().use { server ->
            val raw = AtomicReference(item())
            val gson = Gson()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val data: Any = when {
                        request.path!!.endsWith("/capabilities") -> ModelCapabilities(1, "bridge-fixture", listOf("model-index"))
                        request.path!!.contains("/models/file") -> raw.get()
                        else -> ModelPage(serverId = "bridge-fixture", items = listOf(raw.get()))
                    }
                    return MockResponse().setHeader("Content-Type", "application/json")
                        .setBody(gson.toJson(ModelEnvelope(data = data)))
                }
            }
            val vm = ModelCenterViewModel(app)
            val holder = store(vm)
            try {
                vm.source(Source())
                vm.context("source-bridge-refresh", "电脑", server.url("/").toString(), true)
                await { !vm.state.value.loading && !vm.state.value.fromCache }
                vm.enrich(listOf("file"))
                await { vm.state.value.page.items.single().provider?.modelDescriptionFetched == true }
                vm.refresh()
                await { !vm.state.value.loading }
                assertEquals("这是手机获取的模型简介", vm.state.value.page.items.single().provider!!.modelDescription)
                vm.openDetail("file")
                await { !vm.state.value.detailLoading }
                assertEquals("这是手机获取的模型简介", vm.state.value.detail!!.provider!!.modelDescription)
                raw.set(item().copy(sha256 = "b".repeat(64), annotation = ModelAnnotation(alias = "电脑最新标注")))
                vm.refresh()
                await { vm.state.value.page.items.singleOrNull()?.sha256 == "b".repeat(64) }
                assertNull("A reused path with new bytes must not keep the old introduction", vm.state.value.page.items.single().provider)
                assertEquals("电脑最新标注", vm.state.value.page.items.single().annotation.alias)
                assertNull(vm.state.value.detail!!.provider)
                assertFalse(server.takeRequest().requestLine.contains("/enrichment"))
            } finally { holder.clear() }
        }
    }

    @Test fun accountScopeSwitchHidesPreviousPhoneMetadataAndNeverTouchesManualAnnotation() {
        val identity = "source-account-switch"
        LocalModelCache(directory, identity).save(ModelCacheSnapshot(items = listOf(item())))
        val source = Source()
        val vm = ModelCenterViewModel(app)
        val holder = store(vm)
        try {
            vm.source(source)
            vm.context(identity, "电脑", null, false)
            await { vm.state.value.restored }
            vm.requestSource("file")
            await { vm.state.value.page.items.single().provider != null }
            source.sourceScope = "different-key-scope"
            vm.source(source)
            assertNull(vm.state.value.page.items.single().provider)
            assertEquals("我的中文名称", vm.state.value.page.items.single().annotation.alias)
            vm.requestSource("file")
            await { vm.state.value.page.items.single().provider?.cacheScope == "different-key-scope" }
            assertEquals(2, source.calls)
        } finally { holder.clear() }
    }

    @Test fun missingFingerprintDoesNotIssuePhoneOrComputerEnrichmentRequests() {
        LocalModelCache(directory, "source-no-hash").save(ModelCacheSnapshot(items = listOf(item().copy(sha256 = null))))
        val source = Source()
        val vm = ModelCenterViewModel(app)
        val holder = store(vm)
        try {
            vm.source(source)
            vm.context("source-no-hash", "电脑", null, false)
            await { vm.state.value.restored }
            assertFalse(vm.requestSource("file"))
            vm.enrich(listOf("file"))
            assertEquals(0, source.calls)
            assertTrue(vm.state.value.notice!!.contains("SHA-256"))
        } finally { holder.clear() }
    }

    @Test fun overlayCannotCrossHashOrAccountAndResolvedCacheQueriesUsePhoneFields() {
        val p = ModelProvider(matchedHash = hash, cacheScope = "A", name = "只在手机资料里的名称",
            baseModel = "Anima", modelDescription = "模型简介", modelDescriptionFetched = true)
        val snapshot = ModelCacheSnapshot(items = listOf(item()), phoneSources = mapOf(hash to p))
        assertNull(item().withPhoneSource(snapshot.phoneSources, "B").provider)
        assertNull(item().copy(sha256 = "b".repeat(64)).withPhoneSource(snapshot.phoneSources, "A").provider)
        val resolved = snapshot.withPhoneSources("A")
        assertEquals(1, LocalModelCache.page(resolved, LocalModelQuery(q = "只在手机"), 1).total)
        assertEquals(1, LocalModelCache.page(resolved, LocalModelQuery(source = "matched"), 1).total)
        assertEquals("我的中文名称", resolved.items.single().displayName)
    }
}
