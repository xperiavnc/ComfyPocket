package com.comfyui.mobile.model

import androidx.compose.runtime.Immutable
import java.util.UUID

@Immutable
data class LlmProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val reasoningEffort: String? = null,
    val contextTokens: Int? = null,
    val maxOutputTokens: Int? = null,
    val outputTokenParameter: String? = null
    ) {
    val generationLimits: com.comfyui.mobile.agent.AgentGenerationLimits
        get() = com.comfyui.mobile.agent.AgentGenerationLimits(contextTokens, maxOutputTokens,
            com.comfyui.mobile.agent.OutputTokenParameter.fromStored(outputTokenParameter))
    val displayName: String
        get() = name.ifBlank { model.ifBlank { "未命名配置" } }

    val shortSummary: String
        get() = model.ifBlank { "未指定模型" }
}
