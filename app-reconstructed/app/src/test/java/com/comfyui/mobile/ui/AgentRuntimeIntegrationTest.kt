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
    private val promptBodies = CopyOnWriteArrayList<String>()
    private val uploadBodies = CopyOnWriteArrayList<String>()
    private val gson = Gson()
    private val tagRequests = CopyOnWriteArrayList<RecordedRequest>()

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
                    llmBodies += request.body.readUtf8()
                    responses.poll(5, TimeUnit.SECONDS) ?: MockResponse().setResponseCode(500)
                }
                "/tags.json", "/tag_aliases.json", "/wiki_pages/cat.json" -> {
                    tagRequests += request
                    MockResponse().setResponseCode(410)
                }
                "/prompt" -> {
                    promptBodies += request.body.readUtf8()
                    MockResponse().setBody("""{"prompt_id":"fixture-pid"}""")
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
        pumpUntil { true }
    }

    @After fun close() {
        if (::store.isInitialized) store.clear()
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
    private fun awaitDone() = pumpUntil { !vm.agentBusy.value }
    private fun graphText() = ((vm.agentWorkflow!!["1"] as Map<*, *>)["inputs"] as Map<*, *>)["text"]

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
        vm.sendAgentMessage("画deepseek")
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
        assertTrue(system.contains("AI 不需要自行选择角色或服装"))
        assertFalse(system.contains("换成日常穿搭"))
        assertTrue(system.contains("App已按用户对话选定的服装预设绘画标签"))
        assertTrue(system.contains("不是绘画标签"))
        assertFalse((graphText() as String).contains("大肥鱼"))
        assertFalse((graphText() as String).contains("deepseek"))
        assertFalse((graphText() as String).contains("日常穿搭"))
        assertEquals(2, llmBodies.size)
        assertEquals(2, promptBodies.size)
        println("CHARACTER_OUTFIT actual=${graphText()} selection=${vm.agentMessages.value.last().characterSelections} calls=${llmBodies.size}")
    }

    @Test fun duplicateAliasStopsBeforeModelInsteadOfGuessingAndExactNameStillWorks() {
        vm._characterLores.value = listOf(
            com.comfyui.mobile.model.CharacterLore("a", "星澜", "a_token", aliases = listOf("小蓝")),
            com.comfyui.mobile.model.CharacterLore("b", "月汐", "b_token", aliases = listOf("小蓝")))
        vm.sendAgentMessage("画小蓝")
        awaitDone()
        assertEquals(AgentPhase.FAILED, vm.agentSessionState.value.turn!!.phase)
        assertTrue(vm.agentMessages.value.last().errorMessage!!.contains("角色别名有重名"))
        assertTrue(llmBodies.isEmpty())
        assertTrue(promptBodies.isEmpty())
        responses += success("""{"reply":"好","prompt":"b_token","negative_prompt":"","should_draw":true}""")
        vm.sendAgentMessage("画月汐")
        awaitDone()
        assertEquals(AgentPhase.COMPLETED, vm.agentSessionState.value.turn!!.phase)
        assertEquals("b", vm.agentMessages.value.last().characterSelections!!.single().loreId)
        assertEquals(1, llmBodies.size)
    }

    @Test fun duplicateOutfitDialogueTriggerStopsBeforeModelAndWorkflow() {
        vm._characterLores.value = listOf(com.comfyui.mobile.model.CharacterLore(
            "star", "星澜", "xl_token", aliases = listOf("小澜"),
            outfits = listOf(
                com.comfyui.mobile.model.CharacterOutfit("one", "冬装", "winter_token",
                    dialogueTriggers = listOf("换成演出服")),
                com.comfyui.mobile.model.CharacterOutfit("two", "便服", "casual_token",
                    dialogueTriggers = listOf("换成演出服"))
            )))
        vm.sendAgentMessage("画小澜，换成演出服")
        awaitDone()
        assertEquals(AgentPhase.FAILED, vm.agentSessionState.value.turn!!.phase)
        assertTrue(vm.agentMessages.value.last().errorMessage!!.contains("服装对话触发词有重名"))
        assertTrue(llmBodies.isEmpty())
        assertTrue(promptBodies.isEmpty())
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

    @Test fun registeredAliasDoesNotGetReinterpretedByOptionalOfflineCharacterDictionary() {
        com.comfyui.mobile.taglib.TagLibraryTestData.install(app, vm.localTagLibrary)
        vm._agentPromptSettings.value = AgentPromptSettings(true)
        val raw = kotlinx.coroutines.runBlocking { vm.localTagLibrary.forRequest("画初音未来，蓝发") }
        assertTrue(raw.entries.any { it.kind == "character" && it.name == "hatsune_miku" })
        vm._characterLores.value = listOf(com.comfyui.mobile.model.CharacterLore(
            "custom", "用户登记的角色", "user_exact_trigger", aliases = listOf("初音未来"),
            fixedTriggerWords = "user_exact_trigger"))
        responses += success("""{"reply":"好","prompt":"1girl, blue hair","negative_prompt":"","should_draw":true}""")
        vm.sendAgentMessage("画初音未来，蓝发")
        awaitDone()
        val report = requireNotNull(vm.agentMessages.value.last().localTagReport)
        assertTrue(report.entries.none { it.kind == "character" })
        assertTrue(report.entries.any { it.name == "blue hair" })
        val messages = JsonParser.parseString(llmBodies.single()).asJsonObject["messages"].asJsonArray
        val dictionary = messages.first { it.asJsonObject["content"].asString.contains("离线词库候选") }
            .asJsonObject["content"].asString
        assertFalse(dictionary.contains("hatsune_miku"))
        assertEquals("1girl, blue hair, user_exact_trigger", graphText())
        assertEquals("custom", vm.agentMessages.value.last().characterSelections!!.single().loreId)
        assertEquals(1, llmBodies.size)
        assertEquals(1, promptBodies.size)
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
        val saved = JsonParser.parseString(vm.prefs.getString(MainViewModelAgent.scopedPref("agent_messages", vm), "")!!)
            .asJsonArray.last().asJsonObject
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
        vm.prefs.edit().putString(MainViewModelAgent.getAgentWorkflowParamStorageKey(
            vm, MainViewModel.AGENT_WF_SAVED, "agent-fixture.json"),
            """{"2:text":"saved_embedding, blurry"}""").commit()
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
        assertTrue("fixture-pid" in vm.agentRunPromptIds)
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
        assertTrue(vm.prefs.getString(MainViewModelAgent.scopedPref("agent_messages", vm), "")!!.contains("cat, rain"))
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
        val content = JsonParser.parseString(llmBodies.single()).asJsonObject["messages"].asJsonArray.last()
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
        assertFalse(body.has("tools"))
        val messages = body["messages"].asJsonArray
        assertTrue(messages.any { it.asJsonObject["content"].asString.contains("离线词库候选") })
        assertTrue(messages.any { it.asJsonObject["content"].asString.contains("blue hair") })
        assertTrue(messages.last().asJsonObject["content"].asString.startsWith("画蓝发人物"))
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
        AgentPromptBindingStore(vm.prefs).save(MainViewModelAgent.scope(vm), vm._agentWorkflowSource.value,
            vm._agentWorkflowId.value, negative, binding)
        if (negative) vm._agentNegativeBinding.value = binding else vm._agentPositiveBinding.value = binding
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
