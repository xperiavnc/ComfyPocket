package com.comfyui.mobile.ui.screens.agent

import androidx.compose.foundation.background
import com.comfyui.mobile.ui.designsystem.PocketTokens
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comfyui.mobile.model.AgentChatMessage
import com.comfyui.mobile.model.AgentClarification
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.Report
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role

@Composable
fun FrostedChip(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
    ) {
    val scheme = MiuixTheme.colorScheme
    Box(
        modifier = modifier
            .height(34.dp)
            .clip(PocketTokens.Shape.Pill)
            .background(scheme.surfaceContainerHighest.copy(alpha = 0.92f))
            .border(
                width = 1.dp,
                color = scheme.outline.copy(alpha = 0.28f),
                shape = PocketTokens.Shape.Pill
            )
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = scheme.onSurface,
            style = MiuixTheme.textStyles.footnote1,
            maxLines = 1
    )
    }
}

@Composable
fun AgentBubble(
    msg: AgentChatMessage,
    clarificationEnabled: Boolean = false,
    onClarificationOption: (String) -> Unit = {},
    onQuestionSubmit: (List<com.comfyui.mobile.model.AgentQuestionAnswer>) -> Boolean = { false },
    onQuestionCancel: () -> Unit = {}
) {
    val scheme = MiuixTheme.colorScheme
    val isUser = msg.role == "user"
    val bubbleColor = if (isUser) {
        scheme.primaryContainer
    } else {
        // 助理气泡与下卡片底区分开，避免“没有气泡”
        scheme.surface.copy(alpha = 0.92f)
    }
    val bubbleFg = if (isUser) scheme.onPrimaryContainer else scheme.onSurface

    // Tail side gets the tighter corner.
    val bubbleShape = if (isUser) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 6.dp, bottomStart = 18.dp)
    } else {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 6.dp)
    }

    var thinkingExpanded by remember(msg.id) { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(bubbleShape)
                .background(bubbleColor)
                .border(
                    width = 1.dp,
                    color = scheme.outline.copy(alpha = if (isUser) 0.12f else 0.22f),
                    shape = bubbleShape
    )
                .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!msg.attachments.isNullOrEmpty()) {
                    AgentAttachmentStrip(attachments = msg.attachments)
                }
                // 1. 思考中状态提示（包含实时流式打字展示）
                if (msg.isThinking) {
                    Text(
                        text = msg.activity ?: "✨深度求索中...",
                        style = MiuixTheme.textStyles.body2.copy(
                            fontWeight = FontWeight.Medium,
                            color = scheme.primary
    )
    )
                    if (!msg.thinkingContent.isNullOrBlank()) {
                        SelectionContainer {
                            Text(
                                text = msg.thinkingContent,
                                style = MiuixTheme.textStyles.footnote1.copy(
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp,
                                    color = scheme.onSurfaceVariantSummary
    ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(PocketTokens.Shape.Medium)
                                    .background(scheme.surfaceContainerHigh.copy(alpha = 0.5f))
                                    .padding(8.dp)
    )
                        }
                    }
                } else if (!msg.thinkingContent.isNullOrBlank()) {
                    // 2. 思考完成：收拢为优雅折叠胶囊
                    Box(
                        modifier = Modifier
                            .clip(PocketTokens.Shape.Medium)
                            .background(scheme.surfaceContainerHigh.copy(alpha = 0.7f))
                            .clickable { thinkingExpanded = !thinkingExpanded }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
                            Text(
                                text = "✨思考过程",
                                fontSize = 11.sp,
                                color = scheme.primary,
                                fontWeight = FontWeight.Medium
    )
                            Icon(
                                MiuixIcons.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = scheme.primary
    )
                        }
                    }

                    if (thinkingExpanded) {
                        SelectionContainer {
                            Text(
                                text = msg.thinkingContent,
                                style = MiuixTheme.textStyles.footnote1.copy(
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp,
                                    color = scheme.onSurfaceVariantSummary
    ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(PocketTokens.Shape.Medium)
                                    .background(scheme.surfaceContainerHigh.copy(alpha = 0.5f))
                                    .padding(8.dp)
    )
                        }
                    }
                }

                // 3. 最终回复内容 (非占位状态时显示，包含流式打字中)
                if ((!msg.isThinking || msg.content != "✨深度求索中...") && msg.content.isNotBlank()) {
                    SelectionContainer {
                        Text(
                            text = msg.content,
                            color = bubbleFg,
                            style = MiuixTheme.textStyles.body2
    )
                    }
                }




                if (!isUser) {
                    msg.clarification?.let { clarification ->
                        if (!clarification.questions.isNullOrEmpty()) {
                            AgentQuestionForm(
                                messageId = msg.id, questions = clarification.questions,
                                enabled = clarificationEnabled, onSubmit = onQuestionSubmit, onCancel = onQuestionCancel
                            )
                        } else {
                        if (clarification.question.isNotBlank() && !msg.content.contains(clarification.question)) {
                            Text(clarification.question, style = MiuixTheme.textStyles.body2)
                        }
                        AgentClarificationChoices(
                            clarification = clarification,
                            enabled = clarificationEnabled,
                            onSelect = onClarificationOption
                        )
                        }
                    }
                    if (!msg.characterStatus.isNullOrBlank()) {
                        Text(
                            text = msg.characterStatus,
                            style = MiuixTheme.textStyles.footnote1,
                            color = scheme.onSurfaceVariantSummary
                        )
                    }
                    AgentPromptDetails(msg.expandedPrompt, msg.expandedNegativePrompt, msg.negativePromptStatus,
                        msg.positiveAliases, msg.negativeAliases)
                }
                // 提交出图异常提示（若有），独立渲染在下方，绝不冲掉正文对话
                if (!msg.errorMessage.isNullOrBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(PocketTokens.Shape.Medium)
                            .background(scheme.error.copy(alpha = 0.12f))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
                        Icon(
                            MiuixIcons.Report,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = scheme.error
    )
                        SelectionContainer {
                            Text(
                                text = msg.errorMessage,
                                style = MiuixTheme.textStyles.footnote1.copy(
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
    ),
                                color = scheme.error
    )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AgentClarificationChoices(
    clarification: AgentClarification,
    enabled: Boolean,
    onSelect: (String) -> Unit
) {
    val scheme = MiuixTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            if (enabled) "选择一项继续，也可以直接回复" else "历史选项",
            style = MiuixTheme.textStyles.footnote1,
            color = scheme.primary
        )
        clarification.options.forEachIndexed { index, option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(PocketTokens.Shape.Medium)
                    .background(if (enabled) scheme.primaryContainer.copy(alpha = 0.5f) else scheme.surfaceContainerHigh)
                    .clickable(enabled = enabled, role = Role.Button) { onSelect(option) }
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 10.dp, vertical = 9.dp)
                    .testTag("agent-clarification-option-$index"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = option,
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (enabled) scheme.onSurface else scheme.onSurfaceVariantSummary
                )
            }
        }
        if (clarification.options.isEmpty()) {
            Text(
                "也可以在输入框里直接说明你的选择。",
                style = MiuixTheme.textStyles.footnote1,
                color = scheme.onSurfaceVariantSummary
            )
        }
    }
}
