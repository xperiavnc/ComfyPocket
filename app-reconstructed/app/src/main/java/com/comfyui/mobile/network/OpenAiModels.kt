package com.comfyui.mobile.network

data class ChatMessageDto(
    val role: String,
    val content: String? = null,
    val reasoning_content: String? = null,
    val tool_calls: List<ChatToolCall>? = null,
    val tool_call_id: String? = null
    )

data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessageDto>,
    val temperature: Double? = 0.7,
    val stream: Boolean? = null,
    val reasoning_effort: String? = null,
    val tools: List<ChatToolDefinition>? = null,
    val parallel_tool_calls: Boolean? = null,
    val max_completion_tokens: Int? = null,
    val max_tokens: Int? = null
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

data class ChatFunctionCall(val name: String, val arguments: String)
data class ChatToolCall(val id: String, val function: ChatFunctionCall, val type: String = "function")
data class ChatFunctionDefinition(val name: String, val description: String, val parameters: Map<String, Any>)
data class ChatToolDefinition(val function: ChatFunctionDefinition, val type: String = "function")
