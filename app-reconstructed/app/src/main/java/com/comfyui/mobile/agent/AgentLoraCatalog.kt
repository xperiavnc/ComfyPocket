package com.comfyui.mobile.agent

import com.comfyui.mobile.modelcenter.LocalModel
import com.comfyui.mobile.modelcenter.ModelAnnotation
import com.comfyui.mobile.modelcenter.ModelProvider
import com.comfyui.mobile.modelcenter.modelUseCases
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
    /** The user's own notes, without the Agent's block. */
    val notes: String = "",
    val preferredStrength: Double? = null,
    /** Short provider description for listings. */
    val description: String = "",
    val favorite: Boolean = false,
    /** Model-center location id; blank when only the file name is known. */
    val id: String = "",
    /** Notes the Agent wrote for itself in earlier sessions. */
    val agentNotes: String = "",
    /** Trigger words published by the provider (Civitai), kept apart from the user's confirmed words. */
    val sourceTriggerWords: List<String> = emptyList(),
    /** Model + version description with paragraphs kept, for read_lora. */
    val fullDescription: String = "",
    /** e.g. "Civitai · Kawaii Style · v2". */
    val source: String = ""
) {
    internal fun haystack(): String = buildString {
        append(file).append(' ').append(name).append(' ').append(baseModel).append(' ')
        append(character).append(' ').append(outfit).append(' ').append(notes).append(' ')
        append(agentNotes).append(' ')
        (triggerWords + tags + useCases + useCases.mapNotNull { modelUseCases[it] }).forEach { append(it).append(' ') }
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

/** What the Agent wants to record about one LoRA; blank / empty / null fields are left alone. */
data class AgentLoraNote(
    /** Replaces the Agent's own notes block; "" clears it, null leaves it. The user's text is never touched. */
    val notes: String? = null,
    val tags: List<String> = emptyList(),
    val useCases: List<String> = emptyList(),
    val character: String = "",
    val outfit: String = "",
    val baseModel: String = "",
    val triggerWords: List<String> = emptyList(),
    val preferredStrength: Double? = null
)

data class AgentLoraMerge(val annotation: ModelAnnotation, val applied: List<String>, val skipped: List<String>)

object AgentLoraCatalog {
    const val MAX_DESCRIPTION = 240
    const val MAX_FULL_DESCRIPTION = 2000
    const val MAX_AGENT_NOTES = 800
    const val MAX_TAGS = 24
    /** Heading of the Agent's block at the end of the user's notes; everything after it belongs to the Agent. */
    const val AGENT_MARK = "【Agent 笔记】"

    private val htmlTag = Regex("<[^>]+>")
    private val spaces = Regex("\\s+")
    private val inlineSpaces = Regex("[ \\t\\u00a0]+")
    private val blockBreak = Regex("(?i)<br\\s*/?>|</(p|div|li|h[1-6]|ul|ol|tr)>")
    private val manyBreaks = Regex("\\n{3,}")
    private val stackKey = Regex("(?i)^lora(_name)?_?(\\d+)(_name)?$")

    fun from(models: List<LocalModel>, phoneSources: Map<String, ModelProvider> = emptyMap()): List<AgentLoraInfo> =
        models.asSequence()
            .filter { it.category.equals("LORA", ignoreCase = true) && it.present }
            .map { model -> info(model, model.sha256?.lowercase(Locale.ROOT)?.let { phoneSources[it] } ?: model.provider) }
            .filter { it.file.isNotBlank() }
            .distinctBy { normalize(it.file) }
            .toList()

    /** One model-center record as the Agent sees it; [provider] defaults to the record's own source. */
    fun info(model: LocalModel, provider: ModelProvider? = model.provider): AgentLoraInfo {
        val annotation = model.annotation
        val sourceWords = provider?.trainedWords.orEmpty().map(String::trim).filter(String::isNotEmpty).distinct()
        val words = if ("trainedWords" in annotation.fieldLocks || annotation.trainedWords.isNotEmpty())
            annotation.trainedWords else sourceWords
        val (userNotes, agentNotes) = splitNotes(annotation.notes)
        val descriptions = listOf(provider?.modelDescription.orEmpty(), provider?.description.orEmpty())
            .map { cleanFull(it) }.filter(String::isNotBlank).distinct()
        val full = descriptions.joinToString("\n\n")
        return AgentLoraInfo(
            file = model.nodeModelName?.takeIf(String::isNotBlank) ?: model.relativePath.ifBlank { model.name },
            name = model.displayName,
            triggerWords = words.map(String::trim).filter(String::isNotEmpty).distinct(),
            baseModel = annotation.baseModel.ifBlank { provider?.baseModel.orEmpty() },
            tags = annotation.tags,
            useCases = annotation.useCases,
            character = annotation.character,
            outfit = annotation.outfit,
            notes = userNotes,
            preferredStrength = annotation.preferredStrength,
            description = clean(provider?.description?.ifBlank { null } ?: provider?.modelDescription.orEmpty()),
            favorite = annotation.favorite,
            id = model.id,
            agentNotes = agentNotes,
            sourceTriggerWords = sourceWords,
            fullDescription = if (full.length <= MAX_FULL_DESCRIPTION) full else full.take(MAX_FULL_DESCRIPTION) + "…",
            source = provider?.let { listOf(it.provider, it.name, it.version).filter(String::isNotBlank).joinToString(" · ") }.orEmpty()
        )
    }

    /** Splits stored notes into the user's text and the Agent's block. */
    fun splitNotes(notes: String): Pair<String, String> {
        val index = notes.indexOf(AGENT_MARK)
        if (index < 0) return notes.trim() to ""
        return notes.substring(0, index).trim() to notes.substring(index + AGENT_MARK.length).trim()
    }

    fun joinNotes(user: String, agent: String): String = when {
        agent.isBlank() -> user.trim()
        user.isBlank() -> "$AGENT_MARK\n${agent.trim()}"
        else -> "${user.trim()}\n\n$AGENT_MARK\n${agent.trim()}"
    }

    /** Model-center use-case key for an English key or a Chinese label such as 画风; null when unknown. */
    fun useCaseKey(value: String): String? {
        val wanted = value.trim().lowercase(Locale.ROOT)
        if (wanted.isEmpty()) return null
        return modelUseCases.entries.firstOrNull { (key, label) ->
            key == wanted || label == wanted || label.split('/').any { it.trim() == wanted }
        }?.key
    }

    /**
     * Folds the Agent's [note] into [current] without overwriting what the user wrote: the Agent only
     * replaces its own notes block, adds tags, and fills other fields that are empty and not locked.
     */
    fun merge(
        current: ModelAnnotation, note: AgentLoraNote,
        sourceTriggerWords: List<String> = emptyList(), sourceBaseModel: String = ""
    ): AgentLoraMerge {
        val applied = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val locks = current.fieldLocks.toSet()
        var next = current
        val agentText = note.notes
        if (agentText != null) {
            val (user, previous) = splitNotes(current.notes)
            val agent = agentText.trim()
            if (agent != previous) {
                next = next.copy(notes = joinNotes(user, agent))
                applied += if (agent.isEmpty()) "清除了 Agent 笔记" else "Agent 笔记"
            }
        }
        val known = current.tags.map { it.lowercase(Locale.ROOT) }.toMutableSet()
        val newTags = note.tags.map(String::trim).filter { it.isNotEmpty() && known.add(it.lowercase(Locale.ROOT)) }
        if (newTags.isNotEmpty()) {
            val room = (MAX_TAGS - current.tags.size).coerceAtLeast(0)
            if (room == 0) skipped += "标签已满 $MAX_TAGS 个"
            else {
                next = next.copy(tags = current.tags + newTags.take(room))
                applied += "标签 " + newTags.take(room).joinToString("、")
            }
        }
        if (note.useCases.isNotEmpty()) {
            val keys = note.useCases.mapNotNull(::useCaseKey).distinct()
            when {
                keys.isEmpty() -> skipped += "用途只能是 " + modelUseCases.keys.joinToString("/")
                "useCases" in locks -> skipped += "用途（用户已锁定）"
                current.useCases.isNotEmpty() -> skipped += "用途（已有内容，未覆盖）"
                else -> {
                    next = next.copy(useCases = keys)
                    applied += "用途 " + keys.joinToString("、") { modelUseCases[it] ?: it }
                }
            }
        }
        fun text(label: String, field: String, value: String, existing: String, update: (ModelAnnotation, String) -> ModelAnnotation) {
            val wanted = value.trim()
            if (wanted.isEmpty() || wanted == existing.trim()) return
            when {
                field in locks -> skipped += "$label（用户已锁定）"
                existing.isNotBlank() -> skipped += "$label（已有内容，未覆盖）"
                else -> {
                    next = update(next, wanted)
                    applied += "$label $wanted"
                }
            }
        }
        text("角色", "character", note.character, current.character) { a, v -> a.copy(character = v) }
        text("服装", "outfit", note.outfit, current.outfit) { a, v -> a.copy(outfit = v) }
        text("底模", "baseModel", note.baseModel, current.baseModel.ifBlank { sourceBaseModel }) { a, v -> a.copy(baseModel = v) }
        val words = note.triggerWords.map(String::trim).filter(String::isNotEmpty).distinct()
        if (words.isNotEmpty()) {
            val existing = current.trainedWords.ifEmpty { sourceTriggerWords }
            when {
                existing.map { it.lowercase(Locale.ROOT) }.toSet() == words.map { it.lowercase(Locale.ROOT) }.toSet() -> Unit
                "trainedWords" in locks -> skipped += "触发词（用户已锁定）"
                existing.isNotEmpty() -> skipped += "触发词（已有触发词，未覆盖）"
                else -> {
                    next = next.copy(trainedWords = words)
                    applied += "触发词 " + words.joinToString(", ")
                }
            }
        }
        val strength = note.preferredStrength
        val existingStrength = current.preferredStrength
        if (strength != null && strength != existingStrength) {
            if (existingStrength == null) {
                next = next.copy(preferredStrength = strength)
                applied += "推荐强度 $strength"
            } else {
                skipped += "推荐强度（已有 $existingStrength，未覆盖）"
            }
        }
        return AgentLoraMerge(next, applied, skipped)
    }

    private fun entities(raw: String): String = raw.replace("&nbsp;", " ").replace("&amp;", "&")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")

    fun clean(raw: String): String {
        val text = entities(raw.replace(htmlTag, " ")).replace(spaces, " ").trim()
        return if (text.length <= MAX_DESCRIPTION) text else text.take(MAX_DESCRIPTION) + "…"
    }

    /** Like [clean] but keeps paragraph breaks; the caller decides how much to keep. */
    fun cleanFull(raw: String): String {
        val text = entities(raw.replace(blockBreak, "\n").replace(htmlTag, " "))
        return text.lines().joinToString("\n") { it.replace(inlineSpaces, " ").trim() }
            .replace(manyBreaks, "\n\n").trim()
    }

    /** All whitespace-separated terms must match; favourites and annotated entries rank first. */
    fun search(items: List<AgentLoraInfo>, query: String, limit: Int = 20): List<AgentLoraInfo> {
        val terms = query.lowercase(Locale.ROOT).split(spaces).filter(String::isNotBlank)
        return items.filter { info -> terms.isEmpty() || info.haystack().let { hay -> terms.all { it in hay } } }
            .sortedWith(compareByDescending<AgentLoraInfo> { it.favorite }
                .thenByDescending { it.notes.isNotBlank() || it.agentNotes.isNotBlank() || it.useCases.isNotEmpty() || it.tags.isNotEmpty() }
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
