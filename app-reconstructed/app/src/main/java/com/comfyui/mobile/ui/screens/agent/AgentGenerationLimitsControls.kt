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
        Text("上下文预算 · tokens", style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        TextField(value.context, { onChange(value.copy(context = it)) },
            label = "留空沿用默认", useLabelAsPlaceholder = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("agent-context-tokens"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(16384, 32768, 65536, 131072).forEach { tokens ->
                AgentChoiceChip("${tokens / 1024}K", value.context.toIntOrNull() == tokens,
                    Modifier.testTag("agent-context-preset-$tokens")) { onChange(value.copy(context = tokens.toString())) }
            }
            AgentChoiceChip("默认", value.context.isBlank()) { onChange(value.copy(context = "")) }
        }
        Text("单次输出上限 · tokens", style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        TextField(value.output, { onChange(value.copy(output = it)) },
            label = "留空跟随服务商", useLabelAsPlaceholder = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("agent-output-tokens"))
        Text("输出参数", style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutputTokenParameter.entries.forEach { parameter ->
                AgentChoiceChip(parameter.label, value.parameter == parameter,
                    Modifier.testTag("agent-output-parameter-${parameter.wireValue}")) {
                    onChange(value.copy(parameter = parameter))
                }
            }
        }
        runCatching { value.parsed() }.exceptionOrNull()?.message?.let {
            Text(it, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote2)
        }
    }
}
