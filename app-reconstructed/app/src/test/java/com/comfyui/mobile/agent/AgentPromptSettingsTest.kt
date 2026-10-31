package com.comfyui.mobile.agent

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentPromptSettingsTest {
    @Test fun networkAndFormatPreferencesAreRetiredWithoutTouchingUserPrompts() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("prompt-settings-test", Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("agent_danbooru_enabled", true)
            .putBoolean("agent_danbooru_proxy_enabled", true).putString("agent_danbooru_proxy", "http://fixture:1234")
            .putString("agent_danbooru_mode", "AUTO").putString("agent_prompt_format", "ANIMA")
            .putString("agent_system_prompt", "我的人设").putString("agent_draw_prompt", "我的画图规则").commit()
        val store = AgentPromptSettingsStore(prefs)
        // The multi-step tool agent is on unless the user turned it off.
        assertEquals(AgentPromptSettings(false, toolLoop = true), store.load())
        assertFalse(prefs.contains("agent_prompt_format"))
        assertFalse(prefs.all.keys.any { it.startsWith("agent_danbooru_") })
        assertEquals("我的人设", prefs.getString("agent_system_prompt", null))
        assertEquals("我的画图规则", prefs.getString("agent_draw_prompt", null))
        store.save(AgentPromptSettings(false))
        assertEquals(AgentPromptSettings(false), store.load())
        store.save(AgentPromptSettings(true))
        assertEquals(AgentPromptSettings(true), store.load())
        store.save(AgentPromptSettings(true, toolLoop = true))
        assertEquals(AgentPromptSettings(true, toolLoop = true), store.load())
    }
}
