package com.comfyui.mobile.agent

import java.text.Normalizer
import java.util.Locale

/** Ground generation in known exclusions; only diagnose unambiguous literal tag collisions. */
object AgentNegativePromptPolicy {
    val guidance = """
【反向提示词依据与检查】
negative_prompt 字段必填不等于内容必须非空。每轮独立分析反向，有合适排除项才填写；没有可靠排除项就输出空字符串，不为显得完整凑词。
优先落实用户本轮明确的排除、保留或清空要求；工作流已有反向是基线，保留仍适用的专用词与权重，删除与本轮要求冲突的旧项。历史 AI 反向只是候选，不因重复出现就成为用户要求。
用户没有指定时，只补少量与本轮画面确实相关、且模型适用性有依据的缺陷排除项。负面词库只是词义参考，命中或属于负面分类都不代表本轮必须使用。
工作流节点和文件名只是已观察到的配置，不等于已确认模型能力。资料不足时保持最小反向，不凭空编造 embedding、LoRA、触发词、模型专用质量词或权重，不声称已有这些资源。
用户要求柔焦、胶片颗粒、单色、画面文字等效果时，不用通用负面模板反过来压掉这些效果。角色、人数、发色、衣服、姿势、画风等内容只有被用户明确排除时才写入反向。
输出前检查正向、用户要求和反向是否相互抵消；相同标签即使大小写、空格/下划线或权重不同也要检查。遇到实质矛盾在 reply 中简短说明，不静默删改用户指定词。反向只放最终排除词，理由、来源和解释留在 reply。
""".trimIndent()

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
