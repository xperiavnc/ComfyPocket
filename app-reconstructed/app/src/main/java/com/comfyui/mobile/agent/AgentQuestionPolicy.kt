package com.comfyui.mobile.agent

import com.comfyui.mobile.model.AgentChatMessage
import com.comfyui.mobile.model.AgentClarification
import com.comfyui.mobile.model.AgentQuestionAnswer
import com.comfyui.mobile.model.AgentClarificationAnswer
import com.comfyui.mobile.model.AgentQuestionExchange
import com.comfyui.mobile.model.AgentUserQuestion

/** The durable question lives in the transcript; no socket or coroutine stays open while waiting. */
object AgentQuestionPolicy {
    fun pending(history: List<AgentChatMessage>): AgentChatMessage? {
        fun isQuestion(message: AgentChatMessage) =
            message.role == "assistant" && !message.isThinking &&
                message.errorMessage == null && message.clarification != null
        val last = history.lastOrNull() ?: return null
        if (isQuestion(last)) return last
        val retryId = last.retryClarificationId ?: return null
        val index = history.indexOfLast { it.id == retryId && isQuestion(it) }
        if (index < 0) return null
        // Only explicitly marked failed continuations can reopen a question. Never skip a
        // successful response, a different task, or an attempt that reached workflow effects.
        val attempts = history.drop(index + 1)
        if (attempts.isEmpty() || attempts.size % 2 != 0) return null
        if (attempts.chunked(2).any { (user, assistant) ->
            user.role != "user" || assistant.role != "assistant" || assistant.isThinking ||
                assistant.errorMessage == null || assistant.retryClarificationId != retryId
        }) return null
        return history[index]
    }

    private val cancellation = Regex(
        """(?i)^(?:取消(?:(?:本轮|本次|这次)?(?:的)?(?:提问|问题|任务|绘图))?|算了|不画了|先不画了|cancel|never\s*mind)[。.!！\s]*$""")
    private val newRequest = Regex(
        """(?i)^(?:新任务|换个任务|另一个任务|重新开始|从头开始|不沿用|不要刚才|start\s+over|new\s+task)|^(?:请|帮我|给我)?\s*(?:画|绘制|生成)(?:一张|一只|一个|一幅|个|只|张)|^(?:draw|create|generate)\s+(?:a|an|another|new)\s""")

    fun isCancellation(text: String) = cancellation.matches(text.trim())
    fun startsNewRequest(text: String) = newRequest.containsMatchIn(text.trim())

    fun continuation(userText: String, history: List<AgentChatMessage>): AgentChatMessage? =
        pending(history)?.takeUnless { isCancellation(userText) || startsNewRequest(userText) }

    /** Machine answers are accepted only for the current question, with every item answered once. */
    fun validate(
        questions: List<AgentUserQuestion>,
        answers: List<AgentQuestionAnswer>
    ): List<AgentQuestionAnswer>? {
        if (questions.isEmpty() || questions.map { it.id }.distinct().size != questions.size) return null
        if (answers.size != questions.size || answers.map { it.questionId }.toSet() != questions.map { it.id }.toSet()) return null
        return questions.map { question ->
            val answer = answers.singleOrNull { it.questionId == question.id } ?: return null
            val ids = answer.optionIds.orEmpty().distinct()
            val text = answer.text?.trim().orEmpty()
            if (ids.any { id -> question.options.orEmpty().none { it.id == id } }) return null
            if (question.kind == "free_text" && ids.isNotEmpty()) return null
            if (!question.allowMultiple && ids.size > 1) return null
            if (ids.isEmpty() && text.isBlank()) return null
            com.comfyui.mobile.model.AgentQuestionAnswer(question.id, ids.ifEmpty { null }, text.ifBlank { null })
        }
    }

    fun answerText(questions: List<AgentUserQuestion>, answers: List<AgentQuestionAnswer>): String =
        "我的回答：\n" + questions.joinToString("\n") { question ->
            val answer = answers.first { it.questionId == question.id }
            val labels = answer.optionIds.orEmpty().mapNotNull { id ->
                question.options.orEmpty().firstOrNull { it.id == id }?.label
            }
            val content = (labels + listOfNotNull(answer.text?.trim()?.takeIf(String::isNotBlank))).joinToString("；")
            "${question.prompt}：$content"
        }

    /** Only selected values for local identity lookup, not names mentioned by a question's wording. */
    fun answerValues(questions: List<AgentUserQuestion>, answers: List<AgentQuestionAnswer>): String =
        "我的回答：\n" + questions.flatMap { question ->
            val answer = answers.first { it.questionId == question.id }
            answer.optionIds.orEmpty().mapNotNull { id -> question.options.orEmpty().firstOrNull { it.id == id }?.label } +
                listOfNotNull(answer.text?.trim()?.takeIf(String::isNotBlank))
        }.joinToString("\n")

