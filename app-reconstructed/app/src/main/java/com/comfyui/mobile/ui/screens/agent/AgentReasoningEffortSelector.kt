package com.comfyui.mobile.ui.screens.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
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
                val selected = effort == value
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp))
                        .background(if (selected) scheme.primary else scheme.surfaceContainerHigh)
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { onChange(effort) })
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                ) {
                    Text(effort.label, fontSize = 12.sp, color = if (selected) scheme.onPrimary else scheme.onSurface)
                }
            }
        }
        Text(
            "随当前模型配置保存，聊天和画图均生效。具体支持档位取决于模型与服务商；极高传 xhigh，Max 传 max。不支持时会显示接口错误，请选择「跟随模型」；不会自动降档或重发。",
            fontSize = 12.sp,
            color = scheme.onSurfaceVariantSummary
        )
    }
}
