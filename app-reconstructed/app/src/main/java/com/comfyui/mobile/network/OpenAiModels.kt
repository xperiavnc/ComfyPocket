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
    val tool_choice: String? = null,
    val parallel_tool_calls: Boolean? = null,
    val max_completion_tokens: Int? = null,
    val max_tokens: Int? = null,
    /** Asks OpenAI-compatible servers to append a final usage chunk to the stream. */
    val stream_options: ChatStreamOptions? = null
    )

data class ChatStreamOptions(val include_usage: Boolean = true)

/** Token accounting; cache fields differ per provider (OpenAI details vs DeepSeek hit/miss). */
data class ChatUsageDto(
    val prompt_tokens: Int? = null,
    val completion_tokens: Int? = null,
    val total_tokens: Int? = null,
    val prompt_tokens_details: ChatPromptTokensDetails? = null,
    val prompt_cache_hit_tokens: Int? = null,
    val prompt_cache_miss_tokens: Int? = null
) {
    val cachedTokens: Int? get() = prompt_tokens_details?.cached_tokens ?: prompt_cache_hit_tokens
}

data class ChatPromptTokensDetails(val cached_tokens: Int? = null)

data class ChatCompletionResponse(
    val choices: List<ChatChoiceDto>? = null,
    val error: ChatErrorDto? = null,
    val usage: ChatUsageDto? = null
    )

data class ChatChoiceDto(
    val message: ChatMessageDto? = null,
    val finish_reason: String? = null
    )

data class ChatStreamChunk(
    val choices: List<ChatStreamChoice>? = null,
    val error: ChatErrorDto? = null,
    val usage: ChatUsageDto? = null
    )

data class ChatStreamChoice(
    val delta: ChatStreamDelta? = null,
    val finish_reason: String? = null
    )

data class ChatStreamDelta(
    val role: String? = null,
    val content: String? = null,
    val reasoning_content: String? = null,
    val tool_calls: List<ChatStreamToolCall>? = null
    )

/** Tool arguments, names and IDs may all arrive in separate/interleaved SSE chunks. */
data class ChatStreamToolCall(
    val index: Int? = null,
    val id: String? = null,
    val type: String? = null,
    val function: ChatStreamFunctionCall? = null
)
data class ChatStreamFunctionCall(val name: String? = null, val arguments: String? = null)

data class ChatErrorDto(
    val message: String? = null,
    val type: String? = null
    )

data class ChatFunctionCall(val name: String, val arguments: String)
data class ChatToolCall(val id: String, val function: ChatFunctionCall, val type: String = "function")
data class ChatFunctionDefinition(val name: String, val description: String, val parameters: Map<String, Any>)
data class ChatToolDefinition(val function: ChatFunctionDefinition, val type: String = "function")
