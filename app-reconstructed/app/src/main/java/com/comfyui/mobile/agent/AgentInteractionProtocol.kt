package com.comfyui.mobile.agent

import com.comfyui.mobile.data.AgentReplyParser
import com.comfyui.mobile.model.AgentCharacterSelection
import com.comfyui.mobile.model.AgentQuestionInvocation
import com.comfyui.mobile.network.ChatFunctionDefinition
import com.comfyui.mobile.network.ChatToolCall
import com.comfyui.mobile.network.ChatToolDefinition
import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class AgentProfileTagOverride(val characterId: String, val tag: String, val sourceQuote: String)
data class AgentTaskPlan(
    val summary: String,
    val outfits: List<AgentCharacterSelection> = emptyList(),
    val profileOverrides: List<AgentProfileTagOverride> = emptyList()
)

/** First-class actions, not examples buried inside drawing instructions. */
object AgentInteractionProtocol {
    const val ASK = "ask_user_question"
    const val CONTINUE = "continue_task"

    private fun obj(properties: Map<String, Any>, vararg required: String): Map<String, Any> =
        mapOf("type" to "object", "properties" to properties, "required" to required.toList(),
            "additionalProperties" to false)
    private fun text(description: String) = mapOf("type" to "string", "description" to description)
    private fun array(items: Map<String, Any>, max: Int) =
        mapOf("type" to "array", "items" to items, "maxItems" to max)

    val askTool = ChatToolDefinition(ChatFunctionDefinition(ASK,
        "向用户询问会实质改变结果的冲突、选择或缺失信息。调用后立即暂停，等用户回答再继续；不猜答案。",
        obj(mapOf(
            "reply" to text("简短说明为什么需要用户决定，不写提示词"),
            "questions" to (array(obj(mapOf(
                "id" to text("稳定且唯一的问题 ID，回答会原样带回"),
                "question" to text("只问尚未确认、影响本轮结果的具体问题"),
                "options" to array(obj(mapOf(
                    "id" to text("稳定且唯一的选项 ID"),
                    "label" to text("用户看得懂的名称"),
                    "description" to text("一句话说明区别或取舍；不堆绘画标签")
                ), "id", "label"), 8),
                "multi_select" to mapOf("type" to "boolean", "description" to "是否允许多选；互斥款式为 false"),
                "placeholder" to text("自由填写的提示；没有 options 时是填写题")
            ), "id", "question"), 4) + ("minItems" to 1))
        ), "questions")))

    val continueTool = ChatToolDefinition(ChatFunctionDefinition(CONTINUE,
        "任务已可执行才继续：必要选择已确认、用户明确交给你决定，或只是无关紧要的默认值。未解决的重要冲突应调用 ask_user_question。",
        obj(mapOf(
            "summary" to text("简短的执行结论与已确认选择，不写推理过程或完整提示词"),
            "outfit_selections" to array(obj(mapOf(
                "character_id" to text("本轮已关联角色的真实 ID"),
                "outfit_id" to text("本轮采用的已登记服装 ID"),
                "custom_outfit" to mapOf("type" to "boolean")
            ), "character_id"), 20),
            "profile_overrides" to array(obj(mapOf(
                "character_id" to text("本轮已关联角色的真实 ID"),
                "tag" to text("因用户明确修改而不再采用的原档案绘画词；不能删除角色身份来猜另一个角色"),
                "source_quote" to text("逐字引用用户原任务或已确认答案中的修改依据，不能引用 AI 的猜测")
            ), "character_id", "tag", "source_quote"), 80)
        ), "summary")))
    val decisionTools = listOf(askTool, continueTool)

    fun contextReserve(finalSystem: String, decisionSystem: String): Int =
        (decisionSystem.toByteArray(Charsets.UTF_8).size - finalSystem.toByteArray(Charsets.UTF_8).size).coerceAtLeast(0) +
            com.google.gson.Gson().toJson(decisionTools).toByteArray(Charsets.UTF_8).size + 2048

    fun decisionSystem(persona: String, facts: String, mode: AgentMode): String = buildString {
        appendLine("【应用决策协议】")
        appendLine("本步骤选择一个工具动作：ask_user_question 提问并等待用户；continue_task 继续到正式答复或提示词编写。字段按工具定义填写。")
        appendLine("当前模式：${if (mode == AgentMode.DRAW) "绘图" else "聊天，不执行出图"}。本步骤不输出最终 prompt/negative_prompt。")
        if (persona.isNotBlank()) {
            appendLine(AgentPromptPolicy.RULE_PRIORITY)
            appendLine("【用户导入的对话规则】")
            appendLine(persona)
        }
        if (facts.isNotBlank()) appendLine(facts)
        append("【本步骤最终动作】调用 ask_user_question 或 continue_task 二选一；正式答复和提示词由下一步骤输出。")
    }

