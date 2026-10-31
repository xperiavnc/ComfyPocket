package com.comfyui.mobile.agent

import com.comfyui.mobile.modelcenter.LocalModel
import com.comfyui.mobile.modelcenter.ModelProvider
import java.util.Locale

/** What the Agent may know about one local LoRA: the user's own notes first, then provider metadata. */
data class AgentLoraInfo(
    /** Value ComfyUI expects in `lora_name` (path relative to the loras folder). */
    val file: String,
    val name: String,
    val triggerWords: List<String> = emptyList(),
    val baseModel: String = "",
    val tags: List<String> = emptyList(),
    val useCases: List<String> = emptyList(),
    val character: String = "",
    val outfit: String = "",
    val notes: String = "",
    val preferredStrength: Double? = null,
    val description: String = "",
    val favorite: Boolean = false
) {
    internal fun haystack(): String = buildString {
        append(file).append(' ').append(name).append(' ').append(baseModel).append(' ')
        append(character).append(' ').append(outfit).append(' ').append(notes).append(' ')
        (triggerWords + tags + useCases).forEach { append(it).append(' ') }
        append(description)
    }.lowercase(Locale.ROOT)
}

/** One LoRA loader slot in the workflow the Agent may reconfigure. */
data class AgentLoraSlot(
    val nodeId: String,
    val nameKey: String,
    val strengthKeys: List<String>,
    val current: String,
    val options: List<String>
) {
    val id: String get() = "$nodeId:$nameKey"
}

object AgentLoraCatalog {
    const val MAX_DESCRIPTION = 240

    private val htmlTag = Regex("<[^>]+>")
    private val spaces = Regex("\\s+")
    private val stackKey = Regex("(?i)^lora(_name)?_?(\\d+)(_name)?$")

    fun from(models: List<LocalModel>, phoneSources: Map<String, ModelProvider> = emptyMap()): List<AgentLoraInfo> =
        models.asSequence()
            .filter { it.category.equals("LORA", ignoreCase = true) && it.present }
            .map { model ->
                val hash = model.sha256?.lowercase(Locale.ROOT)
                val provider = hash?.let { phoneSources[it] } ?: model.provider
                val annotation = model.annotation
                val words = if ("trainedWords" in annotation.fieldLocks || annotation.trainedWords.isNotEmpty())
                    annotation.trainedWords else provider?.trainedWords.orEmpty()
                AgentLoraInfo(
                    file = model.nodeModelName?.takeIf(String::isNotBlank) ?: model.relativePath.ifBlank { model.name },
                    name = model.displayName,
                    triggerWords = words.map(String::trim).filter(String::isNotEmpty).distinct(),
                    baseModel = annotation.baseModel.ifBlank { provider?.baseModel.orEmpty() },
                    tags = annotation.tags,
                    useCases = annotation.useCases,
                    character = annotation.character,
                    outfit = annotation.outfit,
                    notes = annotation.notes,
                    preferredStrength = annotation.preferredStrength,
                    description = clean(provider?.description?.ifBlank { null } ?: provider?.modelDescription.orEmpty()),
                    favorite = annotation.favorite
                )
            }
            .filter { it.file.isNotBlank() }
            .distinctBy { normalize(it.file) }
            .toList()

    fun clean(raw: String): String {
        val text = raw.replace(htmlTag, " ").replace("&nbsp;", " ").replace("&amp;", "&")
            .replace("&lt;", "<").replace("&gt;", ">").replace(spaces, " ").trim()
        return if (text.length <= MAX_DESCRIPTION) text else text.take(MAX_DESCRIPTION) + "…"
    }

    /** All whitespace-separated terms must match; favourites and annotated entries rank first. */
    fun search(items: List<AgentLoraInfo>, query: String, limit: Int = 20): List<AgentLoraInfo> {
        val terms = query.lowercase(Locale.ROOT).split(spaces).filter(String::isNotBlank)
        return items.filter { info -> terms.isEmpty() || info.haystack().let { hay -> terms.all { it in hay } } }
            .sortedWith(compareByDescending<AgentLoraInfo> { it.favorite }
                .thenByDescending { it.notes.isNotBlank() || it.useCases.isNotEmpty() || it.tags.isNotEmpty() }
                .thenBy { it.name.lowercase(Locale.ROOT) })
            .take(limit)
    }

    /** Normalised file key so `sub\a.safetensors`, `sub/a.safetensors` and `a` can be compared. */
    fun normalize(file: String): String = file.replace('\\', '/').trim().lowercase(Locale.ROOT)

    private fun stem(file: String): String = normalize(file).substringAfterLast('/').substringBeforeLast('.')

    /** Resolve a requested LoRA (file, path or display name) to one of the slot's combo options. */
    fun resolve(requested: String, options: List<String>, catalog: List<AgentLoraInfo>): String? {
        val wanted = requested.trim()
        if (wanted.isEmpty()) return null
        val byName = catalog.firstOrNull { it.name.equals(wanted, ignoreCase = true) }?.file
        for (candidate in listOfNotNull(wanted, byName)) {
            val key = normalize(candidate)
            options.firstOrNull { normalize(it) == key }?.let { return it }
        }
        for (candidate in listOfNotNull(wanted, byName)) {
            val key = stem(candidate)
            options.filter { stem(it) == key }.singleOrNull()?.let { return it }
        }
        return if (options.isEmpty()) byName ?: wanted.takeIf { '.' in it } else null
    }

    /**
     * LoRA loader slots in [workflow]: `lora_name` with `strength_model`/`strength_clip`/`strength`, and
     * stack nodes with numbered `lora_N` / `lora_N_name` plus `strength_N`-style partners.
     */
    fun slots(workflow: Map<String, Any>, options: (nodeId: String, key: String) -> List<String>): List<AgentLoraSlot> {
        val result = mutableListOf<AgentLoraSlot>()
        for ((nodeId, rawNode) in workflow) {
            val node = rawNode as? Map<*, *> ?: continue
            val classType = node["class_type"] as? String ?: continue
            val inputs = node["inputs"] as? Map<*, *> ?: continue
            if (!classType.contains("lora", ignoreCase = true)) continue
            val keys = inputs.keys.filterIsInstance<String>()
            for (key in keys) {
                val value = inputs[key] as? String ?: continue
                val strengthKeys = when {
                    key == "lora_name" -> listOf("strength_model", "strength_clip", "strength").filter { k -> inputs[k] is Number }
                    else -> {
                        val number = stackKey.matchEntire(key)?.groupValues?.get(2) ?: continue
                        keys.filter { k ->
                            inputs[k] is Number && k.contains("strength", ignoreCase = true) &&
                                Regex("(?<!\\d)$number(?!\\d)").containsMatchIn(k)
                        }
                    }
                }
                result += AgentLoraSlot(nodeId, key, strengthKeys, value, options(nodeId, key))
            }
        }
        return result.sortedWith(compareBy({ it.nodeId.toIntOrNull() ?: Int.MAX_VALUE }, { it.nodeId }, { it.nameKey }))
    }
}
