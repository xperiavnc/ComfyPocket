package com.comfyui.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDrawIntentTest {

    @Test fun vetoBlocksEvenInDrawMode() {
        val (draw, reason) = AgentDrawIntent.resolveShouldDraw(
            true, "只要提示词", promptNonEmpty = true, preferDrawWhenAmbiguous = true
        )
        assertFalse(draw)
        assertEquals("user_veto", reason)
    }

    @Test fun huanGeFuZhuangHitsStrong() {
        assertTrue(AgentDrawIntent.suggestsDraw("换个服装"))
        assertTrue(AgentDrawIntent.suggestsDraw("换服装"))
        assertTrue(AgentDrawIntent.suggestsDraw("换一套"))
        assertTrue(AgentDrawIntent.suggestsDraw("换身衣服"))
        assertTrue(AgentDrawIntent.suggestsDraw("换成连衣裙"))
    }

    @Test fun recoverUsesLastPromptOnBlankParse() {
        val last = "1girl, long hair, red dress, standing"
        val r = AgentDrawIntent.recoverPromptIfNeeded(
            parsedPrompt = "",
            userText = "换个服装",
            lastPrompt = last
        )
        assertTrue(r.recoveredFromLast)
        assertTrue(r.prompt.contains("1girl"))
        assertTrue(r.prompt.contains("different outfit") || r.prompt.contains("change of clothes"))
    }

    @Test fun recoverAppendsDressHint() {
        val r = AgentDrawIntent.recoverPromptIfNeeded(
            "", "换成连衣裙", "1girl, school uniform, smile"
        )
        assertTrue(r.recoveredFromLast)
        assertTrue(r.prompt.contains("dress"))
    }

    @Test fun recoverSkipsPureChat() {
        val r = AgentDrawIntent.recoverPromptIfNeeded("", "你好", "1girl, smile")
        assertFalse(r.recoveredFromLast)
        assertEquals("", r.prompt)
    }

    @Test fun chineseOnlyParsedPromptStillRecovers() {
        // sanitize would empty Chinese-only LLM "prompt"; we receive already-empty here
        val r = AgentDrawIntent.recoverPromptIfNeeded("", "换装成校服", "1girl, casual clothes")
        assertTrue(r.recoveredFromLast)
        assertTrue(r.prompt.contains("school uniform") || r.prompt.contains("1girl"))
    }

    @Test fun drawModeRespectsLlmPauseForHuanZhuang() {
        val (draw, reason) = AgentDrawIntent.resolveShouldDraw(
            false, "换个服装", promptNonEmpty = true, preferDrawWhenAmbiguous = true
        )
        assertFalse(draw)
        assertEquals("llm_false", reason)
    }

    @Test fun ordinaryQuestionsDoNotRecoverPreviousRender() {
        for (text in listOf("你能提问我吗", "你有什么建议", "关于这个设置我有个问题")) {
            val result = AgentDrawIntent.recoverPromptIfNeeded("", text, "cat, rain")
            assertFalse(result.recoveredFromLast)
            assertEquals("", result.prompt)
        }
    }

    @Test fun falseIsRespectedWithoutDrawModeBiasToo() {
        assertFalse(AgentDrawIntent.resolveShouldDraw(false, "换成全身", true, false).first)
    }

    @Test fun noPromptNeverDraws() {
        val (draw, reason) = AgentDrawIntent.resolveShouldDraw(true, "换个服装", promptNonEmpty = false)
        assertFalse(draw)
        assertEquals("no_prompt", reason)
    }

    @Test fun parsedPromptKeepsAsIs() {
        val r = AgentDrawIntent.recoverPromptIfNeeded(
            "1girl, kimono", "换个服装", "1girl, dress"
        )
        assertFalse(r.recoveredFromLast)
        assertEquals("1girl, kimono", r.prompt)
    }
}
