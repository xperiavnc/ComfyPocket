package com.comfyui.mobile.ui.screens.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comfyui.mobile.agent.AgentReasoningEffort
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AgentReasoningEffortSelector(
    value: AgentReasoningEffort,
    onChange: (AgentReasoningEffort) -> Unit
) {
    val scheme = MiuixTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("思考强度", style = MiuixTheme.textStyles.body1)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AgentReasoningEffort.entries.forEach { effort ->
                AgentChoiceChip(effort.label, effort == value) { onChange(effort) }
            }
        }
        Text(
            "聊天和画图共用。可选档位以模型服务商支持情况为准。",
            fontSize = 12.sp,
            color = scheme.onSurfaceVariantSummary
        )
    }
}
