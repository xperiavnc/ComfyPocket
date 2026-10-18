package com.comfyui.mobile.ui.screens.danbooru

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.*
import org.junit.Test

class DanbooruZoomStateTest {
    @Test fun doubleTapMagnifiesAroundTheTappedPointAndTheNextDoubleTapResets() {
        val zoom = DanbooruZoomState(1f)
        zoom.resize(IntSize(400, 400))
        zoom.doubleTap(Offset(300f, 200f))
        assertEquals(2.5f, zoom.scale, 0f)
        assertEquals(Offset(-150f, 0f), zoom.offset)
        zoom.doubleTap(Offset(300f, 200f))
        assertEquals(1f, zoom.scale, 0f)
        assertEquals(Offset.Zero, zoom.offset)
    }

    @Test fun pinchZoomHasBoundsAndZoomingBackToFitClearsPan() {
        val zoom = DanbooruZoomState(1f)
        zoom.resize(IntSize(400, 400))
        zoom.transform(Offset(200f, 200f), Offset(70f, 50f), 20f)
        assertEquals(5f, zoom.scale, 0f)
        zoom.transform(Offset(200f, 200f), Offset(2000f, -2000f), 1f)
        assertEquals(Offset(800f, -800f), zoom.offset)
        zoom.transform(Offset.Zero, Offset.Zero, 0.01f)
        assertEquals(1f, zoom.scale, 0f)
        assertEquals(Offset.Zero, zoom.offset)
    }

    @Test fun letterboxedImagesClampToTheActualFittedPictureAndResizeKeepsItInBounds() {
        val zoom = DanbooruZoomState(2f)
        zoom.resize(IntSize(400, 800))
        zoom.transform(Offset(200f, 400f), Offset(2000f, 2000f), 2f)
        assertEquals(Offset(200f, 0f), zoom.offset)
        zoom.resize(IntSize(800, 400))
        assertEquals(Offset(200f, 0f), zoom.offset)
        zoom.reset()
        assertEquals(Offset.Zero, zoom.offset)
    }
}
