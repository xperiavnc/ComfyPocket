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
        shadowOf(Looper.getMainLooper()).idle()
        ShadowToast.reset()
    }

    @After fun close() {
        store.clear()
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

    @Test fun customPersonaAndDrawRulesSurviveTheOfflineMigration() {
        vm.prefs.edit().putBoolean("agent_offline_prompt_migrated", false)
            .putString("agent_system_prompt", "我的自定义人设")
            .putString("agent_draw_prompt", "我的自定义正反向规则").commit()
        assertEquals("我的自定义人设", MainViewModelAgent.loadAgentSystemPrompt(vm))
        assertEquals("我的自定义正反向规则", MainViewModelAgent.loadAgentDrawPrompt(vm))
    }

    @Test fun exactPreviousDefaultsUpgradeAndKeepLocalCopies() {
        val previous = javaClass.classLoader!!.getResourceAsStream("agent-previous-rules.json")!!.bufferedReader().use {
            com.google.gson.JsonParser.parseString(it.readText()).asJsonObject
        }
        for (field in listOf("draw", "drawBeforeNegative")) {
            val old = previous[field].asString
            vm.prefs.edit().putBoolean("agent_offline_prompt_migrated", false)
                .putString("agent_draw_prompt", old).putString("agent_system_prompt", previous["persona"].asString).commit()
            assertEquals(MainViewModel.DEFAULT_AGENT_DRAW_PROMPT, MainViewModelAgent.loadAgentDrawPrompt(vm))
            assertEquals(MainViewModel.DEFAULT_AGENT_SYSTEM_PROMPT, MainViewModelAgent.loadAgentSystemPrompt(vm))
            assertEquals(old, vm.prefs.getString("agent_offline_previous_agent_draw_prompt", null))
        }
    }

    @Test fun newUserFirstDefaultsMigrateOnlyExactBuiltInsAndBackThemUp() {
        val previous = javaClass.classLoader!!.getResourceAsStream("agent-pre-toolbox-rules.json")!!.bufferedReader().use {
            com.google.gson.JsonParser.parseString(it.readText()).asJsonObject
        }
        val oldPersona = previous["defaultPersona"].asString
        val oldRules = previous["defaultDrawRules"].asString
        vm.prefs.edit().putBoolean("agent_user_instruction_prompt_migrated", false)
            .putString("agent_system_prompt", oldPersona).putString("agent_draw_prompt", oldRules).commit()
        assertEquals(MainViewModel.DEFAULT_AGENT_SYSTEM_PROMPT, MainViewModelAgent.loadAgentSystemPrompt(vm))
        assertEquals(MainViewModel.DEFAULT_AGENT_DRAW_PROMPT, MainViewModelAgent.loadAgentDrawPrompt(vm))
        assertEquals(oldPersona, vm.prefs.getString("agent_user_instruction_previous_agent_system_prompt", null))
        assertEquals(oldRules, vm.prefs.getString("agent_user_instruction_previous_agent_draw_prompt", null))
        vm.prefs.edit().putBoolean("agent_user_instruction_prompt_migrated", false)
            .putString("agent_system_prompt", "我自己改的人设")
            .putString("agent_draw_prompt", "我自己改的分镜").commit()
        assertEquals("我自己改的人设", MainViewModelAgent.loadAgentSystemPrompt(vm))
        assertEquals("我自己改的分镜", MainViewModelAgent.loadAgentDrawPrompt(vm))
    }

    @Test fun negativeRulesUpgradeExactPreviousDefaultAndPreserveUserEdits() {
        val previous = javaClass.classLoader!!.getResourceAsStream("agent-pre-negative-rules.json")!!.bufferedReader().use {
            com.google.gson.JsonParser.parseString(it.readText()).asJsonObject["defaultDrawRules"].asString
        }
        vm.prefs.edit().putBoolean("agent_negative_prompt_v1_migrated", false)
            .putString("agent_draw_prompt", previous).commit()
        assertEquals(MainViewModel.DEFAULT_AGENT_DRAW_PROMPT, MainViewModelAgent.loadAgentDrawPrompt(vm))
        assertEquals(previous, vm.prefs.getString("agent_negative_previous_agent_draw_prompt", null))
        vm.prefs.edit().putBoolean("agent_negative_prompt_v1_migrated", false)
            .putString("agent_draw_prompt", "$previous\n我自己补充的规则").commit()
        assertEquals("$previous\n我自己补充的规则", MainViewModelAgent.loadAgentDrawPrompt(vm))
        assertEquals(previous, vm.prefs.getString("agent_negative_previous_agent_draw_prompt", null))
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

    @Test fun compositionUpgradeCopiesExactOldDefaultAndLeavesEditedRules() {
        val previous = javaClass.classLoader!!.getResourceAsStream("agent-pre-composition-rules.json")!!.bufferedReader().use {
            com.google.gson.JsonParser.parseString(it.readText()).asJsonObject["defaultDrawRules"].asString
        }
        vm.prefs.edit().putBoolean("agent_composition_prompt_v1_migrated", false)
            .putString("agent_draw_prompt", previous).commit()
        assertEquals(MainViewModel.DEFAULT_AGENT_DRAW_PROMPT, MainViewModelAgent.loadAgentDrawPrompt(vm))
        assertEquals(previous, vm.prefs.getString("agent_composition_previous_agent_draw_prompt", null))
        vm.prefs.edit().putBoolean("agent_composition_prompt_v1_migrated", false)
            .putString("agent_draw_prompt", "$previous\n自定义").commit()
        assertEquals("$previous\n自定义", MainViewModelAgent.loadAgentDrawPrompt(vm))
    }
}
