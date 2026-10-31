package com.comfyui.mobile.agent

import com.comfyui.mobile.data.LlmCompletionOutput
import com.comfyui.mobile.model.CharacterLore
import com.comfyui.mobile.network.ChatFunctionCall
import com.comfyui.mobile.network.ChatMessageDto
import com.comfyui.mobile.network.ChatToolCall
import com.google.gson.JsonParser
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AgentToolLoopTest {
    private val messages = listOf(ChatMessageDto("system", "final contract"), ChatMessageDto("user", "画一张雨夜"))

    private fun call(name: String, arguments: String, id: String = "c-$name") =
        LlmCompletionOutput("", "thinking", toolCalls = listOf(ChatToolCall(id, ChatFunctionCall(name, arguments))))

    private class Fixture(vararg results: LlmCompletionOutput) : AgentInteractionModel {
        val requests = mutableListOf<AgentModelRequest>()
        private val queue = ArrayDeque(results.toList())
        override suspend fun complete(request: AgentModelRequest, progress: suspend (AgentProgress) -> Unit): LlmCompletionOutput {
            requests += request
            return queue.removeFirst()
        }
    }

    private fun workflow(): Map<String, Any> = mapOf(
        "3" to mutableMapOf<String, Any>("class_type" to "KSampler",
            "inputs" to mutableMapOf<String, Any>("steps" to 20, "sampler_name" to "euler")))

    private fun toolbox(characters: List<CharacterLore> = emptyList()) = AgentDrawToolbox(
        AgentWorkflowSnapshot(workflow(), listOf(
            AgentFieldSpec("3", "steps", "KSampler", AgentFieldKind.INTEGER, min = 1.0, max = 60.0),
            AgentFieldSpec("3", "sampler_name", "KSampler", AgentFieldKind.TEXT, options = listOf("euler", "dpmpp_2m")))),
        mapOf("3" to "采样器"), characters)

    @Test fun toolErrorsGoBackAsToolMessagesAndTheModelCanCorrectThem() = runBlocking {
        val box = toolbox()
        val fixture = Fixture(
            call(AgentDrawToolbox.INSPECT, "{}"),
            call(AgentDrawToolbox.PATCH, """{"changes":[{"field":"3:steps","value":99}]}"""),
            call(AgentDrawToolbox.PATCH, """{"changes":[{"field":"3:steps","value":30}]}"""),
            call(AgentDrawToolbox.SET_PROMPT, """{"prompt":"rain, night"}"""),
            call(AgentDrawToolbox.SUBMIT, """{"reply":"画好了","draw":true}"""))
        val steps = mutableListOf<AgentToolStep>()
        val progress = mutableListOf<AgentProgress>()
        val outcome = AgentToolLoop(fixture, box).run(messages, "tools", onStep = { steps += it }, onProgress = { progress += it })

        assertEquals(5, fixture.requests.size)
        assertEquals(listOf(true, false, true, true, true), steps.map { it.ok })
        // The failed patch is returned to the model as a tool result, not thrown.
        val afterFailure = fixture.requests[2].messages.last()
        assertEquals("tool", afterFailure.role)
        assertEquals("c-${AgentDrawToolbox.PATCH}", afterFailure.tool_call_id)
        assertTrue(afterFailure.content!!.contains("\"ok\":false"))
        // Only the valid edit is staged; the invalid batch never applied.
        assertEquals(listOf(AgentFieldChange("3", "steps", 30)), box.changes.map { it.copy(value = (it.value as Number).toInt()) })
        val final = JsonParser.parseString(outcome.output!!.content).asJsonObject
        assertEquals("rain, night", final["prompt"].asString)
        assertTrue(final["should_draw"].asBoolean)
        assertTrue(outcome.output.reasoning!!.contains("【工具步骤】"))
        assertTrue(progress.any { it.activity!!.startsWith("第 3/8 步") })
    }

    @Test fun transcriptIsAppendOnlyWithOneStableToolList() = runBlocking {
        val fixture = Fixture(
            call(AgentDrawToolbox.SET_PROMPT, """{"prompt":"rain"}"""),
            call(AgentDrawToolbox.SUBMIT, """{"reply":"好"}"""))
        AgentToolLoop(fixture, toolbox()).run(messages, "tools")
        val (first, second) = fixture.requests
        assertEquals(first.messages, second.messages.take(first.messages.size))
        assertEquals(first.tools, second.tools)
        assertEquals(AgentInteractionProtocol.ASK, first.tools!!.first().function.name)
        assertEquals("auto", first.toolChoice)
    }

    @Test fun submitWithoutPromptIsRejectedSoTheModelMustWriteOne() = runBlocking {
        val fixture = Fixture(
            call(AgentDrawToolbox.SUBMIT, """{"reply":"好"}"""),
            call(AgentDrawToolbox.SET_PROMPT, """{"prompt":"rain"}"""),
            call(AgentDrawToolbox.SUBMIT, """{"reply":"好","draw":false}"""))
        val outcome = AgentToolLoop(fixture, toolbox()).run(messages, "tools")
        val final = JsonParser.parseString(outcome.output!!.content).asJsonObject
        assertFalse(final["should_draw"].asBoolean)
        assertEquals("apply_prompt", final["action"].asString)
    }

    @Test fun askPausesTheLoopWithTheQuestion() = runBlocking {
        val fixture = Fixture(call(AgentInteractionProtocol.ASK,
            """{"reply":"选哪套？","questions":[{"id":"o","question":"服装？","options":[{"id":"a","label":"常服"},{"id":"b","label":"礼服"}]}]}"""))
        val outcome = AgentToolLoop(fixture, toolbox()).run(messages, "tools")
        assertEquals(AgentAction.ASK_USER, outcome.question!!.action)
        assertNull(outcome.output)
    }

    @Test fun plainFinalAnswerIsAcceptedWhenValid() = runBlocking {
        val fixture = Fixture(LlmCompletionOutput("""{"action":"respond","reply":"聊聊天"}"""))
        val outcome = AgentToolLoop(fixture, toolbox()).run(messages, "tools", validate = { null })
        assertTrue(outcome.output!!.content.contains("聊聊天"))
    }

    @Test fun stepBudgetEndsTheTurnWithoutSubmitting() = runBlocking {
        val fixture = Fixture(*Array(3) { call(AgentDrawToolbox.INSPECT, "{}", id = "i$it") })
        val error = runCatching { AgentToolLoop(fixture, toolbox(), maxSteps = 3).run(messages, "tools") }.exceptionOrNull()
        assertTrue(error is AgentExecutionException)
        assertTrue(error!!.message!!.contains("3 步"))
    }

    @Test fun cancellationIsCheckedEveryStep() = runBlocking {
        val fixture = Fixture(call(AgentDrawToolbox.INSPECT, "{}"), call(AgentDrawToolbox.INSPECT, "{}"))
        var checks = 0
        val error = runCatching {
            AgentToolLoop(fixture, toolbox()).run(messages, "tools", checkCurrent = {
                if (++checks > 3) throw CancellationException("stopped")
            })
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertTrue(fixture.requests.size <= 2)
    }

    @Test fun characterProfileAndConflictingNegativesAreReportedToTheModel() = runBlocking {
        val lore = CharacterLore(id = "rin", name = "凛", triggerWords = "rin, black hair")
        val box = toolbox(listOf(lore))
        val found = box.call(AgentDrawToolbox.PROFILE, JsonParser.parseString("""{"name":"凛"}""").asJsonObject)
        assertTrue(found.ok)
        val missing = box.call(AgentDrawToolbox.PROFILE, JsonParser.parseString("""{"name":"樱"}""").asJsonObject)
        assertFalse(missing.ok)
        assertTrue(missing.error!!.contains("凛"))
        val conflict = box.call(AgentDrawToolbox.SET_PROMPT,
            JsonParser.parseString("""{"prompt":"rain, smile","negative_prompt":"smile"}""").asJsonObject)
        assertFalse(conflict.ok)
        val tags = box.call(AgentDrawToolbox.LOOKUP_TAGS, JsonParser.parseString("""{"query":"rain"}""").asJsonObject)
        assertFalse(tags.ok)
    }
}
