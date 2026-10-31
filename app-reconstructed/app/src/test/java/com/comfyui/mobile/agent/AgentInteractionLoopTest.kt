package com.comfyui.mobile.agent

import com.comfyui.mobile.data.LlmCompletionOutput
import com.comfyui.mobile.data.LlmErrorType
import com.comfyui.mobile.data.LlmException
import com.comfyui.mobile.network.ChatFunctionCall
import com.comfyui.mobile.network.ChatMessageDto
import com.comfyui.mobile.network.ChatToolCall
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AgentInteractionLoopTest {
    private val messages = listOf(ChatMessageDto("system", "final contract"), ChatMessageDto("user", "本轮任务"))
    private val questions = """{"reply":"需要你决定。","questions":[{"id":"outfit","question":"使用哪套？","options":[{"id":"a","label":"常服","description":"日常衬衫"},{"id":"b","label":"礼服","description":"正式裙装"}],"multi_select":false}]}"""
    private fun tool(name: String, arguments: String, id: String = "call-1", reasoning: String? = "decision reasoning") =
        LlmCompletionOutput("", reasoning, toolCalls = listOf(ChatToolCall(id, ChatFunctionCall(name, arguments))))
    private fun ready() = tool(AgentInteractionProtocol.CONTINUE, """{"summary":"用户已将背景构图交给我决定。"}""")
    private class Fixture(vararg results: Any) : AgentInteractionModel {
        val requests = mutableListOf<AgentModelRequest>()
        private val queue = ArrayDeque(results.toList())
        override suspend fun complete(request: AgentModelRequest, progress: suspend (AgentProgress) -> Unit): LlmCompletionOutput {
            requests += request
            progress(AgentProgress("reasoning chunk", "internal decision draft"))
            val result = queue.removeFirst()
            if (result is Exception) throw result
            return result as LlmCompletionOutput
        }
    }

    @Test fun nativeAskIsAFirstClassToolAndStopsBeforeComposition() = runBlocking {
        val fixture = Fixture(tool(AgentInteractionProtocol.ASK, questions))
        val progress = mutableListOf<AgentProgress>()
        val result = AgentInteractionLoop(fixture).run(messages, "decision", onProgress = { progress += it })
        assertEquals(1, fixture.requests.size)
        assertEquals("required", fixture.requests.single().toolChoice)
        assertEquals(listOf("ask_user_question", "continue_task"), fixture.requests.single().tools!!.map { it.function.name })
        assertTrue(fixture.requests.single().deciding)
        assertEquals(AgentAction.ASK_USER, result.question!!.action)
        assertEquals("", result.question.prompt)
        assertFalse(result.question.shouldDraw)
        assertEquals("call-1", result.question.clarification!!.toolInvocation!!.id)
        assertEquals("正式裙装", result.question.clarification.questions!!.single().options!![1].description)
        assertNull(result.output)
        assertTrue(progress.all { it.content.isEmpty() })
    }

    @Test fun explicitReadyDecisionIsClosedAsAToolResultBeforeComposition() = runBlocking {
        val fixture = Fixture(ready(), LlmCompletionOutput("""{"action":"draw","prompt":"rain","negative_prompt":""}""", "compose reasoning"))
        val result = AgentInteractionLoop(fixture).run(messages, "decision")
        assertEquals(2, fixture.requests.size)
        val compose = fixture.requests.last()
        assertFalse(compose.deciding)
        assertEquals("auto", compose.toolChoice)
        assertEquals(listOf(AgentInteractionProtocol.ASK), compose.tools!!.map { it.function.name })
        assertEquals("assistant", compose.messages[2].role)
        assertEquals("decision reasoning", compose.messages[2].reasoning_content)
        assertEquals("tool", compose.messages[3].role)
        assertEquals("call-1", compose.messages[3].tool_call_id)
        assertTrue(compose.messages[3].content!!.contains("compose_result"))
        assertTrue(result.output!!.reasoning!!.contains("compose reasoning"))
        assertNull(result.question)
    }

    @Test fun providerThatIgnoresNativeToolChoiceFallsBackToModelSelectedJsonAction() = runBlocking {
        val fixture = Fixture(
            LlmCompletionOutput("""分析中：可能返回 {"action":"continue_task","summary":"猜测"}，但还未确认。"""),
            LlmCompletionOutput("""{"action":"continue_task","summary":"用户已将背景交给我决定。"}"""),
            LlmCompletionOutput("正式答复")
        )
        var textFallbacks = 0
        val result = AgentInteractionLoop(fixture, onTextProtocol = { textFallbacks++ })
            .run(messages, "decision")
        assertEquals("正式答复", result.output!!.content)
        assertEquals(3, fixture.requests.size)
        assertEquals("required", fixture.requests[0].toolChoice)
        assertNotNull(fixture.requests[0].tools)
        assertNull(fixture.requests[1].toolChoice)
        assertNull(fixture.requests[1].tools)
        assertTrue(fixture.requests[1].messages.first().content!!.contains("接口兼容动作协议"))
        assertTrue(fixture.requests[1].messages.last().content!!.contains("结构化动作兼容重试"))
        assertFalse(fixture.requests[1].messages.any { it.content.orEmpty().contains("猜测") })
        assertNull(fixture.requests[2].tools)
        assertEquals(1, textFallbacks)
    }

    @Test fun fencedCompleteJsonActionIsAcceptedButProseIsNeverMined() = runBlocking {
        val fenced = """```json
            |{"action":"ask_user","reply":"请选择","questions":[{"id":"q","question":"哪套？"}]}
            |```""".trimMargin()
        val fixture = Fixture(LlmCompletionOutput(fenced))
        val result = AgentInteractionLoop(fixture, initialTextProtocol = true).run(messages, "decision")
        assertNotNull(result.question)
        assertEquals("q", result.question!!.clarification!!.questions!!.single().id)
        assertNull(AgentInteractionProtocol.objectValue(
            """分析：{"action":"ask_user","questions":[{"id":"q"}]}还要再想想。"""))
    }

    @Test fun compositionMayDiscoverAnotherConflictAndCallTheSameQuestionTool() = runBlocking {
        val fixture = Fixture(ready(), tool(AgentInteractionProtocol.ASK, questions, "later-question"))
        val result = AgentInteractionLoop(fixture).run(messages, "decision")
        assertEquals(2, fixture.requests.size)
        assertEquals("later-question", result.question!!.clarification!!.toolInvocation!!.id)
        assertNull(result.output)
    }

    @Test fun unknownActionGetsOneExplicitToolErrorAndThenTheModelCanAsk() = runBlocking {
        val fixture = Fixture(tool("invented_action", "{}"), tool(AgentInteractionProtocol.ASK, questions))
        val result = AgentInteractionLoop(fixture).run(messages, "decision")
        assertNotNull(result.question)
        assertEquals(2, fixture.requests.size)
        val feedback = fixture.requests.last().messages.last()
        assertEquals("tool", feedback.role)
        assertEquals("call-1", feedback.tool_call_id)
        assertTrue(feedback.content!!.contains("invalid_action"))
    }

    @Test fun repeatedInvalidActionsAreBoundedThenFallThroughToCompose() = runBlocking {
        val fixture = Fixture(tool("bad_action", "{}"), tool("bad_action", "{}"), LlmCompletionOutput("正式答复"))
        val result = AgentInteractionLoop(fixture).run(messages, "decision")
        assertEquals("正式答复", result.output!!.content)
        assertEquals(3, fixture.requests.size)
        assertTrue(fixture.requests.take(2).all { it.deciding })
        val compose = fixture.requests.last()
        assertFalse(compose.deciding)
        // Compose still offers the question tool and never replays the failed decision text.
        assertEquals(listOf(AgentInteractionProtocol.ASK), compose.tools!!.map { it.function.name })
        assertTrue(compose.messages.last().content!!.contains("按用户本轮原任务直接完成"))
        assertEquals(AgentInteractionLoop.FALLBACK_SUMMARY, result.plan!!.summary)
    }

    @Test fun duplicateQuestionIdsAreRepairedRatherThanRenamedBehindTheModel() = runBlocking {
        val duplicate = """{"questions":[{"id":"same","question":"问题一"},{"id":"same","question":"问题二"}]}"""
        val fixture = Fixture(tool(AgentInteractionProtocol.ASK, duplicate), tool(AgentInteractionProtocol.ASK, questions))
        assertNotNull(AgentInteractionLoop(fixture).run(messages, "decision").question)
        assertEquals(2, fixture.requests.size)
        assertTrue(fixture.requests.last().messages.last().content!!.contains("重复"))
    }

    @Test fun parallelActionsDoNotSilentlyChooseDrawOverQuestion() = runBlocking {
        val parallel = ready().copy(toolCalls = ready().toolCalls +
            tool(AgentInteractionProtocol.ASK, questions, "question").toolCalls)
        val fixture = Fixture(parallel, tool(AgentInteractionProtocol.ASK, questions))
        val result = AgentInteractionLoop(fixture).run(messages, "decision")
        assertNotNull(result.question)
        assertEquals(2, fixture.requests.size)
        assertEquals(2, fixture.requests.last().messages.count { it.role == "tool" })
    }

    @Test fun plainWaitingWordsNeverCreateAQuestionAndDoNotSkipTheDecisionGate() = runBlocking {
        val fixture = Fixture(LlmCompletionOutput("请先确认服装，再等你回答。"), LlmCompletionOutput("我再想想。"),
            LlmCompletionOutput("正式答复"))
        val result = AgentInteractionLoop(fixture).run(messages, "decision")
        // Waiting words are never turned into a question; both gate attempts run before composing.
        assertNull(result.question)
        assertEquals(3, fixture.requests.size)
        assertTrue(fixture.requests.take(2).all { it.deciding })
        assertFalse(fixture.requests.last().messages.any { it.content.orEmpty().contains("请先确认服装") })
    }

    @Test fun reasoningOnlyDoesNotBecomeContentAndGetsOneCleanStructuredRetry() = runBlocking {
        val fixture = Fixture(
            LlmCompletionOutput("", reasoning = "still thinking"),
            LlmCompletionOutput("", reasoning = "still thinking"),
            LlmCompletionOutput("正式答复")
        )
        AgentInteractionLoop(fixture).run(messages, "decision")
        assertEquals(3, fixture.requests.size)
        assertNull(fixture.requests[1].messages.lastOrNull { it.role == "assistant" })
        assertNull(fixture.requests[2].messages.lastOrNull { it.role == "assistant" })
    }

    @Test fun explicitUnsupportedToolsUsesJsonDecisionAndCachesOnlyThatEvidence() = runBlocking {
        val unsupported = LlmException(LlmErrorType.UNKNOWN, "fixture", httpCode = 400,
            technicalDetail = "tools is not supported")
        val fixture = Fixture(unsupported, LlmCompletionOutput("""{"action":"continue_task","summary":"资料完整"}"""),
            LlmCompletionOutput("正式答复"))
        var cached = 0
        val result = AgentInteractionLoop(fixture, onTextProtocol = { cached++ }).run(messages, "decision")
        assertEquals("正式答复", result.output!!.content)
        assertEquals(3, fixture.requests.size)
        assertNotNull(fixture.requests.first().tools)
        assertTrue(fixture.requests.drop(1).all { it.tools == null && it.toolChoice == null })
        assertTrue(fixture.requests[1].messages.first().content!!.contains("接口兼容动作协议"))
        assertTrue(cached >= 1)
    }

    @Test fun rejectedRequiredChoiceRetainsNativeToolsForAutomaticOnlyProviders() = runBlocking {
        val unsupported = LlmException(LlmErrorType.UNKNOWN, "fixture", httpCode = 400,
            technicalDetail = "tool_choice required is not supported; only auto or none")
        val fixture = Fixture(unsupported, tool(AgentInteractionProtocol.ASK, questions))
        var autoCached = false
        var textCached = false
        val result = AgentInteractionLoop(fixture, onTextProtocol = { textCached = true },
            onAutoToolChoice = { autoCached = true }).run(messages, "decision")
        assertNotNull(result.question)
        assertTrue(autoCached)
        assertFalse(textCached)
        assertNotNull(fixture.requests.last().tools)
        assertNull(fixture.requests.last().toolChoice)
    }

    @Test fun timeoutAuthQuotaAndOtherParameterErrorsAreNeverRetriedAsCompatibility() = runBlocking {
        for ((type, code, detail) in listOf(
            Triple(LlmErrorType.TIMEOUT_READ, null, "timeout"),
            Triple(LlmErrorType.UNAUTHORIZED, 401, "tools forbidden"),
            Triple(LlmErrorType.RATE_LIMIT_OR_QUOTA, 429, "tools rate limit"),
            Triple(LlmErrorType.UNKNOWN, 400, "reasoning_effort max not supported"),
            Triple(LlmErrorType.UNKNOWN, 400, "tools schema invalid: missing required property")
        )) {
            val fixture = Fixture(LlmException(type, "fixture", httpCode = code, technicalDetail = detail))
            assertTrue(runCatching { AgentInteractionLoop(fixture).run(messages, "decision") }.exceptionOrNull() is LlmException)
            assertEquals(1, fixture.requests.size)
        }
    }

    @Test fun cachedJsonModeCanAskWithoutNativeFieldsOrGuessingAQuestionFromText() = runBlocking {
        val fixture = Fixture(LlmCompletionOutput("""{"action":"ask_user","questions":[{"id":"name","question":"你指哪个角色？"}]}"""))
        val result = AgentInteractionLoop(fixture, initialTextProtocol = true).run(messages, "decision")
        assertEquals("name", result.question!!.clarification!!.questions!!.single().id)
        assertNull(result.question.clarification.toolInvocation)
        assertNull(fixture.requests.single().tools)
    }

    @Test fun cancellationBetweenDecisionAndCompositionNeverStartsTheSecondRequest() = runBlocking {
        val fixture = Fixture(ready())
        var checked = 0
        val error = runCatching {
            AgentInteractionLoop(fixture).run(messages, "decision", checkCurrent = {
                if (++checked >= 3) throw CancellationException("stopped")
            })
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(1, fixture.requests.size)
    }

    @Test fun nativeAndJsonQuestionFormatsShareStableHumanFacingQuestions() {
        val obj = AgentInteractionProtocol.objectValue(questions)!!
        val native = AgentInteractionProtocol.question(obj, tool(AgentInteractionProtocol.ASK, questions).toolCalls.single())
        val json = AgentInteractionProtocol.question(obj)
        assertEquals(native.clarification!!.questions, json.clarification!!.questions)
        assertNotNull(native.clarification.toolInvocation)
        assertNull(json.clarification.toolInvocation)
    }

    @Test fun customRulesCannotTurnTheDecisionStageIntoTwoPromptFields() {
        val system = AgentInteractionProtocol.decisionSystem("USER_DIALOGUE_RULES_854", "真实档案", AgentMode.DRAW)
        assertTrue(system.endsWith("正式答复和提示词由下一步骤输出。"))
        assertTrue(system.contains("真实档案"))
        assertTrue(system.contains("【应用决策协议】"))
        assertTrue(system.contains("【用户导入的对话规则】\nUSER_DIALOGUE_RULES_854"))
        assertTrue(system.contains("ask_user_question"))
        assertTrue(system.contains("continue_task"))
        assertFalse(system.contains("默认必须保留的绘画词"))
        assertFalse(system.contains("Anima 文生图提示词"))
        val empty = AgentInteractionProtocol.decisionSystem("", "", AgentMode.CHAT)
        assertFalse(empty.contains("【用户导入的对话规则】"))
        assertFalse(empty.contains("你是 ComfyPocket"))
    }
}
