package com.comfyui.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryTileAspectTest {
    @Test fun unknownOrInvalidRatiosFallBackToSquare() {
        assertEquals(1f, galleryTileAspect(0f), 0f)
        assertEquals(1f, galleryTileAspect(-2f), 0f)
        assertEquals(1f, galleryTileAspect(Float.NaN), 0f)
        assertEquals(1f, galleryTileAspect(Float.POSITIVE_INFINITY), 0f)
    }

    @Test fun extremeRatiosAreClampedSoTilesStayReadable() {
        assertEquals(1.6f, galleryTileAspect(3.5f), 0f)
        assertEquals(0.62f, galleryTileAspect(0.2f), 0f)
        assertEquals(0.75f, galleryTileAspect(0.75f), 0f)
    }
}
