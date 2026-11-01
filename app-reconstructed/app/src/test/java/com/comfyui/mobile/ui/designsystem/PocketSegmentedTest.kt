package com.comfyui.mobile.ui.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

class PocketSegmentedTest {
    @Test fun wrappedTrackSplitsEvenlyWithTheFullerRowFirst() {
        assertEquals(listOf(listOf(0, 1, 2), listOf(3, 4)), pocketSegmentRows(listOf(60, 60, 80, 60, 60), 260, 2))
        assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4, 5)), pocketSegmentRows(List(6) { 100 }, 210, 2))
        assertEquals(listOf(listOf(0), listOf(1)), pocketSegmentRows(listOf(300, 300), 300, 2))
    }
}
