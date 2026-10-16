package com.comfyui.mobile.ui.screens.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCardStatusFactoryTest {

    @Test
    fun chatLlmUsesSameOverlayNotBanner() {
        val s = AgentCardStatusFactory.resolve(
            llmBusy = true, imageBusy = false, isDrawMode = false, isCloudMode = false,
            progress = 0f, executingNodeName = "", rhAppStatus = null, queueSize = 0,
            briefComplete = false, errorMessage = null,
        )
        assertEquals(AgentCardPhase.LlmThinking, s.phase)
        assertEquals("正在回复…", s.title)
        assertTrue(s.showOverlay)
        assertTrue(s.showProgress)
        assertNull(s.progress)
    }

    @Test
    fun drawLlmSameOverlayDifferentCopy() {
        val s = AgentCardStatusFactory.resolve(
            llmBusy = true, imageBusy = false, isDrawMode = true, isCloudMode = false,
            progress = 0f, executingNodeName = "", rhAppStatus = null, queueSize = 0,
            briefComplete = false, errorMessage = null,
        )
        assertEquals("构思提示词…", s.title)
        assertTrue(s.showOverlay)
        assertNull(s.subtitle)
    }

    @Test
    fun briefCompleteUsesOverlay() {
        val s = AgentCardStatusFactory.resolve(
            llmBusy = false, imageBusy = false, isDrawMode = true, isCloudMode = false,
            progress = 0f, executingNodeName = "", rhAppStatus = null, queueSize = 0,
            briefComplete = true, errorMessage = null,
        )
        assertEquals(AgentCardPhase.BriefComplete, s.phase)
        assertTrue(s.showOverlay)
        assertFalse(s.showProgress)
    }

    @Test
    fun queuedUsesIndeterminate() {
        val s = AgentCardStatusFactory.resolve(
            llmBusy = false, imageBusy = true, isDrawMode = true, isCloudMode = false,
            progress = 0f, executingNodeName = "", rhAppStatus = null, queueSize = 2,
            briefComplete = false, errorMessage = null,
        )
        assertEquals(AgentCardPhase.Queued, s.phase)
        assertTrue(s.showOverlay)
        assertNull(s.progress)
    }

    @Test
    fun localProgressIsRealNotFake() {
        val s = AgentCardStatusFactory.resolve(
            llmBusy = false, imageBusy = true, isDrawMode = true, isCloudMode = false,
            progress = 0.42f, executingNodeName = "KSampler", rhAppStatus = null, queueSize = 0,
            briefComplete = false, errorMessage = null,
        )
        assertEquals(0.42f, s.progress!!, 0.001f)
    }

    @Test
    fun errorBadgeShortened() {
        assertEquals(
            "提示词解析失败",
            AgentCardStatusFactory.shortError("未解析到有效提示词，未出图"),
        )
    }
}
