package com.comfyui.mobile.agent

import com.comfyui.mobile.model.CharacterLore
import com.comfyui.mobile.model.CharacterOutfit
import com.comfyui.mobile.network.ChatFunctionDefinition
import com.comfyui.mobile.network.ChatToolDefinition
import com.comfyui.mobile.taglib.LocalTagEntry
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.util.Locale

/**
 * Drawing tools for [AgentToolLoop]. Workflow edits are validated against [snapshot] (only the auxiliary
 * fields the user exposed to the Agent) and staged; nothing touches the real workflow until the turn is
 * submitted, so a cancelled or failed turn leaves the workflow as it was.
 */
class AgentDrawToolbox(
    private val snapshot: AgentWorkflowSnapshot,
    private val titles: Map<String, String> = emptyMap(),
    characters: List<CharacterLore> = emptyList(),
    private val searchTags: (suspend (query: String, kind: String) -> List<LocalTagEntry>)? = null,
    /** Local LoRA metadata (user annotations + provider info); empty when the model index is unavailable. */
    private val loras: List<AgentLoraInfo> = emptyList(),
    /** Address of the 画风 / 画师 text field shown in the style settings, when bound. */
    private val styleField: String? = null,
    private val favoriteArtists: List<String> = emptyList(),
    /** Persists a character profile into the library; returns an error message or null. */
    private val saveCharacter: (suspend (CharacterLore) -> String?)? = null
) : AgentToolbox {
    private var characters: List<CharacterLore> = characters
    /** Set by submit(save_defaults=true): keep this turn's parameters as the workflow defaults. */
    var saveDefaults: Boolean = false
        private set
    private val patchTool = PatchWorkflowTool()
    private val inspectTool = InspectWorkflowTool()
    private val staged = linkedMapOf<String, AgentFieldChange>()
    var prompt: String? = null
        private set
    var negativePrompt: String? = null
        private set

    /** Validated edits to apply when the turn is submitted. */
    val changes: List<AgentFieldChange> get() = staged.values.toList()

    private val allowedFields = snapshot.fields.associateBy { it.address }
    /** LoRA loader slots whose file field is part of the snapshot; strength partners only when also present. */
    private val loraSlots: List<AgentLoraSlot> = AgentLoraCatalog.slots(snapshot.workflow) { node, key ->
        allowedFields["$node:$key"]?.options.orEmpty()
    }.filter { it.id in allowedFields }.map { slot -> slot.copy(strengthKeys = slot.strengthKeys.filter { "${slot.nodeId}:$it" in allowedFields }) }

    override val definitions: List<ChatToolDefinition> = listOf(
        tool(INSPECT, "查看本轮开放给你的工作流参数（节点、字段、当前值、可选项和范围）。修改参数前先调用。", obj(emptyMap())),
        tool(PATCH, "修改一个或多个已开放的工作流参数。整批校验，任一字段不合法则整批不生效，错误会告诉你原因。",
            obj(mapOf("changes" to array(obj(mapOf(
                "field" to text("inspect_workflow 返回的字段地址，形如 \"12:steps\""),
                "value" to mapOf("description" to "新值；类型须与字段一致（文本/整数/小数/布尔），有可选项时只能取其中之一")
            ), "field", "value"), 20)), "changes")),
        tool(SET_PROMPT, "写入本轮正向提示词和可选的反向提示词。可以多次调用，以最后一次为准。",
            obj(mapOf(
                "prompt" to text("完整正向提示词，按系统中的写法规则"),
                "negative_prompt" to text("完整反向提示词；不传则保留工作流原值，传空字符串表示清空。要去掉的标签只从 prompt 删除，不要放进反向，除非用户明确要避免")
            ), "prompt")),
        tool(LOOKUP_TAGS, "在本地词库中查绘画标签或角色名的标准写法与中文释义。拿不准标签拼写时使用。",
            obj(mapOf(
                "query" to text("要查的词，中英文均可，一次一个"),
                "kind" to mapOf("type" to "string", "enum" to listOf("tag", "character"), "description" to "tag 为普通标签，character 为角色名")
            ), "query")),
        tool(PROFILE, "读取一个已登记角色的档案：外貌绘画词、必须保留的固定词、服装列表及其绘画词。",
            obj(mapOf("name" to text("角色名称、别名或 character_id")), "name")),
        tool(SUBMIT, "提交本轮结果并结束。需要先调用 set_prompt。draw=true 立即出图，false 只写入提示词不出图。",
            obj(mapOf(
                "reply" to text("给用户的简短回复，说明这次画了什么或改了什么"),
                "draw" to mapOf("type" to "boolean", "description" to "是否立即出图，默认 true"),
                "save_defaults" to mapOf("type" to "boolean", "description" to
                    "把本轮画风与辅助参数（含 patch_workflow / set_lora 的修改）保存为这个工作流的默认值，新会话也沿用；用户说保存、记住、以后都这样、设为默认时传 true"),
                "outfit_selections" to array(obj(mapOf(
                    "character_id" to text("已登记角色 ID"),
                    "outfit_id" to text("采用的服装 ID"),
                    "custom_outfit" to mapOf("type" to "boolean")
                ), "character_id"), 20)
            ), "reply"))
    ) + (if (saveCharacter == null) emptyList() else listOf(
        tool(SAVE_CHARACTER, "把角色写入或更新到用户的角色触发词库（设置页可见）。同名或别名已存在时合并：外貌词和固定词按传入值替换，别名与服装按名称合并。",
            obj(mapOf(
                "name" to text("角色名称，对话中用来识别的名字"),
                "appearance_tags" to text("外貌绘画标签，英文逗号分隔，如 \"1girl, fox ears, long hair, green eyes\""),
                "must_keep_tags" to text("可选：每次都必须出现的固定词（通常是角色名标签），不传则沿用原值"),
                "aliases" to array(text("别名"), 10),
                "outfits" to array(obj(mapOf(
                    "name" to text("服装名称"),
                    "tags" to text("服装绘画标签"),
                    "dialogue_triggers" to array(text("对话中会提到的服装说法"), 8)
                ), "name", "tags"), 12),
                "default_outfit" to text("可选：默认服装名称，须是 outfits 或已有服装之一")
            ), "name", "appearance_tags"))
    )) + (if (loraSlots.isEmpty()) emptyList() else listOf(
        tool(LIST_LORAS, "列出工作流里的 LoRA 槽位，并检索本地 LoRA：文件名、触发词、底模、用户标注（标签/用途/角色/服装/备注/推荐强度）和简介。选 LoRA 前先调用。",
            obj(mapOf("query" to text("检索词，可填角色名、画风、用途等，空格分隔多个词须同时命中；留空列出全部")))),
        tool(SET_LORA, "把某个 LoRA 槽位换成指定 LoRA 并设置强度，或用 lora 为空字符串/strength 为 0 停用该槽位。修改会暂存，提交时生效。",
            obj(mapOf(
                "slot" to text("list_loras 返回的槽位地址，形如 \"10:lora_name\""),
                "lora" to text("list_loras 返回的 file；空字符串表示停用"),
                "strength" to mapOf("type" to "number", "description" to "强度，通常 0.4–1.2；不传则用推荐强度或 1.0")
            ), "slot", "lora"))
    ))

    override fun label(name: String): String = when (name) {
        INSPECT -> "查看工作流参数"
        PATCH -> "修改工作流参数"
        SET_PROMPT -> "写入提示词"
        LOOKUP_TAGS -> "查询本地词库"
        PROFILE -> "读取角色档案"
        SUBMIT -> "提交结果"
        LIST_LORAS -> "检索本地 LoRA"
        SET_LORA -> "配置 LoRA"
        SAVE_CHARACTER -> "保存角色到词库"
        else -> name
    }

    override suspend fun call(name: String, arguments: JsonObject): AgentToolResult = when (name) {
        INSPECT -> inspect()
        PATCH -> patch(arguments)
        SET_PROMPT -> setPrompt(arguments)
        LOOKUP_TAGS -> lookup(arguments)
        PROFILE -> profile(arguments)
        SUBMIT -> submit(arguments)
        LIST_LORAS -> if (loraSlots.isEmpty()) unknown(name) else listLoras(arguments)
        SET_LORA -> if (loraSlots.isEmpty()) unknown(name) else setLora(arguments)
        SAVE_CHARACTER -> if (saveCharacter == null) unknown(name) else saveCharacterProfile(arguments)
        else -> unknown(name)
    }

    private fun unknown(name: String) = AgentToolResult(false, error = "未知工具：$name")

    private fun current(): AgentWorkflowSnapshot {
        if (staged.isEmpty()) return snapshot
        val patched = patchTool.execute(AgentPatchRequest(snapshot, staged.values.toList())).getOrNull() ?: return snapshot
        return snapshot.copy(workflow = patched.workflow)
    }

    private fun inspect(): AgentToolResult {
        val fields = inspectTool.execute(current()).getOrElse { return AgentToolResult(false, error = it.message) }
        return AgentToolResult(true, mapOf(
            "summary" to "${fields.size} 个可修改参数",
            "fields" to fields.map { field ->
                linkedMapOf<String, Any?>(
                    "field" to field.spec.address,
                    "node" to (titles[field.spec.nodeId] ?: field.spec.classType),
                    "type" to field.spec.kind.name.lowercase(Locale.ROOT),
                    "value" to field.value
                ).apply {
                    field.spec.options?.takeIf { it.isNotEmpty() }?.let { put("options", it.take(MAX_OPTIONS)); if (it.size > MAX_OPTIONS) put("more_options", it.size - MAX_OPTIONS) }
                    field.spec.min?.let { put("min", it) }
                    field.spec.max?.let { put("max", it) }
                    if (field.spec.address in staged) put("staged", true)
                    if (field.spec.address == styleField) {
                        put("role", "画风/画师")
                        favoriteArtists.takeIf { it.isNotEmpty() }?.let { put("favorite_artists", it.take(MAX_OPTIONS)) }
                    }
                }
            },
            "note" to if (fields.isEmpty()) "用户没有开放可修改的辅助参数；提示词用 set_prompt 写入" else
                "提示词字段不在此列，用 set_prompt 写入。用户要求保存或以后沿用时，submit_workflow 传 save_defaults=true"
        ))
    }

    private fun patch(arguments: JsonObject): AgentToolResult {
        val items = arguments["changes"]?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return AgentToolResult(false, error = "changes 必须为数组")
        if (items.size() == 0) return AgentToolResult(false, error = "changes 为空")
        val requested = items.map { item ->
            if (!item.isJsonObject) return AgentToolResult(false, error = "每项修改必须为对象")
            val field = AgentInteractionProtocol.string(item.asJsonObject, "field")
            val parts = field.split(':', limit = 2)
            if (parts.size != 2 || parts.any(String::isBlank)) return AgentToolResult(false, error = "字段地址格式应为 节点ID:字段名，收到「$field」")
            val value = scalar(item.asJsonObject["value"]) ?: return AgentToolResult(false, error = "$field 的值必须是文本、数字或布尔")
            AgentFieldChange(parts[0], parts[1], value)
        }
        return stage(requested)
    }

    private fun stage(requested: List<AgentFieldChange>, summary: String? = null): AgentToolResult {
        val merged = LinkedHashMap(staged).apply { requested.forEach { put("${it.nodeId}:${it.key}", it) } }
        val result = patchTool.execute(AgentPatchRequest(snapshot, merged.values.toList()))
            .getOrElse { return AgentToolResult(false, error = it.message) }
        staged.clear()
        staged.putAll(merged)
        val changed = requested.map { "${it.nodeId}:${it.key}" }.toSet()
        return AgentToolResult(true, mapOf(
            "summary" to (summary ?: "已暂存 ${changed.size} 项修改，提交时生效"),
            "changes" to result.diff.filter { "${it.nodeId}:${it.key}" in changed }
                .map { mapOf("field" to "${it.nodeId}:${it.key}", "before" to it.before, "after" to it.after) }
        ))
    }

    private fun stagedValue(nodeId: String, key: String): Any? =
        staged["$nodeId:$key"]?.value ?: ((snapshot.workflow[nodeId] as? Map<*, *>)?.get("inputs") as? Map<*, *>)?.get(key)

    private fun listLoras(arguments: JsonObject): AgentToolResult {
        val query = AgentInteractionProtocol.string(arguments, "query")
        val offered = loraSlots.flatMap { it.options }.map(AgentLoraCatalog::normalize).toSet()
        // Only LoRAs the server can actually load; without combo options trust the index.
        val usable = if (offered.isEmpty()) loras else loras.filter { AgentLoraCatalog.normalize(it.file) in offered }
        val hits = AgentLoraCatalog.search(usable, query, MAX_LORAS)
        val known = usable.map { AgentLoraCatalog.normalize(it.file) }.toSet()
        val bare = if (hits.size >= MAX_LORAS) emptyList() else loraSlots.flatMap { it.options }.distinct()
            .filter { AgentLoraCatalog.normalize(it) !in known && (query.isBlank() || query.lowercase(Locale.ROOT).split(' ').filter(String::isNotBlank).all { term -> it.lowercase(Locale.ROOT).contains(term) }) }
            .take(MAX_LORAS - hits.size)
        return AgentToolResult(true, linkedMapOf(
            "summary" to "${loraSlots.size} 个 LoRA 槽位，命中 ${hits.size + bare.size} 个 LoRA" + if (loras.isEmpty()) "（模型中心还没有本地 LoRA 资料，只有文件名）" else "",
            "slots" to loraSlots.map { slot ->
                linkedMapOf<String, Any?>(
                    "slot" to slot.id,
                    "node" to (titles[slot.nodeId] ?: (snapshot.workflow[slot.nodeId] as? Map<*, *>)?.get("class_type")),
                    "current" to stagedValue(slot.nodeId, slot.nameKey),
                    "strength" to slot.strengthKeys.associateWith { stagedValue(slot.nodeId, it) }
                )
            },
            "loras" to hits.map { info ->
                linkedMapOf<String, Any?>(
                    "file" to info.file, "name" to info.name,
                    "trigger_words" to info.triggerWords.takeIf { it.isNotEmpty() },
                    "base_model" to info.baseModel.ifBlank { null },
                    "tags" to info.tags.takeIf { it.isNotEmpty() },
                    "use_cases" to info.useCases.takeIf { it.isNotEmpty() },
                    "character" to info.character.ifBlank { null },
                    "outfit" to info.outfit.ifBlank { null },
                    "notes" to info.notes.ifBlank { null },
                    "preferred_strength" to info.preferredStrength,
                    "description" to info.description.ifBlank { null },
                    "favorite" to info.favorite.takeIf { it }
                ).filterValues { it != null }
            } + bare.map { mapOf("file" to it, "note" to "没有本地资料") }
        ))
    }

    private fun setLora(arguments: JsonObject): AgentToolResult {
        val slotId = AgentInteractionProtocol.string(arguments, "slot")
        val slot = loraSlots.firstOrNull { it.id == slotId || it.nodeId == slotId }
            ?: return AgentToolResult(false, error = "没有槽位「$slotId」。可用：" + loraSlots.joinToString("、") { it.id })
        val requested = AgentInteractionProtocol.string(arguments, "lora")
        val strengthElement = arguments["strength"]
        val strength = strengthElement?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
        if (strengthElement != null && !strengthElement.isJsonNull && strength == null) return AgentToolResult(false, error = "strength 必须是数字")
        val disable = requested.isBlank() || requested.equals("none", ignoreCase = true) || strength == 0.0
        if (disable) {
            if (slot.strengthKeys.isEmpty()) return AgentToolResult(false, error = "这个槽位没有可修改的强度字段，无法停用；可以换成别的 LoRA")
            // Strength 0 alone still leaves the file name, which ComfyUI validates against its list.
            val none = slot.options.firstOrNull { it.equals("None", ignoreCase = true) }
            return stage(listOfNotNull(none?.let { AgentFieldChange(slot.nodeId, slot.nameKey, it) }) +
                slot.strengthKeys.map { AgentFieldChange(slot.nodeId, it, 0.0) }, "已暂存：停用 ${slot.id}")
        }
        val file = AgentLoraCatalog.resolve(requested, slot.options, loras)
            ?: return AgentToolResult(false, error = "服务器上没有 LoRA「$requested」。先用 list_loras 检索，再填返回的 file")
        val info = loras.firstOrNull { AgentLoraCatalog.normalize(it.file) == AgentLoraCatalog.normalize(file) }
        val value = strength ?: info?.preferredStrength ?: 1.0
        if (value < -4.0 || value > 4.0) return AgentToolResult(false, error = "strength 超出范围（-4 到 4）")
        val changes = listOf(AgentFieldChange(slot.nodeId, slot.nameKey, file)) +
            slot.strengthKeys.map { AgentFieldChange(slot.nodeId, it, value) }
        val result = stage(changes, "已暂存：${slot.id} → ${info?.name ?: file}，强度 $value")
        if (!result.ok) return result
        val words = info?.triggerWords.orEmpty()
        return result.copy(data = (result.data as Map<*, *>) + mapOf(
            "trigger_words" to words.takeIf { it.isNotEmpty() },
            "hint" to if (words.isNotEmpty()) "把需要的触发词写进 set_prompt 的正向提示词" else "这个 LoRA 没有登记触发词"
        ).filterValues { it != null })
    }

    private fun setPrompt(arguments: JsonObject): AgentToolResult {
        val positive = AgentInteractionProtocol.string(arguments, "prompt")
        if (positive.isBlank()) return AgentToolResult(false, error = "prompt 不能为空")
        if (positive.length > MAX_PROMPT) return AgentToolResult(false, error = "prompt 过长（${positive.length} 字符，上限 $MAX_PROMPT）")
        val negativeElement = arguments["negative_prompt"]
        val negative = when {
            negativeElement == null || negativeElement.isJsonNull -> null
            negativeElement.isJsonPrimitive && negativeElement.asJsonPrimitive.isString -> negativeElement.asString.trim()
            else -> return AgentToolResult(false, error = "negative_prompt 必须是字符串")
        }
        if (negative != null && negative.length > MAX_PROMPT) return AgentToolResult(false, error = "negative_prompt 过长")
        if (negative != null) {
            val conflicts = AgentNegativePromptPolicy.conflicts(positive, negative)
            if (conflicts.isNotEmpty()) return AgentToolResult(false,
                error = "正反向包含相同标签：${conflicts.take(8).joinToString("、")}。要去掉的标签只从正向删除，除非用户明确要避免，否则不要放进反向，然后重新调用")
        }
        prompt = positive
        negativePrompt = negative
        return AgentToolResult(true, mapOf("summary" to "已写入提示词（${positive.length} 字符）",
            "negative" to when (negative) { null -> "保留工作流原值"; "" -> "清空"; else -> "${negative.length} 字符" }))
    }

    private suspend fun lookup(arguments: JsonObject): AgentToolResult {
        val search = searchTags ?: return AgentToolResult(false, error = "本地词库未启用，按自己的知识书写标签即可")
        val query = AgentInteractionProtocol.string(arguments, "query")
        if (query.isBlank()) return AgentToolResult(false, error = "query 不能为空")
        val kind = AgentInteractionProtocol.string(arguments, "kind").ifBlank { "tag" }
        if (kind !in setOf("tag", "character")) return AgentToolResult(false, error = "kind 只能是 tag 或 character")
        val hits = search(query, kind).take(MAX_TAGS)
        return AgentToolResult(true, mapOf(
            "summary" to if (hits.isEmpty()) "「$query」未命中" else "「$query」命中 ${hits.size} 项",
            "entries" to hits.map { entry ->
                linkedMapOf<String, Any?>("tag" to entry.name, "translation" to entry.translation.ifBlank { null },
                    "category" to entry.category.ifBlank { null }, "exact" to entry.exact,
                    "trigger" to entry.trigger.ifBlank { null }).filterValues { it != null }
            }
        ))
    }

    private fun profile(arguments: JsonObject): AgentToolResult {
        val key = AgentInteractionProtocol.string(arguments, "name")
        if (key.isBlank()) return AgentToolResult(false, error = "name 不能为空")
        val normalized = key.lowercase(Locale.ROOT)
        val lore = characters.firstOrNull { it.id == key }
            ?: characters.firstOrNull { it.name.lowercase(Locale.ROOT) == normalized ||
                it.aliases.orEmpty().any { alias -> alias.lowercase(Locale.ROOT) == normalized } }
            ?: return AgentToolResult(false, error = "没有名为「$key」的角色档案。已登记：" +
                characters.take(30).joinToString("、") { it.name }.ifBlank { "无" })
        return AgentToolResult(true, linkedMapOf(
            "summary" to "角色「${lore.name}」",
            "character_id" to lore.id,
            "name" to lore.name,
            "aliases" to lore.aliases.orEmpty(),
            "appearance_tags" to lore.triggerWords,
            "must_keep_tags" to lore.fixedTriggerWords?.takeIf(String::isNotBlank).orEmpty().ifBlank { lore.triggerWords },
            "default_outfit_id" to lore.defaultOutfitId,
            "outfits" to lore.outfits.orEmpty().map { mapOf("outfit_id" to it.id, "name" to it.name, "tags" to it.triggerWords) }
        ))
    }

    private fun submit(arguments: JsonObject): AgentToolResult {
        val positive = prompt ?: return AgentToolResult(false, error = "还没有提示词，先调用 set_prompt")
        val reply = AgentInteractionProtocol.string(arguments, "reply")
        val draw = arguments["draw"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: true
        saveDefaults = arguments["save_defaults"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true
        val final = JsonObject().apply {
            addProperty("reply", reply)
            addProperty("prompt", positive)
            negativePrompt?.let { addProperty("negative_prompt", it) }
            addProperty("should_draw", draw)
            addProperty("action", if (draw) "draw" else "apply_prompt")
            arguments["outfit_selections"]?.takeIf { it.isJsonArray }?.let { add("outfit_selections", it) }
        }
        return AgentToolResult(true, mapOf("summary" to if (draw) "已提交出图" else "已写入提示词，未出图",
            "staged_changes" to staged.size, "save_defaults" to saveDefaults), finalContent = final.toString())
    }

    private fun strings(element: JsonElement?): List<String> =
        element?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { item ->
            item.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim()?.takeIf(String::isNotBlank)
        }.orEmpty()

    private suspend fun saveCharacterProfile(arguments: JsonObject): AgentToolResult {
        val save = saveCharacter ?: return unknown(SAVE_CHARACTER)
        val merged = mergeCharacter(characters, arguments).getOrElse {
            return AgentToolResult(false, error = it.message)
        }
        save(merged)?.let { return AgentToolResult(false, error = it) }
        characters = characters.filterNot { it.id == merged.id } + merged
        return AgentToolResult(true, mapOf(
            "summary" to "已保存角色「${merged.name}」到词库",
            "character_id" to merged.id,
            "outfits" to merged.outfits.orEmpty().map { it.name },
            "default_outfit" to merged.outfits.orEmpty().firstOrNull { it.id == merged.defaultOutfitId }?.name
        ))
    }

    /** Merge model-supplied profile data into an existing record (matched by name or alias) or create one. */
    internal fun mergeCharacter(existing: List<CharacterLore>, arguments: JsonObject): Result<CharacterLore> {
        val name = AgentInteractionProtocol.string(arguments, "name").trim()
        val appearance = AgentInteractionProtocol.string(arguments, "appearance_tags").trim()
        if (name.isBlank()) return Result.failure(IllegalArgumentException("name 不能为空"))
        if (appearance.isBlank()) return Result.failure(IllegalArgumentException("appearance_tags 不能为空"))
        if (name.length > 40 || appearance.length > MAX_PROMPT)
            return Result.failure(IllegalArgumentException("name 或 appearance_tags 过长"))
        val key = name.lowercase(Locale.ROOT)
        val old = existing.firstOrNull { it.name.lowercase(Locale.ROOT) == key }
            ?: existing.firstOrNull { lore -> lore.aliases.orEmpty().any { it.lowercase(Locale.ROOT) == key } }
        val fixed = arguments["must_keep_tags"]?.takeIf { it.isJsonPrimitive }?.asString?.trim()
        val aliases = (old?.aliases.orEmpty() + strings(arguments["aliases"]))
            .filterNot { it.equals(old?.name ?: name, ignoreCase = true) }
            .distinctBy { it.lowercase(Locale.ROOT) }.take(20)
        val outfits = old?.outfits.orEmpty().toMutableList()
        arguments["outfits"]?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { item ->
            if (!item.isJsonObject) return Result.failure(IllegalArgumentException("outfits 每项必须为对象"))
            val obj = item.asJsonObject
            val outfitName = AgentInteractionProtocol.string(obj, "name").trim()
            val tags = AgentInteractionProtocol.string(obj, "tags").trim()
            if (outfitName.isBlank() || tags.isBlank())
                return Result.failure(IllegalArgumentException("服装须同时填写 name 和 tags"))
            val triggers = strings(obj["dialogue_triggers"]).ifEmpty { null }
            val index = outfits.indexOfFirst { it.name.equals(outfitName, ignoreCase = true) }
            if (index < 0) outfits += CharacterOutfit(name = outfitName, triggerWords = tags, dialogueTriggers = triggers)
            else outfits[index] = outfits[index].copy(triggerWords = tags,
                dialogueTriggers = triggers ?: outfits[index].dialogueTriggers)
        }
        val defaultName = AgentInteractionProtocol.string(arguments, "default_outfit").trim()
        val defaultId = if (defaultName.isBlank()) old?.defaultOutfitId else
            outfits.firstOrNull { it.name.equals(defaultName, ignoreCase = true) }?.id
                ?: return Result.failure(IllegalArgumentException("default_outfit「$defaultName」不在服装列表中"))
        val base = old ?: CharacterLore(name = name, triggerWords = appearance)
        return Result.success(base.copy(
            triggerWords = appearance,
            fixedTriggerWords = if (fixed == null) base.fixedTriggerWords else fixed.ifBlank { null },
            aliases = aliases.ifEmpty { null },
            outfits = outfits.ifEmpty { null },
            defaultOutfitId = defaultId))
    }

    private fun scalar(value: JsonElement?): Any? {
        if (value == null || !value.isJsonPrimitive) return null
        val primitive: JsonPrimitive = value.asJsonPrimitive
        return when {
            primitive.isBoolean -> primitive.asBoolean
            primitive.isString -> primitive.asString
            primitive.isNumber -> primitive.asBigDecimal.let { number ->
                if (number.stripTrailingZeros().scale() <= 0 && number.abs() < java.math.BigDecimal(Long.MAX_VALUE)) number.toLong()
                else number.toDouble()
            }
            else -> null
        }
    }

    companion object {
        const val INSPECT = "inspect_workflow"
        const val PATCH = "patch_workflow"
        const val SET_PROMPT = "set_prompt"
        const val LOOKUP_TAGS = "lookup_tags"
        const val PROFILE = "get_character_profile"
        const val SUBMIT = "submit_workflow"
        const val LIST_LORAS = "list_loras"
        const val SET_LORA = "set_lora"
        const val SAVE_CHARACTER = "save_character_profile"
        private const val MAX_LORAS = 20
        private const val MAX_OPTIONS = 40
        private const val MAX_TAGS = 12
        private const val MAX_PROMPT = 8000

        /** Appended after the user's request; the system message keeps the drawing rules and output format. */
        fun instruction(maxSteps: Int) = """
【智能体工具模式】本轮通过工具完成任务，最多 $maxSteps 步，每步可调用一个或多个工具，工具结果会返回给你。
建议流程：需要时 get_character_profile 核对角色、lookup_tags 查标签写法、inspect_workflow 查看参数并用 patch_workflow 修改；然后 set_prompt 写入提示词，最后 submit_workflow 提交。
系统消息中的提示词写法规则、角色档案和冲突必问规则仍然适用：有会实质改变结果的冲突时调用 ask_user_question 并等待。
工作流里有 LoRA 槽位时会提供 list_loras / set_lora：只有用户提到角色、画风、用途或明确要求时才检索并更换 LoRA，按本地标注和简介挑选，强度优先用推荐强度，并把触发词写进提示词；用户没提就保持原样。
画风/画师字段在 inspect_workflow 中标为 role="画风/画师"，用户要求换画风或画师时用 patch_workflow 修改；用户说保存、记住、以后都用、设为默认时，submit_workflow 传 save_defaults=true，设置页会同步。
有 save_character_profile 时：用户介绍了词库里没有的新角色、要求记住某个角色，或给已登记角色补充新服装/外貌时，把整理好的英文绘画标签写入词库；不要擅自覆盖用户没要求改动的已有档案。
工具报错时按错误修正参数再试，不要重复同样的错误调用。只聊天不出图时，不调用工具，直接按系统中的格式回复。
""".trim()

        private fun tool(name: String, description: String, parameters: Map<String, Any>) =
            ChatToolDefinition(ChatFunctionDefinition(name, description, parameters))
        private fun obj(properties: Map<String, Any>, vararg required: String): Map<String, Any> =
            mapOf("type" to "object", "properties" to properties, "required" to required.toList(), "additionalProperties" to false)
        private fun text(description: String) = mapOf("type" to "string", "description" to description)
        private fun array(items: Map<String, Any>, max: Int) = mapOf("type" to "array", "items" to items, "maxItems" to max)
    }
}
