package com.comfyui.mobile.agent

import com.comfyui.mobile.network.ChatMessageDto

enum class OutputTokenParameter(val wireValue: String, val label: String) {
    COMPLETION("max_completion_tokens", "max_completion_tokens"),
    LEGACY("max_tokens", "max_tokens");

    companion object {
        fun fromStored(value: String?): OutputTokenParameter =
            entries.firstOrNull { it.wireValue == value } ?: COMPLETION
    }
}

data class AgentGenerationLimits(
    val contextTokens: Int? = null,
    val outputTokens: Int? = null,
    val outputParameter: OutputTokenParameter = OutputTokenParameter.COMPLETION
) {
    fun validated(): AgentGenerationLimits {
        require(contextTokens == null || contextTokens in 1024..2_000_000) {
            "上下文预算请填写 1024–2000000，或留空沿用默认"
        }
        require(outputTokens == null || outputTokens in 128..1_000_000) {
            "单次输出上限请填写 128–1000000，或留空跟随服务商"
        }
        require(contextTokens == null || outputReserve < contextTokens) {
            "上下文预算必须大于输出预留（${outputReserve} tokens）"
        }
        return this
    }

    val outputReserve: Int get() = outputTokens ?: 4096
}

data class AgentContextMessage(val message: ChatMessageDto, val images: List<String> = emptyList())

data class AgentContextSelection(
    val messages: List<AgentContextMessage>,
    val removedMessages: Int,
    val estimatedInputTokens: Int
)

/**
 * Provider-independent conservative estimate, not a tokenizer or a model capability claim.
 * Required system/current/reference messages are kept whole; trim oldest complete history turns.
 */
object AgentContextBudget {
    fun estimate(value: AgentContextMessage): Int =
        (value.message.content.orEmpty() + value.message.reasoning_content.orEmpty() +
            value.message.tool_calls?.let { com.google.gson.Gson().toJson(it) }.orEmpty())
            .toByteArray(Charsets.UTF_8).size + 16 + value.images.size * 4096

    fun fit(
        system: AgentContextMessage,
        history: List<AgentContextMessage>,
        current: List<AgentContextMessage>,
        limits: AgentGenerationLimits,
        defaultHistoryMessages: Int,
        reservedInputTokens: Int = 0
    ): AgentContextSelection {
        limits.validated()
        val fixed = listOf(system) + current
        require(reservedInputTokens >= 0)
        val fixedCost = fixed.sumOf(::estimate) + reservedInputTokens
        val available = limits.contextTokens?.minus(limits.outputReserve)
        if (available != null && fixedCost > available) throw AgentExecutionException(
            "本轮指令、系统规则和参考资料约需 $fixedCost tokens，加上输出预留 ${limits.outputReserve} " +
                "超过上下文预算 ${limits.contextTokens}。请提高预算或精简本轮输入；未截断当前指令。"
        )
        val turns = mutableListOf<MutableList<AgentContextMessage>>()
        for (message in history.takeLast(200)) {
            if (message.message.role == "user") turns.add(mutableListOf(message))
            else if (message.message.role == "assistant" && turns.isNotEmpty()) turns.last().add(message)
        }
        var budget = (available ?: Int.MAX_VALUE) - fixedCost
        var count = 0
        val selected = mutableListOf<List<AgentContextMessage>>()
        for (turn in turns.asReversed()) {
            val cost = turn.sumOf(::estimate)
            if (cost > budget || (available == null && count + turn.size > defaultHistoryMessages)) break
            selected.add(turn)
            budget -= cost
            count += turn.size
        }
        val kept = selected.asReversed().flatten()
        val result = listOf(system) + kept + current
        return AgentContextSelection(result, history.size - kept.size, result.sumOf(::estimate) + reservedInputTokens)
    }
}
