package com.comfyui.mobile.network

data class ChatMessageDto(
    val role: String,
    val content: String? = null,
    val reasoning_content: String? = null
    )

data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessageDto>,
    val temperature: Double? = 0.7,
    val stream: Boolean? = null,
    val reasoning_effort: String? = null
    )

data class ChatCompletionResponse(
    val choices: List<ChatChoiceDto>? = null,
    val error: ChatErrorDto? = null
    )

data class ChatChoiceDto(
    val message: ChatMessageDto? = null,
    val finish_reason: String? = null
    )

data class ChatStreamChunk(
    val choices: List<ChatStreamChoice>? = null,
    val error: ChatErrorDto? = null
    )

data class ChatStreamChoice(
    val delta: ChatStreamDelta? = null,
    val finish_reason: String? = null
    )

data class ChatStreamDelta(
    val role: String? = null,
    val content: String? = null,
    val reasoning_content: String? = null
    )

data class ChatErrorDto(
    val message: String? = null,
    val type: String? = null
    )
