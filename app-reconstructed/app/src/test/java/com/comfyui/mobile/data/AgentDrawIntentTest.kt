package com.comfyui.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDrawIntentTest {
    @Test fun vetoBlocksEvenInDrawMode() {
        val (draw, reason) = AgentDrawIntent.resolveShouldDraw(
            true, "只要提示词", promptNonEmpty = true, preferDrawWhenAmbiguous = true)
        assertFalse(draw)
        assertEquals("user_veto", reason)
    }

    @Test fun huanGeFuZhuangHitsStrong() {
        for (text in listOf("换个服装", "换服装", "换一套", "换身衣服", "换成连衣裙")) {
            assertTrue(AgentDrawIntent.suggestsDraw(text))
        }
    }

    @Test fun drawModeRespectsLlmPauseForHuanZhuang() {
        val (draw, reason) = AgentDrawIntent.resolveShouldDraw(
            false, "换个服装", promptNonEmpty = true, preferDrawWhenAmbiguous = true)
        assertFalse(draw)
        assertEquals("llm_false", reason)
    }

    @Test fun falseIsRespectedWithoutDrawModeBiasToo() {
        assertFalse(AgentDrawIntent.resolveShouldDraw(false, "换成全身", true, false).first)
    }

    @Test fun missingOutputNeverDrawsDespiteAnExplicitDrawFlagOrAnEditRequest() {
        for (text in listOf("换个服装", "换成连衣裙", "换装成校服", "去掉红围巾，换成蓝色外套")) {
            for (flag in listOf(true, false, null)) {
                val (draw, reason) = AgentDrawIntent.resolveShouldDraw(flag, text, promptNonEmpty = false)
                assertFalse(draw)
                assertEquals("no_prompt", reason)
            }
        }
    }
}
