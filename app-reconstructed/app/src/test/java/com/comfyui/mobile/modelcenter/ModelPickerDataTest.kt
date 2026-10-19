package com.comfyui.mobile.modelcenter

import com.comfyui.mobile.data.ComfyRepository
import com.comfyui.mobile.market.CivitaiSettingsStore
import com.comfyui.mobile.market.MarketConnectionMode
import com.comfyui.mobile.model.ServerProfile
import com.comfyui.mobile.network.RhResourceItem
import com.comfyui.mobile.network.RhResourcePageData
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelPickerDataTest {
    private val hash = "a".repeat(64)
    private val raw = LocalModel(id = "location", serverId = "bridge", category = "LORA",
        relativePath = "角色/model.safetensors", name = "model.safetensors",
        nodeModelName = "角色\\model.safetensors", sha256 = hash, present = true,
        annotation = ModelAnnotation(alias = "中文画风名称", tags = listOf("柔光"),
            notes = "模型中心使用心得", trainedWords = emptyList(), fieldLocks = listOf("trainedWords")))
    private val resource = RhResourceItem(id = "native", resourceName = "model",
        nodeModelName = "角色\\model.safetensors", desc = "本地伴生说明", localModel = raw)
    private fun provider() = ModelProvider(matchedHash = hash, name = "来源名称",
        baseModel = "Anima", trainedWords = listOf("remote_trigger"), description = "版本说明",
        modelDescription = "同一份完整模型简介", modelDescriptionFetched = true,
        fetchedAt = System.currentTimeMillis(), images = listOf(ModelPreview("https://image.civitai.com/fixture.png")))

    @Test fun adapterKeepsNativePathAndUsesSharedDescriptionTriggersAndChineseAnnotation() {
        val withWords = raw.copy(annotation = raw.annotation.copy(trainedWords = listOf("manual_trigger")),
            provider = provider())
        val result = modelPickerResource(resource, withWords) { "http://computer/preview" }
        assertEquals("model", result.effectiveResourceName)
        assertEquals("角色\\model.safetensors", result.effectiveModelName)
        assertEquals("中文画风名称", result.modelAnnotationName)
        assertEquals("同一份完整模型简介\n\n版本说明", result.desc)
        assertEquals("manual_trigger", result.triggerWords)
        assertEquals("Anima", result.baseModel)
        assertEquals("https://image.civitai.com/fixture.png", result.thumbnailUrl)
        assertEquals("模型中心使用心得", result.localModel!!.annotation.notes)
        val lockedEmpty = modelPickerResource(resource, raw.copy(provider = provider())) { null }
        assertTrue(lockedEmpty.triggerWords.isNullOrBlank())
    }

    @Test fun chineseAliasFallbackDoesNotInventANameFromProviderOrPath() {
        val noName = raw.copy(annotation = ModelAnnotation(aliases = listOf("English alias", "中文备用名")),
            provider = provider())
        assertEquals("中文备用名", resource.copy(localModel = noName).modelAnnotationName)
        assertEquals("", resource.copy(localModel = noName.copy(annotation = ModelAnnotation())).modelAnnotationName)
        assertEquals("", resource.copy(localModel = null).modelAnnotationName)
        val covered = modelPickerResource(resource, noName.copy(preview = ModelPreviewInfo("local", "1"))) {
            "http://computer/exact-local-cover"
        }
        assertEquals("http://computer/exact-local-cover", covered.thumbnailUrl)
    }

    @Test fun pickerUsesModelCentersExactProfileEndpointCacheAndAccountScope() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val server = ServerProfile(id = "picker-cache", host = "one.test")
        val store = CivitaiSettingsStore(app)
        val settings = store.load()
        val scope = settings.copy(mode = MarketConnectionMode.DIRECT).cacheNamespace()
        val cache = LocalModelCache(File(app.filesDir, "local-model-cache-v1"),
            "${server.effectiveId}|${server.baseUrl}")
        cache.save(ModelCacheSnapshot("bridge", items = listOf(raw),
            phoneSources = mapOf(hash to provider().copy(cacheScope = scope))))
        val data = ModelPickerData(app, ComfyRepository.getInstance())
        try {
            val page = RhResourcePageData(records = listOf(resource))
            val same = data.decorate(server, page).records!!.single()
            assertEquals("同一份完整模型简介\n\n版本说明", same.desc)
            assertEquals("中文画风名称", same.modelAnnotationName)
            assertTrue(same.triggerWords.isNullOrBlank())
            val otherEndpoint = data.decorate(server.copy(host = "two.test"), page).records!!.single()
            assertEquals("本地伴生说明", otherEndpoint.desc)
            val otherHash = data.decorate(server, page.copy(records = listOf(
                resource.copy(localModel = raw.copy(sha256 = "b".repeat(64)))))).records!!.single()
            assertEquals("本地伴生说明", otherHash.desc)
            val wrongAccount = provider().copy(cacheScope = "different-account", fetchedAt = Long.MAX_VALUE)
            cache.savePhoneSource("bridge", wrongAccount)
            assertEquals("本地伴生说明", data.decorate(server, page).records!!.single().desc)
        } finally { data.close() }
    }

    @Test fun selectingReadsLatestModelCenterDetailAndRejectsChangedServerOrPath() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        MockWebServer().use { http ->
            val latest = AtomicReference(raw.copy(annotation = raw.annotation.copy(alias = "刚修改的中文标注"),
                provider = provider()))
            var serverId = "bridge"
            http.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body: Any = if (request.path!!.endsWith("/capabilities"))
                        ModelCapabilities(1, serverId, listOf("model-index")) else latest.get()
                    return MockResponse().setHeader("Content-Type", "application/json")
                        .setBody(Gson().toJson(ModelEnvelope(data = body)))
                }
            }
            http.start()
            val server = ServerProfile(id = "picker-detail", host = http.hostName, port = http.port.toString())
            val data = ModelPickerData(app, ComfyRepository.getInstance())
            try {
                val item = data.detail(server, resource).getOrThrow()
                assertEquals("刚修改的中文标注", item.modelAnnotationName)
                assertEquals("同一份完整模型简介\n\n版本说明", item.desc)
                assertEquals("角色\\model.safetensors", item.effectiveModelName)
                assertTrue(item.triggerWords.isNullOrBlank())
                assertEquals(2, http.requestCount)
                latest.set(latest.get().copy(relativePath = "moved/model.safetensors"))
                assertTrue(data.detail(server, resource).isFailure)
                serverId = "different-bridge"
                assertTrue(data.detail(server, resource).isFailure)
                val before = http.requestCount
                assertEquals(resource.copy(localModel = null),
                    data.detail(server, resource.copy(localModel = null)).getOrThrow())
                assertEquals(before, http.requestCount)
            } finally { data.close() }
        }
    }
}
