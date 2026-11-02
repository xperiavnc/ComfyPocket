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
    val source: String = "",
    /** The provider's model name, e.g. "Kawaii Style"; often says more than the file name. */
    val title: String = ""
) {
    internal fun haystack(): String = buildString {
        append(file).append(' ').append(name).append(' ').append(title).append(' ').append(baseModel).append(' ')
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
    val options: List<String>,
    /** Switch that must be on for the slot to apply: the node's `toggle` / `enabled`, or a stack's `switch_N`. */
    val switchKey: String? = null,
    /** Value of [switchKey] that turns the slot on (`true` or `"On"`). */
    val switchOn: Any? = null,
    /** False while the switch is off in the workflow. */
    val switchedOn: Boolean = true
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
    /** Longest notes the Bridge accepts (validate_annotation in ComfyUI-Mobile-Bridge/model_index.py). */
    const val MAX_NOTES = 8000
    /** Heading of the Agent's block at the end of the user's notes; everything after it belongs to the Agent. */
    const val AGENT_MARK = "【Agent 笔记】"

    private val htmlTag = Regex("<[^>]+>")
    private val spaces = Regex("\\s+")
    private val inlineSpaces = Regex("[ \\t\\u00a0]+")
    private val blockBreak = Regex("(?i)<br\\s*/?>|</(p|div|li|h[1-6]|ul|ol|tr)>")
    private val manyBreaks = Regex("\\n{3,}")
    private val stackKey = Regex("(?i)^lora(_name)?_?(\\d+)(_name)?$")
    private val termBreak = Regex("[\\s_\\-,，、/]+")
    /** Weight inputs of stack slots: `lora_1_strength`, `strength_01`, `model_weight_1`, `lora_wt_1`, `clip_str_1`. */
    private val stackStrength = Regex("(?i)strength|weight|(^|_)(wt|str)(_|$)")
    /** Stacks apply only their first N slots. */
    private val stackCountKeys = listOf("num_loras", "lora_count")
    private val nodeSwitchKeys = listOf("toggle", "enabled", "enable")

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
            source = provider?.let { listOf(it.provider, it.name, it.version).filter(String::isNotBlank).joinToString(" · ") }.orEmpty(),
            title = provider?.name.orEmpty().trim()
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
            val joined = joinNotes(user, agent)
            if (agent != previous && joined.length > MAX_NOTES) {
                skipped += "Agent 笔记（和用户备注合计会超过 $MAX_NOTES 字，请写短一些）"
            } else if (agent != previous) {
                next = next.copy(notes = joined)
                applied += if (agent.isEmpty()) "清除了 Agent 笔记" else "Agent 笔记"
            }
        }
        // A classification the user confirmed in the Model Center stays as it is; only the Agent's notes change.
        if (current.classificationStatus == "confirmed") {
            if (note.copy(notes = null) != AgentLoraNote()) skipped += "其他标注（用户已确认分类，未改动）"
            return AgentLoraMerge(next, applied, skipped)
        }
        val notesOnly = next
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
        // Fields beyond the notes stay a suggestion ("AI 建议待确认") until the user saves them in the Model Center.
        if (next != notesOnly) next = next.copy(classificationSource = "ai-suggestion", classificationStatus = "suggested")
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

    /** Lower-case search terms; `_`, `-`, `/` and list separators split words like spaces do. */
    fun terms(query: String): List<String> = query.lowercase(Locale.ROOT).split(termBreak).filter(String::isNotBlank)

    private val ranking: Comparator<AgentLoraInfo> = compareByDescending<AgentLoraInfo> { it.favorite }
        .thenByDescending { it.notes.isNotBlank() || it.agentNotes.isNotBlank() || it.useCases.isNotEmpty() || it.tags.isNotEmpty() }
        .thenBy { it.name.lowercase(Locale.ROOT) }

    /** All terms must match; favourites and annotated entries rank first. */
    fun search(items: List<AgentLoraInfo>, query: String, limit: Int = 20): List<AgentLoraInfo> {
        val terms = terms(query)
        return items.filter { info -> terms.isEmpty() || info.haystack().let { hay -> terms.all { it in hay } } }
            .sortedWith(ranking)
            .take(limit)
    }

    /** Entries matching at least one term, most matched terms first: the fallback when no entry matches them all. */
    fun searchAny(items: List<AgentLoraInfo>, query: String, limit: Int = 20): List<AgentLoraInfo> {
        val terms = terms(query)
        if (terms.isEmpty()) return emptyList()
        return items.map { info -> info to info.haystack().let { hay -> terms.count { it in hay } } }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<AgentLoraInfo, Int>> { it.second }.thenComparator { a, b -> ranking.compare(a.first, b.first) })
            .take(limit)
            .map { it.first }
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
     * stack nodes with numbered `lora_N` / `lora_N_name` plus their own weight inputs. Stack slots beyond
     * `num_loras` / `lora_count` are left out because the node ignores them. A node `toggle`/`enabled` or a
     * stack's `switch_N` is recorded so loading a LoRA can turn it on.
     */
    fun slots(workflow: Map<String, Any>, options: (nodeId: String, key: String) -> List<String>): List<AgentLoraSlot> {
        val result = mutableListOf<AgentLoraSlot>()
        for ((nodeId, rawNode) in workflow) {
            val node = rawNode as? Map<*, *> ?: continue
            val classType = node["class_type"] as? String ?: continue
            val inputs = node["inputs"] as? Map<*, *> ?: continue
            if (!classType.contains("lora", ignoreCase = true)) continue
            val keys = inputs.keys.filterIsInstance<String>()
            val count = stackCountKeys.firstNotNullOfOrNull { (inputs[it] as? Number)?.toInt() }
            val nodeSwitch = nodeSwitchKeys.firstOrNull { inputs[it] is Boolean }
            for (key in keys) {
                val value = inputs[key] as? String ?: continue
                val number = if (key == "lora_name") null else stackKey.matchEntire(key)?.groupValues?.get(2) ?: continue
                if (number != null && count != null && number.toInt() > count) continue
                val strengthKeys = if (number == null) {
                    listOf("strength_model", "strength_clip", "strength").filter { k -> inputs[k] is Number }
                } else {
                    val own = Regex("(?<!\\d)$number(?!\\d)")
                    keys.filter { k -> inputs[k] is Number && own.containsMatchIn(k) && stackStrength.containsMatchIn(k) }
                }
                val slotSwitch = number?.let { n -> keys.firstOrNull { k -> k.equals("switch_$n", ignoreCase = true) && inputs[k] is String } }
                result += when {
                    slotSwitch != null -> AgentLoraSlot(nodeId, key, strengthKeys, value, options(nodeId, key),
                        slotSwitch, "On", (inputs[slotSwitch] as String).equals("On", ignoreCase = true))
                    nodeSwitch != null -> AgentLoraSlot(nodeId, key, strengthKeys, value, options(nodeId, key),
                        nodeSwitch, true, inputs[nodeSwitch] == true)
                    else -> AgentLoraSlot(nodeId, key, strengthKeys, value, options(nodeId, key))
                }
            }
        }
        return result.sortedWith(compareBy({ it.nodeId.toIntOrNull() ?: Int.MAX_VALUE }, { it.nodeId }, { it.nameKey }))
    }
}
