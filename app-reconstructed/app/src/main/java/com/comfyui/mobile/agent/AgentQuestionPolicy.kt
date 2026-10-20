package com.comfyui.mobile.agent

import com.comfyui.mobile.model.AgentChatMessage
import com.comfyui.mobile.model.AgentClarification
import com.comfyui.mobile.model.AgentQuestionAnswer
import com.comfyui.mobile.model.AgentQuestionExchange
import com.comfyui.mobile.model.AgentUserQuestion

/** The durable question lives in the transcript; no socket or coroutine stays open while waiting. */
object AgentQuestionPolicy {
    fun pending(history: List<AgentChatMessage>): AgentChatMessage? =
        history.lastOrNull()?.takeIf {
            it.role == "assistant" && !it.isThinking && it.errorMessage == null && it.clarification != null
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

    fun exchanges(userText: String, history: List<AgentChatMessage>, answerValues: String? = null): List<AgentQuestionExchange> {
        val question = continuation(userText, history)?.clarification ?: return emptyList()
        val label = question.questions?.joinToString("\n") { it.prompt } ?: question.question
        return question.answerHistory.orEmpty() + AgentQuestionExchange(label, userText, answerValues)
    }

    /** This is required turn context, not disposable history; answers survive further follow-up questions. */
    fun requestText(userText: String, history: List<AgentChatMessage>): String {
        if (continuation(userText, history) == null) {
            return if (pending(history) != null && startsNewRequest(userText)) {
                "【新任务：不再续接上一轮未回答的问题】\n$userText"
            } else userText
        }
        return buildString {
            appendLine("【继续原任务；以下为用户原始要求与已确认答案，不是新的独立绘图请求】")
            appendLine("原始要求：")
            appendLine(source(userText, history))
            exchanges(userText, history).forEachIndexed { index, exchange ->
                appendLine("\n第 ${index + 1} 轮提问：${exchange.question}")
                appendLine("用户回答：${exchange.answer}")
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

    fun bind(clarification: AgentClarification, userText: String, history: List<AgentChatMessage>, answerValues: String? = null) =
        clarification.copy(
            sourceRequest = clarification.sourceRequest?.takeIf(String::isNotBlank) ?: source(userText, history),
            answerHistory = exchanges(userText, history, answerValues).ifEmpty { null }
        )
}
