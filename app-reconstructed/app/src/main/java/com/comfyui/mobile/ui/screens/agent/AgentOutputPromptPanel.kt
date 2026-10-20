package com.comfyui.mobile.ui.screens.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.comfyui.mobile.agent.AgentPromptOutput
import com.comfyui.mobile.agent.AgentPromptAliases
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AgentOutputPromptPanel(output: AgentPromptOutput, onCopy: (String, String) -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("正向提示词", color = MiuixTheme.colorScheme.primary)
        SelectionContainer {
            Text(output.positive.ifEmpty { "暂无正向提示词" },
                modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp).verticalScroll(rememberScrollState()))
        }
        PromptAliasDetails("正向中文别名", output.positiveAliases)
        Text("反向提示词", color = MiuixTheme.colorScheme.primary)
        SelectionContainer {
            Text(output.negative?.ifEmpty { "（空反向提示词）" } ?: "尚未记录反向提示词",
                modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp).verticalScroll(rememberScrollState()))
        }
        PromptAliasDetails("反向中文别名", output.negativeAliases)
        if (!output.negativeStatus.isNullOrBlank()) {
            Text(output.negativeStatus, style = MiuixTheme.textStyles.footnote1)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(text = "复制正向", modifier = Modifier.weight(1f),
                enabled = output.positive.isNotEmpty(), onClick = { onCopy("正向提示词", output.positive) })
            TextButton(text = "复制反向", modifier = Modifier.weight(1f),
                enabled = output.negative != null, onClick = { onCopy("反向提示词", output.negative.orEmpty()) })
        }
        TextButton(text = "复制两组", modifier = Modifier.fillMaxWidth(),
            enabled = output.positive.isNotEmpty(), onClick = {
                onCopy("正反向提示词", "正向提示词：\n${output.positive}\n\n反向提示词：\n${output.negative ?: "（尚未记录）"}")
            })
        if (!output.positiveAliases.isNullOrEmpty() || !output.negativeAliases.isNullOrEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(text = "复制正向中文", modifier = Modifier.weight(1f),
                    enabled = !output.positiveAliases.isNullOrEmpty(),
                    onClick = { onCopy("正向中文别名", AgentPromptAliases.text(output.positiveAliases)) })
                TextButton(text = "复制反向中文", modifier = Modifier.weight(1f),
                    enabled = !output.negativeAliases.isNullOrEmpty(),
                    onClick = { onCopy("反向中文别名", AgentPromptAliases.text(output.negativeAliases)) })
            }
        }
    }
}
