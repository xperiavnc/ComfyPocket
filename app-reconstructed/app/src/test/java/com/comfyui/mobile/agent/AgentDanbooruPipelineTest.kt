package com.comfyui.mobile.agent

import com.comfyui.mobile.danbooru.*
import com.comfyui.mobile.data.LlmCompletionOutput
import com.comfyui.mobile.data.LlmErrorType
import com.comfyui.mobile.data.LlmException
import com.comfyui.mobile.network.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AgentDanbooruPipelineTest {
    private lateinit var server: MockWebServer
    private val directory = kotlin.io.path.createTempDirectory("tag-pipeline").toFile()
    private lateinit var pipeline: AgentDanbooruPipeline
    private val messages = listOf(ChatMessageDto("user", "draw a cat"))
    private val draft = """{"reply":"好","prompt":"cat, my_trigger, (blue eyes:1.2), <lora:custom:0.8>","should_draw":true,"tag_queries":["cat"]}"""
    @Before fun setup() {
        server = MockWebServer().apply { start() }
        pipeline = AgentDanbooruPipeline(DanbooruRepository(DanbooruCache(directory), server.url("/"), intervalMillis = 0))
    }
    @After fun close() { server.shutdown(); directory.deleteRecursively() }
    private fun exact() {
        server.enqueue(MockResponse().setBody("""[{"name":"cat","category":0,"post_count":1}]"""))
        server.enqueue(MockResponse().setResponseCode(404))
    }
    @Test fun nativeToolHasOneRoundTripAndBoundedToolResult() = runBlocking {
        exact()
        var calls = 0
        var report: DanbooruCheckReport? = null
        val source = AgentTagGeneration { messages, tools ->
            calls++
            if (calls == 1) {
                assertEquals(SearchDanbooruTagsTool.NAME, tools!!.single().function.name)
                LlmCompletionOutput(draft, toolCalls = listOf(ChatToolCall("lookup-1",
                    ChatFunctionCall(SearchDanbooruTagsTool.NAME, """{"queries":["cat"],"limit":3}"""))))
            } else {
                assertNull(tools)
                val result = messages.single { it.role == "tool" }
                assertEquals("lookup-1", result.tool_call_id)
                assertTrue(result.content!!.contains("NO_WIKI"))
                LlmCompletionOutput("""{"reply":"好了","prompt":"cat, sitting","should_draw":true}""")
            }
        }
        val output = pipeline.run(messages, DanbooruSettings(), source, {}, {}, { report = it },
            protectedWords = listOf("my_trigger"))
        assertEquals(2, calls)
        assertEquals(TagCheckOrigin.MODEL_TOOL, report!!.origin)
        val actual = com.google.gson.JsonParser.parseString(output.content).asJsonObject["prompt"].asString
        assertTrue(actual.contains("my_trigger"))
        assertTrue(actual.contains("(blue eyes:1.2)"))
        assertTrue(actual.contains("<lora:custom:0.8>"))
    }
    @Test fun compatibilityExtractsOneTermAndNeverPretendsModelRequestedIt() = runBlocking {
        exact()
        var calls = 0
        var origin: TagCheckOrigin? = null
        pipeline.run(messages, DanbooruSettings(callMode = TagCallMode.COMPAT),
            AgentTagGeneration { _, tools ->
                assertNull(tools); calls++
                LlmCompletionOutput(if (calls == 1) draft else """{"reply":"好","prompt":"cat","should_draw":true}""")
            }, {}, { origin = it }, {})
        assertEquals(TagCheckOrigin.APP_COMPAT, origin)
        assertEquals(2, calls)
        assertEquals(listOf("cat"), pipeline.extractQueries("cat, <lora:x:1>, (smile:1.2), very complex scene description"))
    }
    @Test fun networkFailureKeepsDraftWithoutAnUnnecessaryRevision() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        var calls = 0
        var report: DanbooruCheckReport? = null
        val output = pipeline.run(messages, DanbooruSettings(callMode = TagCallMode.COMPAT),
            AgentTagGeneration { _, _ -> calls++; LlmCompletionOutput(draft) },
            {}, {}, { report = it })
        assertEquals(draft, output.content)
        assertEquals(1, calls)
        assertTrue(report!!.incomplete)
        assertEquals(LookupStatus.HTTP_ERROR, report!!.items.single().search.status)
    }
    @Test fun unsupportedToolsCanUseOneExplicitCompatibilityAttempt() = runBlocking {
        exact()
        var calls = 0
        val source = AgentTagGeneration { _, tools ->
            calls++
            if (calls == 1) throw LlmException(LlmErrorType.UNKNOWN, "bad tools", httpCode = 400,
                technicalDetail = "tools are not supported")
            assertNull(tools)
            LlmCompletionOutput(draft)
        }
        pipeline.run(messages, DanbooruSettings(), source, {}, {}, {})
        assertEquals(3, calls) // One rejected request, one successful initial response and one revision.
        assertEquals(2, server.requestCount)
    }
    @Test fun stoppedContextDoesNotLookupOrRevise() = runBlocking {
        var current = true
        var stopped = false
        try {
            pipeline.run(messages, DanbooruSettings(callMode = TagCallMode.COMPAT),
                AgentTagGeneration { _, _ -> LlmCompletionOutput(draft) },
                { if (!current) throw CancellationException("stopped") },
                { current = false }, {})
        } catch (_: CancellationException) { stopped = true }
        assertTrue(stopped)
        assertEquals(0, server.requestCount)
    }
    @Test fun fuzzyResultsNeverReplaceUnverifiedOrWeightedTokens() {
        val report = DanbooruCheckReport(TagCheckOrigin.APP_COMPAT,
            listOf(DanbooruQueryResult("my_trigger", DanbooruApiResult(LookupStatus.FOUND,
                tags = listOf(DanbooruTag("popular_word", 0, 100, matchKind = MatchKind.FUZZY))))), 1)
        val result = pipeline.preserveTerms("my_trigger, (eye_color:1.2), <lora:name:1>", "popular_word", report, emptyList())
        assertTrue(result.contains("my_trigger"))
        assertTrue(result.contains("(eye_color:1.2)"))
        assertTrue(result.contains("<lora:name:1>"))
    }
    @Test fun toolAcceptsOnlyShortQueriesAndResultLimit() {
        assertEquals(2, SearchDanbooruTagsTool.parse("""{"queries":["cat"],"limit":2}""").limit)
        for (raw in listOf(
            """{"queries":["cat"],"limit":9}""",
            """{"queries":["https://example.com"],"limit":2}""",
            """{"queries":["cat"],"limit":2,"url":"x"}"""
        )) assertTrue(runCatching { SearchDanbooruTagsTool.parse(raw) }.isFailure)
    }
}
