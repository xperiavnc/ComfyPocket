package com.comfyui.mobile.agent

import com.comfyui.mobile.data.LlmCompletionOutput
import com.comfyui.mobile.network.ChatMessageDto
import com.comfyui.mobile.network.ChatToolCall
import com.comfyui.mobile.network.ChatToolDefinition
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException

/** One result fed back to the model as a role=tool message. */
data class AgentToolResult(
    val ok: Boolean,
    val data: Any? = null,
    val error: String? = null,
    /** A successful terminal tool (submit_workflow) ends the loop with this content as the final answer. */
    val finalContent: String? = null
)

/** One executed tool step, kept for the progress line and the reasoning trace. */
data class AgentToolStep(val index: Int, val tool: String, val label: String, val ok: Boolean, val note: String)

/** The tools a loop may call. Implementations validate their own arguments and never throw for bad input. */
interface AgentToolbox {
    val definitions: List<ChatToolDefinition>
    fun label(name: String): String = name
    suspend fun call(name: String, arguments: JsonObject): AgentToolResult
}

/**
 * A native tool-calling loop: request → tool calls → role=tool results → request …, bounded by [maxSteps]
 * model requests. Tool errors are returned to the model so it can correct itself; only cancellation,
 * transport failures and an exhausted step budget end the turn early. ask_user_question pauses the loop.
 */
class AgentToolLoop(
    private val model: AgentInteractionModel,
    private val toolbox: AgentToolbox,
    private val maxSteps: Int = DEFAULT_MAX_STEPS
) {
    private val gson = Gson()

    suspend fun run(
        messages: List<ChatMessageDto>,
        instruction: String,
        checkCurrent: suspend () -> Unit = {},
        onProgress: suspend (AgentProgress) -> Unit = {},
        onStep: suspend (AgentToolStep) -> Unit = {},
        /** Checks a plain-text final answer; a non-null error is sent back as a correction. */
        validate: ((String) -> String?)? = null
    ): AgentInteractionOutcome {
        val tools = listOf(AgentInteractionProtocol.askTool) + toolbox.definitions
        // Append-only: system, history and tool list stay byte-identical across steps for prompt caching.
        val transcript = messages.toMutableList()
        transcript += ChatMessageDto("user", instruction)
        val steps = mutableListOf<AgentToolStep>()
        val reasoning = StringBuilder()
        var corrections = 0
        for (round in 1..maxSteps) {
            checkCurrent()
            val stepTitle = "第 $round/$maxSteps 步"
            val before = trace(reasoning, steps)
            onProgress(AgentProgress(before, "", "$stepTitle · 正在思考下一步…"))
            val output = model.complete(AgentModelRequest(transcript.toList(), tools, "auto", deciding = false)) { progress ->
                val live = listOf(before, progress.reasoning).filter(String::isNotBlank).joinToString("\n\n")
                onProgress(progress.copy(reasoning = live, activity = "$stepTitle · 正在思考下一步…"))
            }
            checkCurrent()
            output.reasoning?.takeIf(String::isNotBlank)?.let { if (reasoning.isNotEmpty()) reasoning.append("\n\n"); reasoning.append(it) }
            if (output.toolCalls.isEmpty()) {
                val content = output.content
                val problem = when {
                    content.isBlank() -> "没有返回工具调用或结果。请调用工具继续，或调用 submit_workflow 提交。"
                    else -> validate?.invoke(content)
                }
                if (problem == null) return finish(output.copy(reasoning = trace(reasoning, steps)))
                if (++corrections > MAX_CORRECTIONS) throw AgentExecutionException("模型多次未按工具协议返回结果，本轮未提交。$problem")
                transcript += ChatMessageDto("assistant", content.ifBlank { null }, output.reasoning)
                transcript += ChatMessageDto("user", "【工具协议修正】$problem")
                continue
            }
            transcript += ChatMessageDto("assistant", output.content.takeIf(String::isNotBlank), output.reasoning, output.toolCalls)
            val ask = output.toolCalls.firstOrNull { it.function.name == AgentInteractionProtocol.ASK }
            if (ask != null) {
                val parsed = runCatching {
                    val obj = AgentInteractionProtocol.objectValue(ask.function.arguments) ?: error("问题参数不是完整 JSON 对象")
                    AgentInteractionProtocol.question(obj, ask)
                }
                parsed.getOrNull()?.takeIf { output.toolCalls.size == 1 }?.let {
                    return AgentInteractionOutcome(question = it.copy(thinking = trace(reasoning, steps)))
                }
                val error = if (output.toolCalls.size > 1) "ask_user_question 需要单独调用，不能和其他工具同批"
                    else parsed.exceptionOrNull()?.message ?: "问题格式错误"
                output.toolCalls.forEach { call -> transcript += toolMessage(call, AgentToolResult(false, error = error)) }
                continue
            }
            var final: String? = null
            for (call in output.toolCalls) {
                checkCurrent()
                val label = toolbox.label(call.function.name)
                onProgress(AgentProgress(trace(reasoning, steps), "", "$stepTitle · $label"))
                val result = if (final != null) AgentToolResult(false, error = "已提交，本批后续工具未执行")
                else try {
                    val args = AgentInteractionProtocol.objectValue(call.function.arguments.ifBlank { "{}" })
                    if (args == null) AgentToolResult(false, error = "参数不是完整 JSON 对象")
                    else if (toolbox.definitions.none { it.function.name == call.function.name })
                        AgentToolResult(false, error = "未知工具：${call.function.name}")
                    else toolbox.call(call.function.name, args)
                } catch (e: CancellationException) { throw e
                } catch (e: AgentExecutionException) { throw e
                } catch (e: Exception) { AgentToolResult(false, error = e.message ?: e.javaClass.simpleName) }
                checkCurrent()
                val step = AgentToolStep(steps.size + 1, call.function.name, label, result.ok,
                    result.error ?: summary(result.data))
                steps += step
                onStep(step)
                transcript += toolMessage(call, result)
                if (result.ok && result.finalContent != null) final = result.finalContent
            }
            if (final != null) return finish(LlmCompletionOutput(final, trace(reasoning, steps), output.requestId,
                usage = output.usage, firstTokenMillis = output.firstTokenMillis, totalMillis = output.totalMillis))
        }
        throw AgentExecutionException("智能体用完 $maxSteps 步仍未提交结果，本轮未出图。可以把任务拆小一些再试。")
    }

    private fun finish(output: LlmCompletionOutput) = AgentInteractionOutcome(output = output)

    private fun toolMessage(call: ChatToolCall, result: AgentToolResult) = ChatMessageDto("tool",
        gson.toJson(if (result.ok) mapOf("ok" to true, "result" to result.data)
            else mapOf("ok" to false, "error" to result.error, "hint" to "根据错误修正参数后重试，或换一个工具")),
        tool_call_id = call.id)

    private fun summary(data: Any?): String = when (data) {
        null -> "完成"
        is String -> data.take(80)
        is Collection<*> -> "${data.size} 项"
        is Map<*, *> -> (data["summary"] as? String)?.take(80) ?: "完成"
        else -> "完成"
    }

    private fun trace(reasoning: StringBuilder, steps: List<AgentToolStep>): String = buildString {
        append(reasoning)
        if (steps.isNotEmpty()) {
            if (isNotEmpty()) append("\n\n")
            append("【工具步骤】")
            steps.forEach { append("\n${it.index}. ${it.label}：${if (it.ok) "✓" else "✗"} ${it.note}") }
        }
    }

    companion object {
        const val DEFAULT_MAX_STEPS = 8
        private const val MAX_CORRECTIONS = 2
    }
}
