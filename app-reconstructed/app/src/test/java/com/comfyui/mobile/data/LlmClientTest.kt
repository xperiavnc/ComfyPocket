package com.comfyui.mobile.data

import com.comfyui.mobile.agent.AgentReasoningEffort
import com.comfyui.mobile.network.ChatMessageDto
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LlmClientTest {
    private lateinit var server: MockWebServer
    private lateinit var network: OkHttpClient
    private lateinit var client: LlmClient
    private val messages = listOf(ChatMessageDto("user", "fixture"))
    private val gson = Gson()
    private val base get() = server.url("/v1").toString()
    private fun event(content: String = "", reasoning: String = "", finish: String? = null): String =
        "data: " + gson.toJson(mapOf("choices" to listOf(mapOf(
            "delta" to mapOf("content" to content, "reasoning_content" to reasoning), "finish_reason" to finish
        )))) + "\n\n"
    private fun stream(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)
    private suspend fun request(id: String = "fixture", effort: AgentReasoningEffort = AgentReasoningEffort.DEFAULT) =
        client.chatCompletionsStream(base, "fixture-key", "fixture-model", messages,
            requestId = id, reasoningEffort = effort) { _, _, _, _ -> }

    @Before fun setup() {
        server = MockWebServer()
        server.start()
        network = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        client = LlmClient(client = network)
    }

    @After fun close() {
        client.cancelActive()
        network.dispatcher.cancelAll()
        network.connectionPool.evictAll()
        network.dispatcher.executorService.shutdownNow()
        server.shutdown()
    }

    @Test fun streamAccumulatesContentAndReasoningAndCleansIdentity() = runBlocking {
        server.enqueue(stream(event(reasoning = "think") + event("hel") + event("lo", finish = "stop") + "data: [DONE]\n\n"))
        val progress = mutableListOf<Pair<String, String>>()
        val result = client.chatCompletionsStream(base, "fixture-key", "fixture-model", messages, requestId = "stream") {
            _, _, r, c -> progress += r to c
        }.getOrThrow()
        assertEquals("hello", result.content)
        assertEquals("think", result.reasoning)
        assertEquals("stream", result.requestId)
        assertEquals("think" to "hello", progress.last())
        assertTrue(client.activeRequestIds.isEmpty())
        assertEquals("/v1/chat/completions", server.takeRequest().path)
    }

    @Test fun coroutineCancellationClosesBlockedBodyReadPromptly() = runBlocking {
        val partial = event("partial")
        server.enqueue(stream(partial).setHeader("Content-Length", partial.toByteArray().size + 1000))
        val progressed = CountDownLatch(1)
        val job = launch(Dispatchers.IO) {
            client.chatCompletionsStream(base, "fixture-key", "fixture-model", messages, requestId = "cancel-body") {
                _, _, _, _ -> progressed.countDown()
            }
        }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertTrue("The cancellation must occur during a blocked body read", progressed.await(5, TimeUnit.SECONDS))
        withTimeout(5_000) { while ("cancel-body" !in client.activeRequestIds) delay(5) }
        val start = System.nanoTime()
        job.cancel()
        withTimeout(2_000) { job.join() }
        assertTrue(job.isCancelled)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2_000)
        assertTrue(client.activeRequestIds.isEmpty())
    }

    @Test fun cancellationBeforeHeadersAlsoClosesActualCall() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch(Dispatchers.IO) { request("cancel-headers") }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        job.cancel()
        withTimeout(2_000) { job.join() }
        assertTrue(client.activeRequestIds.isEmpty())
    }

    @Test fun cancellingSpecificIdDoesNotCancelConcurrentRequest() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val first = async(Dispatchers.IO) {
            try { request("first"); false } catch (_: CancellationException) { true }
        }
        val second = launch(Dispatchers.IO) { request("second") }
        repeat(2) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
        client.cancelRequest("first")
        assertTrue(withTimeout(2_000) { first.await() })
        assertFalse(second.isCancelled)
        assertEquals(setOf("second"), client.activeRequestIds)
        second.cancel()
        withTimeout(2_000) { second.join() }
        assertTrue(client.activeRequestIds.isEmpty())
    }

    @Test fun emptyStreamIsFailureAndNeverSilentlyRetransmitted() = runBlocking {
        server.enqueue(stream("data: [DONE]\n\n"))
        val error = request().exceptionOrNull() as LlmException
        assertEquals(LlmErrorType.EMPTY_RESPONSE, error.errorType)
        assertEquals(1, server.requestCount)
        assertTrue(client.activeRequestIds.isEmpty())
    }

    @Test fun eofWithoutCompletionMarkerIsNotSuccess() = runBlocking {
        server.enqueue(stream(event("""{"reply":"ok","prompt":"cat","should_draw":true}""")))
        assertEquals(LlmErrorType.INCOMPLETE_RESPONSE, (request().exceptionOrNull() as LlmException).errorType)
        assertEquals(1, server.requestCount)
        assertTrue(client.activeRequestIds.isEmpty())
    }

    @Test fun outputLengthTerminationIsNotUsableForSubmission() = runBlocking {
        server.enqueue(stream(event("""{"prompt":"cat"}""", finish = "length") + "data: [DONE]\n\n"))
        assertEquals(LlmErrorType.INCOMPLETE_RESPONSE, (request().exceptionOrNull() as LlmException).errorType)
    }

    @Test fun malformedStreamChunkIsNotSilentlySkipped() = runBlocking {
        server.enqueue(stream(event("partial") + "data: {broken\n\ndata: [DONE]\n\n"))
        assertEquals(LlmErrorType.INCOMPLETE_RESPONSE, (request().exceptionOrNull() as LlmException).errorType)
    }

    @Test fun serverStreamErrorCleansCall() = runBlocking {
        server.enqueue(stream("""data: {"error":{"message":"fixture failure"}}

"""))
        assertEquals(LlmErrorType.SERVER_ERROR, (request().exceptionOrNull() as LlmException).errorType)
        assertTrue(client.activeRequestIds.isEmpty())
    }

    @Test fun httpFailureKeepsMappedStatusAndCleansCall() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"fixture"}}"""))
        val error = request().exceptionOrNull() as LlmException
        assertEquals(LlmErrorType.UNAUTHORIZED, error.errorType)
        assertEquals(401, error.httpCode)
        assertTrue(client.activeRequestIds.isEmpty())
    }

    @Test fun totalTimeoutHasDistinctFailureState() = runBlocking {
        client = LlmClient(client = network, totalTimeoutMillis = 100)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val error = withTimeout(3_000) { request().exceptionOrNull() as LlmException }
        assertEquals(LlmErrorType.TIMEOUT_TOTAL, error.errorType)
        assertTrue(client.activeRequestIds.isEmpty())
    }

    @Test fun duplicateIdCannotReplaceOrRemoveTheOriginalCall() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val first = launch(Dispatchers.IO) { request("same-id") }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertTrue(request("same-id").isFailure)
        assertEquals(setOf("same-id"), client.activeRequestIds)
        assertEquals(1, server.requestCount)
        first.cancel()
        withTimeout(2_000) { first.join() }
        assertTrue(client.activeRequestIds.isEmpty())
    }

    @Test fun readTimeoutRemainsDistinctFromTheTotalDeadline() = runBlocking {
        network = network.newBuilder().readTimeout(100, TimeUnit.MILLISECONDS).build()
        client = LlmClient(client = network, totalTimeoutMillis = 5_000)
        val partial = event("partial")
        server.enqueue(stream(partial).setHeader("Content-Length", partial.toByteArray().size + 1000))
        val error = withTimeout(3_000) { request().exceptionOrNull() as LlmException }
        assertEquals(LlmErrorType.TIMEOUT_READ, error.errorType)
        assertTrue(client.activeRequestIds.isEmpty())
    }

    @Test fun allExplicitEffortTiersAreSerializedWithoutTemperature() = runBlocking {
        for (effort in AgentReasoningEffort.entries.filter { it.wireValue != null }) {
            server.enqueue(stream(event("ok", finish = "stop") + "data: [DONE]\n\n"))
            assertTrue(request(effort = effort).isSuccess)
            val body = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
            assertEquals(effort.wireValue, body["reasoning_effort"].asString)
            assertFalse(body.has("temperature"))
        }
    }

    @Test fun defaultOmitsEffortForBackwardsCompatibility() = runBlocking {
        server.enqueue(stream(event("ok", finish = "stop") + "data: [DONE]\n\n"))
        request().getOrThrow()
        val body = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertFalse(body.has("reasoning_effort"))
        assertEquals(0.7, body["temperature"].asDouble, 0.0)
    }

    @Test fun nonStreamingAlsoCarriesMaxAndReturnsRequestId() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""))
        val output = client.chatCompletions(base, "fixture-key", "fixture-model", messages,
            requestId = "nonstream", reasoningEffort = AgentReasoningEffort.MAX).getOrThrow()
        assertEquals("nonstream", output.requestId)
        val body = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertEquals("max", body["reasoning_effort"].asString)
        assertFalse(body.has("temperature"))
        assertTrue(client.activeRequestIds.isEmpty())
    }

    @Test fun imageBodiesRetainPerMessageAndLastUserImages() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"ok"}}]}"""))
        client.chatCompletions(base, "fixture-key", "fixture-model",
            listOf(ChatMessageDto("user", "old"), ChatMessageDto("assistant", "reply"), ChatMessageDto("user", "new")),
            images = listOf("data:image/jpeg;base64,new"), messageImages = mapOf(0 to listOf("data:image/jpeg;base64,old")),
            reasoningEffort = AgentReasoningEffort.XHIGH).getOrThrow()
        val body = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertEquals("xhigh", body["reasoning_effort"].asString)
        val msgs = body["messages"].asJsonArray
        assertEquals("old", msgs[0].asJsonObject["content"].asJsonArray[0].asJsonObject["text"].asString)
        assertEquals("data:image/jpeg;base64,old", msgs[0].asJsonObject["content"].asJsonArray[1].asJsonObject["image_url"].asJsonObject["url"].asString)
        assertEquals("reply", msgs[1].asJsonObject["content"].asString)
        assertEquals("data:image/jpeg;base64,new", msgs[2].asJsonObject["content"].asJsonArray[1].asJsonObject["image_url"].asJsonObject["url"].asString)
    }

    @Test fun unsupportedTierIsReportedWithoutDowngradingOrRetrying() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"reasoning_effort max not supported"}}"""))
        assertTrue(request(effort = AgentReasoningEffort.MAX).isFailure)
        assertEquals(1, server.requestCount)
        val body = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertEquals("max", body["reasoning_effort"].asString)
    }

    @Test fun truncatedNonStreamAndConfigurationErrorAreFailures() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"cat"},"finish_reason":"length"}]}"""))
        assertEquals(LlmErrorType.INCOMPLETE_RESPONSE,
            (client.chatCompletions(base, "fixture-key", "fixture-model", messages).exceptionOrNull() as LlmException).errorType)
        assertEquals(LlmErrorType.CONFIG_INVALID,
            (client.chatCompletions("", "", "", messages).exceptionOrNull() as LlmException).errorType)
        assertTrue(client.activeRequestIds.isEmpty())
    }
}
