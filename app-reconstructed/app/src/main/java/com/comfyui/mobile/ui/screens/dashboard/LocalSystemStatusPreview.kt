package com.comfyui.mobile.ui.screens.dashboard

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.comfyui.mobile.model.SystemMonitorData
import com.comfyui.mobile.ui.screens.InfoRow
import com.comfyui.mobile.ui.screens.StatProgressRow
import java.util.Locale
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme

/** CPU/RAM preview is available independently of the optional Crystools GPU feed. */
@Composable
internal fun LocalSystemStatusPreview(
    stats: SystemMonitorData,
    contentColor: Color,
    progressColor: Color = Color(0xFF3482FF)
) {
    if (!stats.deviceName.isNullOrBlank()) {
        InfoRow("GPU 设备", stats.deviceName, contentColor)
        Spacer(Modifier.height(4.dp))
    }
    if (stats.isCrystools) {
        StatProgressRow("GPU 利用率", stats.gpuUtil / 100f, "${stats.gpuUtil.toInt()}%", progressColor, contentColor)
    }
    val vramProgress = if (stats.vramTotal > 0f) (stats.vramUsed / stats.vramTotal).coerceIn(0f, 1f) else 0f
    StatProgressRow("显存占用", vramProgress,
        memoryLabel(stats.vramUsed, stats.vramTotal), progressColor, contentColor)
    val cpu = if (stats.cpuAvailable) stats.cpuUtil.coerceIn(0f, 100f) else null
    StatProgressRow("CPU 利用率", (cpu ?: 0f) / 100f,
        cpu?.let { String.format(Locale.US, "%.1f%%", it) } ?: "暂无数据", progressColor, contentColor)
    val ramProgress = if (stats.ramAvailable && stats.ramTotal > 0f) {
        (stats.ramUsed / stats.ramTotal).coerceIn(0f, 1f)
    } else 0f
    StatProgressRow("内存占用", ramProgress,
        if (stats.ramAvailable) memoryLabel(stats.ramUsed, stats.ramTotal) else "暂无数据",
        progressColor, contentColor)
    if (!stats.cpuAvailable) {
        Spacer(Modifier.height(4.dp))
        Text("CPU 数据需 Mobile Bridge 2.1+ 或 Crystools 实时监控",
            style = MaterialTheme.typography.labelSmall, color = contentColor.copy(alpha = 0.65f))
    }
    if (stats.isCrystools) {
        Spacer(Modifier.height(4.dp))
        InfoRow("GPU 温度", "${stats.gpuTemp}°C", contentColor)
    }
}

internal fun memoryLabel(used: Float, total: Float): String {
    if (!used.isFinite() || !total.isFinite() || total <= 0f) return "暂无数据"
    val percent = (used / total).coerceIn(0f, 1f) * 100f
    // Some legacy monitor providers report relative units; don't label those as bytes.
    return if (total > 100_000f) {
        val gib = 1024f * 1024f * 1024f
        String.format(Locale.US, "%.1f%% (%.1f / %.1f GiB)", percent, used / gib, total / gib)
    } else String.format(Locale.US, "%.1f%%", percent)
}
