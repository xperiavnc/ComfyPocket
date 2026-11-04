package com.comfyui.mobile.agent

import com.comfyui.mobile.model.CharacterLore
import com.comfyui.mobile.model.CharacterOutfit
import com.comfyui.mobile.modelcenter.modelUseCases
import com.comfyui.mobile.network.ChatFunctionDefinition
import com.comfyui.mobile.network.ChatToolDefinition
import com.comfyui.mobile.taglib.LocalTagEntry
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Drawing tools for [AgentToolLoop]. Workflow edits are validated against [snapshot] (only the auxiliary
 * fields the user exposed to the Agent) and staged; nothing touches the real workflow until the turn is
 * submitted, so a cancelled or failed turn leaves the workflow as it was.
 */
class AgentDrawToolbox(
    snapshot: AgentWorkflowSnapshot,
    private val titles: Map<String, String> = emptyMap(),
    characters: List<CharacterLore> = emptyList(),
    private val searchTags: (suspend (query: String, kind: String) -> List<LocalTagEntry>)? = null,
    /** Local LoRA metadata (user annotations + provider info); empty when the model index is unavailable. */
    loras: List<AgentLoraInfo> = emptyList(),
    /** Address of the 画风 / 画师 text field shown in the style settings, when bound. */
    private val styleField: String? = null,
    private val favoriteArtists: List<String> = emptyList(),
    /** Persists a character profile into the library; returns an error message or null. */
    private val saveCharacter: (suspend (CharacterLore) -> String?)? = null,
    /** Live model-center access: refresh, full descriptions and the Agent's own LoRA notes. */
    private val loraLibrary: AgentLoraLibrary? = null,
    /** True when [loras] was just re-read from the computer, so list_loras needn't read it again this turn. */
    freshLoras: Boolean = false,
    /**
     * Re-reads the server's node info and returns the current choices per field address. The workflow's
     * LoRA lists are cached since connect, so a LoRA added on the PC is missing from them until reloaded.
     */
    private val reloadOptions: (suspend () -> Map<String, List<String>>?)? = null
) : AgentToolbox {
    private var snapshot: AgentWorkflowSnapshot = snapshot
    /** Set once the options were reloaded this turn; reloading again would only repeat the same request. */
    private var optionsReloaded = reloadOptions == null
    private var characters: List<CharacterLore> = characters
    private var loras: List<AgentLoraInfo> = loras
    /** True once this turn re-read the computer's LoRA list (or tried and fell back to the cache). */
    private var lorasFresh = loraLibrary == null || freshLoras
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

    private var allowedFields = snapshot.fields.associateBy { it.address }
    /** LoRA loader slots whose file field is part of the snapshot; strength partners only when also present. */
    private var loraSlots: List<AgentLoraSlot> = slotsOf(snapshot)

    private fun slotsOf(snapshot: AgentWorkflowSnapshot): List<AgentLoraSlot> {
        val fields = snapshot.fields.associateBy { it.address }
        return AgentLoraCatalog.slots(snapshot.workflow) { node, key -> fields["$node:$key"]?.options.orEmpty() }
            .filter { it.id in fields }.map { slot -> slot.copy(
                strengthKeys = slot.strengthKeys.filter { "${slot.nodeId}:$it" in fields },
                // Without an editable switch the slot keeps switchedOn=false, so set_lora can warn instead.
                switchKey = slot.switchKey?.takeIf { "${slot.nodeId}:$it" in fields }) }
    }

    /**
     * Reloads the LoRA choices once per turn. Returns the files the server now offers that it didn't
     * before, or null when nothing was reloaded (no loader, already done, or the server didn't answer).
     */
    private suspend fun reloadLoraOptions(): List<String>? {
        if (optionsReloaded || loraSlots.isEmpty()) return null
        optionsReloaded = true
        val fresh = try {
            withTimeoutOrNull(FRESH_TIMEOUT_MS) { reloadOptions?.invoke() }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { null } ?: return null
        val before = loraSlots.flatMap { it.options }.map(AgentLoraCatalog::normalize).toSet()
        val slotFields = loraSlots.map { it.id }.toSet()
        val fields = snapshot.fields.map { spec ->
            val options = fresh[spec.address]?.takeIf { spec.address in slotFields && it.isNotEmpty() }
            if (options == null) spec else spec.copy(options = options)
        }
        snapshot = snapshot.copy(fields = fields)
        allowedFields = fields.associateBy { it.address }
        loraSlots = slotsOf(snapshot)
        return loraSlots.flatMap { it.options }.distinct()
            .filter { AgentLoraCatalog.normalize(it) !in before && !it.equals("None", ignoreCase = true) }
    }

    /** The stack's slot count this turn (staged, else as in the workflow); null when the node has none. */
    private fun countOf(slot: AgentLoraSlot): Int? =
        slot.countKey?.let { (stagedValue(slot.nodeId, it) as? Number)?.toInt() }

    /** Whether the node uses this slot: single loaders always, stack slots up to num_loras / lora_count. */
    private fun applies(slot: AgentLoraSlot): Boolean {
        val number = slot.number ?: return true
        val count = countOf(slot) ?: return true
        return number <= count
    }

    /** The first stack slot past the count, when the count field is open to the Agent and allows one more. */
    private fun isNextSlot(slot: AgentLoraSlot): Boolean {
        val number = slot.number ?: return false
        val key = slot.countKey ?: return false
        val spec = allowedFields["${slot.nodeId}:$key"] ?: return false
        val count = countOf(slot) ?: return false
        return number == count + 1 && (spec.max == null || number <= spec.max)
    }

    /** Slots set_lora can use now: the ones the node applies plus each stack's next slot. */
    private fun usableSlots(): List<AgentLoraSlot> = loraSlots.filter { applies(it) || isNextSlot(it) }

    /** Fields of stack slots past the count: the node ignores them, so inspect_workflow leaves them out. */
    private fun unusedSlotFields(): Set<String> = loraSlots.filterNot(::applies)
        .flatMap { slot -> listOf(slot.id) + slot.strengthKeys.map { "${slot.nodeId}:$it" } }.toSet()

    /**
     * LoRA files this workflow can load, the ones in use first. The model center completes their info in the
     * background, so the next turn's 【LoRA 速查】 shows more than file names.
     */
    val loadableLoras: List<String>
        get() = (usableSlots().mapNotNull(::loadedFile) +
            loraSlots.flatMap { it.options }.filterNot { it.isBlank() || it.equals("None", ignoreCase = true) })
            .distinctBy(AgentLoraCatalog::normalize)

    override val definitions: List<ChatToolDefinition> = listOf(
        tool(INSPECT, "查看本轮开放给你的工作流参数（节点、字段、当前值、可选项和范围）。修改参数前先调用。", obj(emptyMap())),
        tool(PATCH, "修改一个或多个已开放的工作流参数。整批校验，任一字段不合法则整批不生效，错误会告诉你原因。",
            obj(mapOf("changes" to array(obj(mapOf(
                "field" to text("inspect_workflow 返回的字段地址，形如 \"12:steps\""),
                "value" to mapOf("description" to "新值；类型须与字段一致（文本/整数/小数/布尔），有可选项时只能取其中之一")
            ), "field", "value"), 20)), "changes")),
        tool(SET_PROMPT, "写入本轮正向提示词和可选的反向提示词。可以多次调用，以最后一次为准。只写提示词时也可以改用 submit_workflow 的 prompt 参数，省掉一次调用。",
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
        tool(SUBMIT, "提交本轮结果并结束。推荐把完整提示词写进 prompt 参数，一次调用完成写入和提交；已经用 set_prompt 写入过时可以只传 reply。draw=true 立即出图，false 只写入提示词不出图。",
            obj(mapOf(
                "reply" to text("给用户的简短回复，说明这次画了什么或改了什么"),
                "prompt" to text("完整正向提示词，按系统中的写法规则。还没调用 set_prompt 时用它一次完成写入和提交"),
                "negative_prompt" to text("完整反向提示词，只在同一个调用里传了 prompt 时有意义；不传则保留工作流原值，传空字符串表示清空"),
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
    )) + loraTools()

    /** LoRA tools: browsing works from the phone's cached index; set_lora needs a slot in the workflow. */
    private fun loraTools(): List<ChatToolDefinition> {
        if (loraSlots.isEmpty() && loras.isEmpty() && loraLibrary == null) return emptyList()
        val browse: List<ChatToolDefinition> = listOf(
            tool(LIST_LORAS, "按关键词检索本地 LoRA：文件名、触发词、底模、用户标注（标签/用途/角色/服装/备注/推荐强度）、你以前写的 agent_notes 和简介；工作流有 LoRA 槽位时一并列出槽位。【LoRA 速查】里找不到、要按用途或角色筛选，或回答 LoRA 相关问题时使用。",
                obj(mapOf("query" to text("检索词，可填角色名、画风、用途等，空格分隔多个词须同时命中；留空列出全部")))),
            tool(READ_LORA, "读取一个本地 LoRA 的完整资料：模型和版本简介（保留分段）、来源触发词与用户确认的触发词、底模、用户标注、你以前写的 agent_notes，以及能否装进当前工作流。",
                obj(mapOf("lora" to text("list_loras 返回的 file 或 name")), "lora"))
        )
        val live: List<ChatToolDefinition> = if (loraLibrary == null) emptyList() else listOf(
            tool(REFRESH_LORAS, "重新读取电脑上的 LoRA 列表，报告新增和移除的文件。用户说刚放了新 LoRA，或要找的 LoRA 不在列表里时使用。",
                obj(mapOf("rescan" to mapOf("type" to "boolean", "description" to "true 时先让电脑重新扫描模型文件夹（较慢，最多等约 45 秒）")))),
            tool(ANNOTATE_LORA, "把你对一个 LoRA 的理解写进模型中心的标注，供以后的新会话检索和使用。notes 会替换你上次写的笔记，不动用户自己的备注；其余字段只在用户没填、没锁定、也没在模型中心确认过分类时补上，绝不覆盖用户内容；补上的内容在模型中心显示为「AI 建议待确认」。只写从简介、触发词、用户说明或出图结果确认过的事实。",
                obj(mapOf(
                    "lora" to text("list_loras 返回的 file 或 name"),
                    "notes" to text("你的笔记：用途、效果、推荐强度、搭配和注意事项，几句话，不超过 ${AgentLoraCatalog.MAX_AGENT_NOTES} 字；空字符串表示清除你的笔记"),
                    "tags" to array(text("检索用标签，如角色名、画风关键词"), MAX_TAGS),
                    "use_cases" to array(mapOf("type" to "string", "enum" to modelUseCases.keys.toList()), 4),
                    "character" to text("可选：对应的角色名"),
                    "outfit" to text("可选：对应的服装"),
                    "base_model" to text("可选：底模，如 SDXL 1.0、Illustrious、Pony"),
                    "trigger_words" to array(text("触发词"), MAX_TAGS),
                    "preferred_strength" to mapOf("type" to "number", "description" to "可选：推荐强度，-4 到 4")
                ), "lora"))
        )
        val set: List<ChatToolDefinition> = if (usableSlots().isEmpty()) emptyList() else listOf(
            tool(SET_LORA, "把某个 LoRA 槽位换成指定 LoRA 并设置强度，或用 lora 为空字符串/strength 为 0 停用该槽位。修改会暂存，提交时生效；槽位所在 LoRA 组的开关关着时会一并打开，装进「新增槽位」时会一并调大 LoRA 数量。",
                obj(mapOf(
                    "slot" to text("槽位地址，形如 \"10:lora_name\"，见【LoRA 速查】或 list_loras；只有一个在用的槽位时可以不填（表示替换它）；要在原有 LoRA 之外再加一个，填「新增槽位」的地址"),
                    "lora" to text("【LoRA 速查】或 list_loras 里的 file；空字符串表示停用"),
                    "strength" to mapOf("type" to "number", "description" to "强度，通常 0.4–1.2；不传则用推荐强度或 1.0")
                ), "lora"))
        )
        return browse + live + set
    }

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
        READ_LORA -> "读取 LoRA 资料"
        REFRESH_LORAS -> "刷新本地 LoRA"
        ANNOTATE_LORA -> "写入 LoRA 笔记"
        else -> name
    }

    override suspend fun call(name: String, arguments: JsonObject): AgentToolResult = when (name) {
        INSPECT -> inspect()
        PATCH -> patch(arguments)
        SET_PROMPT -> setPrompt(arguments)
        LOOKUP_TAGS -> lookup(arguments)
        PROFILE -> profile(arguments)
        SUBMIT -> submit(arguments)
        LIST_LORAS -> if (!offers(name)) unknown(name) else listLoras(arguments)
        SET_LORA -> if (!offers(name)) unknown(name) else setLora(arguments)
        READ_LORA -> if (!offers(name)) unknown(name) else readLora(arguments)
        REFRESH_LORAS -> if (!offers(name)) unknown(name) else refreshLoras(arguments)
        ANNOTATE_LORA -> if (!offers(name)) unknown(name) else annotateLora(arguments)
        SAVE_CHARACTER -> if (saveCharacter == null) unknown(name) else saveCharacterProfile(arguments)
        else -> unknown(name)
    }

    private fun unknown(name: String) = AgentToolResult(false, error = "未知工具：$name")

    private fun offers(name: String) = definitions.any { it.function.name == name }

    private fun current(): AgentWorkflowSnapshot {
        if (staged.isEmpty()) return snapshot
        val patched = patchTool.execute(AgentPatchRequest(snapshot, staged.values.toList())).getOrNull() ?: return snapshot
        return snapshot.copy(workflow = patched.workflow)
    }

    private fun inspect(): AgentToolResult {
        val unused = unusedSlotFields()
        val fields = inspectTool.execute(current()).getOrElse { return AgentToolResult(false, error = it.message) }
            .filter { it.spec.address !in unused }
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

    private suspend fun listLoras(arguments: JsonObject): AgentToolResult {
        val notice = ensureFresh()
        val query = AgentInteractionProtocol.string(arguments, "query")
        val first = listLorasNow(query, notice, emptyList())
        // Nothing found: the server may have a LoRA the cached node info doesn't list yet.
        if (query.isBlank() || first.second > 0) return first.first
        val added = reloadLoraOptions() ?: return first.first
        return listLorasNow(query, notice, added).first
    }

    /** The list_loras result and its hit count. */
    private fun listLorasNow(query: String, notice: String, added: List<String>): Pair<AgentToolResult, Int> {
        val offered = loraSlots.flatMap { it.options }.map(AgentLoraCatalog::normalize).toSet()
        // Only LoRAs the server can actually load; without combo options trust the index.
        val usable = if (offered.isEmpty()) loras else loras.filter { AgentLoraCatalog.normalize(it.file) in offered }
        val terms = AgentLoraCatalog.terms(query)
        var hits = AgentLoraCatalog.search(usable, query, MAX_LORAS)
        val known = usable.map { AgentLoraCatalog.normalize(it.file) }.toSet()
        val names = loraSlots.flatMap { it.options }.distinct().filterNot { it.equals("None", ignoreCase = true) }
        fun bareMatches(all: Boolean) = names.filter { option ->
            val name = option.lowercase(Locale.ROOT)
            AgentLoraCatalog.normalize(option) !in known &&
                (terms.isEmpty() || if (all) terms.all { it in name } else terms.any { it in name })
        }
        var bare = bareMatches(all = true)
        // Several words that never all match one LoRA: list the closest ones instead of nothing.
        val loose = hits.isEmpty() && bare.isEmpty() && terms.size > 1
        if (loose) {
            hits = AgentLoraCatalog.searchAny(usable, query, MAX_LORAS)
            bare = bareMatches(all = false)
        }
        bare = if (hits.size >= MAX_LORAS) emptyList() else bare.take(MAX_LORAS - hits.size)
        val slots = usableSlots()
        return AgentToolResult(true, linkedMapOf(
            "summary" to (if (slots.isEmpty()) "当前工作流没有 LoRA 槽位（只能查看和写笔记，不能装载），" else "${slots.size} 个 LoRA 槽位，") +
                "命中 ${hits.size + bare.size} 个 LoRA" + (if (loose) "（没有同时命中全部检索词，按命中词数列出）" else "") +
                (if (loras.isEmpty()) "（模型中心还没有本地 LoRA 资料，只有文件名）" else "") +
                (if (added.isNotEmpty()) "；已重新读取服务器的 LoRA 列表，新增 ${added.size} 个文件" else "") +
                (if (notice.isNotEmpty()) "；$notice" else ""),
            "slots" to slots.map { slot ->
                linkedMapOf<String, Any?>(
                    "slot" to slot.id,
                    "node" to (titles[slot.nodeId] ?: (snapshot.workflow[slot.nodeId] as? Map<*, *>)?.get("class_type"))
                ).apply {
                    if (applies(slot)) {
                        put("current", stagedValue(slot.nodeId, slot.nameKey))
                        put("strength", slot.strengthKeys.associateWith { stagedValue(slot.nodeId, it) })
                    } else {
                        put("new_slot", "空槽位，装上 LoRA 时会把 ${slot.countKey} 调到 ${slot.number}")
                    }
                    if (!isOn(slot)) put("enabled", false)
                }
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
                    "agent_notes" to info.agentNotes.ifBlank { null }?.let { if (it.length > 160) it.take(160) + "…" else it },
                    "preferred_strength" to info.preferredStrength,
                    "description" to info.description.ifBlank { null },
                    "favorite" to info.favorite.takeIf { it }
                ).filterValues { it != null }
            } + bare.map { mapOf("file" to it, "note" to "没有本地资料") }
        )) to hits.size + bare.size
    }

    /** Re-reads the computer's LoRA list once per turn so a new session sees new files and notes. */
    private suspend fun ensureFresh(): String {
        if (lorasFresh) return ""
        lorasFresh = true
        val library = loraLibrary ?: return ""
        return try {
            val fresh = withTimeoutOrNull(FRESH_TIMEOUT_MS) { library.refresh() }
                ?: return "电脑没有及时返回最新 LoRA 列表，以下是手机缓存"
            // An index that is still being rebuilt can come back empty; keep the cached list then.
            if (fresh.loras.isNotEmpty() || loras.isEmpty()) loras = fresh.loras
            ""
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) { "没能读取电脑上的最新 LoRA 列表（${error.message ?: "未知错误"}），以下是手机缓存" }
    }

    private fun findLora(requested: String): AgentLoraInfo? {
        val wanted = requested.trim()
        if (wanted.isEmpty()) return null
        val key = AgentLoraCatalog.normalize(wanted)
        loras.firstOrNull { AgentLoraCatalog.normalize(it.file) == key || it.name.equals(wanted, ignoreCase = true) }?.let { return it }
        val file = AgentLoraCatalog.resolve(wanted, loras.map { it.file }, loras) ?: return null
        return loras.firstOrNull { AgentLoraCatalog.normalize(it.file) == AgentLoraCatalog.normalize(file) }
    }

    private fun keep(info: AgentLoraInfo) {
        val key = AgentLoraCatalog.normalize(info.file)
        loras = if (loras.any { AgentLoraCatalog.normalize(it.file) == key })
            loras.map { if (AgentLoraCatalog.normalize(it.file) == key) info else it } else loras + info
    }

    private suspend fun readLora(arguments: JsonObject): AgentToolResult {
        val requested = AgentInteractionProtocol.string(arguments, "lora")
        if (requested.isBlank()) return AgentToolResult(false, error = "lora 不能为空")
        val stale = ensureFresh()
        val found = findLora(requested)
            ?: return AgentToolResult(false, error = "没有找到 LoRA「$requested」。先用 list_loras 检索，再填返回的 file")
        val library = loraLibrary
        var notice = stale
        val current = if (library != null && found.id.isNotBlank()) {
            try {
                val detail = library.detail(found)
                if (detail.notice.isNotEmpty()) notice = detail.notice
                detail.info
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                notice = "没能从电脑读取最新资料（${error.message ?: "未知错误"}），以下是手机缓存"
                found
            }
        } else found
        keep(current)
        val offered = loraSlots.flatMap { it.options }.map(AgentLoraCatalog::normalize).toSet()
        return AgentToolResult(true, linkedMapOf<String, Any?>(
            "file" to current.file, "name" to current.name,
            "base_model" to current.baseModel.ifBlank { null },
            "trigger_words" to current.triggerWords.takeIf { it.isNotEmpty() },
            "source_trigger_words" to current.sourceTriggerWords.takeIf { it.isNotEmpty() && it != current.triggerWords },
            "description" to current.fullDescription.ifBlank { current.description }.ifBlank { null },
            "source" to current.source.ifBlank { null },
            "notes" to current.notes.ifBlank { null },
            "agent_notes" to current.agentNotes.ifBlank { null },
            "tags" to current.tags.takeIf { it.isNotEmpty() },
            "use_cases" to current.useCases.takeIf { it.isNotEmpty() },
            "character" to current.character.ifBlank { null },
            "outfit" to current.outfit.ifBlank { null },
            "preferred_strength" to current.preferredStrength,
            "favorite" to current.favorite.takeIf { it },
            "loadable" to (usableSlots().isNotEmpty() && (offered.isEmpty() || AgentLoraCatalog.normalize(current.file) in offered)),
            "notice" to notice.ifBlank { null },
            "hint" to if (current.triggerWords.isEmpty() && current.fullDescription.isBlank() && library != null)
                "没有简介和触发词：可以问用户这个 LoRA 的用途，确认后用 annotate_lora 记下" else null
        ).filterValues { it != null })
    }

    private suspend fun refreshLoras(arguments: JsonObject): AgentToolResult {
        val library = loraLibrary ?: return unknown(REFRESH_LORAS)
        val rescan = arguments["rescan"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true
        val before = loras.associateBy { AgentLoraCatalog.normalize(it.file) }
        val result = try { library.refresh(rescan) } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            return AgentToolResult(false, error = "没能从电脑刷新 LoRA 列表（${error.message ?: "未知错误"}）。手机缓存里还有 ${loras.size} 个 LoRA，可以继续用 list_loras")
        }
        lorasFresh = true
        loras = result.loras
        val loadable = reloadLoraOptions()
        val after = result.loras.associateBy { AgentLoraCatalog.normalize(it.file) }
        val added = result.loras.filter { AgentLoraCatalog.normalize(it.file) !in before }.map { it.file }
        val removed = before.filterKeys { it !in after }.values.map { it.file }
        val offered = loraSlots.flatMap { it.options }.map(AgentLoraCatalog::normalize).toSet()
        val waiting = added.filter { offered.isNotEmpty() && AgentLoraCatalog.normalize(it) !in offered }
        return AgentToolResult(true, linkedMapOf<String, Any?>(
            "summary" to "电脑上共 ${result.loras.size} 个 LoRA，新增 ${added.size} 个，移除 ${removed.size} 个" +
                (if (!loadable.isNullOrEmpty()) "；工作流可选的 LoRA 新增 ${loadable.size} 个，可以直接 set_lora" else "") +
                (if (result.note.isNotEmpty()) "；${result.note}" else ""),
            "added" to added.take(MAX_LORAS).takeIf { it.isNotEmpty() },
            "loadable_added" to loadable?.take(MAX_LORAS)?.takeIf { it.isNotEmpty() },
            "removed" to removed.take(MAX_LORAS).takeIf { it.isNotEmpty() },
            "hint" to if (waiting.isNotEmpty() && usableSlots().isNotEmpty())
                "${waiting.size} 个新文件 ComfyUI 还没列进可选项（可能要在电脑上刷新节点或重启 ComfyUI），现在可以先用 read_lora 查看" else null
        ).filterValues { it != null })
    }

    private suspend fun annotateLora(arguments: JsonObject): AgentToolResult {
        val library = loraLibrary ?: return unknown(ANNOTATE_LORA)
        val requested = AgentInteractionProtocol.string(arguments, "lora")
        if (requested.isBlank()) return AgentToolResult(false, error = "lora 不能为空")
        val notesElement = arguments["notes"]
        val notes = when {
            notesElement == null || notesElement.isJsonNull -> null
            notesElement.isJsonPrimitive && notesElement.asJsonPrimitive.isString -> notesElement.asString.trim()
            else -> return AgentToolResult(false, error = "notes 必须是字符串")
        }
        if (notes != null && notes.length > AgentLoraCatalog.MAX_AGENT_NOTES)
            return AgentToolResult(false, error = "notes 过长（${notes.length} 字，上限 ${AgentLoraCatalog.MAX_AGENT_NOTES}），请精简成几句话")
        val strengthElement = arguments["preferred_strength"]
        val strength = strengthElement?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
        if (strengthElement != null && !strengthElement.isJsonNull && strength == null)
            return AgentToolResult(false, error = "preferred_strength 必须是数字")
        if (strength != null && (strength < -4.0 || strength > 4.0))
            return AgentToolResult(false, error = "preferred_strength 超出范围（-4 到 4）")
        val note = AgentLoraNote(
            notes = notes, tags = strings(arguments["tags"]).take(MAX_TAGS), useCases = strings(arguments["use_cases"]),
            character = AgentInteractionProtocol.string(arguments, "character"),
            outfit = AgentInteractionProtocol.string(arguments, "outfit"),
            baseModel = AgentInteractionProtocol.string(arguments, "base_model"),
            triggerWords = strings(arguments["trigger_words"]).take(MAX_TAGS), preferredStrength = strength)
        // Field limits of the Bridge's validate_annotation (ComfyUI-Mobile-Bridge/model_index.py).
        val overLimit = listOfNotNull(
            "character".takeIf { note.character.length > 500 }, "outfit".takeIf { note.outfit.length > 500 },
            "base_model".takeIf { note.baseModel.length > 200 }, "tags".takeIf { note.tags.any { it.length > 200 } },
            "trigger_words".takeIf { note.triggerWords.any { it.length > 2000 } })
        if (overLimit.isNotEmpty()) return AgentToolResult(false, error = overLimit.joinToString("、") +
            " 过长：角色和服装不超过 500 字，底模不超过 200 字，单个标签不超过 200 字")
        if (note == AgentLoraNote()) return AgentToolResult(false, error = "没有要写入的内容：至少给 notes 或一个标注字段")
        ensureFresh()
        val found = findLora(requested)
            ?: return AgentToolResult(false, error = "没有找到 LoRA「$requested」。先用 list_loras 检索，再填返回的 file")
        val result = try { library.annotate(found, note) } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            return AgentToolResult(false, error = "写入标注失败：${error.message ?: "未知错误"}")
        }
        keep(result.info)
        return AgentToolResult(true, linkedMapOf<String, Any?>(
            "summary" to if (result.applied.isEmpty()) "「${result.info.name}」的标注没有变化"
                else "已写入「${result.info.name}」：" + result.applied.joinToString("、"),
            "skipped" to result.skipped.takeIf { it.isNotEmpty() },
            "agent_notes" to result.info.agentNotes.ifBlank { null }
        ).filterValues { it != null })
    }

    /**
     * By id, or by node id within that node. Without one: the slot already holding [requested], else the only
     * slot in use. A stack's new slot has to be named, so a bare "换成 X" keeps replacing the LoRA in use.
     */
    private fun pickSlot(slotId: String, requested: String?): AgentLoraSlot? {
        val usable = usableSlots()
        if (slotId.isNotBlank()) {
            usable.firstOrNull { it.id == slotId }?.let { return it }
            val inNode = usable.filter { it.nodeId == slotId }
            return holder(inNode, requested) ?: inNode.filter(::applies).singleOrNull() ?: inNode.singleOrNull()
        }
        return holder(usable, requested) ?: usable.filter(::applies).singleOrNull() ?: usable.singleOrNull()
    }

    /** The one slot among [slots] that already holds [requested]. */
    private fun holder(slots: List<AgentLoraSlot>, requested: String?): AgentLoraSlot? {
        if (requested == null) return null
        return slots.filter { slot ->
            val file = AgentLoraCatalog.resolve(requested, slot.options, loras)
            val loaded = loadedFile(slot)
            file != null && loaded != null && AgentLoraCatalog.normalize(loaded) == AgentLoraCatalog.normalize(file)
        }.singleOrNull()
    }

    /** Whether the slot's switch is on, counting this turn's staged changes. */
    private fun isOn(slot: AgentLoraSlot): Boolean {
        val key = slot.switchKey ?: return slot.switchedOn
        val value = stagedValue(slot.nodeId, key)
        val on = slot.switchOn
        return if (on is String) (value as? String).equals(on, ignoreCase = true) else value == on
    }

    /** The value that turns [slot]'s switch on, spelled the way the field's options spell it. */
    private fun switchValue(slot: AgentLoraSlot, key: String): Any {
        val on = slot.switchOn ?: true
        if (on !is String) return on
        return allowedFields["${slot.nodeId}:$key"]?.options.orEmpty().firstOrNull { it.equals(on, ignoreCase = true) } ?: on
    }

    private fun nodeName(nodeId: String): Any? = titles[nodeId] ?: (snapshot.workflow[nodeId] as? Map<*, *>)?.get("class_type")

    /** The LoRA file a slot holds this turn, or null when it is empty or past the stack's count. */
    private fun loadedFile(slot: AgentLoraSlot): String? = if (!applies(slot)) null else
        (stagedValue(slot.nodeId, slot.nameKey) as? String)?.takeIf { it.isNotBlank() && !it.equals("None", ignoreCase = true) }

    /** "file，强度 0.8，触发词 x" or "空", plus the switch state, from this turn's staged values. */
    private fun describeSlot(slot: AgentLoraSlot): String {
        val file = loadedFile(slot)
        val strength = slot.strengthKeys.firstOrNull()?.let { stagedValue(slot.nodeId, it) as? Number }
        val triggers = file?.let(::infoFor)?.let(::activators).orEmpty()
        return buildString {
            append(file ?: "空")
            if (!applies(slot)) append("（新增槽位：装上 LoRA 时会把 ${slot.countKey} 调到 ${slot.number}）")
            if (file != null && strength != null) append("，强度 ").append(strength)
            if (triggers.isNotEmpty()) append("，触发词 ").append(triggers.take(3).joinToString(" / ") { short(it, 40) })
            if (!isOn(slot)) append(if (slot.switchKey != null) "，开关关着（装 LoRA 时会自动打开）" else "，开关关着（Agent 改不了，装上也不生效）")
        }
    }

    private fun infoFor(file: String): AgentLoraInfo? =
        AgentLoraCatalog.normalize(file).let { key -> loras.firstOrNull { AgentLoraCatalog.normalize(it.file) == key } }

    /** Every trigger word, with comma-separated groups split apart. */
    private fun words(info: AgentLoraInfo?): List<String> =
        info?.triggerWords.orEmpty().flatMap { it.split(',', '，') }.map(String::trim).filter(String::isNotEmpty)

    /** The first word of each trigger group: the word that switches the LoRA on, not the tags that describe it. */
    private fun activators(info: AgentLoraInfo?): List<String> = info?.triggerWords.orEmpty()
        .mapNotNull { group -> group.split(',', '，').map(String::trim).firstOrNull(String::isNotEmpty) }
        .distinctBy { promptText(it) }

    /** Lower-case prompt text with `_` as a space and escapes dropped, for trigger-word checks. */
    private fun promptText(value: String): String =
        value.lowercase(Locale.ROOT).replace("\\", "").replace('_', ' ').replace(blank, " ").trim()

    private fun mentions(prompt: String, word: String): Boolean = promptText(word).let { it.isNotEmpty() && it in promptText(prompt) }

    /**
     * Words submit adds to [positive]: for each LoRA loaded this turn whose few trigger words are all missing,
     * its first word. LoRAs with longer trigger lists (character packs, tag dumps) are left to the model.
     */
    private fun missingTriggers(positive: String): List<String> = loraSlots.mapNotNull { slot ->
        val file = staged[slot.id]?.value as? String ?: return@mapNotNull null
        if (file.equals("None", ignoreCase = true) || !applies(slot) || !isOn(slot)) return@mapNotNull null
        // In use when the turn started: its trigger words are already the user's call. A slot past the count
        // was not in use, whatever file name it kept.
        if (slot.active && AgentLoraCatalog.normalize(file) == AgentLoraCatalog.normalize(slot.current)) return@mapNotNull null
        if (slot.strengthKeys.isNotEmpty() && slot.strengthKeys.all { (stagedValue(slot.nodeId, it) as? Number)?.toDouble() == 0.0 }) return@mapNotNull null
        val words = words(infoFor(file))
        if (words.isEmpty() || words.size > MAX_AUTO_TRIGGERS || words.any { mentions(positive, it) }) null
        else words.first().takeIf { it.length <= MAX_AUTO_TRIGGER_LENGTH }
    }.distinctBy { promptText(it) }

    /**
     * Prompt advice after a slot changed from [old] to [new]: where the new trigger words go and which words of
     * the LoRA taken out are still in this turn's prompt. Old words are only reported, never removed, because the
     * user may want them as plain tags.
     */
    private fun triggerHints(loaded: String?, old: String?): List<String> {
        val written = prompt
        val newInfo = loaded?.let(::infoFor)
        val fresh = words(newInfo)
        val oldInfo = old?.let(::infoFor)
        val oldName = oldInfo?.name ?: old
        val freshKeys = fresh.map(::promptText).toSet()
        val leftover = activators(oldInfo).filter { promptText(it) !in freshKeys }
        return buildList {
            when {
                loaded == null -> {}
                newInfo == null -> add("模型中心没有这个 LoRA 的资料，不知道触发词；用户说过触发词就写进提示词")
                fresh.isEmpty() -> add("这个 LoRA 没有登记触发词，需要时用 read_lora 看简介里的用法")
                written == null -> add("写 set_prompt 时把触发词放进正向提示词")
                fresh.none { mentions(written, it) } -> add("当前提示词里还没有这个 LoRA 的触发词，用 set_prompt 补上")
            }
            if (leftover.isNotEmpty()) {
                if (written == null) add("提示词里如果有换下的「$oldName」的触发词（${leftover.take(6).joinToString("、")}），写 set_prompt 时去掉")
                else leftover.filter { mentions(written, it) }.takeIf { it.isNotEmpty() }?.let { stale ->
                    add("提示词里还留着换下的「$oldName」的触发词：${stale.take(6).joinToString("、")}，不需要就用 set_prompt 去掉")
                }
            }
        }
    }

    /** The count change that makes a stack apply [slot], typed like the workflow's value; null when it applies. */
    private fun growTo(slot: AgentLoraSlot): AgentFieldChange? {
        if (applies(slot)) return null
        val key = slot.countKey ?: return null
        val number = slot.number ?: return null
        val value: Any = when (stagedValue(slot.nodeId, key)) {
            is Int -> number
            is Long -> number.toLong()
            else -> number.toDouble()
        }
        return AgentFieldChange(slot.nodeId, key, value)
    }

    private suspend fun setLora(arguments: JsonObject): AgentToolResult {
        val slotId = AgentInteractionProtocol.string(arguments, "slot")
        val requested = AgentInteractionProtocol.string(arguments, "lora")
        val strengthElement = arguments["strength"]
        val strength = strengthElement?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
        if (strengthElement != null && !strengthElement.isJsonNull && strength == null) return AgentToolResult(false, error = "strength 必须是数字")
        val named = requested.takeUnless { it.isBlank() || it.equals("none", ignoreCase = true) }
        // A file no slot offers may just be newer than the cached node info: reload once and look again.
        if (named != null && usableSlots().none { AgentLoraCatalog.resolve(named, it.options, loras) != null }) reloadLoraOptions()
        val slot = pickSlot(slotId, named) ?: return usableSlots().let { usable ->
            AgentToolResult(false, error =
                (if (slotId.isBlank()) "工作流有 ${usable.size} 个 LoRA 槽位，请用 slot 指定：" else "没有槽位「$slotId」。可用：") +
                    usable.joinToString("、") { "${it.id}（${describeSlot(it)}）" })
        }
        val previous = loadedFile(slot)
        val disable = named == null || strength == 0.0
        if (disable) {
            if (!applies(slot)) return AgentToolResult(false, error = "${slot.id} 是还没启用的新增槽位，不用停用")
            // Strength 0 alone still leaves the file name, which ComfyUI validates against its list.
            val none = slot.options.firstOrNull { it.equals("None", ignoreCase = true) }
            if (slot.strengthKeys.isEmpty() && none == null) return AgentToolResult(false, error = "这个槽位没有可修改的强度字段，无法停用；可以换成别的 LoRA")
            val result = stage(listOfNotNull(none?.let { AgentFieldChange(slot.nodeId, slot.nameKey, it) }) +
                slot.strengthKeys.map { AgentFieldChange(slot.nodeId, it, 0.0) }, "已暂存：停用 ${slot.id}")
            val hints = triggerHints(null, previous)
            return if (!result.ok || hints.isEmpty()) result
            else result.copy(data = (result.data as Map<*, *>) + mapOf("hint" to hints.joinToString("；")))
        }
        val file = AgentLoraCatalog.resolve(requested, slot.options, loras)
            ?: return AgentToolResult(false, error = "服务器上没有 LoRA「$requested」。先用 list_loras 检索，再填返回的 file")
        val info = infoFor(file)
        val value = strength ?: info?.preferredStrength ?: 1.0
        if (value < -4.0 || value > 4.0) return AgentToolResult(false, error = "strength 超出范围（-4 到 4）")
        val replaced = previous?.takeIf { AgentLoraCatalog.normalize(it) != AgentLoraCatalog.normalize(file) }
        // A switched-off LoRA group ignores its slots, so loading a LoRA there turns the switch on.
        val turnOn = slot.switchKey?.takeIf { !isOn(slot) }?.let { AgentFieldChange(slot.nodeId, it, switchValue(slot, it)) }
        // A stack only applies slots up to num_loras / lora_count, so filling the next slot raises the count.
        val grow = growTo(slot)
        val changes = listOf(AgentFieldChange(slot.nodeId, slot.nameKey, file)) +
            slot.strengthKeys.map { AgentFieldChange(slot.nodeId, it, value) } + listOfNotNull(turnOn, grow)
        val result = stage(changes, "已暂存：${slot.id} → ${info?.name ?: file}，强度 $value" +
            (turnOn?.let { "，并打开了 ${it.key}" } ?: "") + (grow?.let { "，并把 ${it.key} 调到 ${slot.number}" } ?: ""))
        if (!result.ok) return result
        val words = info?.triggerWords.orEmpty()
        val hints = triggerHints(file, replaced)
        return result.copy(data = (result.data as Map<*, *>) + mapOf(
            "trigger_words" to words.takeIf { it.isNotEmpty() },
            "replaced" to replaced,
            "hint" to hints.joinToString("；").ifBlank { null },
            "warning" to if (isOn(slot)) null else "这个槽位所在 LoRA 组的开关是关的，Agent 改不了，装上也不会生效；请告诉用户在工作流里打开"
        ).filterValues { it != null })
    }

    private fun setPrompt(arguments: JsonObject): AgentToolResult =
        writePrompt(AgentInteractionProtocol.string(arguments, "prompt"), arguments["negative_prompt"])

    /**
     * Writes this turn's prompt pair. Shared by set_prompt and the one-call submit_workflow, so a turn that only
     * writes a prompt and draws needs one model step instead of two.
     */
    private fun writePrompt(positive: String, negativeElement: JsonElement?): AgentToolResult {
        if (positive.isBlank()) return AgentToolResult(false, error = "prompt 不能为空")
        if (positive.length > MAX_PROMPT) return AgentToolResult(false, error = "prompt 过长（${positive.length} 字符，上限 $MAX_PROMPT）")
        val negative = when {
            negativeElement == null || negativeElement.isJsonNull -> null
            negativeElement.isJsonPrimitive && negativeElement.asJsonPrimitive.isString -> negativeElement.asString.trim()
            else -> return AgentToolResult(false, error = "negative_prompt 必须是字符串")
        }
        if (negative != null && negative.length > MAX_PROMPT) return AgentToolResult(false, error = "negative_prompt 过长")
        val resolution = AgentNegativePromptPolicy.removePositiveCollisions(positive, negative)
        val appliedNegative = negative?.let { resolution.negativePrompt }
        prompt = positive
        negativePrompt = appliedNegative
        val removedTags = resolution.removedTags
        return AgentToolResult(true, buildMap {
            put("summary", "已写入提示词（${positive.length} 字符）")
            put("negative", when (appliedNegative) {
                null -> "保留工作流原值"
                "" -> "清空"
                else -> "${appliedNegative.length} 字符"
            })
            if (removedTags.isNotEmpty()) {
                put("warning", "已从反向提示词移除与正向重复的标签：${removedTags.joinToString("、")}；保留正向提示词并继续出图")
                put("removed_duplicate_negative_tags", removedTags)
            }
        })
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
        // One-call form: the prompt may arrive with the submission, so "just draw this" costs a single model step.
        val inline = arguments["prompt"]?.takeUnless { it.isJsonNull }
        if (inline != null && (!inline.isJsonPrimitive || !inline.asJsonPrimitive.isString)) {
            return AgentToolResult(false, error = "prompt 必须是字符串")
        }
        val inlineResult = inline?.let { writePrompt(it.asString, arguments["negative_prompt"]) }
        if (inlineResult != null && !inlineResult.ok) return inlineResult
        val inlineWarning = (inlineResult?.data as? Map<*, *>)?.get("warning") as? String
        val written = prompt ?: return AgentToolResult(false,
            error = "还没有提示词：把完整提示词写进 prompt 参数，或先调用 set_prompt")
        // A LoRA loaded this turn whose trigger word never made it into the prompt often does nothing visible;
        // adding the word beats failing the turn over it.
        val added = missingTriggers(written)
        val positive = if (added.isEmpty()) written
            else written.trimEnd().trimEnd(',', '，').trimEnd() + ", " + added.joinToString(", ")
        prompt = positive
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
        return AgentToolResult(true, buildMap<String, Any> {
            put("summary", (if (draw) "已提交出图" else "已写入提示词，未出图") +
                (if (added.isEmpty()) "" else "；已补上 LoRA 触发词：${added.joinToString("、")}"))
            put("staged_changes", staged.size)
            put("save_defaults", saveDefaults)
            inlineWarning?.let { put("warning", it) }
        }, finalContent = final.toString())
    }

    /**
     * 【LoRA 速查】 for the tool instruction: this workflow's LoRA slots and the LoRAs the server can load, so
     * "换成 X 画风" needs no list_loras round trip. Built from the turn's starting state; empty without slots.
     */
    override fun briefing(): String {
        val slots = usableSlots()
        if (slots.isEmpty()) return ""
        val options = loraSlots.flatMap { it.options }.filterNot { it.equals("None", ignoreCase = true) }
            .distinctBy(AgentLoraCatalog::normalize)
        val offered = options.map(AgentLoraCatalog::normalize).toSet()
        // Same rule as list_loras: only what the server can load; without combo options trust the index.
        val usable = if (offered.isEmpty()) loras else loras.filter { AgentLoraCatalog.normalize(it.file) in offered }
        val loaded = slots.mapNotNull { loadedFile(it)?.let(AgentLoraCatalog::normalize) }.toSet()
        val ranked = usable.sortedWith(compareByDescending<AgentLoraInfo> { AgentLoraCatalog.normalize(it.file) in loaded }
            .thenByDescending { it.favorite }
            .thenByDescending { it.notes.isNotBlank() || it.agentNotes.isNotBlank() || it.useCases.isNotEmpty() || it.tags.isNotEmpty() }
            .thenByDescending { it.triggerWords.isNotEmpty() }
            .thenBy { it.file.lowercase(Locale.ROOT) })
        val known = usable.map { AgentLoraCatalog.normalize(it.file) }.toSet()
        val lines = ranked.map(::briefLine) + options.filter { AgentLoraCatalog.normalize(it) !in known }
            .sortedBy { it.lowercase(Locale.ROOT) }
            .map { "- " + short(it, MAX_BRIEF_LINE) + "（没有资料）" }
        return buildString {
            append("【LoRA 速查】本轮工作流的 LoRA 槽位和服务器上能装的 LoRA。换 LoRA 时直接用这里的槽位和 file 调用 set_lora，不必先 list_loras；")
            append("这里没有、要按用途筛选或要看完整简介时再用 list_loras / read_lora。\n槽位：\n")
            slots.forEach { slot -> append("- ${slot.id}（${nodeName(slot.nodeId) ?: "LoRA"}）：${describeSlot(slot)}\n") }
            if (lines.isEmpty()) {
                append("服务器没有报告可选的 LoRA 文件，用 list_loras 检索。")
                return@buildString
            }
            append("可用 LoRA（共 ${lines.size} 个）：\n")
            var shown = 0
            for (line in lines) {
                if (shown >= MAX_BRIEF_LORAS || length + line.length + 1 > MAX_BRIEF_CHARS) break
                append(line).append('\n')
                shown++
            }
            if (shown < lines.size) append("……还有 ${lines.size - shown} 个没列出，用 list_loras 按关键词检索。")
        }.trim()
    }

    /** One 速查 line: what the LoRA is, how to trigger it, and what the user and the Agent noted about it. */
    private fun briefLine(info: AgentLoraInfo): String {
        fun stem(value: String) = AgentLoraCatalog.normalize(value).substringAfterLast('/').substringBeforeLast('.')
        val file = stem(info.file)
        val parts = buildList {
            add(info.file)
            listOf(info.name, info.title).map(String::trim).filter { it.isNotEmpty() && stem(it) != file }
                .distinctBy { it.lowercase(Locale.ROOT) }.forEach { add(short(it, 40)) }
            val groups = info.triggerWords
            if (groups.isNotEmpty()) add("触发词 " + groups.take(3).joinToString(" / ") { short(it, 60) } +
                if (groups.size > 3) " 等 ${groups.size} 组" else "")
            if (info.baseModel.isNotBlank()) add(info.baseModel)
            if (info.useCases.isNotEmpty()) add("用途 " + info.useCases.joinToString("、") { modelUseCases[it] ?: it })
            if (info.character.isNotBlank()) add("角色 " + short(info.character, 30))
            info.preferredStrength?.let { add("推荐强度 $it") }
            if (info.favorite) add("收藏")
            if (info.notes.isNotBlank()) add("备注 " + short(info.notes, 50))
            if (info.agentNotes.isNotBlank()) add("你的笔记 " + short(info.agentNotes, 70))
        }
        return short("- " + parts.joinToString("｜"), MAX_BRIEF_LINE)
    }

    /** [text] on one line, cut to [max] characters with an ellipsis. */
    private fun short(text: String, max: Int): String {
        val line = text.replace(blank, " ").trim()
        return if (line.length <= max) line else line.take(max - 1).trimEnd() + "…"
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
        const val READ_LORA = "read_lora"
        const val REFRESH_LORAS = "refresh_loras"
        const val ANNOTATE_LORA = "annotate_lora"
        private const val MAX_LORAS = 20
        private const val FRESH_TIMEOUT_MS = 10_000L
        private const val MAX_OPTIONS = 40
        private const val MAX_TAGS = 12
        private const val MAX_PROMPT = 8000
        private const val MAX_BRIEF_LORAS = 80
        private const val MAX_BRIEF_CHARS = 8000
        private const val MAX_BRIEF_LINE = 220
        /** submit adds a missing trigger word only for LoRAs with at most this many words. */
        private const val MAX_AUTO_TRIGGERS = 3
        private const val MAX_AUTO_TRIGGER_LENGTH = 40
        private val blank = Regex("\\s+")

        /** [instruction] followed by this turn's toolbox briefing (the 【LoRA 速查】) when there is one. */
        fun instruction(maxSteps: Int, briefing: String): String =
            if (briefing.isBlank()) instruction(maxSteps) else instruction(maxSteps) + "\n\n" + briefing.trim()

        /** Appended after the user's request; the system message keeps the drawing rules and output format. */
        fun instruction(maxSteps: Int) = """
【智能体工具模式】本轮通过工具完成任务，最多 $maxSteps 步，每步可调用一个或多个工具，工具结果会返回给你。
建议流程：需要时 get_character_profile 核对角色、lookup_tags 查标签写法、inspect_workflow 查看参数并用 patch_workflow 修改；最后调用 submit_workflow，把完整提示词写进它的 prompt 参数（一次调用完成写入和提交）。
能确定的步骤放进同一批调用，少走来回：
- 换 LoRA（包括用 LoRA 换画风）：按【LoRA 速查】直接 set_lora，同一批 submit_workflow 并在 prompt 里带上新 LoRA 的触发词、去掉换下的 LoRA 的触发词；速查里没有或拿不准用哪个时才 list_loras / read_lora。
- 替换角色外貌：用户给了角色标签或说"换成 xxx"时，直接替换提示词里对应的角色和外貌标签，再 submit_workflow（带 prompt）；不认识这个角色或缺关键外貌时才 get_character_profile / lookup_tags。
- 只改提示词或直接画：不用 inspect_workflow，也不用先 set_prompt，把完整提示词写进 submit_workflow 的 prompt 参数即可。
**要出图时必须调用 submit_workflow**：提示词写在 set_prompt 里或直接写在 submit_workflow 的 prompt 参数里都行，但只有调用 submit_workflow 才会出图；patch_workflow 修改参数后也要调用 submit_workflow 才能生效；只有用户明确说"不出图""先别画"时才不提交（此时 submit_workflow 传 draw=false）。
现在是绘图模式。对话记录里只有文字、没有提示词的回复来自聊天模式，不要照着只回文字；用户这一轮要画就调用工具出图。
系统消息中的提示词写法规则、角色档案和冲突必问规则仍然适用：有会实质改变结果的冲突时调用 ask_user_question 并等待。
正反向提示词重复标签：系统会自动从负面提示词中移除与正面重复的标签，不影响出图，你会在 set_prompt 或 submit_workflow 的返回值中看到 warning；不需要再改提示词，还没提交就继续 submit_workflow。
有 LoRA 工具时：list_loras 按关键词检索本地 LoRA（含你以前写的笔记），read_lora 看完整简介和触发词；set_lora 只在工作流有 LoRA 槽位时提供。用户要在原有 LoRA 之外再加一个时，装进速查里的「新增槽位」（会一并调大 LoRA 数量），不要替换原有的。只有用户提到角色、画风、用途或明确要求时才更换 LoRA，用户没提就保持原样；按标注、笔记和简介挑选，强度优先用推荐强度，新 LoRA 的触发词写进提示词。SD1.5、SDXL 系（Illustrious、Pony、NoobAI 等）、Flux 等不同架构的 LoRA 互不通用：自己挑选时选底模和当前模型相符的；用户点名要用的照常装上，底模明显不符时在回复里提一句。
有 refresh_loras / annotate_lora 时：用户说放了新 LoRA 或列表里找不到时先 refresh_loras；读懂简介或出图确认了效果后，用 annotate_lora 记下简短笔记（用途、效果、推荐强度、注意事项），方便以后的新会话直接使用；用户告诉你某个 LoRA 的触发词、用途、推荐强度或效果时，同一批调用 annotate_lora 记下；只记确认过的内容，不写猜测。
画风/画师字段在 inspect_workflow 中标为 role="画风/画师"，用户要求换画风或画师时用 patch_workflow 修改；用户说保存、记住、以后都用、设为默认时，submit_workflow 传 save_defaults=true，设置页会同步。
有 save_character_profile 时：用户介绍了词库里没有的新角色、要求记住某个角色，或给已登记角色补充新服装/外貌时，把整理好的英文绘画标签写入词库；不要擅自覆盖用户没要求改动的已有档案。
工具报错时按错误修正参数再试，不要重复同样的错误调用。只聊天不出图时，不调用工具，直接按系统中的格式回复；只查看或整理 LoRA 资料、不出图时，用完 LoRA 工具后不调用 submit_workflow，直接按系统中的格式回复。
""".trim()

        private fun tool(name: String, description: String, parameters: Map<String, Any>) =
            ChatToolDefinition(ChatFunctionDefinition(name, description, parameters))
        private fun obj(properties: Map<String, Any>, vararg required: String): Map<String, Any> =
            mapOf("type" to "object", "properties" to properties, "required" to required.toList(), "additionalProperties" to false)
        private fun text(description: String) = mapOf("type" to "string", "description" to description)
        private fun array(items: Map<String, Any>, max: Int) = mapOf("type" to "array", "items" to items, "maxItems" to max)
    }
}
