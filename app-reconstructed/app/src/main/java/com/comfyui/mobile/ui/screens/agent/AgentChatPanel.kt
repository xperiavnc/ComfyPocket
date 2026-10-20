package com.comfyui.mobile.ui.screens.agent

import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import com.comfyui.mobile.model.AgentAttachment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import com.comfyui.mobile.ui.rememberAppHaptics
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import com.comfyui.mobile.model.AgentChatMessage
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Send
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import kotlinx.coroutines.launch

@Composable
fun AgentChatPanel(
    messages: List<AgentChatMessage>,
    input: TextFieldValue,
    onInputChange: (TextFieldValue) -> Unit,
    busy: Boolean,
    cardColors: CardColors,
    sidePad: Dp,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    inputEnabled: Boolean = true,
    statusMessage: String = "",
    onStatusClick: () -> Unit = {},
    hasEarlier: Boolean = false,
    onLoadEarlier: () -> Unit = {},
    attachments: List<AgentAttachment> = emptyList(),
    onPickImages: () -> Unit = {},
    onPickFiles: () -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {},
    onStop: () -> Unit = {},
    onAnswerQuestions: ((String, List<com.comfyui.mobile.model.AgentQuestionAnswer>) -> Boolean)? = null,
    onSendMessage: (String) -> Unit
    ) {
    val haptics = rememberAppHaptics()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val focusScope = rememberCoroutineScope()
    var attachMenuOpen by remember { mutableStateOf(false) }

    val scheme = MiuixTheme.colorScheme

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = sidePad, vertical = 6.dp)
            .border(
                width = 1.dp,
                color = scheme.outline.copy(alpha = 0.26f),
                shape = RoundedCornerShape(PanelCorner)
            ),
        cornerRadius = PanelCorner,
        colors = cardColors
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(PanelPadding)) {
            if (statusMessage.isNotBlank()) Text(
                text = statusMessage,
                style = MiuixTheme.textStyles.footnote1,
                color = scheme.onSurfaceVariantSummary,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onStatusClick).padding(bottom = 8.dp)
            )
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("agent-chat-list")
                    // Concentric with the card: 28dp corner - 12dp content padding.
                    .clip(RoundedCornerShape(PanelCorner - PanelPadding))
                    .overScrollVertical(),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
                if (hasEarlier) item(key = "earlier") {
                    Text("加载更早消息", color = scheme.primary,
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onLoadEarlier).padding(10.dp))
                }
                val pendingClarificationId = com.comfyui.mobile.agent.AgentQuestionPolicy.pending(messages)?.id
                items(messages, key = { it.id }) { msg ->
                    AgentBubble(
                        msg = msg,
                        clarificationEnabled = inputEnabled && !busy && msg.id == pendingClarificationId,
                        onClarificationOption = { option -> onSendMessage("我选择：$option") },
                        onQuestionSubmit = { answers ->
                            onAnswerQuestions?.invoke(msg.id, answers) ?: run {
                                onSendMessage(com.comfyui.mobile.agent.AgentQuestionPolicy.answerText(
                                    msg.clarification?.questions.orEmpty(), answers))
                                true
                            }
                        },
                        onQuestionCancel = { onSendMessage("取消本次提问") }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (attachments.isNotEmpty()) {
                AgentAttachmentStrip(
                    attachments = attachments,
                    onRemove = { id -> haptics.tick(); onRemoveAttachment(id) },
                    modifier = Modifier.padding(bottom = 6.dp)
    )
            }
            if (attachMenuOpen && !busy && inputEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
                    AttachMenuChip("图片") { attachMenuOpen = false; haptics.tick(); onPickImages() }
                    AttachMenuChip("文档") { attachMenuOpen = false; haptics.tick(); onPickFiles() }
                }
            }
            val canSend = inputEnabled && !busy && (input.text.isNotBlank() || attachments.isNotEmpty())
            // Bottom-aligned so the buttons stay put while the field grows to 5 lines.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
                val plusRotation by animateFloatAsState(if (attachMenuOpen) 45f else 0f, label = "attachPlus")
                ChatRoundButton(
                    filled = false,
                    enabled = inputEnabled && !busy,
                    onClick = { haptics.tick(); attachMenuOpen = !attachMenuOpen }
    ) { tint ->
                    Icon(
                        MiuixIcons.Add,
                        contentDescription = if (attachMenuOpen) "收起附件菜单" else "添加附件",
                        modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = plusRotation },
                        tint = tint
    )
                }
                BasicTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = ChatButtonSize)
                        .clip(RoundedCornerShape(ChatButtonSize / 2))
                        .background(scheme.surface)
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .bringIntoViewRequester(bringIntoViewRequester)
                        .onFocusEvent { fs ->
                            if (fs.isFocused) {
                                focusScope.launch { bringIntoViewRequester.bringIntoView() }
                            }
                        },
                    enabled = inputEnabled && !busy,
                    maxLines = 5,
                    textStyle = MiuixTheme.textStyles.body2.copy(color = scheme.onSurface),
                    cursorBrush = SolidColor(scheme.primary),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (input.text.isEmpty()) {
                                Text(
                                    text = if (com.comfyui.mobile.agent.AgentQuestionPolicy.pending(messages) != null)
                                        "也可以直接回复，或说“取消本次提问”" else "Ciallo～(∠・ω< )⌒☆",
                                    style = MiuixTheme.textStyles.body2,
                                    color = scheme.onSurfaceVariantSummary,
                                    maxLines = 1
    )
                            }
                            inner()
                        }
                    }
    )
                ChatRoundButton(
                    filled = true,
                    enabled = busy || canSend,
                    onClick = {
                        if (busy) {
                            haptics.tick()
                            onStop()
                        } else {
                            attachMenuOpen = false
                            haptics.confirm()
                            onSendMessage(input.text.trim())
                        }
                    }
    ) { tint ->
                    Icon(
                        if (busy) MiuixIcons.Close else MiuixIcons.Send,
                        contentDescription = if (busy) "停止生成" else "发送",
                        modifier = Modifier.size(18.dp),
                        tint = tint
    )
                }
            }
        }
    }
}

