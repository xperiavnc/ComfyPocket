package com.comfyui.mobile.data

import com.comfyui.mobile.network.ChatFunctionCall
import com.comfyui.mobile.network.ChatStreamToolCall
import com.comfyui.mobile.network.ChatToolCall
import com.google.gson.JsonParser

/** Accumulate only the protocol fields. Reasoning/text is never interpreted as a tool call. */
internal class LlmToolCallAccumulator {
    private class Partial {
        val id = StringBuilder()
        val name = StringBuilder()
        val arguments = StringBuilder()
        var type: String? = null
    }
    private val partial = sortedMapOf<Int, Partial>()

    fun append(deltas: List<ChatStreamToolCall>) {
        for (delta in deltas) {
            val index = delta.index ?: if (partial.size <= 1) partial.keys.firstOrNull() ?: 0
                else error("工具分片缺少 index")
            require(index in 0..31) { "工具分片 index 异常" }
            val call = partial.getOrPut(index) { Partial() }
            delta.type?.let {
                require(it == "function" && (call.type == null || call.type == it)) { "工具类型异常" }
                call.type = it
            }
            fun StringBuilder.metadata(piece: String?) {
                if (!piece.isNullOrEmpty() && toString() != piece) append(piece)
                require(length <= 256) { "工具标识过长" }
            }
            call.id.metadata(delta.id)
            delta.function?.name?.let(call.name::append)
            require(call.name.length <= 256) { "工具名称过长" }
            delta.function?.arguments?.let(call.arguments::append)
            require(call.arguments.length <= 65_536) { "工具参数过长" }
        }
    }

    fun completed(): List<ChatToolCall> = partial.values.map {
        ChatToolCall(it.id.toString(), ChatFunctionCall(it.name.toString(), it.arguments.toString()),
            it.type ?: "function")
    }.also(::validateToolCalls)
}

internal fun validateToolCalls(calls: List<ChatToolCall>) {
    require(calls.size <= 32 && calls.map { it.id }.distinct().size == calls.size) { "工具调用 ID 重复" }
    for (call in calls) {
        require(!call.id.isNullOrBlank() && call.id.length <= 256 &&
            call.type == "function" && !call.function?.name.isNullOrBlank()) { "工具调用不完整" }
        val arguments = call.function.arguments
        require(!arguments.isNullOrBlank() && arguments.length <= 65_536 &&
            JsonParser.parseString(arguments).isJsonObject) { "工具参数未完整结束" }
    }
}
