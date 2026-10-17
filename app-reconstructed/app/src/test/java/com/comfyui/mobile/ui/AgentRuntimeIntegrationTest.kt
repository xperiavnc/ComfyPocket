package com.comfyui.mobile.ui

import android.app.Application
import android.content.Context
import android.os.Looper
import android.net.Uri
import android.graphics.Bitmap
import androidx.lifecycle.ViewModelStore
import com.comfyui.mobile.agent.AgentPhase
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
}
