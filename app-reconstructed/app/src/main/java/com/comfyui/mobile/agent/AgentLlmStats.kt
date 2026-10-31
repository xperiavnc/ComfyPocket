package com.comfyui.mobile.agent

import com.comfyui.mobile.data.LlmCompletionOutput
import com.comfyui.mobile.network.ChatMessageDto
import java.util.Locale

/**
 * What the last model call cost and how fast it answered, for the Agent chat footer.
 * Server usage wins; without it the counts are rough display estimates and flagged as such.
 */
data class AgentLlmStats(
    val model: String,
    val promptTokens: Int,
    val outputTokens: Int,
    val cachedTokens: Int? = null,
    val contextLimit: Int? = null,
    val tokensPerSecond: Double? = null,
    val estimated: Boolean = false,
    /** True while the answer is still streaming (counts and speed are live estimates). */
    val live: Boolean = false
) {
    val contextPercent: Int? get() = contextLimit?.takeIf { it > 0 }?.let {
        ((promptTokens + outputTokens) * 100.0 / it).toInt().coerceIn(0, 999)
    }

    /** e.g. "上下文 12.3k / 128k (10%) · 42 tok/s · 缓存 80%" */
    fun summary(): String {
        val approx = if (estimated) "≈" else ""
        val used = approx + compact(promptTokens + outputTokens)
        val context = contextLimit?.let { "上下文 $used / ${compact(it)} (${contextPercent}%)" } ?: "上下文 $used"
        val parts = mutableListOf(context)
        tokensPerSecond?.takeIf { it.isFinite() && it > 0 }?.let {
            parts += String.format(Locale.US, if (it < 10) "%.1f tok/s" else "%.0f tok/s", it)
        }
        if (!live) cachedTokens?.takeIf { promptTokens > 0 }?.let {
            parts += "缓存 ${(it * 100.0 / promptTokens).toInt().coerceIn(0, 100)}%"
        }
        return parts.joinToString(" · ")
    }

    companion object {
        fun compact(tokens: Int): String = when {
            tokens >= 1_000_000 -> String.format(Locale.US, "%.1fM", tokens / 1_000_000.0)
            tokens >= 10_000 -> String.format(Locale.US, "%.0fk", tokens / 1000.0)
            tokens >= 1000 -> String.format(Locale.US, "%.1fk", tokens / 1000.0)
            else -> tokens.toString()
        }

        /** CJK ≈ 1 token per char, other text ≈ 4 chars per token; only for display. */
        fun estimateTokens(text: String): Int {
            var cjk = 0
            var other = 0
            text.forEach { if (it.code >= 0x2E80) cjk++ else other++ }
            return cjk + (other + 3) / 4
        }

        fun estimatePrompt(messages: List<ChatMessageDto>, imageCount: Int, toolsJson: String?): Int =
            messages.sumOf { estimateTokens(it.content.orEmpty() + it.reasoning_content.orEmpty()) + 4 } +
                imageCount * 800 + toolsJson?.let(::estimateTokens).orZero()

        fun estimateOutput(content: String, reasoning: String, toolArgs: String = ""): Int =
            estimateTokens(content) + estimateTokens(reasoning) + estimateTokens(toolArgs)

        /** Generation speed excludes time to first token when it is known. */
        fun speed(outputTokens: Int, firstTokenMillis: Long?, totalMillis: Long?): Double? {
            val total = totalMillis ?: return null
            val generating = total - (firstTokenMillis ?: 0L)
            if (outputTokens <= 0 || generating < 200) return null
            return outputTokens * 1000.0 / generating
        }

        fun from(
            model: String,
            output: LlmCompletionOutput,
            estimatedPrompt: Int,
            contextLimit: Int?
        ): AgentLlmStats {
            val usage = output.usage
            val estimatedOut = estimateOutput(output.content, output.reasoning.orEmpty(),
                output.toolCalls.joinToString("") { it.function.arguments })
            val prompt = usage?.prompt_tokens ?: estimatedPrompt
            val out = usage?.completion_tokens ?: estimatedOut
            return AgentLlmStats(
                model = model,
                promptTokens = prompt,
                outputTokens = out,
                cachedTokens = usage?.cachedTokens,
                contextLimit = contextLimit,
                tokensPerSecond = speed(out, output.firstTokenMillis, output.totalMillis),
                estimated = usage?.prompt_tokens == null || usage.completion_tokens == null
            )
        }

        private fun Int?.orZero(): Int = this ?: 0
    }
}
