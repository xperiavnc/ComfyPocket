package com.comfyui.mobile.ui.screens.agent

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import com.comfyui.mobile.taglib.LocalTagEntry
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme

@Composable
internal fun LocalTagEntryText(entry: LocalTagEntry) {
    Column {
        Text("${entry.name} · ${entry.translation.ifBlank { entry.matchedTerm }}",
            style = MaterialTheme.typography.bodyMedium)
        Text("${if (entry.kind == "character") "角色身份" else "普通词"} · ${entry.category} / ${entry.section}",
            style = MaterialTheme.typography.bodySmall)
        if (entry.kind == "character") {
            val appearance = entry.detail.takeIf { it.isNotBlank() }?.let { "可选外貌（不自动拼入）：${it.take(360)}" }
                ?: "词库未附带外貌资料；按本轮描述和启用的角色档案编写。"
            Text("身份：${entry.trigger}\n$appearance",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
