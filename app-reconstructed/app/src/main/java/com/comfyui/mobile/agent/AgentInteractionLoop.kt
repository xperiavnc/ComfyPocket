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
        onProgress: suspend (AgentProgress) -> Unit = {}
    ): AgentInteractionOutcome {
        var textProtocol = initialTextProtocol
        var autoToolChoice = initialAutoToolChoice
        val transcript = messages.toMutableList()
        val decision = messages.toMutableList().apply {
            this[0] = ChatMessageDto("system", decisionSystem)
        }
        suspend fun request(input: List<ChatMessageDto>, deciding: Boolean): LlmCompletionOutput {
            checkCurrent()
            onProgress(AgentProgress("", "", if (deciding) "正在核对任务与待确认项…" else "正在编写结果…"))
            suspend fun execute(): LlmCompletionOutput {
                val send = if (!textProtocol) input.mapIndexed { index, message ->
                    if (index == 0 && !deciding) message.copy(content = message.content +
                        "\n【当前交互工具】本步可调用 ask_user_question；如果发现新的必要冲突，调用它并等待，不要只在普通回复中问完就继续出图。")
                    else message
                } else input.mapIndexed { index, message ->
                    when {
                        index == 0 && deciding -> message.copy(content = message.content + "\n" + AgentInteractionProtocol.textDecisionContract)
                        !message.tool_calls.isNullOrEmpty() -> ChatMessageDto("assistant", gson.toJson(mapOf(
                            "tool_calls" to message.tool_calls)))
                        message.role == "tool" -> ChatMessageDto("user", "【先前工具结果数据】\n${message.content}")
                        else -> message
                    }
                }
                return model.complete(AgentModelRequest(send,
                    if (textProtocol) null else if (deciding) AgentInteractionProtocol.decisionTools
                        else listOf(AgentInteractionProtocol.askTool),
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
                }
                // Only an explicit parameter rejection is retried, never a timeout/empty/truncated result.
                execute()
            }
        }
        fun repair(target: MutableList<ChatMessageDto>, output: LlmCompletionOutput, error: String, deciding: Boolean) {
            if (output.toolCalls.isNotEmpty()) {
                target += assistant(output)
                output.toolCalls.forEach { call -> target += ChatMessageDto("tool",
                    gson.toJson(mapOf("status" to "invalid_action", "error" to error)), tool_call_id = call.id) }
            } else {
                target += ChatMessageDto("assistant", output.content)
                target += ChatMessageDto("user",
                    "【动作结构修正，仅此一次】$error\n" +
                        if (deciding) AgentInteractionProtocol.textDecisionContract
                        else "需要用户决定时调用 ask_user_question；否则返回完整结果。不要用推理过程代替动作。")
            }
        }
        var ready: LlmCompletionOutput? = null
        var plan: AgentTaskPlan? = null
        for (attempt in 0..1) {
            val output = request(decision, true)
            checkCurrent()
            try {
                if (output.toolCalls.size > 1) error("一次只选择一个动作，将相关问题合并为同一批")
                val call = output.toolCalls.singleOrNull()
                val obj = AgentInteractionProtocol.objectValue(call?.function?.arguments ?: output.content)
                    ?: error("请返回明确的 ask_user_question 或 continue_task 动作，而不是普通文字或推理")
                val action = call?.function?.name ?: AgentInteractionProtocol.string(obj, "action")
                if (action in listOf(AgentInteractionProtocol.ASK, "ask_user")) {
                    val question = AgentInteractionProtocol.question(obj, call).copy(thinking = output.reasoning.orEmpty())
                    if (call == null) { textProtocol = true; onTextProtocol() }
                    return AgentInteractionOutcome(question = question)
                }
                require(action == AgentInteractionProtocol.CONTINUE) { "尚未形成可执行决策；不要直接编写提示词" }
                plan = AgentInteractionProtocol.plan(obj)
                ready = output
                if (call == null) { textProtocol = true; onTextProtocol() }
                break
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (attempt == 1 || output.content.isBlank() && output.toolCalls.isEmpty())
                    throw AgentExecutionException("模型未形成有效交互决策，本轮未修改工作流。${e.message.orEmpty()}")
                repair(decision, output, e.message.orEmpty(), true)
            }
        }
        val decisionOutput = checkNotNull(ready)
        if (decisionOutput.toolCalls.isNotEmpty()) {
            transcript += assistant(decisionOutput)
            transcript += ChatMessageDto("tool", gson.toJson(mapOf(
                "status" to "ready", "next" to "compose_result", "plan" to plan,
                "instruction" to "按已确认选择继续。新发现重要冲突可调用 ask_user_question，不能猜身份或漏掉档案触发词。"
            )), tool_call_id = decisionOutput.toolCalls.single().id)
        } else transcript += ChatMessageDto("user", "【智能体已完成的执行决策，仅作本轮数据】\n${gson.toJson(plan)}")
        for (attempt in 0..1) {
            val output = request(transcript, false)
            checkCurrent()
            if (output.toolCalls.isEmpty()) {
                // Legacy JSON actions remain supported; no question is inferred from natural-language keywords.
                val parsed = AgentReplyParser.parse(output.content, preserveNaturalLanguage = true)
                if (parsed.clarification != null) return AgentInteractionOutcome(
                    question = AgentReply(parsed.reply.ifBlank { parsed.clarification.question },
                        thinking = reasoning(decisionOutput, output), clarification = parsed.clarification,
                        action = AgentAction.ASK_USER), plan = plan)
                if (output.content.isBlank()) throw AgentExecutionException("模型只返回了推理，尚无最终结果；本轮未提交。")
                return AgentInteractionOutcome(output = output.copy(reasoning = reasoning(decisionOutput, output)), plan = plan)
            }
            try {
                require(output.toolCalls.size == 1) { "一次只选择一个提问动作" }
                val call = output.toolCalls.single()
                require(call.function.name == AgentInteractionProtocol.ASK) { "本阶段只开放 ask_user_question" }
                val obj = AgentInteractionProtocol.objectValue(call.function.arguments) ?: error("问题参数不是完整对象")
                return AgentInteractionOutcome(question = AgentInteractionProtocol.question(obj, call)
                    .copy(thinking = reasoning(decisionOutput, output)), plan = plan)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (attempt == 1) throw AgentExecutionException("模型提问格式仍有误，本轮未提交。${e.message.orEmpty()}")
                repair(transcript, output, e.message.orEmpty(), false)
            }
        }
        error("交互循环未返回结果")
    }

    private fun assistant(output: LlmCompletionOutput) = ChatMessageDto("assistant",
        output.content.takeIf(String::isNotBlank), output.reasoning, output.toolCalls)
    private fun reasoning(first: LlmCompletionOutput, last: LlmCompletionOutput): String =
        listOfNotNull(first.reasoning?.takeIf(String::isNotBlank), last.reasoning?.takeIf(String::isNotBlank)).joinToString("\n\n")

    companion object {
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
