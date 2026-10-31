package com.comfyui.mobile.ui.screens.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.comfyui.mobile.agent.AgentPromptOutput
import com.comfyui.mobile.agent.AgentPromptAliases
import com.comfyui.mobile.ui.designsystem.PocketSectionHeader
import com.comfyui.mobile.ui.designsystem.PocketTokens
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AgentOutputPromptPanel(output: AgentPromptOutput, onCopy: (String, String) -> Unit) {
    val hasPositive = output.positive.isNotEmpty()
    val hasNegative = output.negative != null
    Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(PocketTokens.Space.XS)) {
        PocketSectionHeader("正向提示词")
        PromptBox(output.positive.ifEmpty { "暂无正向提示词" }, placeholder = !hasPositive)
        PromptAliasDetails("正向中文别名", output.positiveAliases)
        PocketSectionHeader("反向提示词")
        PromptBox(output.negative?.ifEmpty { "（空反向提示词）" } ?: "尚未记录反向提示词",
            placeholder = output.negative.isNullOrEmpty())
        PromptAliasDetails("反向中文别名", output.negativeAliases)
        if (!output.negativeStatus.isNullOrBlank()) {
            Text(output.negativeStatus, style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        // Copy actions only appear when there is something to copy (no row of greyed-out buttons).
        if (hasPositive || hasNegative) {
            Row(Modifier.fillMaxWidth().padding(top = PocketTokens.Space.XS),
                horizontalArrangement = Arrangement.spacedBy(PocketTokens.Space.XS)) {
                if (hasPositive) TextButton(text = "复制正向", modifier = Modifier.weight(1f),
                    onClick = { onCopy("正向提示词", output.positive) })
                if (hasNegative) TextButton(text = "复制反向", modifier = Modifier.weight(1f),
                    onClick = { onCopy("反向提示词", output.negative.orEmpty()) })
            }
        }
        if (hasPositive) {
            TextButton(text = "复制两组", modifier = Modifier.fillMaxWidth(), onClick = {
                onCopy("正反向提示词", buildString {
                    appendLine("正向提示词："); appendLine(output.positive); appendLine()
                    appendLine("反向提示词："); append(output.negative ?: "（尚未记录）")
                })
            })
        }
        if (!output.positiveAliases.isNullOrEmpty() || !output.negativeAliases.isNullOrEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PocketTokens.Space.XS)) {
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

/** Selectable prompt text on a quiet rounded surface; placeholders render in the summary colour. */
@Composable
private fun PromptBox(text: String, placeholder: Boolean) {
    val scheme = MiuixTheme.colorScheme
    SelectionContainer {
        Text(text,
            style = MiuixTheme.textStyles.body2,
            color = if (placeholder) scheme.onSurfaceVariantSummary else scheme.onSurface,
            modifier = Modifier.fillMaxWidth().clip(PocketTokens.Shape.Medium)
                .background(scheme.surfaceContainerHigh)
                .heightIn(max = 140.dp).verticalScroll(rememberScrollState())
                .padding(horizontal = PocketTokens.Space.S, vertical = PocketTokens.Space.XS + 2.dp))
    }
}
