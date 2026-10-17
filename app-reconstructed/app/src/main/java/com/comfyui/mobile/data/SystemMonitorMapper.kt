package com.comfyui.mobile.data

import com.comfyui.mobile.model.MobileSystemMonitor
import com.comfyui.mobile.model.SystemMonitorData
import com.comfyui.mobile.model.SystemStatsResponse

/** Missing telemetry stays missing; a real zero-percent sample remains valid. */
internal object SystemMonitorMapper {
    fun fromRest(native: SystemStatsResponse?, mobile: MobileSystemMonitor?): SystemMonitorData? {
        if (native == null && mobile == null) return null
        val device = native?.devices?.firstOrNull()
        val vramTotal = device?.vram_total?.takeIf { it > 0L } ?: 0L
        val vramFree = device?.vram_free
        val ramTotal = mobile?.ram_total?.takeIf { it > 0L }
            ?: native?.system?.ram_total?.takeIf { it > 0L }
        val ramUsed = if (mobile?.ram_total == ramTotal && mobile?.ram_used != null) {
            mobile.ram_used
        } else {
            val free = native?.system?.ram_free
            if (ramTotal != null && free != null) ramTotal - free else null
        }
        val validRam = ramTotal != null && ramUsed != null && ramUsed in 0L..ramTotal
        val cpu = (mobile?.cpu_percent ?: native?.system?.cpu_percent)
            ?.takeIf { it.isFinite() && it in 0f..100f }
        return SystemMonitorData(
            vramUsed = if (vramFree != null) (vramTotal - vramFree).coerceIn(0L, vramTotal).toFloat() else 0f,
            vramTotal = vramTotal.toFloat(),
            cpuUtil = cpu ?: 0f,
            cpuAvailable = cpu != null,
            ramUsed = if (validRam) ramUsed.toFloat() else 0f,
            ramTotal = if (validRam) ramTotal.toFloat() else 0f,
            ramAvailable = validRam,
            deviceName = device?.name,
            isCrystools = false
        )
    }
}
