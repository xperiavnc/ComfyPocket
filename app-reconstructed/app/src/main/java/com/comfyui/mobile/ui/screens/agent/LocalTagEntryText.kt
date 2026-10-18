package com.comfyui.mobile.ui.screens.agent

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import com.comfyui.mobile.taglib.LocalTagEntry
import com.google.gson.JsonParser
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun LocalTagEntryText(entry: LocalTagEntry) {
    Column {
        Text("${entry.name} · ${entry.translation.ifBlank { "上游暂缺中文对照" }}",
            style = MiuixTheme.textStyles.body2)
        Text("${if (entry.kind == "character") "角色身份" else "普通词"} · ${entry.category} / ${entry.section}",
            style = MiuixTheme.textStyles.footnote1)
        if (entry.kind == "character") {
            val detail = runCatching { JsonParser.parseString(entry.detail).asJsonObject }.getOrNull()
            val general = detail?.getAsJsonArray("optionalAppearance")?.take(12)?.map { it.asString }.orEmpty()
            val specific = detail?.getAsJsonArray("optionalSpecificAppearance")?.take(4)?.map {
                it.asJsonObject.let { word -> word["zh"].asString.ifBlank { word["en"].asString } }
            }.orEmpty()
            val appearance = if (general.isEmpty() && specific.isEmpty())
                "词库未附带外貌资料；按本轮描述和启用的角色档案编写。"
            else "可选外貌（不自动拼入）：${general.joinToString(", ")}" +
                if (specific.isNotEmpty()) "\n角色专用参考：${specific.joinToString(", ")}" else ""
            Text("身份：${entry.trigger}\n$appearance",
                style = MiuixTheme.textStyles.footnote1)
        }
    }
}
