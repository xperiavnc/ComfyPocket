package com.comfyui.mobile.ui

import android.app.Application
import android.content.Context
import android.os.Looper
import android.net.Uri
import android.graphics.Bitmap
import androidx.lifecycle.ViewModelStore
import com.comfyui.mobile.agent.AgentPhase
import com.comfyui.mobile.agent.AgentPromptBinding
import com.comfyui.mobile.agent.AgentPromptBindingStore
import com.comfyui.mobile.agent.AgentPromptSettings
import com.comfyui.mobile.agent.PromptBindingMode
import com.comfyui.mobile.agent.AgentReasoningEffort
import com.comfyui.mobile.model.LlmProfile
import com.comfyui.mobile.model.AgentAttachment
import com.comfyui.mobile.model.AgentQuestionAnswer
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * Real ViewModel, SSE client, reply parser, runner, file loader, tools, preferences and /prompt path.
 * Only the LLM/ComfyUI remote services are loopback fixtures. No paid API or real generation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class AgentRuntimeIntegrationTest {
    private lateinit var app: Application
    private lateinit var server: MockWebServer
    private lateinit var vm: MainViewModel
    private lateinit var store: ViewModelStore
    private val responses = LinkedBlockingQueue<MockResponse>()
    private val llmBodies = CopyOnWriteArrayList<String>()
    /** Separate requests: decisions do not consume a queued final-answer fixture. */
    private val decisionBodies = CopyOnWriteArrayList<String>()
    private val promptBodies = CopyOnWriteArrayList<String>()
    private val uploadBodies = CopyOnWriteArrayList<String>()
    private val gson = Gson()
    private val tagRequests = CopyOnWriteArrayList<RecordedRequest>()
    private var automaticDecisions = true

    private fun nativeAction(name: String, arguments: String, id: String = "call-question"): MockResponse =
        MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: " + gson.toJson(mapOf(
            "choices" to listOf(mapOf("delta" to mapOf("tool_calls" to listOf(mapOf(
                "index" to 0, "id" to id, "type" to "function",
                "function" to mapOf("name" to name, "arguments" to arguments)))),
                "finish_reason" to "tool_calls")))) + "\n\n")

    @Test fun nativeQuestionToolOpensFormBeforeAnyWorkflowMutation() {
        automaticDecisions = false
        responses += nativeAction("ask_user_question",
            """{"reply":"两种要求互斥，需要你来选。","questions":[{"id":"choice","question":"本轮采用哪个方案？","options":[{"id":"first","label":"第一种"},{"id":"second","label":"第二种"}],"multi_select":false}]}""")
        vm.sendAgentMessage("画面里这两个要求有冲突，你先确认")
        awaitDone()
        val last = vm.agentMessages.value.last()
        println("NATIVE_QUESTION phase=${vm.agentSessionState.value.turn?.phase} question=${last.clarification?.questions?.firstOrNull()?.id} submissions=${promptBodies.size}")
        assertEquals(AgentPhase.WAITING_USER, vm.agentSessionState.value.turn!!.phase)
        assertEquals("choice", last.clarification!!.questions!!.single().id)
        assertTrue(promptBodies.isEmpty())
        assertTrue(uploadBodies.isEmpty())
        assertNull(vm.agentWorkflow)
        assertTrue(vm.llmClient.activeRequestIds.isEmpty())
        val request = JsonParser.parseString(decisionBodies.single()).asJsonObject
        assertEquals("required", request["tool_choice"].asString)
        assertFalse(request["parallel_tool_calls"].asBoolean)
        assertEquals(listOf("ask_user_question", "continue_task"),
            request["tools"].asJsonArray.map { it.asJsonObject["function"].asJsonObject["name"].asString })
    }

    @Test fun ignoredNativeToolChoiceRetriesAsJsonAndCanOpenQuestionForm() {
        automaticDecisions = false
        responses += success("我先思考一下，再决定如何处理。")
        responses += success(
            """{"action":"ask_user","reply":"需要你确认。","questions":[{"id":"choice","question":"本轮采用哪个方案？","options":[{"id":"first","label":"第一种"},{"id":"second","label":"第二种"}],"multi_select":false}]}"""
        )
        vm.sendAgentMessage("画面有冲突，先确认方案")
        awaitDone()

        val last = vm.agentMessages.value.last()
        assertEquals(AgentPhase.WAITING_USER, vm.agentSessionState.value.turn!!.phase)
        assertEquals("choice", last.clarification!!.questions!!.single().id)
        assertNull(last.clarification.toolInvocation)
        assertEquals(2, decisionBodies.size)
        assertTrue(promptBodies.isEmpty())
        assertTrue(uploadBodies.isEmpty())
        assertNull(vm.agentWorkflow)

        val nativeRequest = JsonParser.parseString(decisionBodies.first()).asJsonObject
        assertEquals("required", nativeRequest["tool_choice"].asString)
        assertTrue(nativeRequest.has("tools"))
        val fallback = JsonParser.parseString(decisionBodies.last()).asJsonObject
        assertFalse(fallback.has("tool_choice"))
        assertFalse(fallback.has("tools"))
        assertTrue(fallback["messages"].asJsonArray.first().asJsonObject["content"].asString
            .contains("接口兼容动作协议"))
        assertTrue(fallback["messages"].asJsonArray.last().asJsonObject["content"].asString
            .contains("结构化动作兼容重试"))
    }

    @Test fun invalidActionsAfterTheBoundedFallbackShowOneErrorPanelNotDuplicateReplyText() {
        automaticDecisions = false
        responses += success("我先想一想。")
        responses += success("这是一段普通回复，不含动作对象。")
        vm.sendAgentMessage("这里存在冲突，请处理")
        awaitDone()

        val failed = vm.agentMessages.value.last()
        assertEquals(AgentPhase.FAILED, vm.agentSessionState.value.turn!!.phase)
        assertEquals(2, decisionBodies.size)
        assertTrue(failed.content.isBlank())
        assertTrue(failed.errorMessage!!.contains("已尝试一次结构化格式兼容重试"))
        assertTrue(promptBodies.isEmpty())
        assertTrue(uploadBodies.isEmpty())
    }

    @Test fun nativeQuestionInvocationAndAnswersSurviveRoomRestartAndResumeAsAToolResult() {
        automaticDecisions = false
        saveSuzuran()
        responses += nativeAction("ask_user_question",
            """{"reply":"先选服装。","questions":[{"id":"wardrobe","question":"铃兰穿哪套？","options":[{"id":"casual","label":"常服"},{"id":"formal","label":"礼服","description":"正式连衣裙"}],"multi_select":false}]}""",
            "original-question-id")
        vm.sendAgentMessage("画铃兰，背景和动作你来")
        awaitDone()
        val pending = vm.agentMessages.value.last()
        assertEquals("original-question-id", pending.clarification!!.toolInvocation!!.id)
        assertFalse(vm.answerAgentQuestions(pending.id, emptyList()))
        assertEquals(1, decisionBodies.size)
        store.clear()
        kotlinx.coroutines.runBlocking { vm.agentWorkspace.awaitClosed() }
        vm.repository.disconnect()
        vm = MainViewModel(app)
        store = ViewModelStore().apply { put("agent", vm) }
        pumpUntil { vm.agentWorkspace.ready }
        vm._llmBaseUrl.value = server.url("/v1").toString()
        vm._llmApiKey.value = "fixture-key"
        vm._llmModel.value = "fixture-model"
        vm.repository.setBaseUrlOnly(server.url("/").toString())
        assertEquals(AgentPhase.WAITING_USER, vm.agentSessionState.value.turn!!.phase)
        assertEquals(pending.clarification, vm.agentMessages.value.last().clarification)
        responses += nativeAction("continue_task",
            """{"summary":"用户选定礼服，背景动作交给我。","outfit_selections":[{"character_id":"suzuran","outfit_id":"formal"}]}""", "ready-after-answer")
        responses += success("""{"action":"draw","reply":"按礼服绘制。","prompt":"portrait","negative_prompt":"","should_draw":true}""")
        assertTrue(vm.answerAgentQuestions(pending.id, listOf(AgentQuestionAnswer("wardrobe", listOf("formal")))))
        awaitDone()
        assertEquals(2, decisionBodies.size)
        assertEquals(1, llmBodies.size)
        assertEquals(1, promptBodies.size)
        val resumed = JsonParser.parseString(decisionBodies.last()).asJsonObject["messages"].asJsonArray
        val nativeAssistant = resumed.single { it.asJsonObject["tool_calls"]?.isJsonArray == true }.asJsonObject
        assertEquals("original-question-id", nativeAssistant["tool_calls"].asJsonArray.single().asJsonObject["id"].asString)
        val answer = resumed.single { it.asJsonObject["role"].asString == "tool" }.asJsonObject
        assertEquals("original-question-id", answer["tool_call_id"].asString)
        val values = JsonParser.parseString(answer["content"].asString).asJsonObject["answers"].asJsonArray.single().asJsonObject
        assertEquals("wardrobe", values["id"].asString)
        assertEquals("formal", values["option_ids"].asJsonArray.single().asString)
        assertEquals("礼服", values["selected"].asJsonArray.single().asString)
        assertEquals("portrait, suzuran_token, formal_token, dress", graphText())
        assertFalse((graphText() as String).contains("casual_token"))
        assertFalse(vm.answerAgentQuestions(pending.id, listOf(AgentQuestionAnswer("wardrobe", listOf("casual")))))
        println("NATIVE_RESUME restart=true exactToolId=true structuredAnswer=true decisions=${decisionBodies.size} compositions=${llmBodies.size} submissions=${promptBodies.size}")
    }

    @Test fun stoppingDuringTheDecisionPhaseDoesNotComposeOrOpenAPartialQuestion() {
        automaticDecisions = false
        responses += blocked("")
        vm.sendAgentMessage("先核对这里的冲突")
        pumpUntil { decisionBodies.size == 1 }
        assertEquals(AgentPhase.DECIDING, vm.agentSessionState.value.turn!!.phase)
        vm.stopAgentGeneration()
        awaitDone()
        assertEquals(AgentPhase.STOPPED, vm.agentSessionState.value.turn!!.phase)
        assertNull(vm.agentMessages.value.last().clarification)
        assertNull(vm.agentMessages.value.last().activity)
        assertTrue(llmBodies.isEmpty())
        assertTrue(promptBodies.isEmpty())
        assertTrue(vm.llmClient.activeRequestIds.isEmpty())
    }

    @Test fun originalProfileWordsReachActualWorkflowWithoutDuplicatingThemInTheOptionalFixedField() {
        vm.saveCharacterLore(com.comfyui.mobile.model.CharacterLore(
            "saved", "小黑", "user_identity, white hair, red eyes",
            outfits = listOf(com.comfyui.mobile.model.CharacterOutfit("uniform", "校服", "user_uniform"))))
        responses += success("""{"action":"draw","reply":"好","prompt":"portrait, rain","negative_prompt":"","should_draw":true}""")
        vm.sendAgentMessage("帮我生成小黑图片，校服，背景构图动作你来")
        awaitDone()
        assertEquals("portrait, rain, user_identity, white hair, red eyes, user_uniform", graphText())
        assertTrue(vm.agentMessages.value.last().characterStatus!!.contains("补齐 4 项"))
        assertEquals(1, decisionBodies.size)
        assertEquals(1, llmBodies.size)
        assertEquals(1, promptBodies.size)
        println("ORIGINAL_PROFILE actual=${graphText()}")
    }

    @Test fun reviewBlankEditDoesNotGuessClothesOrSubmitTheOldImage() {
        responses += success("""{"action":"draw","reply":"好了","prompt":"cat, red scarf","negative_prompt":"","should_draw":true}""")
        vm.sendAgentMessage("画一只猫")
        awaitDone()
        responses += success("""{"action":"draw","reply":"换好了","prompt":"","negative_prompt":"","should_draw":true}""")
        vm.sendAgentMessage("去掉红围巾，换成蓝色外套")
        awaitDone()
        assertEquals(AgentPhase.FAILED, vm.agentSessionState.value.turn!!.phase)
        assertEquals(1, promptBodies.size)
        assertEquals("cat, red scarf", graphText())
    }

    @Test fun reviewDrawingActuallyReceivesTheAttachedTextDocument() {
        val file = File(vm.agentWorkspace.importsDirectory.apply { mkdirs() }, "scene.txt").apply {
            writeText("SCENE_REFERENCE_782: a cat beside a blue ceramic vase")
        }
        vm._agentPendingAttachments.value = listOf(AgentAttachment(
            "reference", "file", file.toURI().toString(), file.name, file.length(), textContent = file.readText()))
        responses += success("""{"action":"apply_prompt","reply":"好了","prompt":"cat, blue vase","negative_prompt":"","should_draw":false}""")
        vm.sendAgentMessage("参考附件写提示词，先别出图")
        awaitDone()
        assertTrue(decisionBodies.single().contains("SCENE_REFERENCE_782"))
        assertTrue(llmBodies.single().contains("SCENE_REFERENCE_782"))
        assertTrue(promptBodies.isEmpty())
    }

    @Test fun reviewAcceptedSendClearsDraftButRejectedDuplicateKeepsTheNextDraft() {
        vm._agentInteractionMode.value = MainViewModel.AGENT_MODE_CHAT
        vm.updateAgentDraft(AgentInputDraft("发送内容"))
        responses += blocked("partial")
        assertTrue(vm.sendAgentMessage("发送内容"))
        assertEquals("", vm.agentInputDraft.value.text)
        vm.updateAgentDraft(AgentInputDraft("下一条尚未发送"))
        assertFalse(vm.sendAgentMessage("下一条尚未发送"))
        assertEquals("下一条尚未发送", vm.agentInputDraft.value.text)
        vm.stopAgentGeneration()
        awaitDone()
    }

    @Test fun reviewOmittedNegativeChecksThePreservedAutoBoundValueBeforeApplying() {
        withNegativeWorkflow()
        val file = File(MainViewModelWorkflow.savedWorkflowsDir(vm), "agent-fixture.json")
        file.writeText(file.readText().replace("template_embedding, (blurry:1.2)", "cat"))
        responses += success("""{"action":"draw","reply":"好了","prompt":"cat","should_draw":true}""")
        vm.sendAgentMessage("画一只猫")
        awaitDone()
        assertEquals(AgentPhase.FAILED, vm.agentSessionState.value.turn!!.phase)
        assertEquals("before", graphText())
        assertTrue(promptBodies.isEmpty())
    }

    @Test fun reviewFailedAnswerCanRetryTheSameQuestionWithItsOriginalTask() {
        responses += success(askThree)
        vm.sendAgentMessage("画星澜，保留蓝色外套")
        awaitDone()
        val question = vm.agentMessages.value.last()
        responses += MockResponse().setResponseCode(503).setBody("""{"error":{"message":"fixture unavailable"}}""")
        assertTrue(vm.answerAgentQuestions(question.id, threeAnswers))
        awaitDone()
        assertEquals(AgentPhase.FAILED, vm.agentSessionState.value.turn!!.phase)
        assertEquals(question.id, com.comfyui.mobile.agent.AgentQuestionPolicy.pending(vm.agentMessages.value)?.id)
        responses += success("""{"action":"apply_prompt","reply":"好了","prompt":"blue coat, rain","negative_prompt":"","should_draw":false}""")
        vm.updateAgentDraft(AgentInputDraft("另一条还没发送的草稿"))
        assertTrue(vm.answerAgentQuestions(question.id, threeAnswers))
        awaitDone()
        assertEquals(AgentPhase.COMPLETED, vm.agentSessionState.value.turn!!.phase)
        assertEquals("另一条还没发送的草稿", vm.agentInputDraft.value.text)
        assertTrue(llmBodies.last().contains("画星澜，保留蓝色外套"))
        assertTrue(promptBodies.isEmpty())
        assertNull(com.comfyui.mobile.agent.AgentQuestionPolicy.pending(vm.agentMessages.value))
    }

    @Test fun reviewBlankApplyPromptFailsWithoutChangingTheWorkflow() {
        responses += success("""{"action":"apply_prompt","reply":"改好了","prompt":"","should_draw":false}""")
        vm.sendAgentMessage("只要提示词，给猫加一条蓝围巾")
        awaitDone()
        assertEquals(AgentPhase.FAILED, vm.agentSessionState.value.turn!!.phase)
        assertTrue(promptBodies.isEmpty())
        assertNull(vm.agentWorkflow)
        assertEquals("", vm.agentPromptOutput.value.positive)
    }

    @Test fun reviewDocumentAddedWhileAnsweringSurvivesAnotherFollowupQuestion() {
        responses += success(askThree)
        vm.sendAgentMessage("画星澜，背景稍后确认")
        awaitDone()
        val first = vm.agentMessages.value.last()
        val file = File(vm.agentWorkspace.importsDirectory.apply { mkdirs() }, "extra-reference.txt")
            .apply { writeText("FOLLOWUP_DOCUMENT_613") }
        vm._agentPendingAttachments.value = listOf(AgentAttachment(
            "extra-reference", "file", file.toURI().toString(), file.name, textContent = file.readText()))
        responses += success("""{"action":"ask_user","questions":[{"id":"light","prompt":"选择光线","kind":"free_text"}]}""")
        assertTrue(vm.answerAgentQuestions(first.id, threeAnswers))
        awaitDone()
        val next = vm.agentMessages.value.last()
        responses += success("""{"action":"apply_prompt","reply":"好","prompt":"portrait, rain","negative_prompt":"","should_draw":false}""")
        assertTrue(vm.answerAgentQuestions(next.id, listOf(AgentQuestionAnswer("light", text = "晨光"))))
        awaitDone()
        assertEquals(AgentPhase.COMPLETED, vm.agentSessionState.value.turn!!.phase)
        val lastRequest = JsonParser.parseString(llmBodies.last()).asJsonObject["messages"].asJsonArray
            .last { it.asJsonObject["role"].asString == "user" }.asJsonObject["content"].asString
        assertTrue(lastRequest.contains("FOLLOWUP_DOCUMENT_613"))
        assertTrue(lastRequest.contains("画星澜，背景稍后确认"))
        assertTrue(lastRequest.contains("晨光"))
        assertTrue(promptBodies.isEmpty())
    }

    @Test fun reviewNativeQuestionRetrySurvivesRestartAndKeepsItsInvocationAndDocument() {
        vm._agentInteractionMode.value = MainViewModel.AGENT_MODE_CHAT
        val profile = vm.llmProfiles.value.first()
        // AndroidKeyStore is not emulated by this fixture; only its test key is injected.
        vm.addOrUpdateLlmProfile(profile.copy(baseUrl = server.url("/v1").toString(),
            apiKey = "", model = "fixture-model"))
        vm._llmApiKey.value = "fixture-key"
        val file = File(vm.agentWorkspace.importsDirectory.apply { mkdirs() }, "reference.txt")
            .apply { writeText("TASK_REFERENCE_925") }
        vm._agentPendingAttachments.value = listOf(AgentAttachment(
            "reference-925", "file", file.toURI().toString(), file.name, textContent = file.readText()))
        automaticDecisions = false
        responses += nativeAction("ask_user_question",
            """{"questions":[{"id":"choice","question":"选哪项？","options":[{"id":"a","label":"第一项"},{"id":"b","label":"第二项"}]}]}""",
            "retry-native-call")
        vm.sendAgentMessage("参考附件比较两个方案")
        awaitDone()
        val question = vm.agentMessages.value.last()
        val answers = listOf(AgentQuestionAnswer("choice", listOf("b")))
        responses += MockResponse().setResponseCode(503).setBody("""{"error":{"message":"fixture unavailable"}}""")
        assertTrue(vm.answerAgentQuestions(question.id, answers))
        awaitDone()
        assertEquals(question.id, com.comfyui.mobile.agent.AgentQuestionPolicy.pending(vm.agentMessages.value)?.id)
        store.clear()
        kotlinx.coroutines.runBlocking { vm.agentWorkspace.awaitClosed() }
        vm.repository.disconnect()
        vm = MainViewModel(app)
        store = ViewModelStore().apply { put("agent", vm) }
        pumpUntil { vm.agentWorkspace.ready }
        vm._llmApiKey.value = "fixture-key"
        assertEquals(question.id, com.comfyui.mobile.agent.AgentQuestionPolicy.pending(vm.agentMessages.value)?.id)
        assertEquals(AgentPhase.WAITING_USER, vm.agentSessionState.value.turn!!.phase)
        automaticDecisions = true
        responses += success("比较结果：第二项")
        assertTrue(vm.answerAgentQuestions(question.id, answers))
        awaitDone()
        assertEquals(vm.agentMessages.value.last().toString(), AgentPhase.COMPLETED, vm.agentSessionState.value.turn!!.phase)
        val request = JsonParser.parseString(decisionBodies.last()).asJsonObject["messages"].asJsonArray
        assertTrue(request.toString().contains("TASK_REFERENCE_925"))
        assertTrue(request.any { it.asJsonObject["tool_call_id"]?.asString == "retry-native-call" })
        assertTrue(promptBodies.isEmpty())
    }

    @Test fun reviewQuestionIsNotReopenedAfterAWorkflowExecutionFailure() {
        responses += success(askThree)
        vm.sendAgentMessage("画星澜")
        awaitDone()
        val question = vm.agentMessages.value.last()
        File(MainViewModelWorkflow.savedWorkflowsDir(vm), "agent-fixture.json").delete()
        responses += success("""{"action":"draw","reply":"开始","prompt":"portrait","negative_prompt":"","should_draw":true}""")
        assertTrue(vm.answerAgentQuestions(question.id, threeAnswers))
        awaitDone()
        assertEquals(AgentPhase.FAILED, vm.agentSessionState.value.turn!!.phase)
        assertNull(com.comfyui.mobile.agent.AgentQuestionPolicy.pending(vm.agentMessages.value))
        assertFalse(vm.answerAgentQuestions(question.id, threeAnswers))
        assertTrue(promptBodies.isEmpty())
    }

    private fun event(text: String) = "data: " + gson.toJson(mapOf(
        "choices" to listOf(mapOf("delta" to mapOf("content" to text)))
    )) + "\n\n"
    private fun success(text: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(event(text) + "data: [DONE]\n\n")
    private fun blocked(text: String): MockResponse {
        val partial = event(text)
        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(partial)
            .setHeader("Content-Length", partial.toByteArray().size + 1000)
    }

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("comfy_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path?.substringBefore('?')) {
                "/v1/chat/completions" -> {
                    val body = request.body.readUtf8()
                    val json = JsonParser.parseString(body).asJsonObject
                    val deciding = json["tool_choice"]?.asString == "required" ||
                        json["messages"].asJsonArray.first().asJsonObject["content"].asString.contains("交互决策步骤")
                    if (deciding) decisionBodies += body else llmBodies += body
                    if (deciding && automaticDecisions)
                        nativeAction("continue_task", """{"summary":"本地模拟模型决定继续，后续响应由测试指定。"}""", "call-ready")
                    else responses.poll(5, TimeUnit.SECONDS) ?: MockResponse().setResponseCode(500)
                }
                "/tags.json", "/tag_aliases.json", "/wiki_pages/cat.json" -> {
                    tagRequests += request
                    MockResponse().setResponseCode(410)
                }
                "/prompt" -> {
                    promptBodies += request.body.readUtf8()
                    MockResponse().setBody("""{"prompt_id":"fixture-pid-${promptBodies.size}"}""")
                }
                "/upload/image" -> {
                    uploadBodies += request.body.readUtf8()
                    MockResponse().setBody("""{"name":"uploaded.png","subfolder":"","type":"input"}""")
                }
                "/queue" -> MockResponse().setBody("""{"queue_running":[],"queue_pending":[]}""")
                else -> MockResponse().setBody("{}")
            }
        }
        server.start()
        vm = MainViewModel(app)
        pumpUntil { vm.agentWorkspace.ready }
        vm._agentPromptSettings.value = AgentPromptSettings(useLocalLibrary = false)
        store = ViewModelStore().apply { put("agent", vm) }
        vm._servers.value = emptyList()
        vm._currentServerId.value = ""
        vm.repository.setCloudActive(false)
        vm.repository.setBaseUrlOnly(server.url("/").toString())
        vm._llmBaseUrl.value = server.url("/v1").toString()
        vm._llmApiKey.value = "fixture-key"
        vm._llmModel.value = "fixture-model"
        val file = File(MainViewModelWorkflow.savedWorkflowsDir(vm), "agent-fixture.json")
        file.writeText("""{"1":{"class_type":"CLIPTextEncode","inputs":{"text":"before"},"_meta":{"title":"positive prompt"}}}""")
        vm._agentWorkflowSource.value = MainViewModel.AGENT_WF_SAVED
        vm._agentWorkflowId.value = file.name
        vm._agentWorkflowLabel.value = "fixture"
        vm.agentWorkspace.saveConfiguration()
        flushWorkspace()
    }

    @After fun close() {
        if (::store.isInitialized) store.clear()
        if (::vm.isInitialized) kotlinx.coroutines.runBlocking { vm.agentWorkspace.awaitClosed() }
        if (::vm.isInitialized) {
            pumpUntil { vm.llmClient.activeRequestIds.isEmpty() }
            vm.repository.disconnect()
        }
        if (::server.isInitialized) server.shutdown()
    }

    private fun pumpUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        do {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            if (condition()) return
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        fail("Agent fixture timed out: ${vm.agentSessionState.value}, ${vm.agentMessages.value}")
    }
    private fun flushWorkspace() = kotlinx.coroutines.runBlocking { vm.agentWorkspace.flush() }
    private fun awaitDone() {
        pumpUntil { !vm.agentBusy.value }
        flushWorkspace()
    }
    private fun graphText() = ((vm.agentWorkflow!!["1"] as Map<*, *>)["inputs"] as Map<*, *>)["text"]

    private val askThree = """{"action":"ask_user","reply":"先确认构图、配件和背景。","prompt":"must_not_apply","negative_prompt":"must_not_apply","should_draw":true,"questions":[{"id":"framing","prompt":"构图用哪种？","kind":"multiple_choice","options":[{"id":"full","label":"全身"},{"id":"half","label":"半身"}]},{"id":"details","prompt":"保留哪些配件？","kind":"multiple_choice","allow_multiple":true,"options":[{"id":"hat","label":"帽子"},{"id":"gloves","label":"手套"}]},{"id":"background","prompt":"背景有什么要求？","kind":"free_text"}]}"""
    private val threeAnswers get() = listOf(
        AgentQuestionAnswer("framing", listOf("full")),
        AgentQuestionAnswer("details", listOf("hat", "gloves")),
        AgentQuestionAnswer("background", text = "雨夜街道"))

    private fun saveSuzuran() = vm.saveCharacterLore(com.comfyui.mobile.model.CharacterLore(
        "suzuran", "铃兰", "suzuran_token", fixedTriggerWords = "suzuran_token",
        outfits = listOf(
            com.comfyui.mobile.model.CharacterOutfit("casual", "常服", "casual_token, shirt"),
            com.comfyui.mobile.model.CharacterOutfit("formal", "礼服", "formal_token, dress"),
            com.comfyui.mobile.model.CharacterOutfit("winter", "冬装", "winter_token, coat")),
        defaultOutfitId = "casual"))

    @Test fun suzuranDefaultStillSuppliesAllOutfitsAndModelQuestionResumesWithAliasesAndRoomRecovery() {
        withNegativeWorkflow()
        saveSuzuran()
        responses += success("""{"action":"ask_user_question","reply":"铃兰有三套已登记服装，这次想用哪套？","questions":[{"id":"suzuran-outfit","question":"铃兰本轮服装","options":[{"id":"casual","label":"常服","description":"日常衬衫"},{"id":"formal","label":"礼服","description":"正式连衣裙"},{"id":"winter","label":"冬装","description":"保暖外套"}],"multi_select":false}]}""")
        vm.sendAgentMessage("画铃兰")
        awaitDone()
        val initialSystem = JsonParser.parseString(llmBodies.single()).asJsonObject["messages"].asJsonArray
            .first().asJsonObject["content"].asString
        for (word in listOf("casual_token", "formal_token", "winter_token", "档案默认回退"))
            assertTrue(word, initialSystem.contains(word))
        val pending = vm.agentMessages.value.last()
        assertEquals(AgentPhase.WAITING_USER, vm.agentSessionState.value.turn!!.phase)
        assertTrue(promptBodies.isEmpty())
        assertEquals("正式连衣裙", pending.clarification!!.questions!!.single().options!![1].description)
        responses += success("""{"action":"draw","reply":"按礼服绘制。","prompt":"portrait, formal_token, dress","negative_prompt":"blurry","prompt_aliases":[{"tag":"portrait","zh":"肖像"},{"tag":"formal_token","zh":"礼服触发词"},{"tag":"dress","zh":"连衣裙"},{"tag":"suzuran_token","zh":"角色触发词"},{"tag":"casual_token","zh":"未选常服"}],"negative_prompt_aliases":[{"tag":"blurry","zh":"模糊"}],"should_draw":true}""")
        assertTrue(vm.answerAgentQuestions(pending.id, listOf(AgentQuestionAnswer("suzuran-outfit", listOf("formal")))))
        awaitDone()
        assertEquals(2, llmBodies.size)
        assertEquals(1, promptBodies.size)
        assertEquals("portrait, formal_token, dress, suzuran_token", graphText())
        val answerContext = JsonParser.parseString(llmBodies.last()).asJsonObject["messages"].asJsonArray.last { it.asJsonObject["role"].asString == "user" }
            .asJsonObject["content"].asString
        assertTrue(answerContext.contains("\"id\":\"suzuran-outfit\""))
        assertTrue(answerContext.contains("\"option_ids\":[\"formal\"]"))
        assertTrue(answerContext.contains("\"selected\":[\"礼服\"]"))
        assertEquals("blurry", submittedNegative())
        val result = vm.agentMessages.value.last()
        assertNull(result.clarification)
        assertEquals("formal", result.characterSelections!!.single().outfitId)
        assertEquals(listOf("portrait", "formal_token", "dress", "suzuran_token"), result.positiveAliases!!.map { it.tag })
        assertEquals("模糊", result.negativeAliases!!.single().zh)
        assertEquals(result.positiveAliases, vm.agentPromptOutput.value.positiveAliases)
        assertFalse((graphText() as String).contains("肖像"))
        assertFalse((graphText() as String).contains("casual_token"))
        val savedOutput = vm.agentPromptOutput.value
        store.clear()
        kotlinx.coroutines.runBlocking { vm.agentWorkspace.awaitClosed() }
        vm.repository.disconnect()
        vm = MainViewModel(app)
        store = ViewModelStore().apply { put("agent", vm) }
        pumpUntil { vm.agentWorkspace.ready }
        assertEquals(savedOutput, vm.agentPromptOutput.value)
        assertEquals(result.positiveAliases, vm.agentMessages.value.last().positiveAliases)
        assertEquals(result.negativeAliases, vm.agentMessages.value.last().negativeAliases)
        println("SUZURAN_MODEL_ASK candidates=3 calls=${llmBodies.size} submissions=${promptBodies.size} outfit=formal aliases=${savedOutput.positiveAliases!!.size}/1 roomRestored=true")
    }

    @Test fun delegatedModelOutfitReplacesDefaultAndIsRetainedOnTheNextBackgroundEdit() {
        saveSuzuran()
        responses += success("""{"action":"draw","reply":"选择冬装。","prompt":"portrait, winter_token","negative_prompt":"","outfit_selections":[{"character_id":"suzuran","outfit_id":"winter"}],"should_draw":true}""")
        vm.sendAgentMessage("画铃兰，服装你来选")
        awaitDone()
        assertEquals("portrait, winter_token, suzuran_token, coat", graphText())
        assertEquals("winter", vm.agentMessages.value.last().characterSelections!!.single().outfitId)
        assertNull(vm.agentMessages.value.last().clarification)
        responses += success("""{"action":"draw","reply":"保持冬装，换背景。","prompt":"portrait, rain","negative_prompt":"","should_draw":true}""")
        vm.sendAgentMessage("保持人物服装，背景改为雨夜")
        awaitDone()
        assertEquals("winter", vm.agentMessages.value.last().characterSelections!!.single().outfitId)
        assertEquals("portrait, rain, suzuran_token, winter_token, coat", graphText())
        assertEquals(2, llmBodies.size)
        assertEquals(2, promptBodies.size)
        println("MODEL_OUTFIT_DEFAULT_OVERRIDE outfit=winter persisted=true localQuestion=false")
    }

    @Test fun omittedAndEmptyNegativeNeverReuseStaleChineseAliases() {
        withNegativeWorkflow()
        responses += success("""{"action":"draw","reply":"好","prompt":"cat","negative_prompt":"blurry","prompt_aliases":[{"tag":"cat","zh":"猫"}],"negative_prompt_aliases":[{"tag":"blurry","zh":"模糊"}],"should_draw":true}""")
        vm.sendAgentMessage("画猫")
        awaitDone()
        assertEquals("模糊", vm.agentPromptOutput.value.negativeAliases!!.single().zh)
        responses += success("""{"action":"draw","reply":"好","prompt":"cat, rain","should_draw":true}""")
        vm.sendAgentMessage("加上雨景")
        awaitDone()
        assertEquals("blurry", vm.agentPromptOutput.value.negative)
        assertNull(vm.agentPromptOutput.value.negativeAliases)
        assertNull(vm.agentPromptOutput.value.positiveAliases)
        responses += success("""{"action":"draw","reply":"清空反向","prompt":"cat, rain","negative_prompt":"","negative_prompt_aliases":[{"tag":"blurry","zh":"模糊"}],"should_draw":true}""")
        vm.sendAgentMessage("清空反向，再画")
        awaitDone()
        assertEquals("", submittedNegative())
        assertEquals("", vm.agentPromptOutput.value.negative)
        assertNull(vm.agentPromptOutput.value.negativeAliases)
        assertEquals(3, llmBodies.size)
    }

    @Test fun savedOutfitsNeverPreemptTheModelsQuestionOrResponse() {
        vm._characterLores.value = listOf(com.comfyui.mobile.model.CharacterLore(
            "star", "星澜", "xl_token", aliases = listOf("小黑"),
            outfits = listOf(
                com.comfyui.mobile.model.CharacterOutfit("one", "常服", "casual_token"),
                com.comfyui.mobile.model.CharacterOutfit("two", "礼服", "dress_token"))))
        responses += success("""{"action":"respond","reply":"先讨论你的画面要求。","should_draw":false}""")
        vm.sendAgentMessage("我要画小黑图片，你来提问我要求试试")
        awaitDone()
        val message = vm.agentMessages.value.last()
        println("QUESTION_OWNER calls=${llmBodies.size} phase=${vm.agentSessionState.value.turn!!.phase} question=${message.clarification?.question}")
        assertEquals(1, llmBodies.size)
        assertEquals("先讨论你的画面要求。", message.content)
        assertNull(message.clarification)
        assertEquals(AgentPhase.COMPLETED, vm.agentSessionState.value.turn!!.phase)
        assertTrue(promptBodies.isEmpty())
    }

    @Test fun multipleOutfitsDoNotBlockAModelThatChoosesToDrawWithoutQuestions() {
        vm._characterLores.value = listOf(com.comfyui.mobile.model.CharacterLore(
            "star", "星澜", "xl_token", fixedTriggerWords = "xl_token",
            outfits = listOf(
                com.comfyui.mobile.model.CharacterOutfit("one", "常服", "casual_token"),
                com.comfyui.mobile.model.CharacterOutfit("two", "礼服", "dress_token"))))
        responses += success("""{"action":"draw","reply":"按本轮要求绘制。","prompt":"portrait, casual_token","negative_prompt":"","should_draw":true}""")
        vm.sendAgentMessage("画星澜")
        awaitDone()
        assertEquals(1, llmBodies.size)
        assertEquals(1, promptBodies.size)
        assertEquals("portrait, casual_token, xl_token", graphText())
        assertNull(vm.agentMessages.value.last().clarification)
        assertEquals(AgentPhase.COMPLETED, vm.agentSessionState.value.turn!!.phase)
        println("MODEL_DIRECT calls=${llmBodies.size} submissions=${promptBodies.size} positive=${graphText()}")
    }

    @Test fun modelAskedOutfitAnswersContinueWithoutTheRemovedLocalQuestionLoop() {
        vm._characterLores.value = listOf(com.comfyui.mobile.model.CharacterLore(
            "star", "星澜", "xl_token", aliases = listOf("小黑"), fixedTriggerWords = "xl_token",
            outfits = listOf(
                com.comfyui.mobile.model.CharacterOutfit("one", "常服", "casual_token"),
                com.comfyui.mobile.model.CharacterOutfit("two", "礼服", "dress_token"))))
        responses += success("""{"action":"ask_user","reply":"这次想穿哪套？","questions":[{"id":"outfit","prompt":"服装选哪套？","kind":"multiple_choice","options":[{"id":"casual","label":"常服"},{"id":"dress","label":"礼服"}]}]}""")
        val task = "我要画小黑图片，你来提问我要求试试"
        vm.sendAgentMessage(task)
        awaitDone()
        assertEquals(1, llmBodies.size)
        assertEquals(AgentPhase.WAITING_USER, vm.agentSessionState.value.turn!!.phase)
        assertTrue(promptBodies.isEmpty())
        val message = vm.agentMessages.value.last()
        assertEquals("这次想穿哪套？", message.content)
        responses += success("""{"action":"draw","reply":"按常服绘制。","prompt":"portrait","negative_prompt":"","should_draw":true}""")
        assertTrue(vm.answerAgentQuestions(message.id, listOf(AgentQuestionAnswer("outfit", listOf("casual")))))
        awaitDone()
        assertEquals(2, llmBodies.size)
        assertEquals(1, promptBodies.size)
        assertEquals("portrait, xl_token, casual_token", graphText())
        assertEquals("one", vm.agentMessages.value.last().characterSelections!!.single().outfitId)
        val current = JsonParser.parseString(llmBodies.last()).asJsonObject["messages"].asJsonArray
            .last { it.asJsonObject["role"].asString == "user" }.asJsonObject["content"].asString
        assertTrue(current.contains(task))
        assertTrue(current.contains("常服"))
        assertFalse(current.contains("礼服"))
        assertNull(vm.agentMessages.value.last().clarification)
        println("MODEL_OUTFIT_ANSWER calls=${llmBodies.size} submissions=${promptBodies.size} positive=${graphText()}")
    }

    @Test fun plainReplyAfterAnEditNeitherStartsAFormNorRendersTheOldPrompt() {
        responses += success("""{"action":"draw","reply":"好","prompt":"cat, rain","should_draw":true}""")
        vm.sendAgentMessage("画猫")
        awaitDone()
        responses += success("请先确认想用什么背景，等你回答后我再处理。")
        vm.sendAgentMessage("把背景改一下")
        awaitDone()
        assertEquals(2, llmBodies.size)
        assertEquals(1, promptBodies.size)
        assertEquals("cat, rain", graphText())
        assertNull(vm.agentMessages.value.last().clarification)
        assertNull(vm.agentMessages.value.last().expandedPrompt)
        assertEquals(AgentPhase.COMPLETED, vm.agentSessionState.value.turn!!.phase)
    }

    @Test fun plainWaitingWordsDoNotTriggerAQuestionOrRecoverPreviousImage() {
        responses += success("""{"reply":"好","prompt":"cat, rain","should_draw":true}""")
        vm.sendAgentMessage("画猫")
        awaitDone()
        responses += success("可以，请先确认 3 点：\n1）使用哪套服装？\n2）保留帽子和手套吗？\n3）全身还是半身？你确认后我再改提示词。")
        vm.sendAgentMessage("你能提问我吗")
        awaitDone()
        val message = vm.agentMessages.value.last()
        println("ASK_KEYWORD phase=${vm.agentSessionState.value.turn!!.phase} calls=${llmBodies.size} submissions=${promptBodies.size} positive=${graphText()}")
        assertEquals(AgentPhase.COMPLETED, vm.agentSessionState.value.turn!!.phase)
        assertEquals("cat, rain", graphText())
        assertEquals(1, promptBodies.size)
        assertNull(message.expandedPrompt)
        assertNull(message.errorMessage)
        assertTrue(message.content.contains("3）全身还是半身"))
        assertNull(message.clarification)
    }

    @Test fun autonomousBatchQuestionRejectsPartialAndStaleAnswersThenContinuesExactlyOnce() {
        responses += success(askThree)
        vm.sendAgentMessage("画星澜，保留蓝色外套")
        awaitDone()
        assertNull(vm.agentWorkflow)
        assertTrue(promptBodies.isEmpty())
        val message = vm.agentMessages.value.last()
        val size = vm.agentMessages.value.size
        assertEquals(3, message.clarification!!.questions!!.size)
        assertEquals(AgentPhase.WAITING_USER, vm.agentSessionState.value.turn!!.phase)
        assertFalse(vm.answerAgentQuestions(message.id, threeAnswers.dropLast(1)))
        assertFalse(vm.answerAgentQuestions("old-message", threeAnswers))
        assertEquals(size, vm.agentMessages.value.size)
        assertEquals(1, llmBodies.size)
        responses += success("""{"action":"draw","reply":"按你的选择绘制","prompt":"blue coat, full body, hat, gloves, rainy street","negative_prompt":"","should_draw":true}""")
        assertTrue(vm.answerAgentQuestions(message.id, threeAnswers))
        assertFalse(vm.answerAgentQuestions(message.id, threeAnswers))
        awaitDone()
        assertEquals(AgentPhase.COMPLETED, vm.agentSessionState.value.turn!!.phase)
        assertEquals(2, llmBodies.size)
        assertEquals(1, promptBodies.size)
        val current = JsonParser.parseString(llmBodies.last()).asJsonObject["messages"].asJsonArray.last { it.asJsonObject["role"].asString == "user" }
            .asJsonObject["content"].asString
        assertTrue(current.contains("画星澜，保留蓝色外套"))
        assertTrue(current.contains("全身"))
        assertTrue(current.contains("帽子；手套"))
        assertTrue(current.contains("雨夜街道"))
        assertFalse(current.contains("半身"))
        assertEquals(message.id, MainViewModelAgent.loadAgentMessages(vm).last { it.role == "user" }.clarificationAnswer?.messageId)
        println("ASK_BATCH calls=${llmBodies.size} submissions=${promptBodies.size} answerLinked=true")
    }

    @Test fun modelCanAskAgainAndAllConfirmedAnswersStayInRequiredContext() {
        responses += success(askThree)
        vm.sendAgentMessage("画星澜，保留蓝色外套")
        awaitDone()
        responses += success("""{"action":"ask_user","reply":"再确认光照。","questions":[{"id":"light","prompt":"光照有什么要求？","kind":"free_text"}]}""")
        vm.answerAgentQuestions(vm.agentMessages.value.last().id, threeAnswers)
        awaitDone()
        assertTrue(promptBodies.isEmpty())
        assertEquals(AgentPhase.WAITING_USER, vm.agentSessionState.value.turn!!.phase)
        assertEquals(1, vm.agentMessages.value.last().clarification!!.answerHistory!!.size)
        responses += success("""{"action":"draw","reply":"好","prompt":"blue coat, full body, rain, neon lighting","should_draw":true}""")
        vm.answerAgentQuestions(vm.agentMessages.value.last().id, listOf(AgentQuestionAnswer("light", text = "霓虹光")))
        awaitDone()
        val current = JsonParser.parseString(llmBodies.last()).asJsonObject["messages"].asJsonArray.last { it.asJsonObject["role"].asString == "user" }
            .asJsonObject["content"].asString
        for (term in listOf("画星澜，保留蓝色外套", "全身", "帽子；手套", "雨夜街道", "霓虹光")) assertTrue(current.contains(term))
        assertEquals(3, llmBodies.size)
        assertEquals(1, promptBodies.size)
    }

    @Test fun unselectedNamesInQuestionWordingNeverActivateOtherCharacterRecords() {
        vm._characterLores.value = listOf(
            com.comfyui.mobile.model.CharacterLore("a", "星澜", "a_token", fixedTriggerWords = "a_token"),
            com.comfyui.mobile.model.CharacterLore("b", "月汐", "b_token", fixedTriggerWords = "b_token"))
        responses += success("""{"action":"ask_user","questions":[{"id":"who","prompt":"想画星澜还是月汐？","kind":"multiple_choice","options":[{"id":"a","label":"星澜"},{"id":"b","label":"月汐"}]}]}""")
        vm.sendAgentMessage("画一个角色")
        awaitDone()
        responses += success("""{"action":"ask_user","questions":[{"id":"bg","prompt":"什么背景？","kind":"free_text"}]}""")
        vm.answerAgentQuestions(vm.agentMessages.value.last().id, listOf(AgentQuestionAnswer("who", listOf("b"))))
        awaitDone()
        assertEquals(listOf("b"), vm.agentMessages.value.last().characterSelections!!.map { it.loreId })
        responses += success("""{"action":"draw","reply":"好","prompt":"b_token, garden","should_draw":true}""")
        vm.answerAgentQuestions(vm.agentMessages.value.last().id, listOf(AgentQuestionAnswer("bg", text = "花园")))
        awaitDone()
        assertEquals(listOf("b"), vm.agentMessages.value.last().characterSelections!!.map { it.loreId })
        assertEquals("b_token, garden", graphText())
        assertEquals(1, promptBodies.size)
    }

    @Test fun cancellingPendingQuestionIsLocalAndDoesNotSubmitOrRecallItLater() {
        responses += success(askThree)
        vm.sendAgentMessage("画星澜")
        awaitDone()
        val pendingId = vm.agentMessages.value.last().id
        vm.sendAgentMessage("取消本次提问")
        awaitDone()
        assertEquals(1, llmBodies.size)
        assertTrue(promptBodies.isEmpty())
        assertNull(vm.agentWorkflow)
        assertNull(vm.agentMessages.value.last().clarification)
        assertFalse(vm.answerAgentQuestions(pendingId, threeAnswers))
        assertEquals(AgentPhase.COMPLETED, vm.agentSessionState.value.turn!!.phase)
    }

    @Test fun newTaskDoesNotInheritPendingSourceRequestOrReferenceImage() {
        val file = File(app.cacheDir, "unread-reference.jpg").apply { writeText("not an image") }
        vm._agentPendingAttachments.value = listOf(AgentAttachment("old", "image", Uri.fromFile(file).toString(), file.name))
        responses += success(askThree)
        vm.sendAgentMessage("画星澜，保留蓝色外套")
        awaitDone()
        responses += success("""{"action":"draw","reply":"改做新任务","prompt":"dog","should_draw":true}""")
        vm.sendAgentMessage("画一只狗")
        awaitDone()
        val last = JsonParser.parseString(llmBodies.last()).asJsonObject["messages"].asJsonArray.last { it.asJsonObject["role"].asString == "user" }.asJsonObject["content"]
        assertTrue(last.isJsonPrimitive)
        assertTrue(last.asString.contains("画一只狗"))
        assertFalse(last.asString.contains("蓝色外套"))
        assertTrue(uploadBodies.isEmpty())
        assertEquals("dog", graphText())
        assertEquals(1, promptBodies.size)
    }

    @Test fun waitingQuestionAndOriginalImageSurviveViewModelRestartThenUploadOnlyAfterAnswer() {
        File(MainViewModelWorkflow.savedWorkflowsDir(vm), "agent-fixture.json").writeText(
            """{"1":{"class_type":"CLIPTextEncode","inputs":{"text":"before"}},"2":{"class_type":"LoadImage","inputs":{"image":"before.png"}}}""")
        // Production selection stages files under the managed imports directory, not arbitrary cache.
        val image = File(vm.agentWorkspace.importsDirectory.apply { mkdirs() }, "question-reference.jpg")
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bitmap.recycle()
        vm._agentPendingAttachments.value = listOf(AgentAttachment("reference", "image", Uri.fromFile(image).toString(), image.name))
        responses += success(askThree)
        vm.sendAgentMessage("参考这张图画星澜，保留蓝色外套")
        awaitDone()
        assertTrue(uploadBodies.isEmpty())
        assertTrue(promptBodies.isEmpty())
        store.clear()
        kotlinx.coroutines.runBlocking { vm.agentWorkspace.awaitClosed() }
        vm.repository.disconnect()
        vm = MainViewModel(app)
        store = ViewModelStore().apply { put("agent", vm) }
        pumpUntil { vm.agentWorkspace.ready }
        vm.repository.setCloudActive(false)
        vm.repository.setBaseUrlOnly(server.url("/").toString())
        vm._llmBaseUrl.value = server.url("/v1").toString()
        vm._llmApiKey.value = "fixture-key"
        vm._llmModel.value = "fixture-model"
        vm._agentPromptSettings.value = AgentPromptSettings(useLocalLibrary = false)
        val pending = vm.agentMessages.value.last()
        assertEquals(AgentPhase.WAITING_USER, vm.agentSessionState.value.turn!!.phase)
        assertEquals(3, pending.clarification!!.questions!!.size)
        assertTrue(File(Uri.parse(pending.attachments!!.single().uri).path!!).exists())
        responses += success("""{"action":"draw","reply":"好","prompt":"blue coat, full body, rain","should_draw":true}""")
        assertTrue(vm.answerAgentQuestions(pending.id, threeAnswers))
        awaitDone()
        assertEquals(1, uploadBodies.size)
        assertEquals(1, promptBodies.size)
        val graph = JsonParser.parseString(promptBodies.single()).asJsonObject["prompt"].asJsonObject
        assertEquals("uploaded.png", graph["2"].asJsonObject["inputs"].asJsonObject["image"].asString)
        val last = JsonParser.parseString(llmBodies.last()).asJsonObject["messages"].asJsonArray.last { it.asJsonObject["role"].asString == "user" }.asJsonObject["content"].asJsonArray
        assertTrue(last[0].asJsonObject["text"].asString.contains("参考这张图画星澜"))
        assertEquals("image_url", last[1].asJsonObject["type"].asString)
        println("ASK_RESTART phase=${vm.agentSessionState.value.turn!!.phase} uploads=${uploadBodies.size} submissions=${promptBodies.size}")
    }

    @Test fun falseDrawingFlagAndRespondActionNeverRecycleOrPatchLastImage() {
        responses += success("""{"reply":"好","prompt":"cat","should_draw":true}""")
        vm.sendAgentMessage("画猫")
        awaitDone()
        responses += success("""{"reply":"先讨论方案。","prompt":"","should_draw":false}""")
        vm.sendAgentMessage("换成全身")
        awaitDone()
        assertEquals(AgentPhase.COMPLETED, vm.agentSessionState.value.turn!!.phase)
        assertNull(vm.agentMessages.value.last().errorMessage)
        responses += success("""{"action":"respond","reply":"这里只说明设置。","prompt":"must_not_patch","should_draw":true}""")
        vm.sendAgentMessage("解释一下")
        awaitDone()
        assertEquals("cat", graphText())
        assertEquals(1, promptBodies.size)
        assertNull(vm.agentMessages.value.last().expandedPrompt)
    }

    @Test fun chatCanAutonomouslyAskAndResumeWithoutAnyWorkflowSideEffect() {
        vm._agentInteractionMode.value = MainViewModel.AGENT_MODE_CHAT
        responses += success(askThree)
        vm.sendAgentMessage("帮我整理一下需求")
        awaitDone()
        assertEquals(AgentPhase.WAITING_USER, vm.agentSessionState.value.turn!!.phase)
        responses += success("收到你的回答，已整理成需求清单。")
        vm.answerAgentQuestions(vm.agentMessages.value.last().id, threeAnswers)
        awaitDone()
        assertEquals(2, llmBodies.size)
        assertEquals("收到你的回答，已整理成需求清单。", vm.agentMessages.value.last().content)
        assertNull(vm.agentWorkflow)
        assertTrue(promptBodies.isEmpty())
        assertTrue(uploadBodies.isEmpty())
    }

    private fun withNegativeWorkflow() {
        File(MainViewModelWorkflow.savedWorkflowsDir(vm), "agent-fixture.json").writeText(
            """{"2":{"class_type":"CLIPTextEncode","inputs":{"text":"template_embedding, (blurry:1.2)"}},"1":{"class_type":"CLIPTextEncode","inputs":{"text":"before"}},"3":{"class_type":"KSampler","inputs":{"positive":["1",0],"negative":["2",0]}}}"""
        )
    }

    private fun submittedNegative(): String = JsonParser.parseString(promptBodies.last()).asJsonObject["prompt"]
        .asJsonObject["2"].asJsonObject["inputs"].asJsonObject["text"].asString

    @Test fun characterRecordIsReinjectedForFollowupWithoutRepeatingItsName() {
        vm._characterLores.value = listOf(com.comfyui.mobile.model.CharacterLore(
            id = "star", name = "星澜", triggerWords = "xl_token, blue hair"))
        responses += success("""{"reply":"好","prompt":"1girl, xl_token, blue hair","negative_prompt":"","should_draw":true}""")
        vm.sendAgentMessage("画星澜")
        awaitDone()
        responses += success("""{"reply":"好","prompt":"1girl, rain","negative_prompt":"","should_draw":true}""")
        vm.sendAgentMessage("保持人物，换成雨夜背景")
        awaitDone()
        val system = JsonParser.parseString(llmBodies.last()).asJsonObject["messages"]
            .asJsonArray.first().asJsonObject["content"].asString
        println("CHARACTER_FOLLOWUP injected=${system.contains("xl_token")} calls=${llmBodies.size} submissions=${promptBodies.size}")
        assertTrue("Follow-up must receive the original character record without repeating its name", system.contains("xl_token"))
        assertEquals(2, llmBodies.size)
        assertEquals(2, promptBodies.size)
    }

    @Test fun dialogueIdentityNeverLeaksIntoWorkflowPrompt() {
        withNegativeWorkflow()
        vm._characterLores.value = listOf(com.comfyui.mobile.model.CharacterLore(
            "whale", "大肥鱼", "ds_whalemaid", aliases = listOf("deepseek"),
            fixedTriggerWords = "ds_whalemaid"))
        responses += success("""{"reply":"好","prompt":"1girl, 大肥鱼, (deepseek:1.2), ds_whalemaid, blue hair","negative_prompt":"blurry","should_draw":true}""")
        vm.sendAgentMessage("画deepseek")
        awaitDone()
        println("DIALOGUE_ROUTING positive=${graphText()} negative=${submittedNegative()} calls=${llmBodies.size} submissions=${promptBodies.size}")
        assertEquals("1girl, ds_whalemaid, blue hair", graphText())
        assertEquals("blurry", submittedNegative())
        assertEquals(vm.agentPromptOutput.value.positive, graphText())
        assertEquals(1, llmBodies.size)
        assertEquals(1, promptBodies.size)
    }

    @Test fun registeredAliasOutfitAndMissingFixedKeysReachActualWorkflowAndPersistAcrossReload() {
        val winter = com.comfyui.mobile.model.CharacterOutfit("winter", "冬装", "winter_token, coat")
        val casual = com.comfyui.mobile.model.CharacterOutfit("casual", "便服", "casual_token, shirt",
            dialogueTriggers = listOf("换成日常穿搭", "穿轻便衣服"))
        vm.saveCharacterLore(com.comfyui.mobile.model.CharacterLore(
            id = "star", name = "大肥鱼", triggerWords = "ds_whalemaid, blue hair",
            aliases = listOf("deepseek"), fixedTriggerWords = "ds_whalemaid",
            outfits = listOf(winter, casual), defaultOutfitId = winter.id))
        responses += success("""{"reply":"好","prompt":"1girl, 大肥鱼, deepseek, rain","negative_prompt":"deepseek","should_draw":true}""")
        vm.sendAgentMessage("画deepseek，使用默认服装")
        awaitDone()
        assertEquals("1girl, rain, ds_whalemaid, winter_token, coat", graphText())
        assertTrue(vm.agentMessages.value.last().characterStatus!!.contains("补齐 3 项"))
        vm._agentMessages.value = MainViewModelAgent.loadAgentMessages(vm)
        assertEquals("star", vm.agentMessages.value.last().characterSelections!!.single().loreId)
        assertEquals("winter", vm.agentMessages.value.last().characterSelections!!.single().outfitId)
        responses += success("""{"reply":"好","prompt":"1girl, 大肥鱼, deepseek, winter_token, coat, 换成日常穿搭","negative_prompt":"deepseek, blurry","should_draw":true}""")
        vm.sendAgentMessage("她换成日常穿搭")
        awaitDone()
        assertEquals("1girl, ds_whalemaid, casual_token, shirt", graphText())
        assertEquals("casual", vm.agentMessages.value.last().characterSelections!!.single().outfitId)
        val messages = JsonParser.parseString(llmBodies.last()).asJsonObject["messages"].asJsonArray
        val system = messages.first().asJsonObject["content"].asString
        assertTrue(system.contains("casual_token"))
        assertFalse(system.contains("winter_token"))
        assertTrue(system.contains("App 仅提供已匹配资料与待定候选"))
        assertFalse(system.contains("换成日常穿搭"))
        assertTrue(system.contains("当前服装参考"))
        assertTrue(system.contains("用户本轮明确指定"))
        assertTrue(system.contains("不是绘画标签"))
        assertFalse((graphText() as String).contains("大肥鱼"))
        assertFalse((graphText() as String).contains("deepseek"))
        assertFalse((graphText() as String).contains("日常穿搭"))
        assertEquals(2, llmBodies.size)
        assertEquals(2, promptBodies.size)
        println("CHARACTER_OUTFIT actual=${graphText()} selection=${vm.agentMessages.value.last().characterSelections} calls=${llmBodies.size}")
    }

    @Test fun duplicateAliasReachesModelWithCandidatesAndNoAppQuestion() {
        vm._characterLores.value = listOf(
            com.comfyui.mobile.model.CharacterLore("a", "星澜", "a_token", aliases = listOf("小蓝")),
            com.comfyui.mobile.model.CharacterLore("b", "月汐", "b_token", aliases = listOf("小蓝")))
        responses += success("""{"action":"ask_user","questions":[{"id":"who","prompt":"想画星澜还是月汐？","kind":"multiple_choice","allow_multiple":false,"options":[{"id":"a","label":"星澜"},{"id":"b","label":"月汐"}]}]}""")
        vm.sendAgentMessage("画小蓝")
        awaitDone()
        assertEquals(AgentPhase.WAITING_USER, vm.agentSessionState.value.turn!!.phase)
        assertEquals(1, llmBodies.size)
        assertTrue(promptBodies.isEmpty())
        val system = JsonParser.parseString(llmBodies.single()).asJsonObject["messages"].asJsonArray
            .first().asJsonObject["content"].asString
        assertTrue(system.contains("存在名称歧义"))
        assertTrue(system.contains("星澜"))
        assertTrue(system.contains("月汐"))
        assertNull(vm.agentMessages.value.last().characterSelections?.takeIf { it.isNotEmpty() })
    }

    @Test fun duplicateOutfitDialogueTriggerReachesModelWithoutPreemptiveAppQuestion() {
        vm._characterLores.value = listOf(com.comfyui.mobile.model.CharacterLore(
            "star", "星澜", "xl_token", aliases = listOf("小澜"),
            outfits = listOf(
                com.comfyui.mobile.model.CharacterOutfit("one", "冬装", "winter_token",
                    dialogueTriggers = listOf("换成演出服")),
                com.comfyui.mobile.model.CharacterOutfit("two", "便服", "casual_token",
                    dialogueTriggers = listOf("换成演出服"))
            )))
        responses += success("""{"action":"ask_user","questions":[{"id":"outfit","prompt":"使用冬装还是便服？","kind":"multiple_choice","allow_multiple":false,"options":[{"id":"one","label":"冬装"},{"id":"two","label":"便服"}]}]}""")
        vm.sendAgentMessage("画小澜，换成演出服")
        awaitDone()
        assertEquals(AgentPhase.WAITING_USER, vm.agentSessionState.value.turn!!.phase)
        assertEquals(listOf("冬装", "便服"), vm.agentMessages.value.last().clarification!!.questions!!.single().options!!.map { it.label })
        assertNull(vm.agentMessages.value.last().expandedPrompt)
        assertEquals(1, llmBodies.size)
        assertTrue(promptBodies.isEmpty())
        val system = JsonParser.parseString(llmBodies.single()).asJsonObject["messages"].asJsonArray
            .first().asJsonObject["content"].asString
        assertTrue(system.contains("尚未选定的服装候选"))
        assertTrue(system.contains("winter_token"))
        assertTrue(system.contains("casual_token"))
    }

    @Test fun modelClarificationPersistsChoicesAndDoesNotRecoverOldPromptOrTouchWorkflow() {
        responses += success("""{"reply":"好","prompt":"cat, rain","negative_prompt":"","should_draw":true}""")
        vm.sendAgentMessage("画猫")
        awaitDone()
        val initialGraph = graphText()
        responses += success(
            """{"reply":"这两个要求有冲突。","prompt":"must_not_apply","negative_prompt":"must_not_apply","should_draw":true,"needs_clarification":true,"clarification_question":"使用哪一套？","clarification_options":["保留第一套","使用第二套"]}"""
        )
        vm.sendAgentMessage("换一套")
        awaitDone()
        assertEquals(initialGraph, graphText())
        assertEquals(1, promptBodies.size)
        val message = MainViewModelAgent.loadAgentMessages(vm).last()
        assertEquals("使用哪一套？", message.clarification?.question)
        assertEquals(listOf("保留第一套", "使用第二套"), message.clarification?.options)
        assertNull(message.expandedPrompt)
        assertNull(message.errorMessage)
        assertEquals(AgentPhase.WAITING_USER, vm.agentSessionState.value.turn!!.phase)
    }

    @Test fun pinnedDisabledAndExplicitSkipLibraryAreResolvedByAppNotAssistantClaims() {
        vm._characterLores.value = listOf(com.comfyui.mobile.model.CharacterLore(
            "star", "星澜", "xl_token", alwaysActive = true, fixedTriggerWords = "xl_token"))
        responses += success("""{"reply":"好","prompt":"1girl","negative_prompt":"","should_draw":true}""")
        vm.sendAgentMessage("画雨夜人像")
        awaitDone()
        assertEquals("1girl, xl_token", graphText())
        responses += success("""{"reply":"我猜是星澜","prompt":"landscape","negative_prompt":"","should_draw":true}""")
        vm.sendAgentMessage("这次不用角色库，画风景")
        awaitDone()
        assertEquals("landscape", graphText())
        assertTrue(vm.agentMessages.value.last().characterSelections!!.isEmpty())
        vm.toggleCharacterLoreEnabled("star")
        responses += success("""{"reply":"好","prompt":"1girl","negative_prompt":"","should_draw":true}""")
        vm.sendAgentMessage("画星澜")
        awaitDone()
        assertEquals("1girl", graphText())
        assertTrue(vm.agentMessages.value.last().characterSelections!!.isEmpty())
        assertFalse(JsonParser.parseString(llmBodies.last()).asJsonObject["messages"]
            .asJsonArray.first().asJsonObject["content"].asString.contains("xl_token"))
    }

    @Test fun characterCompletionRunsBeforeExistingPositiveNegativeConflictValidation() {
        withNegativeWorkflow()
        vm._characterLores.value = listOf(com.comfyui.mobile.model.CharacterLore(
            "star", "星澜", "xl_token", fixedTriggerWords = "xl_token"))
        responses += success("""{"reply":"好","prompt":"1girl","negative_prompt":"xl_token","should_draw":true}""")
        vm.sendAgentMessage("画星澜")
        awaitDone()
        assertEquals(AgentPhase.FAILED, vm.agentSessionState.value.turn!!.phase)
        assertTrue(promptBodies.isEmpty())
        assertEquals("before", graphText())
        assertTrue(vm.agentMessages.value.last().expandedPrompt!!.contains("xl_token"))
        assertTrue(vm.agentMessages.value.last().errorMessage!!.contains("正反向提示词包含相同标签"))
    }

    @Test fun assistantProposedAliasOrCharacterIdsCannotSelectAnUnmatchedRecord() {
        vm._characterLores.value = listOf(com.comfyui.mobile.model.CharacterLore(
            "star", "星澜", "xl_token", aliases = listOf("小澜"), fixedTriggerWords = "xl_token"))
        responses += success("""{"reply":"我猜这个昵称是星澜","prompt":"1girl","negative_prompt":"","should_draw":true,"characterSelections":[{"loreId":"star"}]}""")
        vm.sendAgentMessage("画一个未登记昵称的人物")
        awaitDone()
        assertEquals("1girl", graphText())
        assertTrue(vm.agentMessages.value.last().characterSelections!!.isEmpty())
        val system = JsonParser.parseString(llmBodies.single()).asJsonObject["messages"]
            .asJsonArray.first().asJsonObject["content"].asString
        assertFalse(system.contains("星澜"))
        assertFalse(system.contains("小澜"))
        assertFalse(system.contains("xl_token"))
        assertEquals(1, promptBodies.size)
    }

    @Test fun blankReplyForAnotherCharacterDoesNotRecycleThePreviousCharactersPrompt() {
        vm._characterLores.value = listOf(
            com.comfyui.mobile.model.CharacterLore("a", "星澜", "a_token", fixedTriggerWords = "a_token"),
            com.comfyui.mobile.model.CharacterLore("b", "月汐", "b_token", fixedTriggerWords = "b_token"))
        responses += success("""{"reply":"好","prompt":"1girl, a_token","negative_prompt":"","should_draw":true}""")
        vm.sendAgentMessage("画星澜")
        awaitDone()
        responses += success("""{"reply":"好","prompt":"","negative_prompt":"","should_draw":true}""")
        vm.sendAgentMessage("画月汐")
        awaitDone()
        assertEquals(AgentPhase.FAILED, vm.agentSessionState.value.turn!!.phase)
        assertEquals(1, promptBodies.size)
        assertEquals("1girl, a_token", graphText())
        assertNull(vm.agentMessages.value.last().expandedPrompt)
        assertTrue(vm.agentMessages.value.last().characterSelections!!.isEmpty())
    }

    @Test fun registeredAliasKeepsDictionaryCandidatesScopedAndLetsModelChooseWhetherToAsk() {
        com.comfyui.mobile.taglib.TagLibraryTestData.install(app, vm.localTagLibrary)
        vm._agentPromptSettings.value = AgentPromptSettings(true)
        val raw = kotlinx.coroutines.runBlocking { vm.localTagLibrary.forRequest("画初音未来，蓝发") }
        assertTrue(raw.entries.any { it.kind == "character" && it.name == "hatsune_miku" })
        vm._characterLores.value = listOf(com.comfyui.mobile.model.CharacterLore(
            "custom", "用户登记的角色", "user_exact_trigger", aliases = listOf("初音未来"),
            fixedTriggerWords = "user_exact_trigger"))
        responses += success("""{"action":"ask_user","questions":[{"id":"version","prompt":"选择要用的版本？","kind":"multiple_choice","allow_multiple":false,"options":[{"id":"miku","label":"初音未来常服"},{"id":"other","label":"初音未来礼服"}]}]}""")
        vm.sendAgentMessage("画初音未来，蓝发")
        awaitDone()
        assertEquals(AgentPhase.WAITING_USER, vm.agentSessionState.value.turn!!.phase)
        assertEquals(1, llmBodies.size)
        assertTrue(promptBodies.isEmpty())

        val report = requireNotNull(vm.agentMessages.value.last().localTagReport)
        assertTrue(report.entries.none { it.kind == "character" && it.profileName == null })
        assertTrue(report.entries.any { it.kind == "character" && it.profileName == "用户登记的角色" })
        assertTrue(report.entries.any { it.name == "blue hair" })
        val messages = JsonParser.parseString(llmBodies.single()).asJsonObject["messages"].asJsonArray
        val dictionary = messages.first { it.asJsonObject["content"].asString.contains("离线词库候选") }
            .asJsonObject["content"].asString
        assertTrue(dictionary.contains("hatsune_miku"))
        assertTrue(dictionary.contains("registeredProfile"))
        assertEquals("custom", vm.agentMessages.value.last().characterSelections?.singleOrNull()?.loreId)
        assertEquals(1, llmBodies.size)
        assertEquals(0, promptBodies.size)
    }

    @Test fun negativePromptReachesTheRealSubmissionHistoryAndSavedConversation() {
        withNegativeWorkflow()
        responses += success("""{"reply":"好","prompt":"cat, rain","negative_prompt":"template_embedding, (blurry:1.2), extra limbs","should_draw":true}""")
        vm.sendAgentMessage("画猫，反向提示词加上 extra limbs")
        awaitDone()
        assertEquals(AgentPhase.COMPLETED, vm.agentSessionState.value.turn!!.phase)
        assertEquals("cat, rain", graphText())
        assertEquals("template_embedding, (blurry:1.2), extra limbs", submittedNegative())
        assertEquals(1, promptBodies.size)
        val message = vm.agentMessages.value.last()
        assertEquals(submittedNegative(), message.expandedNegativePrompt)
        assertTrue(message.negativePromptStatus!!.startsWith("已写入"))
        val saved = gson.toJsonTree(MainViewModelAgent.loadAgentMessages(vm).last()).asJsonObject
        assertFalse(vm.prefs.contains(MainViewModelAgent.scopedPref("agent_messages", vm)))
        assertEquals(message.expandedNegativePrompt, saved["expandedNegativePrompt"].asString)
        val requestMessages = JsonParser.parseString(llmBodies.single()).asJsonObject["messages"].asJsonArray
        assertTrue(requestMessages.first().asJsonObject["content"].asString.contains("negative_prompt"))
        assertTrue(requestMessages.any { it.asJsonObject["content"].asString.contains("template_embedding") })

        responses += success("""{"reply":"换好了","prompt":"cat, snow","negative_prompt":"template_embedding, extra limbs","should_draw":true}""")
        vm.sendAgentMessage("换成雪景")
        awaitDone()
        val nextMessages = JsonParser.parseString(llmBodies.last()).asJsonObject["messages"].asJsonArray
        val history = nextMessages.first { it.asJsonObject["role"].asString == "assistant" }.asJsonObject["content"].asString
        assertEquals(message.expandedNegativePrompt, JsonParser.parseString(history).asJsonObject["negative_prompt"].asString)
        assertEquals(2, promptBodies.size)
    }

    @Test fun oldResponseWithoutNegativePreservesTemplateAndExplicitEmptyClearsIt() {
        withNegativeWorkflow()
        responses += success("""{"reply":"好","prompt":"cat","should_draw":true}""")
        vm.sendAgentMessage("画猫")
        awaitDone()
        assertEquals("template_embedding, (blurry:1.2)", submittedNegative())
        assertNull(vm.agentMessages.value.last().expandedNegativePrompt)
        responses += success("""{"reply":"已清空","prompt":"cat","negative_prompt":"","should_draw":true}""")
        vm.sendAgentMessage("清空反向提示词，然后出图")
        awaitDone()
        assertEquals("", submittedNegative())
        assertEquals("", vm.agentMessages.value.last().expandedNegativePrompt)
        assertTrue(vm.agentMessages.value.last().negativePromptStatus!!.startsWith("已清空"))
    }

    @Test fun noNegativeInputKeepsGeneratedNegativeVisibleWithoutClaimingItWasApplied() {
        responses += success("""{"reply":"好","prompt":"cat","negative_prompt":"blurry","should_draw":true}""")
        vm.sendAgentMessage("画猫")
        awaitDone()
        assertEquals(1, promptBodies.size)
        assertEquals("cat", graphText())
        assertEquals("blurry", vm.agentMessages.value.last().expandedNegativePrompt)
        assertTrue(vm.agentMessages.value.last().negativePromptStatus!!.startsWith("未写入"))
        val graph = JsonParser.parseString(promptBodies.single()).asJsonObject["prompt"].asJsonObject
        assertEquals(setOf("1"), graph.keySet())
    }

    @Test fun promptOnlyWritesBothFieldsWithoutSubmitting() {
        withNegativeWorkflow()
        responses += success("""{"reply":"给你两组词","prompt":"cat, rain","negative_prompt":"blurry","should_draw":false}""")
        vm.sendAgentMessage("只要提示词，先别出图")
        awaitDone()
        assertEquals(AgentPhase.COMPLETED, vm.agentSessionState.value.turn!!.phase)
        assertEquals("cat, rain", graphText())
        val negative = ((vm.agentWorkflow!!["2"] as Map<*, *>)["inputs"] as Map<*, *>)["text"]
        assertEquals("blurry", negative)
        assertTrue(promptBodies.isEmpty())
    }

    @Test fun sdxlNegativeWritesBothTextSlotsAndPreservesConditioningLinks() {
        File(MainViewModelWorkflow.savedWorkflowsDir(vm), "agent-fixture.json").writeText(
            """{"1":{"class_type":"CLIPTextEncode","inputs":{"text":"before"}},"2":{"class_type":"CLIPTextEncodeSDXL","inputs":{"text_g":"old g","text_l":"old l"}},"3":{"class_type":"KSampler","inputs":{"positive":["1",0],"negative":["2",0]}}}"""
        )
        responses += success("""{"reply":"好","prompt":"cat","negative_prompt":"blurry, extra limbs","should_draw":true}""")
        vm.sendAgentMessage("画猫")
        awaitDone()
        val graph = JsonParser.parseString(promptBodies.single()).asJsonObject["prompt"].asJsonObject
        val inputs = graph["2"].asJsonObject["inputs"].asJsonObject
        assertEquals("blurry, extra limbs", inputs["text_g"].asString)
        assertEquals("blurry, extra limbs", inputs["text_l"].asString)
        assertEquals("2", graph["3"].asJsonObject["inputs"].asJsonObject["negative"].asJsonArray[0].asString)
    }

    @Test fun negativeReferenceUsesSavedOverridesWithoutLoadingOrMutatingTheActiveGraph() {
        withNegativeWorkflow()
        vm.agentWorkflowParamValues["2:text"] = "saved_embedding, blurry"
        vm.agentWorkspace.saveConfiguration()
        vm.agentWorkflowParamValues.clear()
        assertEquals("saved_embedding, blurry",
            AgentWorkflowPromptContext.negative(vm, vm.agentSession.contextVersion()))
        assertNull(vm.agentWorkflow)
        assertTrue(vm.agentWorkflowParamValues.isEmpty())
        assertTrue(promptBodies.isEmpty())
    }

    @Test fun conflictingWeightedNegativeStopsBeforeChangingPromptFieldsOrSubmitting() {
        withNegativeWorkflow()
        responses += success("""{"reply":"好了","prompt":"cat, (red eyes:1.2), soft focus","negative_prompt":"blurry, red_eyes","should_draw":true}""")
        vm.sendAgentMessage("画红眼睛的猫，柔焦风格")
        awaitDone()
        println("NEGATIVE_CONFLICT phase=${vm.agentSessionState.value.turn!!.phase} submissions=${promptBodies.size} positive=${graphText()}")
        assertEquals(AgentPhase.FAILED, vm.agentSessionState.value.turn!!.phase)
        assertEquals("before", graphText())
        assertTrue(promptBodies.isEmpty())
        val message = vm.agentMessages.value.last()
        assertTrue(message.errorMessage!!.contains("正反向提示词包含相同标签"))
        assertTrue(message.errorMessage.contains("red_eyes"))
        assertEquals("blurry, red_eyes", message.expandedNegativePrompt)
    }

    @Test fun keepOriginalBindingDoesNotBlockAnUnusedConflictingGeneratedNegative() {
        withNegativeWorkflow()
        installBinding(true, AgentPromptBinding(PromptBindingMode.KEEP))
        responses += success("""{"reply":"好","prompt":"cat","negative_prompt":"cat","should_draw":true}""")
        vm.sendAgentMessage("画猫")
        awaitDone()
        assertEquals(AgentPhase.COMPLETED, vm.agentSessionState.value.turn!!.phase)
        assertEquals("template_embedding, (blurry:1.2)", submittedNegative())
        assertEquals("cat", vm.agentMessages.value.last().expandedNegativePrompt)
    }

    @Test fun generationReceivesObservedModelFactsAndDoesNotDemandFillingANegativeTemplate() {
        withNegativeWorkflow()
        val file = File(MainViewModelWorkflow.savedWorkflowsDir(vm), "agent-fixture.json")
        val graph = JsonParser.parseString(file.readText()).asJsonObject
        graph.add("4", JsonParser.parseString(
            """{"class_type":"CheckpointLoaderSimple","inputs":{"ckpt_name":"my-model.safetensors"}}"""
        ))
        file.writeText(graph.toString())
        responses += success("""{"reply":"好","prompt":"cat","negative_prompt":"","should_draw":true}""")
        vm.sendAgentMessage("画猫，清空反向")
        awaitDone()
        val request = JsonParser.parseString(llmBodies.single()).asJsonObject["messages"].asJsonArray
        val system = request.first().asJsonObject["content"].asString
        val modelReference = request.firstOrNull {
            it.asJsonObject["content"].asString.contains("my-model.safetensors")
        }?.asJsonObject?.get("content")?.asString
        println("NEGATIVE_MODEL_CONTEXT present=${modelReference != null} emptyAllowed=${system.contains("字段必填不等于内容必须非空")}")
        assertNotNull(modelReference)
        assertTrue(modelReference!!.contains("仅作事实参考"))
        assertTrue(system.contains("字段必填不等于内容必须非空"))
        assertFalse(system.contains("你需要主动编写完整 negative_prompt"))
        assertEquals("", submittedNegative())
    }

    @Test fun chatUsesMaxAndDoubleTapIsReservedSynchronously() {
        vm._agentInteractionMode.value = MainViewModel.AGENT_MODE_CHAT
        vm._llmReasoningEffort.value = AgentReasoningEffort.MAX
        responses += success("你好")
        vm.sendAgentMessage("hello")
        vm.sendAgentMessage("duplicate")
        assertTrue(vm.agentBusy.value)
        assertEquals(1, vm.agentMessages.value.count { it.role == "user" })
        awaitDone()
        assertEquals("你好", vm.agentMessages.value.last().content)
        assertEquals(AgentPhase.COMPLETED, vm.agentSessionState.value.turn!!.phase)
        assertEquals("max", JsonParser.parseString(llmBodies.single()).asJsonObject["reasoning_effort"].asString)
        assertTrue(promptBodies.isEmpty())
    }

    @Test fun drawLoadsPatchesPersistsAndQueuesExactlyOnce() {
        responses += success("""{"reply":"好了","prompt":"cat, rain","should_draw":true}""")
        vm.sendAgentMessage("画一只猫")
        awaitDone()
        assertEquals(AgentPhase.COMPLETED, vm.agentSessionState.value.turn!!.phase)
        assertEquals("cat, rain", graphText())
        assertEquals("cat, rain", vm.agentLastExpandedPrompt.value)
        val prompt = JsonParser.parseString(promptBodies.single()).asJsonObject["prompt"].asJsonObject
        assertEquals("cat, rain", prompt["1"].asJsonObject["inputs"].asJsonObject["text"].asString)
        assertTrue("fixture-pid-1" in vm.agentRunPromptIds)
        assertEquals("cat, rain", vm.agentMessages.value.last().expandedPrompt)
        assertNull(vm.agentMessages.value.last().errorMessage)
    }

    @Test fun promptOnlyNeverQueuesButStillWritesTags() {
        responses += success("""{"reply":"先给你词","prompt":"cat, rain","should_draw":false}""")
        vm.sendAgentMessage("只要提示词，先别出图")
        awaitDone()
        assertEquals(AgentPhase.COMPLETED, vm.agentSessionState.value.turn!!.phase)
        assertEquals("cat, rain", graphText())
        assertTrue(promptBodies.isEmpty())
        assertTrue(gson.toJson(MainViewModelAgent.loadAgentMessages(vm)).contains("cat, rain"))
        assertFalse(vm.prefs.contains(MainViewModelAgent.scopedPref("agent_messages", vm)))
    }

    @Test fun stopKeepsPartialAndNewTurnIsNotClearedByOldFinally() {
        vm._agentInteractionMode.value = MainViewModel.AGENT_MODE_CHAT
        responses += blocked("部分回复")
        vm.sendAgentMessage("first")
        pumpUntil { vm.agentMessages.value.last().content == "部分回复" }
        vm.stopAgentGeneration()
        assertEquals("部分回复", vm.agentMessages.value.last().content)
        assertFalse(vm.agentMessages.value.last().isThinking)
        assertEquals(AgentPhase.STOPPED, vm.agentSessionState.value.turn!!.phase)
        responses += success("新回复")
        vm.sendAgentMessage("second")
        awaitDone()
        pumpUntil { vm.llmClient.activeRequestIds.isEmpty() }
        assertEquals(AgentPhase.COMPLETED, vm.agentSessionState.value.turn!!.phase)
        assertEquals("新回复", vm.agentMessages.value.last().content)
        assertEquals(2, llmBodies.size)
    }

    @Test fun clearingChatCancelsSocketAndLateCallbacksCannotRepopulateIt() {
        responses += blocked("""{"reply":"部分""")
        vm.sendAgentMessage("画猫")
        pumpUntil { llmBodies.size == 1 }
        vm.clearAgentChat()
        pumpUntil { vm.llmClient.activeRequestIds.isEmpty() }
        assertTrue(vm.agentMessages.value.isEmpty())
        assertFalse(vm.agentBusy.value)
        assertTrue(promptBodies.isEmpty())
    }

    @Test fun sessionSwitchCancelsRealLlmSocketAndKeepsItsPartialReplyOutOfTheNewSession() {
        vm._agentInteractionMode.value = MainViewModel.AGENT_MODE_CHAT
        val origin = vm.agentWorkspace.conversationId!!
        responses += blocked("A 部分回复")
        vm.sendAgentMessage("A 的请求")
        pumpUntil { vm.agentMessages.value.last().content == "A 部分回复" }
        vm.newAgentConversation("B")
        pumpUntil { vm.agentStorageStatus.value.mode == AgentStorageMode.ROOM &&
            vm.agentWorkspace.conversationId != origin && vm.llmClient.activeRequestIds.isEmpty() }
        assertFalse(vm.agentBusy.value)
        assertTrue(vm.agentMessages.value.isEmpty())
        assertTrue(promptBodies.isEmpty())
        vm.openAgentConversation(origin)
        pumpUntil { vm.agentStorageStatus.value.mode == AgentStorageMode.ROOM &&
            vm.agentWorkspace.conversationId == origin }
        assertEquals("A 部分回复", vm.agentMessages.value.last().content)
        assertFalse(vm.agentMessages.value.last().isThinking)
        assertEquals(1, llmBodies.size)
    }

    @Test fun deletingStreamingSessionCancelsItsSocketAndDoesNotRecreateDeletedMessages() {
        vm._agentInteractionMode.value = MainViewModel.AGENT_MODE_CHAT
        val origin = vm.agentWorkspace.conversationId!!
        responses += blocked("即将删除的部分回复")
        vm.sendAgentMessage("临时任务")
        pumpUntil { vm.agentMessages.value.last().content == "即将删除的部分回复" }
        vm.requestDeleteAgentConversation(origin)
        pumpUntil { vm.agentConversationDeleteInfo.value != null }
        vm.deleteAgentConversation(origin)
        pumpUntil { vm.agentStorageStatus.value.mode == AgentStorageMode.ROOM &&
            vm.agentWorkspace.conversationId != origin && vm.llmClient.activeRequestIds.isEmpty() }
        flushWorkspace()
        assertTrue(vm.agentMessages.value.isEmpty())
        assertFalse(vm.agentConversations.value.any { it.id == origin })
        assertTrue(promptBodies.isEmpty())
        assertEquals(1, llmBodies.size)
    }

    @Test fun pendingQuestionStaysWithItsSessionAndReturningDoesNotAutomaticallyResubmitIt() {
        automaticDecisions = false
        responses += nativeAction("ask_user_question",
            """{"questions":[{"id":"outfit","question":"选择哪套服装？","options":[{"id":"a","label":"第一套"},{"id":"b","label":"第二套"}]}]}""",
            "question-session-a")
        vm.sendAgentMessage("先确定服装")
        awaitDone()
        val origin = vm.agentWorkspace.conversationId!!
        val question = vm.agentMessages.value.last()
        vm.newAgentConversation("无问题的会话 B")
        pumpUntil { vm.agentStorageStatus.value.mode == AgentStorageMode.ROOM &&
            vm.agentWorkspace.conversationId != origin }
        assertTrue(vm.agentMessages.value.isEmpty())
        assertFalse(vm.answerAgentQuestions(question.id, listOf(AgentQuestionAnswer("outfit", listOf("a")))))
        vm.openAgentConversation(origin)
        pumpUntil { vm.agentStorageStatus.value.mode == AgentStorageMode.ROOM &&
            vm.agentWorkspace.conversationId == origin }
        assertEquals(question.clarification, vm.agentMessages.value.last().clarification)
        assertEquals(AgentPhase.WAITING_USER, vm.agentSessionState.value.turn!!.phase)
        assertEquals(1, decisionBodies.size)
        assertTrue(llmBodies.isEmpty())
        assertTrue(promptBodies.isEmpty())
    }

    @Test fun changingServerInvalidatesCurrentTurnBeforeAnySubmission() {
        responses += blocked("""{"reply":"部分""")
        vm.sendAgentMessage("画猫")
        pumpUntil { llmBodies.size == 1 }
        val version = vm.agentSession.contextVersion()
        vm.onServerSwitching(modeChanged = false)
        pumpUntil { vm.llmClient.activeRequestIds.isEmpty() }
        assertTrue(vm.agentSession.contextVersion() > version)
        assertEquals(AgentPhase.STOPPED, vm.agentSessionState.value.turn!!.phase)
        assertTrue(promptBodies.isEmpty())
    }

    @Test fun workflowSwitchStopsOldTurnAndKeepsNewGraph() {
        responses += blocked("""{"reply":"部分""")
        vm.sendAgentMessage("画猫")
        pumpUntil { llmBodies.size == 1 }
        val next = File(MainViewModelWorkflow.savedWorkflowsDir(vm), "next.json")
        next.writeText("""{"1":{"class_type":"CLIPTextEncode","inputs":{"text":"next graph"}}}""")
        vm.setAgentDefaultWorkflow(MainViewModel.AGENT_WF_SAVED, next.name, "next")
        pumpUntil { vm.agentWorkflow != null && vm.llmClient.activeRequestIds.isEmpty() }
        assertEquals("next graph", graphText())
        assertTrue(promptBodies.isEmpty())
        assertEquals(AgentPhase.STOPPED, vm.agentSessionState.value.turn!!.phase)
    }

    @Test fun profileSelectionPersistsDistinctXhighAndMaxAndStopsOldRequest() {
        val first = LlmProfile(id = "one", name = "one", baseUrl = server.url("/v1").toString(),
            apiKey = "", model = "fixture-model", reasoningEffort = "xhigh")
        val second = first.copy(id = "two", name = "two", reasoningEffort = "max")
        vm.addOrUpdateLlmProfile(first)
        // Device AndroidKeyStore is not emulated here. Persist real profiles with an empty key;
        // supply the loopback-only token to the live request state, not to the encryption layer.
        vm._llmApiKey.value = "fixture-key"
        vm._agentInteractionMode.value = MainViewModel.AGENT_MODE_CHAT
        responses += blocked("partial")
        vm.sendAgentMessage("first")
        pumpUntil { llmBodies.size == 1 }
        vm.addOrUpdateLlmProfile(second)
        vm._llmApiKey.value = "fixture-key"
        pumpUntil { vm.llmClient.activeRequestIds.isEmpty() }
        assertEquals(AgentReasoningEffort.MAX, vm.llmReasoningEffort.value)
        responses += success("next")
        vm.sendAgentMessage("second")
        awaitDone()
        assertEquals("xhigh", JsonParser.parseString(llmBodies[0]).asJsonObject["reasoning_effort"].asString)
        assertEquals("max", JsonParser.parseString(llmBodies[1]).asJsonObject["reasoning_effort"].asString)
        val loaded = vm.loadLlmProfiles()
        assertEquals("xhigh", loaded.first { it.id == "one" }.reasoningEffort)
        assertEquals("max", loaded.first { it.id == "two" }.reasoningEffort)
        vm.selectLlmProfile("one")
        assertEquals(AgentReasoningEffort.XHIGH, vm.llmReasoningEffort.value)
        assertFalse(vm.prefs.getString("llm_profiles_v1", "")!!.contains("fixture-key"))
    }

    @Test fun truncatedLlmNeverTouchesGraphOrQueue() {
        responses += MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(event("""{"reply":"好","prompt":"cat","should_draw":true}"""))
        vm.sendAgentMessage("画猫")
        awaitDone()
        assertEquals(AgentPhase.FAILED, vm.agentSessionState.value.turn!!.phase)
        assertEquals("INCOMPLETE_RESPONSE", vm.agentSessionState.value.turn!!.failure)
        assertTrue(promptBodies.isEmpty())
        assertNull(vm.agentWorkflow)
    }

    @Test fun viewModelClearCancelsOwnedTurnAndSocket() {
        vm._agentInteractionMode.value = MainViewModel.AGENT_MODE_CHAT
        responses += blocked("partial")
        vm.sendAgentMessage("hello")
        pumpUntil { llmBodies.size == 1 }
        store.clear()
        pumpUntil { vm.llmClient.activeRequestIds.isEmpty() }
        assertFalse(vm.agentBusy.value)
        assertEquals(AgentPhase.STOPPED, vm.agentSessionState.value.turn!!.phase)
        assertTrue(promptBodies.isEmpty())
    }

    @Test fun capturedImageSurvivesInputClearAndIsPatchedWithPromptBeforeQueue() {
        File(MainViewModelWorkflow.savedWorkflowsDir(vm), "agent-fixture.json").writeText(
            """{"1":{"class_type":"CLIPTextEncode","inputs":{"text":"before"}},"2":{"class_type":"LoadImage","inputs":{"image":"before.png"}}}"""
        )
        val image = File(app.cacheDir, "agent-image.jpg")
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bitmap.recycle()
        vm._agentPendingAttachments.value = listOf(
            AgentAttachment("image", "image", Uri.fromFile(image).toString(), image.name)
        )
        responses += success("""{"reply":"好","prompt":"cat","should_draw":true}""")
        vm.sendAgentMessage("参考这张图画猫")
        assertTrue(vm.agentPendingAttachments.value.isEmpty())
        awaitDone()
        assertEquals(AgentPhase.COMPLETED, vm.agentSessionState.value.turn!!.phase)
        assertEquals(1, uploadBodies.size)
        val prompt = JsonParser.parseString(promptBodies.single()).asJsonObject["prompt"].asJsonObject
        assertEquals("cat", prompt["1"].asJsonObject["inputs"].asJsonObject["text"].asString)
        assertEquals("uploaded.png", prompt["2"].asJsonObject["inputs"].asJsonObject["image"].asString)
        val content = JsonParser.parseString(llmBodies.single()).asJsonObject["messages"].asJsonArray.last { it.asJsonObject["role"].asString == "user" }
            .asJsonObject["content"].asJsonArray
        assertEquals("image_url", content[1].asJsonObject["type"].asString)
        assertFalse(File(app.cacheDir, "agent_upload_${vm.agentSessionState.value.turn!!.id}").exists())
    }

    @Test fun linkedPromptPortIsNotOverwrittenOrSubmitted() {
        File(MainViewModelWorkflow.savedWorkflowsDir(vm), "agent-fixture.json").writeText(
            """{"1":{"class_type":"CLIPTextEncode","inputs":{"text":["2",0]}},"2":{"class_type":"Other","inputs":{}}}"""
        )
        responses += success("""{"reply":"好","prompt":"cat","should_draw":true}""")
        vm.sendAgentMessage("画猫")
        awaitDone()
        assertEquals(AgentPhase.FAILED, vm.agentSessionState.value.turn!!.phase)
        assertEquals(listOf("2", 0), graphText())
        assertTrue(promptBodies.isEmpty())
    }

    @Test fun offlineCandidatesReachTheSingleModelRequestAndBothOutputs() {
        withNegativeWorkflow()
        com.comfyui.mobile.taglib.TagLibraryTestData.install(app, vm.localTagLibrary)
        vm._agentPromptSettings.value = AgentPromptSettings(true)
        responses += success("""{"reply":"好","prompt":"blue hair, my_trigger","negative_prompt":"blurry, bad anatomy","should_draw":true}""")
        vm.sendAgentMessage("画蓝发人物，不要模糊，保留 my_trigger")
        awaitDone()
        assertEquals(1, llmBodies.size)
        assertEquals(1, promptBodies.size)
        assertTrue(tagRequests.isEmpty())
        val body = JsonParser.parseString(llmBodies.single()).asJsonObject
        assertEquals(listOf("ask_user_question"),
            body["tools"].asJsonArray.map { it.asJsonObject["function"].asJsonObject["name"].asString })
        val messages = body["messages"].asJsonArray
        assertTrue(messages.any { it.asJsonObject["content"].asString.contains("离线词库候选") })
        assertTrue(messages.any { it.asJsonObject["content"].asString.contains("blue hair") })
        assertTrue(messages.last { it.asJsonObject["role"].asString == "user" }.asJsonObject["content"].asString.startsWith("画蓝发人物"))
        assertTrue(vm.agentMessages.value.last().localTagReport!!.entries.any { it.name == "blue hair" })
        assertEquals("blue hair, my_trigger", vm.agentPromptOutput.value.positive)
        assertEquals("blurry, bad anatomy", vm.agentPromptOutput.value.negative)
        assertEquals(vm.agentPromptOutput.value, MainViewModelAgent.loadPromptOutput(vm))
        assertEquals("blurry, bad anatomy", submittedNegative())
    }

    @Test fun disabledLocalLookupAddsNoCandidatesAndNoReport() {
        responses += success("""{"reply":"好","prompt":"blue hair","negative_prompt":"blurry","should_draw":true}""")
        vm.sendAgentMessage("画蓝发人物")
        awaitDone()
        assertEquals(1, llmBodies.size)
        assertFalse(llmBodies.single().contains("离线词库候选"))
        assertNull(vm.agentMessages.value.last().localTagReport)
        assertTrue(tagRequests.isEmpty())
    }

    @Test fun localReadFailurePreservesCustomPromptAndStillOnlyCallsModelOnce() {
        com.comfyui.mobile.taglib.TagLibraryTestData.install(app, vm.localTagLibrary)
        vm._agentPromptSettings.value = AgentPromptSettings(true)
        val manifest = JsonParser.parseString(java.io.File(com.comfyui.mobile.taglib.TagLibraryTestData.source,
            "manifest.json").readText()).asJsonObject
        val target = File(app.noBackupFilesDir, "tag-library/catalogue-" + manifest["sha256"].asString.take(16) + ".db")
        target.parentFile!!.mkdirs()
        target.writeBytes(ByteArray(manifest["bytes"].asInt))
        try {
            responses += success("""{"reply":"好","prompt":"blue hair, __my_trigger__","negative_prompt":"blurry","should_draw":true}""")
            vm.sendAgentMessage("画蓝发人物，保留 __my_trigger__")
            awaitDone()
            assertEquals(1, llmBodies.size)
            assertEquals(1, promptBodies.size)
            assertEquals("blue hair, __my_trigger__", graphText())
            assertNotNull(vm.agentMessages.value.last().localTagReport!!.error)
        } finally { target.delete() }
    }

    @Test fun enabledButUninstalledLibraryDoesNotAddCandidatesOrInterruptDrawing() {
        kotlinx.coroutines.runBlocking { vm.localTagLibrary.store.remove() }
        vm._agentPromptSettings.value = AgentPromptSettings(true)
        responses += success("""{"reply":"好","prompt":"blue hair, custom_trigger","negative_prompt":"","should_draw":true}""")
        vm.sendAgentMessage("画蓝发人物，保留 custom_trigger")
        awaitDone()
        assertEquals(1, llmBodies.size)
        assertEquals(1, promptBodies.size)
        assertFalse(llmBodies.single().contains("离线词库候选"))
        assertNull(vm.agentMessages.value.last().localTagReport)
        assertEquals("blue hair, custom_trigger", graphText())
    }

    private fun installBinding(negative: Boolean, binding: AgentPromptBinding) {
        if (negative) vm._agentNegativeBinding.value = binding else vm._agentPositiveBinding.value = binding
        vm.agentWorkspace.saveConfiguration()
    }

    @Test fun explicitNegativeFieldIsUsedByReferenceAndRealSubmission() {
        File(MainViewModelWorkflow.savedWorkflowsDir(vm), "agent-fixture.json").writeText(
            """{"1":{"class_type":"CLIPTextEncode","inputs":{"text":"before"}},"9":{"class_type":"CustomText","inputs":{"avoid":"fixture_embedding, blurry","unrelated":"keep"}}}""")
        installBinding(true, AgentPromptBinding(PromptBindingMode.EXPLICIT, "9", "avoid"))
        assertEquals("fixture_embedding, blurry", AgentWorkflowPromptContext.negative(vm, vm.agentSession.contextVersion()))
        assertNull(vm.agentWorkflow)
        responses += success("""{"reply":"好","prompt":"cat","negative_prompt":"fixture_embedding, extra limbs","should_draw":true}""")
        vm.sendAgentMessage("画猫")
        awaitDone()
        val graph = JsonParser.parseString(promptBodies.single()).asJsonObject["prompt"].asJsonObject
        assertEquals("fixture_embedding, extra limbs", graph["9"].asJsonObject["inputs"].asJsonObject["avoid"].asString)
        assertEquals("keep", graph["9"].asJsonObject["inputs"].asJsonObject["unrelated"].asString)
        assertEquals("fixture_embedding, extra limbs", vm.agentPromptOutput.value.negative)
    }

    @Test fun twoFieldsInOneNodeRemainIndependentInTheActualWorkflow() {
        File(MainViewModelWorkflow.savedWorkflowsDir(vm), "agent-fixture.json").writeText(
            """{"1":{"class_type":"InlinePrompt","inputs":{"draw":"old positive","avoid":"old negative"}}}""")
        installBinding(false, AgentPromptBinding(PromptBindingMode.EXPLICIT, "1", "draw"))
        installBinding(true, AgentPromptBinding(PromptBindingMode.EXPLICIT, "1", "avoid"))
        responses += success("""{"reply":"好","prompt":"cat, rain","negative_prompt":"blurry","should_draw":true}""")
        vm.sendAgentMessage("画猫")
        awaitDone()
        val inputs = JsonParser.parseString(promptBodies.single()).asJsonObject["prompt"].asJsonObject["1"].asJsonObject["inputs"].asJsonObject
        assertEquals("cat, rain", inputs["draw"].asString)
        assertEquals("blurry", inputs["avoid"].asString)
    }

    @Test fun invalidExplicitNegativeStopsBeforePatchOrSubmit() {
        withNegativeWorkflow()
        installBinding(true, AgentPromptBinding(PromptBindingMode.EXPLICIT, "missing", "text"))
        responses += success("""{"reply":"好","prompt":"cat","negative_prompt":"blurry","should_draw":true}""")
        vm.sendAgentMessage("画猫")
        awaitDone()
        assertEquals(AgentPhase.FAILED, vm.agentSessionState.value.turn!!.phase)
        assertEquals("before", graphText())
        assertTrue(promptBodies.isEmpty())
        assertTrue(vm.agentMessages.value.last().errorMessage!!.contains("已失效"))
    }

    @Test fun keepSettingKeepsOriginalWhileShowingGeneratedNegativeHonestly() {
        withNegativeWorkflow()
        installBinding(true, AgentPromptBinding(PromptBindingMode.KEEP))
        responses += success("""{"reply":"好","prompt":"cat","negative_prompt":"new negative","should_draw":true}""")
        vm.sendAgentMessage("画猫")
        awaitDone()
        assertEquals("template_embedding, (blurry:1.2)", submittedNegative())
        assertEquals("new negative", vm.agentPromptOutput.value.negative)
        assertTrue(vm.agentPromptOutput.value.negativeStatus!!.contains("保留工作流反向原值"))
    }

    @Test fun omittedNegativeOutputShowsRetainedOriginalAndClearChatClearsPair() {
        withNegativeWorkflow()
        responses += success("""{"reply":"好","prompt":"cat","should_draw":true}""")
        vm.sendAgentMessage("画猫")
        awaitDone()
        assertEquals("template_embedding, (blurry:1.2)", vm.agentPromptOutput.value.negative)
        vm.clearAgentChat()
        assertEquals("", vm.agentPromptOutput.value.positive)
        assertNull(vm.agentPromptOutput.value.negative)
        assertEquals("", MainViewModelAgent.loadPromptOutput(vm).positive)
    }

    @Test fun llmSettingsAreFrozenAtSendRatherThanReadAfterLaunch() {
        vm._agentInteractionMode.value = MainViewModel.AGENT_MODE_CHAT
        vm._llmReasoningEffort.value = AgentReasoningEffort.XHIGH
        responses += success("ok")
        vm.sendAgentMessage("hello")
        // Directly change the flow before the IO job executes; public setting switches also stop it.
        vm._llmReasoningEffort.value = AgentReasoningEffort.LOW
        vm._llmModel.value = "changed-after-send"
        awaitDone()
        val body = JsonParser.parseString(llmBodies.single()).asJsonObject
        assertEquals("xhigh", body["reasoning_effort"].asString)
        assertEquals("fixture-model", body["model"].asString)
    }

    @Test fun chatAndDrawUseFrozenPerProfileOutputLimitAndBudgetedHistory() {
        val profile = LlmProfile(id = "limit-profile", name = "limits", apiKey = "",
            baseUrl = server.url("/v1").toString(), model = "fixture-model",
            contextTokens = 16384, maxOutputTokens = 2048, outputTokenParameter = "max_tokens")
        vm.addOrUpdateLlmProfile(profile)
        vm._llmApiKey.value = "fixture-key"
        vm._agentInteractionMode.value = MainViewModel.AGENT_MODE_CHAT
        responses += success("chat")
        vm.sendAgentMessage("hello")
        awaitDone()
        assertEquals(2048, JsonParser.parseString(llmBodies.last()).asJsonObject["max_tokens"].asInt)
        vm._agentInteractionMode.value = MainViewModel.AGENT_MODE_DRAW
        responses += success("""{"reply":"ok","prompt":"cat","negative_prompt":"","should_draw":false}""")
        vm.sendAgentMessage("只要提示词，猫")
        awaitDone()
        val body = JsonParser.parseString(llmBodies.last()).asJsonObject
        assertEquals(2048, body["max_tokens"].asInt)
        assertFalse(body.has("max_completion_tokens"))
        assertTrue(promptBodies.isEmpty())
    }
}
