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
}
