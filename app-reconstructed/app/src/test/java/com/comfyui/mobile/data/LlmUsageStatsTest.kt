package com.comfyui.mobile.data

import com.comfyui.mobile.agent.AgentLlmStats
import com.comfyui.mobile.network.ChatMessageDto
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LlmUsageStatsTest {
    private lateinit var server: MockWebServer
    private lateinit var network: OkHttpClient
    private lateinit var client: LlmClient
    private val gson = Gson()
    private val messages = listOf(ChatMessageDto("user", "fixture"))

    private fun data(payload: Any) = "data: " + gson.toJson(payload) + "\n\n"
    private fun delta(content: String, finish: String? = null) = data(mapOf("choices" to listOf(
        mapOf("delta" to mapOf("content" to content), "finish_reason" to finish))))

    @Before fun setup() {
        server = MockWebServer(); server.start()
        network = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        client = LlmClient(client = network)
    }

    @After fun close() {
        network.dispatcher.executorService.shutdownNow()
        server.shutdown()
    }

    private suspend fun stream() = client.chatCompletionsStream(server.url("/v1").toString(), "k", "m", messages) { _, _, _, _ -> }
        .getOrThrow()

    @Test fun trailingUsageChunkAfterFinishIsRead() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            delta("hel") + delta("lo", finish = "stop") +
                data(mapOf("choices" to emptyList<Any>(), "usage" to mapOf(
                    "prompt_tokens" to 1200, "completion_tokens" to 34,
                    "prompt_tokens_details" to mapOf("cached_tokens" to 1024)))) + "data: [DONE]\n\n"))
        val out = stream()
        assertEquals("hello", out.content)
        assertEquals(1200, out.usage?.prompt_tokens)
        assertEquals(1024, out.usage?.cachedTokens)
        val body = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertTrue(body.getAsJsonObject("stream_options").get("include_usage").asBoolean)
    }

    @Test fun deepSeekCacheFieldsAndMissingUsageBothWork() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            data(mapOf("choices" to listOf(mapOf("delta" to mapOf("content" to "ok"), "finish_reason" to "stop")),
                "usage" to mapOf("prompt_tokens" to 10, "completion_tokens" to 2, "prompt_cache_hit_tokens" to 8)))
                + "data: [DONE]\n\n"))
        assertEquals(8, stream().usage?.cachedTokens)

        // No usage and no [DONE]: the answer still completes and the stats fall back to estimates.
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(delta("好的", finish = "stop")))
        val out = stream()
        assertNull(out.usage)
        val stats = AgentLlmStats.from("m", out, estimatedPrompt = 500, contextLimit = 8000)
        assertTrue(stats.estimated)
        assertEquals(502, stats.promptTokens + stats.outputTokens)
        assertTrue(stats.summary().startsWith("上下文 ≈502 / 8.0k (6%)"))
    }

    @Test fun streamThatKeepsFlowingOutlivesTheFirstDataDeadline() = runBlocking {
        client = LlmClient(client = network, totalTimeoutMillis = 300)
        val body = (1..8).joinToString("") { delta("x") } + delta("", finish = "stop") + "data: [DONE]\n\n"
        // ~1.2 s total, far past the 300 ms deadline, but the first chunk arrives immediately.
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)
            .throttleBody(48, 120, java.util.concurrent.TimeUnit.MILLISECONDS))
        assertEquals("xxxxxxxx", stream().content)
    }

    @Test fun streamWithNoDataHitsTheTotalDeadline() = runBlocking {
        client = LlmClient(client = network, totalTimeoutMillis = 200)
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(delta("late", finish = "stop")).setBodyDelay(3, java.util.concurrent.TimeUnit.SECONDS))
        val error = kotlinx.coroutines.withTimeout(2_000) {
            client.chatCompletionsStream(server.url("/v1").toString(), "k", "m", messages) { _, _, _, _ -> }
                .exceptionOrNull() as LlmException
        }
        assertEquals(LlmErrorType.TIMEOUT_TOTAL, error.errorType)
        assertTrue(client.activeRequestIds.isEmpty())
    }

    @Test fun summaryShowsSpeedAndCacheRate() {
        val stats = AgentLlmStats("m", promptTokens = 12_000, outputTokens = 300, cachedTokens = 9_000,
            contextLimit = 128_000, tokensPerSecond = 41.6)
        assertEquals("上下文 12k / 128k (9%) · 42 tok/s · 缓存 75%", stats.summary())
        assertFalse(stats.copy(live = true).summary().contains("缓存"))
        assertNull(AgentLlmStats.speed(10, firstTokenMillis = 900, totalMillis = 1000))
        assertEquals(1, AgentLlmStats.estimateTokens("a"))
        assertEquals(2, AgentLlmStats.estimateTokens("好的"))
    }
}
