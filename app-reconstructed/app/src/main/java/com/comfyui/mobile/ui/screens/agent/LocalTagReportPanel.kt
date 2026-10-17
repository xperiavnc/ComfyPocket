package com.comfyui.mobile.ui.screens.agent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.comfyui.mobile.taglib.LocalTagEntry
import com.comfyui.mobile.taglib.LocalTagReport
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun LocalTagReportPanel(report: LocalTagReport) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${report.summary}  ${if (expanded) "收起" else "详情"}",
            modifier = Modifier.clickable { expanded = !expanded },
            color = MiuixTheme.colorScheme.primary, style = MiuixTheme.textStyles.footnote1)
        if (expanded) {
            Text("仅为编写参考，不表示每项都已采用；未命中不删除触发词或自定义语法。",
                style = MiuixTheme.textStyles.footnote1)
            SelectionContainer {
                Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    report.entries.forEach { LocalTagEntryText(it) }
                }
            }
        }
    }
}

@Composable
internal fun LocalTagEntryText(entry: LocalTagEntry) {
    Column {
        Text("${entry.name} · ${entry.translation.ifBlank { entry.matchedTerm }}",
            style = MiuixTheme.textStyles.body2)
        Text("${if (entry.kind == "character") "角色身份" else "普通词"} · ${entry.category} / ${entry.section}",
            style = MiuixTheme.textStyles.footnote1)
        if (entry.kind == "character") {
            Text("身份：${entry.trigger}\n可选外貌（不自动拼入）：${entry.detail.take(360)}",
                style = MiuixTheme.textStyles.footnote1)
        }
    }
}
