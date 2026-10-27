package com.comfyui.mobile.agent

import java.text.Normalizer
import java.util.Locale

/** Diagnose unambiguous literal tag collisions without supplying drawing instructions. */
object AgentNegativePromptPolicy {
    /**
     * Return original negative pieces for display, without rewriting either prompt.
     * No substring, synonym or "blurry vs soft focus" inference: those need model/user judgement.
     * Unsupported model syntax and quoted image text are deliberately left alone.
     */
    fun conflicts(positive: String, negative: String?): List<String> {
        if (negative.isNullOrBlank()) return emptyList()
        val positiveTags = pieces(positive).mapNotNull(::canonicalTag).toSet()
        if (positiveTags.isEmpty()) return emptyList()
        return pieces(negative).mapNotNull { piece ->
            canonicalTag(piece)?.takeIf { it in positiveTags }?.let { it to piece.trim() }
        }.distinctBy { it.first }.map { it.second }
    }

    private fun canonicalTag(raw: String): String? {
        var tag = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC)
        repeat(3) {
            val weighted = Regex("""^\(([^():]+):(\d+(?:\.\d+)?)\)$""").matchEntire(tag)
            if (weighted != null) {
                if (weighted.groupValues[2].toDoubleOrNull()?.let { it > 0.0 } != true) return null
                tag = weighted.groupValues[1].trim()
            } else if (tag.startsWith('(') && tag.endsWith(')')) {
                tag = tag.substring(1, tag.lastIndex).trim()
            }
        }
        // Reject sentences, quotes, embeddings, wildcards, schedules, LoRA markup and other syntax.
        if (tag.length !in 1..96 || tag.contains("__") ||
            !Regex("""[\p{L}\p{N}_ '\-]+""").matches(tag)) return null
        return tag.lowercase(Locale.ROOT).replace(Regex("""[\s_]+"""), " ").trim().takeIf(String::isNotEmpty)
    }

    private fun pieces(value: String): List<String> {
        if (value.length > 32_000) return emptyList()
        val result = mutableListOf<String>()
        val brackets = ArrayDeque<Char>()
        var quote = false
        var escaped = false
        var start = 0
        for ((index, char) in value.withIndex()) {
            if (escaped) { escaped = false; continue }
            if (char == '\\') { escaped = true; continue }
            if (char == '"') { quote = !quote; continue }
            if (quote) continue
            when (char) {
                '(', '[', '{', '<' -> brackets.addLast(char)
                ')', ']', '}', '>' -> {
                    val expected = when (char) { ')' -> '('; ']' -> '['; '}' -> '{'; else -> '<' }
                    if (brackets.lastOrNull() != expected) return emptyList()
                    brackets.removeLast()
                }
                ',', '，', '\n', '\r' -> if (brackets.isEmpty()) {
                    result.add(value.substring(start, index))
                    start = index + 1
                }
            }
        }
        if (quote || brackets.isNotEmpty()) return emptyList()
        result.add(value.substring(start))
        return result
    }
}
