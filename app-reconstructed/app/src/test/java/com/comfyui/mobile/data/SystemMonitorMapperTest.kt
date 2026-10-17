package com.comfyui.mobile.data

import com.comfyui.mobile.model.MobileSystemMonitor
import com.comfyui.mobile.model.SystemStatsResponse
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class SystemMonitorMapperTest {
    private val gib = 1024L * 1024 * 1024
    private fun native() = Gson().fromJson(
        """{"system":{"ram_total":34359738368,"ram_free":21474836480},"devices":[{"name":"GPU","vram_total":17179869184,"vram_free":10737418240}]}""",
        SystemStatsResponse::class.java)

    @Test fun nativeComfyUiMemoryWorksWithoutAnyMonitorPlugin() {
        val data = SystemMonitorMapper.fromRest(native(), null)!!
        assertTrue(data.ramAvailable)
        assertEquals((12 * gib).toFloat(), data.ramUsed, 0f)
        assertEquals((32 * gib).toFloat(), data.ramTotal, 0f)
        assertEquals((6 * gib).toFloat(), data.vramUsed, 0f)
        assertFalse(data.cpuAvailable)
        assertFalse(data.isCrystools)
    }

    @Test fun bridgeAddsCpuAndNewerMemoryWithoutChangingGpu() {
        val data = SystemMonitorMapper.fromRest(native(), MobileSystemMonitor(26.5f, 32 * gib, 14 * gib))!!
        assertTrue(data.cpuAvailable)
        assertEquals(26.5f, data.cpuUtil, 0f)
        assertEquals((14 * gib).toFloat(), data.ramUsed, 0f)
        assertEquals("GPU", data.deviceName)
    }

    @Test fun realZeroCpuSampleIsAvailableButMissingOrInvalidSamplesAreNot() {
        assertTrue(SystemMonitorMapper.fromRest(native(), MobileSystemMonitor(0f))!!.cpuAvailable)
        assertFalse(SystemMonitorMapper.fromRest(native(), MobileSystemMonitor(Float.NaN))!!.cpuAvailable)
        assertFalse(SystemMonitorMapper.fromRest(native(), MobileSystemMonitor(101f))!!.cpuAvailable)
        assertFalse(SystemMonitorMapper.fromRest(SystemStatsResponse(), null)!!.ramAvailable)
        assertNull(SystemMonitorMapper.fromRest(null, null))
    }

    @Test fun invalidMemoryIsNotDisplayedAsAnEmptyMachine() {
        val data = SystemMonitorMapper.fromRest(null, MobileSystemMonitor(1f, 32 * gib, -1))!!
        assertFalse(data.ramAvailable)
        assertEquals(0f, data.ramTotal, 0f)
    }
}