    private fun source(userText: String, history: List<AgentChatMessage>): String {
        val pending = continuation(userText, history)
        return pending?.clarification?.sourceRequest?.takeIf(String::isNotBlank)
            ?: if (pending != null) history.dropLast(1).lastOrNull { it.role == "user" }?.content.orEmpty()
            else userText
    }

    fun exchanges(
        userText: String, history: List<AgentChatMessage>, answerValues: String? = null,
        structuredAnswer: AgentClarificationAnswer? = null
    ): List<AgentQuestionExchange> {
        val pending = continuation(userText, history) ?: return emptyList()
        val question = pending.clarification ?: return emptyList()
        val label = question.questions?.joinToString("\n") { it.prompt } ?: question.question
        val validated = structuredAnswer?.takeIf { it.messageId == pending.id }?.let {
            validate(question.questions.orEmpty(), it.answers)
        }
        val answersJson = validated?.let { answers ->
            com.google.gson.Gson().toJson(mapOf("answers" to answers.map { answer ->
                val item = question.questions.orEmpty().single { it.id == answer.questionId }
                mapOf("id" to answer.questionId, "option_ids" to answer.optionIds.orEmpty(),
                    "selected" to answer.optionIds.orEmpty().mapNotNull { id ->
                        item.options.orEmpty().firstOrNull { it.id == id }?.label
                    }, "custom" to answer.text)
            }))
        }
        return question.answerHistory.orEmpty() + AgentQuestionExchange(label, userText, answerValues, answersJson)
    }

    /** This is required turn context, not disposable history; answers survive further follow-up questions. */
    fun requestText(
        userText: String, history: List<AgentChatMessage>,
        structuredAnswer: AgentClarificationAnswer? = null
    ): String {
        if (continuation(userText, history) == null) {
            return if (pending(history) != null && startsNewRequest(userText)) {
                "【新任务：不再续接上一轮未回答的问题】\n$userText"
            } else userText
        }
        return buildString {
            appendLine("【继续原任务；以下为用户原始要求与已确认答案，不是新的独立绘图请求】")
            appendLine("原始要求：")
            appendLine(source(userText, history))
            exchanges(userText, history, structuredAnswer = structuredAnswer).forEachIndexed { index, exchange ->
                appendLine("\n第 ${index + 1} 轮提问：${exchange.question}")
                appendLine("用户回答：${exchange.answer}")
                exchange.answersJson?.let {
                    appendLine("结构化回答（ID 对应问题与选项，不是绘画标签）：$it")
                }
            }
            append("根据原任务和上述回答决定下一动作；仍有实质歧义可继续 ask_user，未回答的项目不要猜选。")
        }
    }

    /** Lookup excludes the questions' unselected choices, which must never activate character tags. */
    fun lookupText(userText: String, history: List<AgentChatMessage>): String {
        val pending = continuation(userText, history)?.clarification ?: return userText
        if (pending.purpose.orEmpty().startsWith("character_")) return userText
        return (listOf(source(userText, history)) + pending.answerHistory.orEmpty().map { it.lookupText ?: it.answer } + userText)
            .joinToString("\n")
    }

    fun bind(
        clarification: AgentClarification, userText: String, history: List<AgentChatMessage>,
        answerValues: String? = null, structuredAnswer: AgentClarificationAnswer? = null
    ) =
        clarification.copy(
            sourceRequest = clarification.sourceRequest?.takeIf(String::isNotBlank) ?: source(userText, history),
            answerHistory = exchanges(userText, history, answerValues, structuredAnswer).ifEmpty { null }
        )

    /** Complete the exact native invocation, including after Room restore. Never fabricate a tool ID. */
    fun toolContinuation(
        userText: String, history: List<AgentChatMessage>,
        structuredAnswer: AgentClarificationAnswer? = null
    ): List<com.comfyui.mobile.network.ChatMessageDto> {
        val message = continuation(userText, history) ?: return emptyList()
        val clarification = message.clarification ?: return emptyList()
        val invocation = clarification.toolInvocation ?: return emptyList()
        if (invocation.id.isBlank() || invocation.name != AgentInteractionProtocol.ASK ||
            AgentInteractionProtocol.objectValue(invocation.arguments) == null) return emptyList()
        val answer = exchanges(userText, history, structuredAnswer = structuredAnswer).lastOrNull()
            ?: return emptyList()
        val result = if (answer.answersJson != null) answer.answersJson else com.google.gson.Gson().toJson(
            mapOf("status" to "user_replied", "custom" to userText,
                "instruction" to "这是用户自然语言回答；自行核对是否已答全，未决定的必要项继续提问，不猜选。"))
        return listOf(
            com.comfyui.mobile.network.ChatMessageDto("assistant", reasoning_content = message.thinkingContent,
                tool_calls = listOf(com.comfyui.mobile.network.ChatToolCall(invocation.id,
                    com.comfyui.mobile.network.ChatFunctionCall(invocation.name, invocation.arguments)))),
            com.comfyui.mobile.network.ChatMessageDto("tool", result, tool_call_id = invocation.id))
    }
}
