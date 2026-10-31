package com.comfyui.mobile.ui.screens.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPreviewPageWidthTest {
    @Test fun unknownSquareOrLandscapeImagesAreFittedWithAmbientBlur() {
        assertFalse(previewIsPortrait(null))
        assertFalse(previewIsPortrait(Float.NaN))
        assertFalse(previewIsPortrait(0f))
        assertFalse(previewIsPortrait(1f))
        assertFalse(previewIsPortrait(1.5f))
        assertFalse(previewIsPortrait(0.9f))
    }

    @Test fun portraitImagesFillTheCard() {
        assertTrue(previewIsPortrait(9f / 16f))
        assertTrue(previewIsPortrait(2f / 3f))
        assertTrue(previewIsPortrait(0.2f))
    }
}
