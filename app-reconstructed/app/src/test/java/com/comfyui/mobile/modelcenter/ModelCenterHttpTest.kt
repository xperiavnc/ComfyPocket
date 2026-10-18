package com.comfyui.mobile.modelcenter

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelCenterHttpTest {
    @get:Rule val temporary = TemporaryFolder()
    private val gson = Gson()
    private fun json(data: Any) = MockResponse().setHeader("Content-Type", "application/json")
        .setBody(gson.toJson(ModelEnvelope(code = 0, data = data)))
    private fun repository(server: MockWebServer) =
        LocalModelRepository(temporary.newFolder(), "fixture", server.url("/comfy/").toString(), OkHttpClient())

    @Test fun capabilityNegotiationAndUnicodePaginationUseVersionedReadOnlyContract() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json(ModelCapabilities(1, "server", listOf("model-index"))))
            server.enqueue(json(ModelPage(serverId = "server", items = listOf(LocalModel(id = "a")))))
            val repo = repository(server)
            assertTrue(repo.capabilities().supported)
            assertEquals("a", repo.models(LocalModelQuery(q = "中文角色", useCase = "clothing"), 2).items.single().id)
            assertEquals("/comfy/api/mobile/model-center/capabilities", server.takeRequest().path)
            val query = server.takeRequest()
            assertEquals("GET", query.method)
            assertEquals("中文角色", query.requestUrl!!.queryParameter("q"))
            assertEquals("2", query.requestUrl!!.queryParameter("page"))
            assertEquals("40", query.requestUrl!!.queryParameter("pageSize"))
            assertFalse(query.path!!.contains("/prompt"))
        }
    }

    @Test fun oldBridgeAndFutureSchemaGetSpecificUpgradeMessages() = runBlocking {
        MockWebServer().use { server ->
            val repo = repository(server)
            server.enqueue(MockResponse().setResponseCode(404).setBody("old bridge"))
            assertTrue(runCatching { repo.capabilities() }.exceptionOrNull()!!.message!!.contains("Bridge 更新包"))
            server.enqueue(json(ModelCapabilities(99, "future", listOf("model-index"))))
            assertTrue(runCatching { repo.capabilities() }.isFailure)
        }
    }

    @Test fun annotationCarriesExpectedRevisionAndPreservesExactTriggerSpelling() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json(LocalModel(id = "a", revision = 8)))
            val repo = repository(server)
            repo.annotate(LocalModel(id = "a", revision = 7), ModelAnnotation(trainedWords = listOf("UPPER_case (A)")))
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/comfy/api/mobile/model-center/models/a/annotation", request.path)
            val body = gson.fromJson(request.body.readUtf8(), AnnotationWrite::class.java)
            assertEquals(7, body.expectedRevision)
            assertEquals(listOf("UPPER_case (A)"), body.annotation.trainedWords)
        }
    }

    @Test fun metadataQueueIsSeparateFromWeightQueueAndDoesNotTransmitKeys() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json(ModelEnrichmentResult()))
            server.enqueue(json(ModelTasks()))
            val repo = repository(server)
            repo.enrich(listOf("a", "b"), true)
            repo.tasks()
            val enrich = server.takeRequest()
            assertEquals("/comfy/api/mobile/model-center/enrichment", enrich.path)
            assertNull(enrich.getHeader("Authorization"))
            assertFalse(enrich.body.readUtf8().contains("downloadUrl"))
            assertEquals("/comfy/api/mobile/model-center/tasks", server.takeRequest().path)
        }
    }

    @Test fun conflictRetainsServerMessageInsteadOfPretendingSaved() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(409).setBody(
                """{"code":409,"msg":"标注已被其他页面修改，请刷新","data":null}"""))
            val result = runCatching { repository(server).annotate(LocalModel(id = "a"), ModelAnnotation()) }
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("刷新"))
        }
    }

    @Test fun previewRequestIsServerScopedVersionedAndSupportsOldBridgeFallback() {
        MockWebServer().use { server ->
            val repo = repository(server)
            val local = LocalModel(id = "cover-id", preview = ModelPreviewInfo("local", "cover revision"))
            val url = repo.preview(local)!!.toHttpUrl()
            assertEquals("/comfy/api/mobile/model-center/models/cover-id/preview", url.encodedPath)
            assertEquals("cover revision", url.queryParameter("v"))
            assertEquals("0", url.queryParameter("index"))
            assertNotEquals(repo.preview(local), repo.preview(local.copy(preview = ModelPreviewInfo("local", "new"))))
            assertNull(repo.preview(local.copy(preview = ModelPreviewInfo("none"))))
            assertNotNull(repo.preview(LocalModel(id = "old-bridge-item")))
        }
    }
}
