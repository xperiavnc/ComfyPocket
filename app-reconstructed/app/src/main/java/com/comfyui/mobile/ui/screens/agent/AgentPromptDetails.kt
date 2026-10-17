package com.comfyui.mobile.ui.screens.agent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme

@Composable
fun AgentPromptDetails(positive: String?, negative: String?, status: String?) {
    if (positive.isNullOrBlank() && negative == null) return
    var expanded by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "正向 / 反向提示词",
            color = scheme.primary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 6.dp)
        )
        if (expanded) {
            Text("正向提示词", style = MaterialTheme.typography.bodySmall)
            SelectionContainer { Text(positive.orEmpty(), style = MaterialTheme.typography.bodySmall) }
            Text("反向提示词", style = MaterialTheme.typography.bodySmall)
            SelectionContainer {
                Text(negative?.ifEmpty { "（空）" } ?: "模型未返回，保留工作流原值",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (!status.isNullOrBlank()) {
                Text(status, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