    val textDecisionContract = """
【接口兼容动作协议】本服务本轮使用 JSON 动作封装，不调用原生工具。
只输出一个完整 JSON：需要提问为 {"action":"ask_user","reply":"原因","questions":[{"id":"q1","question":"具体问题","options":[{"id":"a","label":"选择","description":"区别"}],"multi_select":false}]}；
可以继续为 {"action":"continue_task","summary":"已确认的执行结论","outfit_selections":[],"profile_overrides":[]}。
此步不是写图像提示词；普通文字提问或只返回 prompt/negative_prompt 都不是有效决策。
""".trimIndent()

    fun question(arguments: JsonObject, call: ChatToolCall? = null): AgentReply {
        val values = arguments["questions"]?.takeIf { it.isJsonArray }?.asJsonArray
            ?: error("questions 必须为数组")
        require(values.size() in 1..4) { "每批需要 1–4 个问题" }
        val ids = mutableSetOf<String>()
        values.forEach { value ->
            require(value.isJsonObject) { "问题必须为对象" }
            val item = value.asJsonObject
            val id = string(item, "id")
            require(id.isNotBlank() && id.length <= 80 && ids.add(id)) { "问题 ID 缺失或重复" }
            val question = string(item, "question").ifBlank { string(item, "prompt") }
            require(question.isNotBlank() && question.length <= 2000) { "问题内容缺失或过长" }
            item["options"]?.takeUnless { it.isJsonNull }?.let { options ->
                require(options.isJsonArray && options.asJsonArray.size() <= 8) { "选项格式错误" }
                val optionIds = mutableSetOf<String>()
                options.asJsonArray.forEach { option ->
                    require(option.isJsonObject) { "选项必须为对象" }
                    val optionId = string(option.asJsonObject, "id")
                    require(optionId.isNotBlank() && optionId.length <= 80 && optionIds.add(optionId) &&
                        string(option.asJsonObject, "label").isNotBlank()) { "选项 ID 或名称缺失/重复" }
                }
            }
        }
        val envelope = arguments.deepCopy().apply { addProperty("action", "ask_user") }
        val parsed = AgentReplyParser.parse(envelope.toString(), preserveNaturalLanguage = true)
        val clarification = checkNotNull(parsed.clarification)
        require(clarification.questions?.size == values.size()) { "问题结构未完整解析" }
        return AgentReply(parsed.reply.ifBlank { "请确认以下问题，再继续本轮任务。" },
            clarification = clarification.copy(toolInvocation = call?.let {
                AgentQuestionInvocation(it.id, it.function.name, it.function.arguments)
            }), action = AgentAction.ASK_USER)
    }

    fun plan(arguments: JsonObject): AgentTaskPlan {
        val summary = string(arguments, "summary").ifBlank { "按用户本轮任务继续" }.take(2000)
        val outfits = arguments["outfit_selections"]?.takeIf { it.isJsonArray }?.asJsonArray
            ?.take(20)?.mapNotNull { value ->
                if (!value.isJsonObject) return@mapNotNull null
                val item = value.asJsonObject
                val character = string(item, "character_id")
                val outfit = string(item, "outfit_id").ifBlank { null }
                val custom = item["custom_outfit"]?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean } == true
                if (character.isBlank() || (outfit == null && !custom) || (outfit != null && custom)) null
                else AgentCharacterSelection(character, outfit, custom)
            }.orEmpty()
        val overrides = arguments["profile_overrides"]?.takeIf { it.isJsonArray }?.asJsonArray
            ?.take(80)?.mapNotNull { value ->
                if (!value.isJsonObject) return@mapNotNull null
                val item = value.asJsonObject
                val id = string(item, "character_id")
                val tag = string(item, "tag")
                val quote = string(item, "source_quote")
                if (id.isBlank() || tag.isBlank() || quote.isBlank()) null else AgentProfileTagOverride(id, tag, quote)
            }.orEmpty()
        return AgentTaskPlan(summary, outfits, overrides)
    }

    /** Only a complete JSON object (optionally fenced) is a valid decision; prose is never mined. */
    fun objectValue(raw: String): JsonObject? = runCatching {
        JsonParser.parseString(AgentReplyParser.stripFences(raw)).takeIf { it.isJsonObject }?.asJsonObject
    }.getOrNull()
    fun string(obj: JsonObject, key: String): String =
        obj[key]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim().orEmpty()
}
