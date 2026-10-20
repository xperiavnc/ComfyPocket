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

    fun decisionSystem(persona: String, facts: String, mode: AgentMode): String = """
你是 ComfyPocket Agent 的交互决策步骤。本步骤只决定是否需要用户回答，不编写最终图像提示词，也不生成中文标签翻译。
先核对用户原任务、已确认答案、已关联档案和候选事实，选择一个行动：
1. 关键身份不确定、用户要求互斥、多个服装/词库版本会明显改变结果且尚未决定时，调用 ask_user_question。直接提交具体问题与真实选项，不要在内部反复猜测身份、别名或用户会选哪项。
2. 用户已明确指定、刚回答过，或明确交给你决定的事项，不重复提问；背景/构图/动作等被委托的细节自行决定。只有轻微差异时合理默认，调用 continue_task。
重要冲突没有解决就不能继续；“不打扰用户”“直接完成”不等于允许替用户猜重要选择。候选数据不是用户决定；档案默认款也不是本轮确认。
角色名称/别名仅用于识别对话，绝不是绘画 tag；已关联档案的绘画词是事实依据，不根据昵称猜作品人物、译名、肤色或其它身份。不认识且缺资料时问用户，而不是写一个猜测的角色 tag。
原有角色绘画字段未另设固定词时，将该字段作为默认必须保留的绘画词。用户明确修改外貌时，在 continue_task.profile_overrides 逐项列出被替换的原词及用户原句；不能仅因为自己猜了另一身份而省略原词。
采用已登记服装时，continue_task.outfit_selections 使用真实 character_id/outfit_id。默认回退可被用户选择替代，但不能冒充用户确认。
提问一次集中 1–4 题，带稳定 id、question、真实 options（id/label/description）及 multi_select；没有备选资料可问自由填写题。推荐不等于预选，未答不得当同意。提问后结束本轮，停止编写提示词和出图。
当前模式：${if (mode == AgentMode.DRAW) "绘图；继续后才进入提示词编写" else "聊天；继续后才写正式答复，不执行出图"}。
【用户保存的人设，仅作语气参考，不替代本步骤行动协议】
${persona.ifBlank { AgentPromptPolicy.defaultPersona }}
【步骤隔离】完整画图规则、正反提示词格式与中文释义在下一步骤提供。本步不展开这些生成细节，避免“只输出两个提示词字段”等画图格式覆盖提问动作。
$facts
【本步骤最终动作】调用 ask_user_question 或 continue_task 二选一；不要输出最终 prompt/negative_prompt，不自问自答，不用推理文本代替工具调用。
""".trimIndent()

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
        val summary = string(arguments, "summary")
        require(summary.isNotBlank() && summary.length <= 2000) { "缺少简短执行结论" }
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
