package com.comfyui.mobile.agent

import android.content.SharedPreferences

data class AgentPromptSettings(
    val useLocalLibrary: Boolean = false
)

class AgentPromptSettingsStore(private val prefs: SharedPreferences) {
    fun load(): AgentPromptSettings {
        // Retire obsolete options without changing user-written persona/composition rules.
        if (prefs.contains("agent_prompt_format")) prefs.edit().remove("agent_prompt_format").apply()
        if (!prefs.getBoolean("agent_offline_tags_v1_migrated", false)) {
            prefs.edit().remove("agent_danbooru_enabled").remove("agent_danbooru_proxy_enabled")
                .remove("agent_danbooru_proxy").remove("agent_danbooru_mode")
                .putBoolean("agent_offline_tags_v1_migrated", true).apply()
        }
        return AgentPromptSettings(
            prefs.getBoolean("agent_local_tags_enabled", false)
        )
    }

    fun save(value: AgentPromptSettings) {
        prefs.edit().putBoolean("agent_local_tags_enabled", value.useLocalLibrary)
            .remove("agent_prompt_format").apply()
    }
}
