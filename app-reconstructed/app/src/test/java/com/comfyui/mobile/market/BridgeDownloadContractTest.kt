package com.comfyui.mobile.market

import com.comfyui.mobile.model.CivitaiDownloadRequest
import com.comfyui.mobile.model.CivitaiTaskActionRequest
import com.comfyui.mobile.network.ComfyApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class BridgeDownloadContractTest {
    private fun api(server: MockWebServer): ComfyApi = Retrofit.Builder()
        .baseUrl(server.url("/"))
        .addConverterFactory(GsonConverterFactory.create())
        .build().create(ComfyApi::class.java)

    @Test fun `submit uses native path with stable file id and no phone credentials`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"code":0,"msg":"queued"}"""))
            assertTrue(api(server).downloadCivitaiModel(CivitaiDownloadRequest(
                modelId = 10, modelVersionId = 20, fileId = 30,
                modelRoot = "/models/loras", subfolder = "fixture"
            )).isSuccessful)
            val request = server.takeRequest()
            assertEquals("/api/mobile/civitai/native-download", request.path)
            val body = request.body.readUtf8()
            assertTrue(body.contains(""""fileId":30"""))
            assertFalse(body.contains("apiKey"))
            assertFalse(body.contains("proxyPassword"))
            assertNull(request.getHeader("Authorization"))
        }
    }

    @Test fun `native task fields decode progress and action uses native endpoint`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """{"code":0,"data":{"queue":[{"download_id":"fixture","status":"paused","progress":12.5,"speed_bytes":1024}],"stats":{"paused":1}}}"""))
            val tasks = api(server).getCivitaiDownloadTasks().body()!!.data!!.queue!!
            assertEquals("/api/mobile/civitai/native-tasks", server.takeRequest().path)
            assertEquals("paused", tasks.single().status)
            assertEquals(12.5, tasks.single().progress!!, 0.0)
            server.enqueue(MockResponse().setBody("""{"code":0}"""))
            assertTrue(api(server).civitaiTaskAction(CivitaiTaskActionRequest("resume", "fixture")).isSuccessful)
            val request = server.takeRequest()
            assertEquals("/api/mobile/civitai/native-task-action", request.path)
            assertTrue(request.body.readUtf8().contains(""""downloadId":"fixture""""))
        }
    }

    @Test fun `old bridge 404 never triggers a legacy download fallback`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            val response = api(server).downloadCivitaiModel(
                CivitaiDownloadRequest(modelId = 10, modelVersionId = 20))
            assertEquals(404, response.code())
            assertEquals("/api/mobile/civitai/native-download", server.takeRequest().path)
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        }
    }
}
