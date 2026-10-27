package com.comfyui.mobile.ui

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import com.comfyui.mobile.agent.AgentReasoningEffort
import com.comfyui.mobile.model.LlmProfile
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class AgentSettingsFeedbackTest {
    private lateinit var vm: MainViewModel
    private lateinit var store: ViewModelStore

    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("comfy_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        vm = MainViewModel(app)
        store = ViewModelStore().apply { put("settings", vm) }
        awaitAgentStorage(vm)
        shadowOf(Looper.getMainLooper()).idle()
        ShadowToast.reset()
    }

    @After fun close() {
        store.clear()
        kotlinx.coroutines.runBlocking { vm.agentWorkspace.awaitClosed() }
        vm.repository.disconnect()
    }

    @Test fun saveAppliesPersistsAndShowsImmediateConfirmation() {
        // AndroidKeyStore is not emulated: this test saves a profile with no API key.
        val profile = LlmProfile(id = "feedback", name = "我的模型",
            baseUrl = "https://example.test/v1", apiKey = "", model = "model", reasoningEffort = "max")
        vm.addOrUpdateLlmProfile(profile)
        shadowOf(Looper.getMainLooper()).idle()
        println("savedProfile=${vm.currentLlmProfileId.value}, toast=${ShadowToast.getTextOfLatestToast()}")
        assertEquals(profile.id, vm.currentLlmProfileId.value)
        assertEquals(AgentReasoningEffort.MAX, vm.llmReasoningEffort.value)
        assertEquals(profile, vm.loadLlmProfiles().single { it.id == profile.id })
        assertEquals("已保存并应用配置：我的模型", ShadowToast.getTextOfLatestToast())
    }

    @Test fun updatingExistingProfileStillConfirmsEachSave() {
        val profile = LlmProfile(id = "feedback", name = "模型", baseUrl = "https://example.test/v1",
            apiKey = "", model = "before", reasoningEffort = "xhigh")
        vm.addOrUpdateLlmProfile(profile)
        shadowOf(Looper.getMainLooper()).idle()
        ShadowToast.reset()
        vm.addOrUpdateLlmProfile(profile.copy(model = "after", reasoningEffort = "max"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, vm.llmProfiles.value.count { it.id == profile.id })
        assertEquals("after", vm.llmModel.value)
        assertEquals("max", vm.loadLlmProfiles().single { it.id == profile.id }.reasoningEffort)
        assertEquals("已保存并应用配置：模型", ShadowToast.getTextOfLatestToast())
    }

    @Test fun localLibrarySettingsPersistAndShowConfirmation() {
        val settings = com.comfyui.mobile.agent.AgentPromptSettings(false)
        vm.setAgentPromptSettings(settings)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(settings, vm.agentPromptSettings.value)
        assertEquals(settings, com.comfyui.mobile.agent.AgentPromptSettingsStore(vm.prefs).load())
        assertEquals("已保存本地词库设置", ShadowToast.getTextOfLatestToast())
    }

    @Test fun rulesStartEmptyAndExplicitRemovalDoesNotRestoreAnyDefault() {
        assertEquals("", vm.agentSystemPrompt.value)
        assertEquals("", vm.agentDrawPrompt.value)
        vm.setAgentSystemPrompt("USER_DIALOGUE")
        vm.setAgentDrawPrompt("USER_DRAWING")
        val settings = AgentLlmSettings.capture(vm)
        vm.removeAgentRules(com.comfyui.mobile.agent.AgentRuleKind.DIALOGUE)
        vm.removeAgentRules(com.comfyui.mobile.agent.AgentRuleKind.DRAWING)
        assertEquals("", vm.agentSystemPrompt.value)
        assertEquals("", vm.agentDrawPrompt.value)
        assertEquals(com.comfyui.mobile.agent.AgentRuleFiles(),
            com.comfyui.mobile.agent.AgentRuleFilesStore(vm.prefs).load())
        assertEquals("USER_DIALOGUE", settings.persona)
        assertEquals("USER_DRAWING", settings.drawRules)
    }

    @Test fun contextAndOutputLimitsPersistPerProfileAndRejectInvalidUpdates() {
        val first = LlmProfile(id = "limits-a", name = "limits-a", apiKey = "", model = "test",
            contextTokens = 32768, maxOutputTokens = 4096, outputTokenParameter = "max_tokens")
        val second = first.copy(id = "limits-b", contextTokens = 65536, maxOutputTokens = 8192,
            outputTokenParameter = "max_completion_tokens")
        vm.addOrUpdateLlmProfile(first)
        vm.addOrUpdateLlmProfile(second)
        assertEquals(second.generationLimits, vm.loadLlmProfiles().first { it.id == second.id }.generationLimits)
        vm.selectLlmProfile(first.id)
        assertEquals(first.generationLimits, AgentLlmSettings.capture(vm).generationLimits)
        vm.addOrUpdateLlmProfile(first.copy(contextTokens = 1024, maxOutputTokens = 4096))
        assertEquals(first.generationLimits, vm.loadLlmProfiles().first { it.id == first.id }.generationLimits)
    }

}
