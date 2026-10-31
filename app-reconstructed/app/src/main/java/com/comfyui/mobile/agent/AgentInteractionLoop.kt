package com.comfyui.mobile.agent

import com.comfyui.mobile.data.AgentReplyParser
import com.comfyui.mobile.data.LlmCompletionOutput
import com.comfyui.mobile.data.LlmException
import com.comfyui.mobile.network.ChatMessageDto
import com.comfyui.mobile.network.ChatToolDefinition
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException

data class AgentModelRequest(
    val messages: List<ChatMessageDto>,
    val tools: List<ChatToolDefinition>?,
    val toolChoice: String?,
    val deciding: Boolean
)
fun interface AgentInteractionModel {
    suspend fun complete(request: AgentModelRequest, progress: suspend (AgentProgress) -> Unit): LlmCompletionOutput
}
data class AgentInteractionOutcome(
    val output: LlmCompletionOutput? = null,
    val question: AgentReply? = null,
    val plan: AgentTaskPlan? = null
)

/**
 * A bounded decide -> ask/wait OR compose loop. Only the model selects questions.
 * No workflow, image upload or suspended human wait exists inside this component.
 */
class AgentInteractionLoop(
    private val model: AgentInteractionModel,
    private val initialTextProtocol: Boolean = false,
    private val onTextProtocol: () -> Unit = {},
    private val initialAutoToolChoice: Boolean = false,
    private val onAutoToolChoice: () -> Unit = {}
) {
    private val gson = Gson()

    suspend fun run(
        messages: List<ChatMessageDto>,
        decisionSystem: String,
        checkCurrent: suspend () -> Unit = {},
        onProgress: suspend (AgentProgress) -> Unit = {},
        /** Checks a plain-text result; a non-null error gets one repair round before giving up. */
        validate: ((String) -> String?)? = null
    ): AgentInteractionOutcome {
        var textProtocol = initialTextProtocol
        var autoToolChoice = initialAutoToolChoice
        // One append-only transcript for the whole turn. The system message, history and tool list are
        // identical for the decision, repair and result requests, so the provider prompt cache can reuse
        // the entire prefix; each step only appends its own instruction at the end.
        val transcript = messages.toMutableList()
        transcript += ChatMessageDto("user", decisionSystem)
        fun ensureTextContract(prefix: String = "") {
            if (transcript.none { it.content?.contains(TEXT_CONTRACT_MARK) == true }) {
                transcript += ChatMessageDto("user", prefix + AgentInteractionProtocol.textDecisionContract)
            } else if (prefix.isNotEmpty()) transcript += ChatMessageDto("user", prefix.trimEnd())
        }
        if (textProtocol) ensureTextContract()
        suspend fun request(deciding: Boolean): LlmCompletionOutput {
            checkCurrent()
            onProgress(AgentProgress("", "", if (deciding) "正在核对任务与待确认项…" else "正在编写结果…"))
            suspend fun execute(): LlmCompletionOutput {
                val send = if (!textProtocol) transcript.toList() else transcript.map { message ->
                    when {
                        !message.tool_calls.isNullOrEmpty() -> ChatMessageDto("assistant", gson.toJson(mapOf(
                            "tool_calls" to message.tool_calls)))
                        message.role == "tool" -> ChatMessageDto("user", "【先前工具结果数据】\n${message.content}")
                        else -> message
                    }
                }
                return model.complete(AgentModelRequest(send,
                    if (textProtocol) null else AgentInteractionProtocol.decisionTools,
                    if (textProtocol || autoToolChoice) null else if (deciding) "required" else "auto", deciding)) { progress ->
                    // Decision content is an action, not a draft final answer.
                    onProgress(if (deciding) progress.copy(content = "", activity = "正在核对任务与待确认项…") else progress)
                }
            }
            return try { execute() } catch (e: LlmException) {
                if (textProtocol || !explicitlyRejectsTools(e)) throw e
                checkCurrent()
                if (!autoToolChoice && rejectsRequiredChoice(e)) {
                    // Some reasoning APIs support native tools but only automatic tool selection.
                    autoToolChoice = true
                    onAutoToolChoice()
                } else {
                    textProtocol = true
                    onTextProtocol()
                    if (deciding) ensureTextContract()
                }
                // Only an explicit parameter rejection is retried, never a timeout/empty/truncated result.
                execute()
            }
        }
        fun repair(output: LlmCompletionOutput, error: String, deciding: Boolean) {
            if (output.toolCalls.isNotEmpty()) {
                transcript += assistant(output)
                output.toolCalls.forEach { call -> transcript += ChatMessageDto("tool",
                    gson.toJson(mapOf("status" to "invalid_action", "error" to error)), tool_call_id = call.id) }
            } else {
                transcript += ChatMessageDto("assistant", output.content)
                transcript += ChatMessageDto("user",
                    "【动作结构修正，仅此一次】$error\n" +
                        if (deciding) AgentInteractionProtocol.textDecisionContract
                        else "需要用户决定时调用 ask_user_question；否则返回完整结果。不要用推理过程代替动作。")
            }
        }
        var ready: LlmCompletionOutput? = null
        var plan: AgentTaskPlan? = null
        for (attempt in 0..1) {
            val output = request(true)
            checkCurrent()
            try {
                if (output.toolCalls.size > 1) error("一次只选择一个动作，将相关问题合并为同一批")
                val call = output.toolCalls.singleOrNull()
                val obj = AgentInteractionProtocol.objectValue(call?.function?.arguments ?: output.content)
                    ?: error("请返回明确的 ask_user_question 或 continue_task 动作，而不是普通文字或推理")
                val action = call?.function?.name ?: AgentInteractionProtocol.string(obj, "action").ifBlank {
                    if (obj.has("questions")) AgentInteractionProtocol.ASK else AgentInteractionProtocol.CONTINUE
                }
                if (action in listOf(AgentInteractionProtocol.ASK, "ask_user")) {
                    val question = AgentInteractionProtocol.question(obj, call).copy(thinking = output.reasoning.orEmpty())
                    if (call == null && !textProtocol) { textProtocol = true; onTextProtocol() }
                    return AgentInteractionOutcome(question = question)
                }
                require(action == AgentInteractionProtocol.CONTINUE) { "尚未形成可执行决策；不要直接编写提示词" }
                plan = AgentInteractionProtocol.plan(obj)
                ready = output
                if (call == null && !textProtocol) { textProtocol = true; onTextProtocol() }
                break
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (attempt == 1) {
                    // The decision step is only a gate. If the model still can't produce an action,
                    // go on to the result step (which can still ask) rather than failing the turn.
                    onProgress(AgentProgress("", "", "决策格式未通过，直接编写结果…"))
                    break
                }
                if (output.toolCalls.isEmpty()) {
                    // A compatible endpoint/model may accept tools but ignore required tool_choice.
                    // Retry once with a model-selected JSON action; never mine prose/reasoning for questions
                    // or replay the failed model text into the retry context.
                    if (!textProtocol) {
                        textProtocol = true
                        onTextProtocol()
                    }
                    ensureTextContract("【结构化动作兼容重试】上次响应没有形成可执行动作。请遵循 JSON 动作协议，" +
                        "只返回 ask_user 或 continue_task 对象；不要输出普通文字、分析过程或最终提示词。\n")
                } else {
                    repair(output, e.message.orEmpty(), true)
                }
            }
        }
        val decisionOutput = ready ?: LlmCompletionOutput("")
        if (ready == null) {
            plan = AgentTaskPlan(FALLBACK_SUMMARY)
            transcript += ChatMessageDto("user", "【执行说明】按用户本轮原任务直接完成；" +
                "若存在会实质改变结果的冲突，调用 ask_user_question 或返回 ask_user 动作，不要猜。\n" + COMPOSE_STEP)
        } else if (decisionOutput.toolCalls.isNotEmpty()) {
            transcript += assistant(decisionOutput)
            transcript += ChatMessageDto("tool", gson.toJson(mapOf(
                "status" to "ready", "next" to "compose_result", "plan" to plan,
                "instruction" to "按已确认选择继续。新发现重要冲突可调用 ask_user_question，不能猜身份或漏掉档案触发词。" + COMPOSE_STEP
            )), tool_call_id = decisionOutput.toolCalls.single().id)
        } else transcript += ChatMessageDto("user",
            "【智能体已完成的执行决策，仅作本轮数据】\n${gson.toJson(plan)}\n$COMPOSE_STEP")
        for (attempt in 0..1) {
            val output = request(false)
            checkCurrent()
            if (output.toolCalls.isEmpty()) {
                // Legacy JSON actions remain supported; no question is inferred from natural-language keywords.
                val parsed = AgentReplyParser.parse(output.content, preserveNaturalLanguage = true)
                if (parsed.clarification != null) return AgentInteractionOutcome(
                    question = AgentReply(parsed.reply.ifBlank { parsed.clarification.question },
                        thinking = reasoning(decisionOutput, output), clarification = parsed.clarification,
                        action = AgentAction.ASK_USER), plan = plan)
                if (output.content.isBlank()) throw AgentExecutionException("模型只返回了推理，尚无最终结果；本轮未提交。")
                val invalid = if (attempt == 0) validate?.invoke(output.content) else null
                if (invalid != null) {
                    onProgress(AgentProgress("", "", "结果格式有误，正在让模型修正…"))
                    transcript += ChatMessageDto("assistant", output.content)
                    transcript += ChatMessageDto("user", RESULT_REPAIR_PREFIX + invalid + RESULT_REPAIR_RULES)
                    continue
                }
                return AgentInteractionOutcome(output = output.copy(reasoning = reasoning(decisionOutput, output)), plan = plan)
            }
            try {
                require(output.toolCalls.size == 1) { "一次只选择一个提问动作" }
                val call = output.toolCalls.single()
                require(call.function.name == AgentInteractionProtocol.ASK) {
                    "决策已经完成，本步骤只可调用 ask_user_question，或直接返回最终结果"
                }
                val obj = AgentInteractionProtocol.objectValue(call.function.arguments) ?: error("问题参数不是完整对象")
                return AgentInteractionOutcome(question = AgentInteractionProtocol.question(obj, call)
                    .copy(thinking = reasoning(decisionOutput, output)), plan = plan)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (attempt == 1) throw AgentExecutionException("模型提问格式仍有误，本轮未提交。${e.message.orEmpty()}")
                repair(output, e.message.orEmpty(), false)
            }
        }
        error("交互循环未返回结果")
    }

    private fun assistant(output: LlmCompletionOutput) = ChatMessageDto("assistant",
        output.content.takeIf(String::isNotBlank), output.reasoning, output.toolCalls)
    private fun reasoning(first: LlmCompletionOutput, last: LlmCompletionOutput): String =
        listOfNotNull(first.reasoning?.takeIf(String::isNotBlank), last.reasoning?.takeIf(String::isNotBlank)).joinToString("\n\n")

    companion object {
        const val FALLBACK_SUMMARY = "按用户本轮原任务执行（决策动作未通过格式校验，已跳过）"
        private const val TEXT_CONTRACT_MARK = "【接口兼容动作协议】"
        /** Appended (never merged into the system message) so the cached prefix stays byte-identical. */
        const val COMPOSE_STEP = "【当前步骤：编写结果】按系统中的输出格式返回最终结果。" +
            "如果发现新的必要冲突，调用 ask_user_question 并等待，不要只在普通回复中问完就继续出图；不要再调用 continue_task。"
        const val RESULT_REPAIR_PREFIX = "【结果格式修正，仅此一次】上一条结果无法解析："
        const val RESULT_REPAIR_RULES = "。请按原任务重新输出一个完整、合法的 JSON 对象：" +
            "字符串内的双引号写成 \\\"，不要在字符串里直接换行，不要截断，JSON 前后不要附加解释或代码块之外的文字。"
        fun explicitlyRejectsTools(error: LlmException): Boolean {
            if (error.httpCode !in listOf(400, 422, 501)) return false
            val detail = error.technicalDetail.orEmpty().lowercase()
            return Regex("tool_choice|parallel_tool_calls|\\btools\\b|function calling").containsMatchIn(detail) &&
                Regex("not support|unsupported|not allowed|unknown (?:field|parameter)|unrecognized|not (?:enabled|available)|extra inputs? (?:are )?not permitted|only.*(?:auto|none)")
                    .containsMatchIn(detail)
        }
        private fun rejectsRequiredChoice(error: LlmException): Boolean =
            "tool_choice" in error.technicalDetail.orEmpty().lowercase() &&
                !Regex("(?:\\btools\\b|function calling).{0,30}(?:not support|unsupported|not enabled)")
                    .containsMatchIn(error.technicalDetail.orEmpty().lowercase())
    }
}
