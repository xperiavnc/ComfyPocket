package com.comfyui.mobile.model

import androidx.compose.runtime.Immutable

@Immutable
data class AgentClarification(
    val question: String,
    val options: List<String>,
    val purpose: String = "general",
    val subjectId: String? = null,
    val sourceRequest: String? = null,
    /** Nullable so conversations saved before structured questions remain readable by Gson. */
    val questions: List<AgentUserQuestion>? = null,
    val answerHistory: List<AgentQuestionExchange>? = null
)

@Immutable
data class AgentQuestionOption(val id: String, val label: String)

@Immutable
data class AgentUserQuestion(
    val id: String,
    val prompt: String,
    /** "multiple_choice" (single selection unless allowMultiple) or "free_text". */
    val kind: String,
    val options: List<AgentQuestionOption>? = null,
    val allowMultiple: Boolean = false,
    val placeholder: String? = null
)

@Immutable
data class AgentQuestionAnswer(
    val questionId: String,
    val optionIds: List<String>? = null,
    val text: String? = null
)

@Immutable
data class AgentClarificationAnswer(val messageId: String, val answers: List<AgentQuestionAnswer>)

@Immutable
data class AgentQuestionExchange(val question: String, val answer: String, val lookupText: String? = null)

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
    val localTagReport: com.comfyui.mobile.taglib.LocalTagReport? = null,
    /** Nullable for older conversations and responses that omitted the negative field. */
    val expandedNegativePrompt: String? = null,
    val negativePromptStatus: String? = null,
    val characterSelections: List<AgentCharacterSelection>? = null,
    val characterStatus: String? = null,
    /** Persisted pending interaction, not a suspended HTTP request. */
    val clarification: AgentClarification? = null,
    val clarificationAnswer: AgentClarificationAnswer? = null
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
