package com.comfyui.mobile.danbooru

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelStore
import com.comfyui.mobile.agent.AgentPromptFormat
import com.comfyui.mobile.agent.AgentPromptPolicy
import com.comfyui.mobile.ui.MainViewModel
import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentDanbooruSettingsTest {
    private lateinit var app: Application
    private lateinit var store: ViewModelStore
    private val prefs get() = app.getSharedPreferences("comfy_prefs", Context.MODE_PRIVATE)
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        prefs.edit().clear().commit()
        store = ViewModelStore()
    }
    @After fun close() { store.clear() }
    private fun vm(): MainViewModel = MainViewModel(app).also { store.put("vm", it) }
    private fun legacy() = JsonParser.parseString(javaClass.getResource("/agent-legacy-defaults.json")!!.readText()).asJsonObject

    @Test fun savesSettingsPersistsAcrossRecreationAndShowsFeedback() {
        val value = DanbooruSettings(enabled = true, proxyEnabled = true, proxyAddress = "http://127.0.0.1:9080",
            callMode = TagCallMode.COMPAT, promptFormat = AgentPromptFormat.ANIMA)
        vm().setAgentDanbooruSettings(value)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(value, vm().agentDanbooruSettings.value)
        assertEquals("已保存提示词格式与 Danbooru 设置", ShadowToast.getTextOfLatestToast())
    }
    @Test fun migratesOnlyExactOldDefaultsAndKeepsRecoverablePreferenceCopies() {
        val old = legacy()
        prefs.edit().putBoolean("agent_prompt_v2_migrated", true)
            .putString("agent_system_prompt", old["persona"].asString)
            .putString("agent_draw_prompt", old["rules"].asString).commit()
        val vm = vm()
        assertEquals(AgentPromptPolicy.defaultPersona, vm.agentSystemPrompt.value)
        assertEquals(AgentPromptPolicy.defaultDrawRules, vm.agentDrawPrompt.value)
        assertEquals(old["rules"].asString, prefs.getString("agent_prompt_v3_previous_agent_draw_prompt", null))
    }
    @Test fun evenSmallUserCustomizationsAreNotOverwritten() {
        val customized = legacy()["rules"].asString + "\n我的自定义镜头规则"
        prefs.edit().putBoolean("agent_prompt_v2_migrated", true)
            .putString("agent_system_prompt", "我的人设")
            .putString("agent_draw_prompt", customized).commit()
        val vm = vm()
        assertEquals("我的人设", vm.agentSystemPrompt.value)
        assertEquals(customized, vm.agentDrawPrompt.value)
    }
    @Test fun invalidProxyDoesNotChangeSavedSettings() {
        val vm = vm()
        vm.setAgentDanbooruSettings(DanbooruSettings(proxyEnabled = true, proxyAddress = "https://host/path"))
        assertEquals(DanbooruSettings(), vm.agentDanbooruSettings.value)
        assertFalse(prefs.contains("agent_danbooru_proxy"))
    }
}
