package com.comfyui.mobile.agent

import android.content.SharedPreferences

data class AgentPromptSettings(
    val useLocalLibrary: Boolean = true,
    val promptFormat: AgentPromptFormat = AgentPromptFormat.TAGS
)

class AgentPromptSettingsStore(private val prefs: SharedPreferences) {
    fun load(): AgentPromptSettings {
        // Retire only the removed feature's preferences. Format and user-written prompts survive.
        if (!prefs.getBoolean("agent_offline_tags_v1_migrated", false)) {
            prefs.edit().remove("agent_danbooru_enabled").remove("agent_danbooru_proxy_enabled")
                .remove("agent_danbooru_proxy").remove("agent_danbooru_mode")
                .putBoolean("agent_offline_tags_v1_migrated", true).apply()
        }
        return AgentPromptSettings(
            prefs.getBoolean("agent_local_tags_enabled", true),
            AgentPromptFormat.fromStored(prefs.getString("agent_prompt_format", null))
        )
    }

    fun save(value: AgentPromptSettings) {
        prefs.edit().putBoolean("agent_local_tags_enabled", value.useLocalLibrary)
            .putString("agent_prompt_format", value.promptFormat.name).apply()
    }
}
