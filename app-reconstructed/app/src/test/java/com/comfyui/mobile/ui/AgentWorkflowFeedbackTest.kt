package com.comfyui.mobile.ui

import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import com.google.gson.JsonParser
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
import org.robolectric.shadows.ShadowToast
import java.io.File

/** The exact screenshot action: success must follow a real write/reload, never a failed save. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class AgentWorkflowFeedbackTest {
    private lateinit var vm: MainViewModel
    private lateinit var store: ViewModelStore

    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("comfy_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        vm = MainViewModel(app)
        store = ViewModelStore().apply { put("feedback", vm) }
        shadowOf(Looper.getMainLooper()).idle()
        ShadowToast.reset()
    }

    @After fun close() {
        store.clear()
        vm.repository.disconnect()
    }

    private fun workflow(name: String): File {
        vm._agentWorkflowSource.value = MainViewModel.AGENT_WF_SAVED
        vm._agentWorkflowId.value = name
        vm.agentWorkflow = mapOf("1" to mapOf("class_type" to "CLIPTextEncode",
            "inputs" to mapOf("text" to "current workflow text")))
        return File(MainViewModelWorkflow.savedWorkflowsDir(vm), name)
    }

    private fun awaitFeedback() {
        val deadline = System.nanoTime() + 10_000_000_000L
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (ShadowToast.shownToastCount() > 0) return
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        fail("Workflow action produced no feedback")
    }

    @Test fun resetSuccessIsReportedOnceAfterTheActualFileIsWrittenAndReloaded() {
        val file = workflow("toast-success.json")
        file.writeText("""{"1":{"class_type":"CLIPTextEncode","inputs":{"text":"old file text"}}}""")
        vm.resetAgentSavedWorkflow()
        awaitFeedback()
        assertEquals("已重置并保存", ShadowToast.getTextOfLatestToast())
        assertEquals(1, ShadowToast.shownToastCount())
        val saved = JsonParser.parseString(file.readText()).asJsonObject["1"].asJsonObject["inputs"].asJsonObject["text"].asString
        assertEquals("current workflow text", saved)
        val loaded = ((vm.agentWorkflow!!["1"] as Map<*, *>)["inputs"] as Map<*, *>)["text"]
        assertEquals(saved, loaded)
        println("AGENT_RESET success toast=已重置并保存 count=1 savedAndReloaded=true")
    }

    @Test fun failedWriteShowsOnlyFailureAndKeepsCurrentParameters() {
        val directory = workflow("toast-write-failure.json")
        assertTrue(directory.mkdir())
        vm.agentWorkflowParamValues["1:text"] = "keep my draft"
        vm.resetAgentSavedWorkflow()
        awaitFeedback()
        assertTrue(ShadowToast.getTextOfLatestToast().toString().startsWith("重置失败:"))
        assertEquals(1, ShadowToast.shownToastCount())
        assertEquals("keep my draft", vm.agentWorkflowParamValues["1:text"])
        assertTrue(directory.isDirectory)
        println("AGENT_RESET failure toast=failure count=1 successToast=absent draftRetained=true")
    }

    @Test fun missingWorkflowShowsValidationFeedbackInsteadOfSuccess() {
        vm._agentWorkflowSource.value = ""
        vm._agentWorkflowId.value = ""
        vm.resetAgentSavedWorkflow()
        awaitFeedback()
        assertEquals("请先选择工作流", ShadowToast.getTextOfLatestToast())
        assertEquals(1, ShadowToast.shownToastCount())
    }
}
