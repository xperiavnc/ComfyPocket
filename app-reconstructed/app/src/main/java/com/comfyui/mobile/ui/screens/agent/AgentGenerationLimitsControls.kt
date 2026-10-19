package com.comfyui.mobile.ui.screens.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.comfyui.mobile.agent.AgentGenerationLimits
import com.comfyui.mobile.agent.OutputTokenParameter
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class AgentLimitsDraft(
    val context: String = "",
    val output: String = "",
    val parameter: OutputTokenParameter = OutputTokenParameter.COMPLETION
) {
    fun parsed(): AgentGenerationLimits {
        fun number(text: String, label: String): Int? = if (text.isBlank()) null else {
            require(text.all(Char::isDigit)) { "$label 请填写正整数，或留空" }
            requireNotNull(text.toIntOrNull()) { "$label 数值过大" }
        }
        return AgentGenerationLimits(number(context, "上下文预算"), number(output, "输出上限"), parameter).validated()
    }
    companion object {
        fun from(value: AgentGenerationLimits) = AgentLimitsDraft(
            value.contextTokens?.toString().orEmpty(), value.outputTokens?.toString().orEmpty(), value.outputParameter)
    }
}

@Composable
internal fun AgentGenerationLimitsControls(value: AgentLimitsDraft, onChange: (AgentLimitsDraft) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("上下文与输出长度", style = MiuixTheme.textStyles.body1)
        TextField(value.context, { onChange(value.copy(context = it)) },
            label = "上下文预算（tokens，留空沿用默认）", useLabelAsPlaceholder = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("agent-context-tokens"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(16384, 32768, 65536, 131072).forEach { tokens ->
                TextButton("${tokens / 1024}K", onClick = { onChange(value.copy(context = tokens.toString())) })
            }
            TextButton("默认", onClick = { onChange(value.copy(context = "")) })
        }
        TextField(value.output, { onChange(value.copy(output = it)) },
            label = "单次输出上限（tokens，留空跟随服务商）", useLabelAsPlaceholder = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("agent-output-tokens"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutputTokenParameter.entries.forEach { parameter ->
                TextButton((if (value.parameter == parameter) "✓ " else "") + parameter.label,
                    onClick = { onChange(value.copy(parameter = parameter)) })
            }
        }
        AgentSettingsDisclosure("长度参数说明", "留空跟随默认；超出预算时裁剪较早的对话") {
            Text("上下文包含输入与输出预留，按文本字节保守估算；图片每张预留 4096。超预算先移除最早的完整历史轮次，当前指令与规则保持完整。未设置输出上限时预留 4096，实际 token 以服务商为准。",
                style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text("默认历史：聊天最近约 20 条、画图 4 条。设置预算后最多纳入最近 200 条。输出参数由服务商支持情况决定；max_completion_tokens 可能包含思考消耗，长度截断会提示，不自动重发。",
                style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        runCatching { value.parsed() }.exceptionOrNull()?.message?.let {
            Text(it, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote2)
        }
    }
}
