package com.comfyui.mobile.agent

import com.comfyui.mobile.model.*
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class AgentQuestionPolicyTest {
    private val questions = listOf(
        AgentUserQuestion("framing", "构图？", "multiple_choice",
            listOf(AgentQuestionOption("full", "全身"), AgentQuestionOption("half", "半身"))),
        AgentUserQuestion("details", "保留哪些？", "multiple_choice",
            listOf(AgentQuestionOption("hat", "帽子"), AgentQuestionOption("gloves", "手套")), true),
        AgentUserQuestion("background", "什么背景？", "free_text"))

    @Test fun completeBatchSupportsSingleMultipleAndCustomAnswers() {
        val answers = listOf(AgentQuestionAnswer("framing", listOf("full")),
            AgentQuestionAnswer("details", listOf("hat", "gloves")),
            AgentQuestionAnswer("background", text = " 雨夜街道 "))
        val valid = AgentQuestionPolicy.validate(questions, answers)!!
        assertEquals("雨夜街道", valid.last().text)
        val text = AgentQuestionPolicy.answerText(questions, valid)
        assertTrue(text.contains("全身"))
        assertTrue(text.contains("帽子；手套"))
        assertFalse(text.contains("半身"))
        assertFalse(AgentQuestionPolicy.startsNewRequest(text))
        assertFalse(AgentQuestionPolicy.answerValues(questions, valid).contains("构图"))
    }

    @Test fun incompleteDuplicateForeignOrMultiSelectedSingleAnswersAreRejected() {
        val valid = listOf(AgentQuestionAnswer("framing", listOf("full")),
            AgentQuestionAnswer("details", text = "不添加"), AgentQuestionAnswer("background", text = "雨夜"))
        for (answers in listOf(valid.dropLast(1), valid.drop(1) + valid.last(),
            valid.map { if (it.questionId == "framing") it.copy(optionIds = listOf("unknown")) else it },
            valid.map { if (it.questionId == "framing") it.copy(optionIds = listOf("full", "half")) else it },
            valid.map { if (it.questionId == "background") it.copy(optionIds = listOf("x")) else it })) {
            assertNull(AgentQuestionPolicy.validate(questions, answers))
        }
    }

    private fun history(clarification: AgentClarification) = listOf(
        AgentChatMessage("u1", "user", "画星澜，保留蓝色外套"),
        AgentChatMessage("a1", "assistant", "先确认", clarification = clarification))

    @Test fun machineAnswersKeepQuestionAndOptionIdsInRequiredContextAcrossFurtherQuestions() {
        val first = AgentClarification("确认", emptyList(), questions = questions,
            sourceRequest = "画星澜，保留蓝色外套")
        val h = history(first)
        val answers = AgentClarificationAnswer("a1", listOf(AgentQuestionAnswer("framing", listOf("full")),
            AgentQuestionAnswer("details", listOf("hat")), AgentQuestionAnswer("background", text = "雨夜")))
        val text = AgentQuestionPolicy.answerText(questions, answers.answers)
        val context = AgentQuestionPolicy.requestText(text, h, answers)
        assertTrue(context.contains("\"id\":\"framing\""))
        assertTrue(context.contains("\"option_ids\":[\"full\"]"))
        assertTrue(context.contains("\"selected\":[\"全身\"]"))
        assertTrue(context.contains("\"custom\":\"雨夜\""))
        assertFalse(context.contains("半身"))
        val bound = AgentQuestionPolicy.bind(AgentClarification("光照？", emptyList()),
            text, h, AgentQuestionPolicy.answerValues(questions, answers.answers), answers)
        val restored = Gson().fromJson(Gson().toJson(bound), AgentClarification::class.java)
        val next = AgentQuestionPolicy.requestText("霓虹光", history(restored).takeLast(1))
        assertTrue(next.contains("\"option_ids\":[\"full\"]"))
        assertTrue(next.contains("雨夜"))
        assertFalse(AgentQuestionPolicy.lookupText("霓虹光", history(restored)).contains("option_ids"))
        assertFalse(AgentQuestionPolicy.requestText(text, h, answers.copy(messageId = "stale")).contains("option_ids"))
    }

    @Test fun repeatedQuestionsPreserveOriginalTaskAndAllAnswersOutsideHistoryBudget() {
        val first = AgentClarification("构图？", emptyList(), purpose = "agent_question", questions = questions)
        val bound = AgentQuestionPolicy.bind(first, "画星澜，保留蓝色外套", emptyList())
        val second = AgentQuestionPolicy.bind(first.copy(question = "背景？"), "全身", history(bound))
        // Only the last persisted question is needed; the first task/answer can be paged out.
        val text = AgentQuestionPolicy.requestText("雨夜", history(second).takeLast(1))
        assertTrue(text.contains("画星澜，保留蓝色外套"))
        assertTrue(text.contains("全身"))
        assertTrue(text.contains("雨夜"))
        val lookup = AgentQuestionPolicy.lookupText("雨夜", history(second))
        assertFalse(lookup.contains("半身"))
        assertTrue(lookup.contains("全身"))
    }

    @Test fun cancelAndExplicitNewTaskDoNotReuseAnUnansweredRequest() {
        val h = history(AgentClarification("构图？", listOf("全身", "半身"), sourceRequest = "旧任务"))
        assertTrue(AgentQuestionPolicy.isCancellation("取消本次提问"))
        assertNull(AgentQuestionPolicy.continuation("取消本次提问", h))
        assertNull(AgentQuestionPolicy.continuation("画一只猫", h))
        assertNotNull(AgentQuestionPolicy.continuation("画全身", h))
        assertFalse(AgentQuestionPolicy.isCancellation("不用穿冬装"))
        assertFalse(AgentQuestionPolicy.requestText("画一只猫", h).contains("旧任务"))
    }

    @Test fun oldJsonRemainsReadableAndOnlyLatestValidQuestionIsPending() {
        val old = Gson().fromJson("""{"question":"哪套？","options":["便服","礼服"]}""", AgentClarification::class.java)
        assertNull(old.questions)
        assertNull(old.answerHistory)
        assertNotNull(AgentQuestionPolicy.pending(history(old)))
        assertNull(AgentQuestionPolicy.pending(history(old) + AgentChatMessage("u2", "user", "便服")))
        assertNull(AgentQuestionPolicy.pending(listOf(history(old).last().copy(errorMessage = "失败"))))
    }

    @Test fun onlyExplicitFailedContinuationCanReopenTheExactOriginalQuestion() {
        val question = AgentClarification("哪套？", listOf("便服", "礼服"), sourceRequest = "画星澜")
        val h = history(question)
        val failed = listOf(AgentChatMessage("u2", "user", "便服"),
            AgentChatMessage("a2", "assistant", "", errorMessage = "网络错误", retryClarificationId = "a1"))
        assertEquals("a1", AgentQuestionPolicy.pending(h + failed)?.id)
        assertTrue(AgentQuestionPolicy.requestText("礼服", h + failed).contains("画星澜"))
        assertNull(AgentQuestionPolicy.pending(h + failed.dropLast(1) + failed.last().copy(retryClarificationId = null)))
        assertNull(AgentQuestionPolicy.pending(h + failed.dropLast(1) + failed.last().copy(errorMessage = null)))
        assertNull(AgentQuestionPolicy.pending(h + failed.dropLast(1) + failed.last().copy(isThinking = true)))
        assertNull(AgentQuestionPolicy.pending(h + AgentChatMessage("u3", "user", "另一件事") +
            AgentChatMessage("a3", "assistant", "完成") + failed))
        assertNull(AgentQuestionPolicy.continuation("取消本次提问", h + failed))
        assertNull(AgentQuestionPolicy.continuation("画一只猫", h + failed))
    }

    @Test fun retriesKeepExactNativeInvocationAndDoNotTreatFailedAnswersAsConfirmedHistory() {
        val h = history(AgentClarification("背景？", emptyList(), sourceRequest = "原始绘图任务",
            questions = listOf(questions.last()), toolInvocation = AgentQuestionInvocation(
                "original-call", AgentInteractionProtocol.ASK, """{"questions":[{"id":"background","question":"背景？"}]}""")))
        val failed = listOf(AgentChatMessage("u2", "user", "沙漠"),
            AgentChatMessage("a2", "assistant", "", errorMessage = "503", retryClarificationId = "a1"))
        val answers = AgentClarificationAnswer("a1", listOf(AgentQuestionAnswer("background", text = "雨夜")))
        val restored = Gson().fromJson(Gson().toJson(h + failed),
            object : com.google.gson.reflect.TypeToken<List<AgentChatMessage>>() {}.type) as List<AgentChatMessage>
        val request = AgentQuestionPolicy.requestText("雨夜", restored, answers)
        assertTrue(request.contains("原始绘图任务"))
        assertTrue(request.contains("雨夜"))
        assertFalse(request.contains("沙漠"))
        val native = AgentQuestionPolicy.toolContinuation("雨夜", restored, answers)
        assertEquals("original-call", native.first().tool_calls!!.single().id)
        assertEquals("original-call", native.last().tool_call_id)
    }
}
