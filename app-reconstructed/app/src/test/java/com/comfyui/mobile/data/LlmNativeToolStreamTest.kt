package com.comfyui.mobile.data

import com.comfyui.mobile.agent.AgentInteractionProtocol
import com.comfyui.mobile.agent.AgentReasoningEffort
import com.comfyui.mobile.network.ChatMessageDto
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LlmNativeToolStreamTest {
    private lateinit var server: MockWebServer
    private lateinit var network: OkHttpClient
    private lateinit var client: LlmClient
    private val gson = Gson()
    private val messages = listOf(ChatMessageDto("user", "fixture task"))
    private val base get() = server.url("/v1").toString()
    private fun delta(index: Int, name: String? = null, arguments: String? = null, id: String? = null) =
        mapOf("index" to index, "id" to id, "type" to if (id != null) "function" else null,
            "function" to mapOf("name" to name, "arguments" to arguments))
    private fun event(
        calls: List<Map<String, Any?>> = emptyList(), finish: String? = null,
        reasoning: String? = null, content: String? = null
    ) = "data: " + gson.toJson(mapOf("choices" to listOf(mapOf(
        "delta" to mapOf("tool_calls" to calls, "reasoning_content" to reasoning, "content" to content),
        "finish_reason" to finish)))) + "\n\n"
    private fun stream(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)
    private suspend fun request() = client.chatCompletionsStream(base, "fixture-key", "fixture-model", messages,
        requestId = "native", tools = AgentInteractionProtocol.decisionTools, toolChoice = "required") { _, _, _, _ -> }

    @Before fun setup() {
        server = MockWebServer().apply { start() }
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

    @Test fun fragmentedToolNamesAndArgumentsAreAccumulatedWithoutNeedingDoneOrFinalContent() = runBlocking {
        server.enqueue(stream(event(reasoning = "check facts") +
            event(listOf(delta(0, "ask_", "{\"questions\":[", "call-"))) +
            event(listOf(delta(0, "user_question", "{\"id\":\"q1\",\"question\":\"选哪套？\"}", "question"))) +
            event(listOf(delta(0, arguments = "]}")), finish = "tool_calls")))
        val output = request().getOrThrow()
        val call = output.toolCalls.single()
        assertEquals("call-question", call.id)
        assertEquals("ask_user_question", call.function.name)
        assertEquals("""{"questions":[{"id":"q1","question":"选哪套？"}]}""", call.function.arguments)
        assertEquals("", output.content)
        assertEquals("check facts", output.reasoning)
        assertEquals("tool_calls", output.finishReason)
        val body = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertEquals("required", body["tool_choice"].asString)
        assertFalse(body["parallel_tool_calls"].asBoolean)
        assertEquals(listOf("ask_user_question", "continue_task"),
            body["tools"].asJsonArray.map { it.asJsonObject["function"].asJsonObject["name"].asString })
        assertTrue(client.activeRequestIds.isEmpty())
    }

    @Test fun interleavedToolIndicesRetainTheirOwnIdsAndUnicodeArgumentFragments() = runBlocking {
        server.enqueue(stream(event(listOf(
            delta(1, "continue_", "{\"summary\":\"可", "second"),
            delta(0, "ask_user_", "{\"questions\":[", "first"))) +
            event(listOf(delta(0, "question", "{\"id\":\"q\",\"question\":\"你好\"}]}"),
                delta(1, "task", "执行\"}")), finish = "tool_calls")))
        val calls = request().getOrThrow().toolCalls
        assertEquals(listOf("first", "second"), calls.map { it.id })
        assertEquals(listOf("ask_user_question", "continue_task"), calls.map { it.function.name })
        assertEquals("可执行", JsonParser.parseString(calls[1].function.arguments).asJsonObject["summary"].asString)
    }

    @Test fun finishChunkMayContainBothTheLastArgumentFragmentAndTheFinishReason() = runBlocking {
        server.enqueue(stream(event(listOf(delta(0, "continue_task", "{\"summary\":", "ready"))) +
            event(listOf(delta(0, arguments = "\"ready\"}")), finish = "tool_calls")))
        assertEquals("""{"summary":"ready"}""", request().getOrThrow().toolCalls.single().function.arguments)
    }

    @Test fun doneOnlyTerminationStillRequiresCompleteProtocolFieldsAndObjectArguments() = runBlocking {
        for (call in listOf(
            delta(0, "ask_user_question", """{"questions":[]}""", "valid"),
            delta(0, "ask_user_question", """{"questions":[""", "truncated"),
            delta(0, "ask_user_question", "[]", "array"),
            delta(0, "ask_user_question", "{}", null)
        )) {
            server.enqueue(stream(event(listOf(call)) + "data: [DONE]\n\n"))
            val result = request()
            if (call["id"] == "valid") assertTrue(result.isSuccess)
            else assertEquals(LlmErrorType.INCOMPLETE_RESPONSE, (result.exceptionOrNull() as LlmException).errorType)
            assertTrue(client.activeRequestIds.isEmpty())
        }
        assertEquals(4, server.requestCount)
    }

    @Test fun duplicateCallIdsAndLengthTerminationNeverReturnExecutableActions() = runBlocking {
        server.enqueue(stream(event(listOf(delta(0, "continue_task", "{}", "same"),
            delta(1, "ask_user_question", "{}", "same")), finish = "tool_calls")))
        assertEquals(LlmErrorType.INCOMPLETE_RESPONSE, (request().exceptionOrNull() as LlmException).errorType)
        server.enqueue(stream(event(listOf(delta(0, "continue_task", """{"summary":"ready"}""", "ready")), finish = "length")))
        assertEquals(LlmErrorType.INCOMPLETE_RESPONSE, (request().exceptionOrNull() as LlmException).errorType)
        assertEquals(2, server.requestCount)
    }

    @Test fun unregisteredToolCallsAreNotExecutedAsOrdinaryContent() = runBlocking {
        server.enqueue(stream(event(listOf(delta(0, "ask_user_question", "{}", "unexpected")), finish = "tool_calls")))
        val result = client.chatCompletionsStream(base, "fixture-key", "fixture-model", messages) { _, _, _, _ -> }
        assertEquals(LlmErrorType.INCOMPLETE_RESPONSE, (result.exceptionOrNull() as LlmException).errorType)
    }

    @Test fun reasoningOnlyRemainsSeparateInsteadOfPretendingToBeTheAnswer() = runBlocking {
        server.enqueue(stream(event(reasoning = "internal reasoning", finish = "stop")))
        val output = request().getOrThrow()
        assertEquals("", output.content)
        assertEquals("internal reasoning", output.reasoning)
        assertTrue(output.toolCalls.isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test fun nativeNonStreamHasTheSameChoiceAndFinalToolSemantics() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","reasoning_content":"check","content":null,"tool_calls":[{"id":"q","type":"function","function":{"name":"ask_user_question","arguments":"{\"questions\":[]}"}}]},"finish_reason":"tool_calls"}]}"""))
        val output = client.chatCompletions(base, "fixture-key", "fixture-model", messages,
            tools = AgentInteractionProtocol.decisionTools, toolChoice = "required", reasoningEffort = AgentReasoningEffort.XHIGH).getOrThrow()
        assertEquals("q", output.toolCalls.single().id)
        assertEquals("", output.content)
        assertEquals("check", output.reasoning)
        val body = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertEquals("required", body["tool_choice"].asString)
        assertEquals("xhigh", body["reasoning_effort"].asString)
        assertFalse(body.has("temperature"))
    }

    @Test fun cancellationDuringAnUnfinishedToolCallClosesTheSocketAndCannotYieldAQuestion() = runBlocking {
        val partial = event(reasoning = "working") + event(listOf(delta(0, "ask_user_question", "{\"questions\":[", "q")))
        server.enqueue(stream(partial).setHeader("Content-Length", partial.toByteArray().size + 1000))
        val progressed = CountDownLatch(1)
        val job = launch(Dispatchers.IO) {
            client.chatCompletionsStream(base, "fixture-key", "fixture-model", messages,
                requestId = "blocked-tool", tools = AgentInteractionProtocol.decisionTools, toolChoice = "required"
            ) { _, _, _, _ -> progressed.countDown() }
            fail("A cancelled partial invocation must never return a usable question")
        }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertTrue(progressed.await(5, TimeUnit.SECONDS))
        job.cancel()
        withTimeout(2_000) { job.join() }
        assertTrue(client.activeRequestIds.isEmpty())
        assertTrue(job.isCancelled)
    }
}
