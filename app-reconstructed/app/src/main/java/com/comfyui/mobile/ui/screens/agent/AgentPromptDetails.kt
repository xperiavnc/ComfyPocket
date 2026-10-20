package com.comfyui.mobile.ui.screens.agent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import com.comfyui.mobile.agent.AgentPromptAlias
import com.comfyui.mobile.agent.AgentPromptAliases
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AgentPromptDetails(
    positive: String?, negative: String?, status: String?,
    positiveAliases: List<AgentPromptAlias>? = null,
    negativeAliases: List<AgentPromptAlias>? = null
) {
    if (positive.isNullOrBlank() && negative == null) return
    var expanded by remember { mutableStateOf(false) }
    val scheme = MiuixTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "正向 / 反向提示词",
            color = scheme.primary,
            style = MiuixTheme.textStyles.footnote1,
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 6.dp)
        )
        if (expanded) {
            Text("正向提示词", style = MiuixTheme.textStyles.footnote1)
            SelectionContainer { Text(positive.orEmpty(), style = MiuixTheme.textStyles.footnote1) }
            PromptAliasDetails("正向中文别名", positiveAliases)
            Text("反向提示词", style = MiuixTheme.textStyles.footnote1)
            SelectionContainer {
                Text(negative?.ifEmpty { "（空）" } ?: "模型未返回，保留工作流原值",
                    style = MiuixTheme.textStyles.footnote1)
            }
            PromptAliasDetails("反向中文别名", negativeAliases)
            if (!status.isNullOrBlank()) {
                Text(status, color = scheme.onSurfaceVariantSummary, style = MiuixTheme.textStyles.footnote1)
            }
        }
    }
}

@Composable
internal fun PromptAliasDetails(title: String, aliases: List<AgentPromptAlias>?) {
    if (aliases.isNullOrEmpty()) return
    Text("$title · 仅供查看", color = MiuixTheme.colorScheme.primary,
        style = MiuixTheme.textStyles.footnote1)
    SelectionContainer {
        Text(AgentPromptAliases.text(aliases), style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp).verticalScroll(rememberScrollState()))
    }
}
