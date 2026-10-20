package com.comfyui.mobile.data

import org.junit.Assert.*
import org.junit.Test

/** Questions are initiated only by the model's explicit protocol, not reply wording. */
class AgentAskUserRegressionTest {
    @Test fun ordinaryQuestionDoesNotBecomeAFormOrRecoverAnOldRender() {
        val raw = "可以，请先确认 3 点：\n1）使用哪套服装？\n2）保留帽子和手套吗？\n3）全身还是半身？你确认后我再改提示词。"
        val parsed = AgentReplyParser.parse(raw, preserveNaturalLanguage = true)
        val recovery = AgentDrawIntent.recoverPromptIfNeeded(parsed.prompt, "你能提问我吗", "cat, rain")
        val draw = if (parsed.clarification != null) false else AgentDrawIntent.resolveShouldDraw(
            if (recovery.recoveredFromLast) true else parsed.shouldDraw,
            "你能提问我吗", recovery.prompt.isNotBlank()).first
        println("ASK_WORDS_REGRESSION waiting=${parsed.clarification != null} recovered=${recovery.recoveredFromLast} draw=$draw prompt=${recovery.prompt}")
        assertNull(parsed.clarification)
        assertFalse(recovery.recoveredFromLast)
        assertFalse(draw)
    }

    @Test fun explicitAskActionStillPausesWithoutRecoveringAnOldRender() {
        val parsed = AgentReplyParser.parse(
            """{"action":"ask_user","reply":"服装用哪套？","questions":[{"id":"outfit","prompt":"服装用哪套？","kind":"free_text"}],"prompt":"must_not_apply","should_draw":true}""")
        assertNotNull(parsed.clarification)
        assertEquals("", parsed.prompt)
        assertEquals(false, parsed.shouldDraw)
        assertEquals(com.comfyui.mobile.agent.AgentAction.ASK_USER, parsed.action)
    }

    @Test fun explicitModelPauseIsNotOverriddenByDrawingMode() {
        val (draw, reason) = AgentDrawIntent.resolveShouldDraw(false, "换个服装", promptNonEmpty = true)
        println("ASK_FALSE_REGRESSION draw=$draw reason=$reason")
        assertFalse(draw)
    }
}
