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
    val estimatedInputTokens: Int,
    /** History messages kept right after the system message (they may be dropped again mid-turn). */
    val keptHistory: Int = 0
)

/** One model request after [AgentContextBudget.trimRequest]; [fits] is false when even no history overflows. */
data class AgentTrimmedRequest(
    val messages: List<ChatMessageDto>,
    val images: Map<Int, List<String>>,
    val droppedHistory: Int,
    val cost: Int,
    val fits: Boolean
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

    /** History is trimmed in steps of this many turns to keep the cached prompt prefix stable. */
    const val HISTORY_CHUNK = 4

    fun fit(
        system: AgentContextMessage,
        history: List<AgentContextMessage>,
        current: List<AgentContextMessage>,
        limits: AgentGenerationLimits,
        defaultHistoryMessages: Int,
        reservedInputTokens: Int = 0,
        /**
         * Room kept free for what this turn appends itself (tool calls, tool answers, reasoning).
         * Only shrinks the history; capped at a third of what is left so it never fails a turn.
         */
        growthHeadroom: Int = 0
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
        val headroom = available?.let { growthHeadroom.coerceIn(0, (it - fixedCost) / 3) } ?: 0
        var budget = (available ?: Int.MAX_VALUE) - fixedCost - headroom
        var count = 0
        val selected = mutableListOf<List<AgentContextMessage>>()
        for (turn in turns.asReversed()) {
            val cost = turn.sumOf(::estimate)
            if (cost > budget || (available == null && count + turn.size > defaultHistoryMessages)) break
            selected.add(turn)
            budget -= cost
            count += turn.size
        }
        // Snap the first kept turn to a multiple of HISTORY_CHUNK so the oldest retained turns (right
        // after the system message) stay the same for several turns: a one-turn sliding window would
        // change the cached prefix on every request. We only ever keep fewer turns than the greedy
        // pick (never more than fits or than the legacy cap), and only when at least half survive.
        val greedy = selected.size
        // Chunk scales with the window (at most HISTORY_CHUNK) so at least half of it always survives.
        val chunk = ((greedy + 1) / 2).coerceIn(1, HISTORY_CHUNK)
        val start = ((turns.size - greedy + chunk - 1) / chunk) * chunk
        val aligned = turns.size - start
        val keepTurns = if (aligned in 1..greedy) aligned else greedy
        val kept = turns.takeLast(keepTurns).flatten()
        val result = listOf(system) + kept + current
        return AgentContextSelection(result, history.size - kept.size, result.sumOf(::estimate) + reservedInputTokens,
            keptHistory = kept.size)
    }

    /** Headroom asked for by the multi-step tool loop: several tool answers plus reasoning per step. */
    const val TOOL_GROWTH = 96 * 1024

    /**
     * Mid-turn safety net. A tool loop appends calls, answers and reasoning on every step, so a request
     * can outgrow the budget that [fit] chose. Messages 1..[historyCount] are older turns; drop whole
     * turns oldest first (to 7/8 of [limit] when possible, so later steps keep the same prefix), and
     * keep the system message, the current task and every tool answer intact. Image indexes are remapped.
     */
    fun trimRequest(
        messages: List<ChatMessageDto>, images: Map<Int, List<String>>, historyCount: Int, limit: Int
    ): AgentTrimmedRequest {
        fun cost(index: Int) = estimate(AgentContextMessage(messages[index], images[index].orEmpty()))
        val costs = messages.indices.map(::cost)
        val total = costs.sum()
        if (total <= limit || messages.size < 2) return AgentTrimmedRequest(messages, images, 0, total, total <= limit)
        val history = historyCount.coerceIn(0, (messages.size - 1).coerceAtLeast(0))
        // Possible cut points: the start of a history user turn, or the end of history.
        val cuts = (2..history).filter { messages[it].role == "user" } + (history + 1)
        fun dropped(cut: Int) = costs.subList(1, cut).sum()
        val cut = cuts.firstOrNull { total - dropped(it) <= limit - limit / 8 }
            ?: cuts.firstOrNull { total - dropped(it) <= limit }
            ?: cuts.last()
        val drop = cut - 1
        val kept = listOf(messages.first()) + messages.drop(cut)
        val remapped = images.mapNotNull { (index, value) ->
            when {
                index == 0 -> 0 to value
                index < cut -> null
                else -> index - drop to value
            }
        }.toMap()
        val keptCost = total - dropped(cut)
        return AgentTrimmedRequest(kept, remapped, drop, keptCost, keptCost <= limit)
    }
}
