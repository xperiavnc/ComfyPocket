package com.comfyui.mobile.danbooru

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DanbooruApiQueueTest {
    @Test fun serializesStartsWithOneSecondSpacing() = runTest {
        var now = 0L
        val waits = mutableListOf<Long>()
        val queue = DanbooruApiQueue(1000, { now }, { waits += it; now += it })
        val values = mutableListOf<Int>()
        queue.execute { values += 1 }
        queue.execute { values += 2 }
        assertEquals(listOf(1, 2), values)
        assertEquals(listOf(1000L), waits)
    }
}