private val ChatButtonSize = 40.dp
private val PanelCorner = 28.dp
private val PanelPadding = 12.dp
/**
 * Circular input-row capsule.
 * Card is surfaceContainerHighest — capsules use [surface] so they stay visible without outline chrome.
 * [filled] + enabled = solid primary (send / stop).
 */
@Composable
private fun ChatRoundButton(
    filled: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable (tint: Color) -> Unit
) {
    val scheme = MiuixTheme.colorScheme
    // One step off the chat card (highest) so stadium chips read clearly in light and dark.
    val capsule = scheme.surface
    val bg = when {
        filled && enabled -> scheme.primary
        enabled -> capsule
        else -> capsule.copy(alpha = 0.55f)
    }
    val tint = when {
        filled && enabled -> scheme.onPrimary
        enabled -> scheme.onSurface
        else -> scheme.onSurface.copy(alpha = 0.38f)
    }
    Box(
        modifier = Modifier
            .size(ChatButtonSize)
            .clip(CircleShape)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content(tint)
    }
}

@Composable
private fun AttachMenuChip(label: String, onClick: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(scheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(text = label, style = MiuixTheme.textStyles.footnote1, color = scheme.onSurface)
    }
}

/** Thumbnails for images, name chips for text files. [onRemove] null = read-only (message bubble). */
@Composable
fun AgentAttachmentStrip(
    attachments: List<AgentAttachment>,
    modifier: Modifier = Modifier,
    onRemove: ((String) -> Unit)? = null
    ) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        attachments.forEach { att ->
            Box(
                modifier = Modifier
                    .then(if (att.isImage) Modifier.size(56.dp) else Modifier.height(40.dp).widthIn(max = 180.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .background(scheme.surfaceContainerHigh),
                contentAlignment = Alignment.CenterStart
    ) {
                if (att.isImage) {
                    AsyncImage(
                        model = att.uri,
                        contentDescription = att.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
    )
                } else {
                    Text(
                        text = "📄 ${att.name} · ${(att.sizeBytes + 1023) / 1024}KB",
                        style = MiuixTheme.textStyles.footnote1,
                        color = scheme.onSurface,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 10.dp, end = if (onRemove != null) 26.dp else 10.dp)
    )
                }
                if (onRemove != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                            .size(18.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .clickable { onRemove(att.id) },
                        contentAlignment = Alignment.Center
    ) {
                        Icon(MiuixIcons.Close, contentDescription = "移除附件", modifier = Modifier.size(12.dp), tint = Color.White)
                    }
                }
            }
        }
    }
}
