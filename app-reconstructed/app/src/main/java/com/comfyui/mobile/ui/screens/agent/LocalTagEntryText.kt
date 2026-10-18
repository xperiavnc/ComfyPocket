package com.comfyui.mobile.ui.screens.agent

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import com.comfyui.mobile.taglib.LocalTagEntry
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun LocalTagEntryText(entry: LocalTagEntry) {
    Column {
        Text("${entry.name} · ${entry.translation.ifBlank { entry.matchedTerm }}",
            style = MiuixTheme.textStyles.body2)
        Text("${if (entry.kind == "character") "角色身份" else "普通词"} · ${entry.category} / ${entry.section}",
            style = MiuixTheme.textStyles.footnote1)
        if (entry.kind == "character") {
            val appearance = entry.detail.takeIf { it.isNotBlank() }?.let { "可选外貌（不自动拼入）：${it.take(360)}" }
                ?: "词库未附带外貌资料；按本轮描述和启用的角色档案编写。"
            Text("身份：${entry.trigger}\n$appearance",
                style = MiuixTheme.textStyles.footnote1)
        }
    }
}
