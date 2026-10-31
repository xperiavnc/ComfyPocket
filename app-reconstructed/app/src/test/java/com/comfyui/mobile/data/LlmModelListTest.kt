package com.comfyui.mobile.data

import com.comfyui.mobile.ui.screens.agent.filterModels
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LlmModelListTest {
    private lateinit var server: MockWebServer
    private lateinit var network: OkHttpClient
    private lateinit var client: LlmClient

    @Before fun setup() {
        server = MockWebServer(); server.start()
        network = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        client = LlmClient(client = network)
    }

    @After fun close() {
        network.dispatcher.executorService.shutdownNow()
        server.shutdown()
    }

    @Test fun modelsEndpointSitsBesideChatCompletions() {
        assertEquals("https://api.deepseek.com/v1/models", LlmClient.resolveModelsEndpoint("https://api.deepseek.com/v1"))
        assertEquals("https://api.deepseek.com/v1/models",
            LlmClient.resolveModelsEndpoint("https://api.deepseek.com/v1/chat/completions"))
    }

    @Test fun openAiListIsParsedSortedAndDeduplicated() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"object":"list","data":[{"id":"glm-5.2"},{"id":"deepseek-chat"},{"id":"glm-5.2"},{"id":"Claude-x"}]}"""))
        val models = client.listModels(server.url("/v1").toString(), "secret").getOrThrow()
        assertEquals(listOf("Claude-x", "deepseek-chat", "glm-5.2"), models)
        val request = server.takeRequest()
        assertEquals("/v1/models", request.path)
        assertEquals("GET", request.method)
        assertEquals("Bearer secret", request.getHeader("Authorization"))
    }

    @Test fun geminiStyleNamesLoseTheirPrefix() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"models":[{"name":"models/gemini-pro"}]}"""))
        assertEquals(listOf("gemini-pro"), client.listModels(server.url("/v1").toString(), "k").getOrThrow())
    }

    @Test fun httpErrorsAndEmptyListsFailWithReadableMessages() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        val denied = client.listModels(server.url("/v1").toString(), "k").exceptionOrNull()
        assertTrue(denied is LlmException)
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        val empty = client.listModels(server.url("/v1").toString(), "k").exceptionOrNull() as LlmException
        assertEquals("服务没有返回可用的模型列表。", empty.userMessage)
    }

    @Test fun filterMatchesEveryTermCaseInsensitively() {
        val all = listOf("deepseek-chat", "deepseek-reasoner", "glm-5.2", "DeepSeek-V3")
        assertEquals(all, filterModels(all, "  "))
        assertEquals(listOf("deepseek-chat", "deepseek-reasoner", "DeepSeek-V3"), filterModels(all, "DEEPSEEK"))
        assertEquals(listOf("deepseek-reasoner"), filterModels(all, "seek reas"))
        assertEquals(emptyList<String>(), filterModels(all, "qwen"))
    }
}
