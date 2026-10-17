package com.comfyui.mobile.model

import androidx.compose.runtime.Immutable

@Immutable
data class AgentChatMessage(
    val id: String,
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val expandedPrompt: String? = null,
    val thinkingContent: String? = null,
    val isThinking: Boolean = false,
    val errorMessage: String? = null,
    /** Nullable: older persisted JSON has no field (Gson skips Kotlin defaults). */
    val attachments: List<AgentAttachment>? = null,
    val tagCheck: com.comfyui.mobile.danbooru.DanbooruCheckReport? = null,
    val tagCheckingOrigin: com.comfyui.mobile.danbooru.TagCheckOrigin? = null
    )

@Immutable
data class AgentAttachment(
    val id: String,
    /** "image" or "file" */
    val kind: String,
    /** file:// uri of the compressed local copy (image) or source uri (file). */
    val uri: String,
    val name: String,
    val sizeBytes: Long = 0,
    /** Inline text for text files (<=100KB). */
    val textContent: String? = null
    ) {
    val isImage: Boolean get() = kind == "image"
}
