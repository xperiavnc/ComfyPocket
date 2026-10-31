package com.comfyui.mobile.ui.screens.agent

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentPreviewPageWidthTest {
    private val w = 340.dp
    private val h = 300.dp

    @Test fun unknownOrLandscapeImagesFillTheCard() {
        assertEquals(w, previewPageWidth(w, h, null))
        assertEquals(w, previewPageWidth(w, h, 1.5f))
        assertEquals(w, previewPageWidth(w, h, 1f))
        assertEquals(w, previewPageWidth(w, h, Float.NaN))
    }

    @Test fun portraitImagesGetAnImageWidePageSoNeighboursPeek() {
        // 9:16 at 300dp tall -> 168.75dp page instead of two blurred bars.
        assertEquals(300f * 9f / 16f, previewPageWidth(w, h, 9f / 16f).value, 0.01f)
        // Extremely tall images are clamped so the page never becomes a sliver.
        assertEquals(300f * 0.45f, previewPageWidth(w, h, 0.2f).value, 0.01f)
    }

    @Test fun nearlyFullWidthPortraitFallsBackToFill() {
        // 0.85 at 330dp tall = 280.5dp, more than 80% of 340dp (272dp): not worth a carousel.
        assertEquals(w, previewPageWidth(w, 330.dp, 0.85f))
    }
}
