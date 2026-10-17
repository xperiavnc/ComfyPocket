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
import androidx.compose.material3.Text
import com.comfyui.mobile.ui.designsystem.PocketTextButton as TextButton
import androidx.compose.material3.MaterialTheme

@Composable
internal fun AgentOutputPromptPanel(output: AgentPromptOutput, onCopy: (String, String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("正向提示词", color = MaterialTheme.colorScheme.primary)
        SelectionContainer {
            Text(output.positive.ifEmpty { "暂无正向提示词" },
                modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp).verticalScroll(rememberScrollState()))
        }
        Text("反向提示词", color = MaterialTheme.colorScheme.primary)
        SelectionContainer {
            Text(output.negative?.ifEmpty { "（空反向提示词）" } ?: "尚未记录反向提示词",
                modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp).verticalScroll(rememberScrollState()))
        }
        if (!output.negativeStatus.isNullOrBlank()) {
            Text(output.negativeStatus, style = MaterialTheme.typography.bodySmall)
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
    }
}
