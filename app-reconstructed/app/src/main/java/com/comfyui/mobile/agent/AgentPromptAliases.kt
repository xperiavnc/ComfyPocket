package com.comfyui.mobile.agent

import com.comfyui.mobile.taglib.LocalTagEntry
import com.google.gson.JsonElement
import java.text.Normalizer
import java.util.Locale

/** Display metadata only. These strings never replace the prompt sent to the workflow. */
data class AgentPromptAlias(val tag: String, val zh: String)

object AgentPromptAliases {
    fun parse(value: JsonElement?): List<AgentPromptAlias>? {
        if (value == null || !value.isJsonArray) return null
        return value.asJsonArray.take(300).mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            fun text(key: String) = element.asJsonObject[key]?.takeIf {
                it.isJsonPrimitive && it.asJsonPrimitive.isString
            }?.asString?.trim().orEmpty()
            val tag = text("tag")
            val zh = text("zh")
            if (tag.isBlank() || zh.isBlank() || tag.length > 4000 || zh.length > 2000) null
            else AgentPromptAlias(tag, zh)
        }.ifEmpty { null }
    }

    private fun key(raw: String): String {
        var text = raw.trim()
        repeat(3) {
            val weighted = Regex("""^\(([^():,]+):\d+(?:\.\d+)?\)$""").matchEntire(text)
            text = weighted?.groupValues?.get(1)?.trim() ?: if (
                text.startsWith('(') && text.endsWith(')') && ',' !in text
            ) text.substring(1, text.lastIndex).trim() else text
        }
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).replace('_', ' ').replace(Regex("""\s+"""), " ").trim()
    }

    /** Match the final prompt, after character completion/removal, not an earlier model draft. */
    fun align(
        prompt: String?,
        aliases: List<AgentPromptAlias>?,
        dictionary: List<LocalTagEntry> = emptyList()
    ): List<AgentPromptAlias>? {
        if (prompt.isNullOrBlank()) return null
        val known = linkedMapOf<String, String>()
        // A compound character entry's name is not a translation for every one of its tags.
        dictionary.filter { it.kind == "tag" && it.translation.isNotBlank() }.forEach {
            if (AgentCharacterPolicy.pieces(it.trigger).size == 1) known[key(it.trigger)] = it.translation.trim()
        }
        aliases.orEmpty().forEach { alias ->
            if (!alias.tag.isNullOrBlank() && !alias.zh.isNullOrBlank()) {
                known.putIfAbsent(key(alias.tag), alias.zh.trim())
            }
        }
        val parts = AgentCharacterPolicy.pieces(prompt)
        val phrases = aliases.orEmpty().filter { !it.tag.isNullOrBlank() && !it.zh.isNullOrBlank() }
            .map { it to AgentCharacterPolicy.pieces(it.tag) }.filter { it.second.size > 1 }
            .sortedByDescending { it.second.size }
        val result = mutableListOf<AgentPromptAlias>()
        var index = 0
        while (index < parts.size) {
            // Anima descriptions may contain commas; translate a complete matching sentence as a unit.
            val phrase = phrases.firstOrNull { (_, words) ->
                index + words.size <= parts.size &&
                    words.indices.all { key(words[it]) == key(parts[index + it]) }
            }
            if (phrase != null) {
                val (alias, words) = phrase
                result += AgentPromptAlias(parts.subList(index, index + words.size).joinToString(", "), alias.zh.trim())
                index += words.size
            } else {
                val tag = parts[index++]
                known[key(tag)]?.let { result += AgentPromptAlias(tag, it) }
            }
        }
        return result.distinctBy { it.tag }.ifEmpty { null }
    }

    fun text(aliases: List<AgentPromptAlias>?): String =
        aliases.orEmpty().joinToString("\n") { "${it.tag} → ${it.zh}" }
}
