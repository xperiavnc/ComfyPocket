package com.comfyui.mobile.ui.screens.agent

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comfyui.mobile.agent.*
import com.comfyui.mobile.ui.components.rememberDialogDismiss
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AgentRuleFilesPanel(
    files: AgentRuleFiles,
    importing: Set<AgentRuleKind>,
    onImport: (AgentRuleKind, android.net.Uri) -> Unit,
    onRemove: (AgentRuleKind) -> Unit
) {
    val dialoguePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onImport(AgentRuleKind.DIALOGUE, it) }
    }
    val drawingPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onImport(AgentRuleKind.DRAWING, it) }
    }
    var preview by remember { mutableStateOf<Pair<AgentRuleKind, AgentRuleDocument>?>(null) }
    var removal by remember { mutableStateOf<Pair<AgentRuleKind, AgentRuleDocument>?>(null) }
    val scheme = MiuixTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Same header as "模型服务" / "可选资源" so the settings page reads as one grouped list.
        com.comfyui.mobile.ui.designsystem.PocketSectionHeader("提示词规则")
        Text("导入自己的 TXT / Markdown 文件。未导入时留空；文件内容用于后续请求。",
            style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
        AgentRuleKind.entries.forEach { kind ->
            val document = files.document(kind)
            val key = if (kind == AgentRuleKind.DIALOGUE) "system" else "draw"
            val reading = kind in importing
            Card(Modifier.fillMaxWidth().testTag("agent-prompt-$key")
                .then(if (document != null) Modifier.clickable { preview = kind to document } else Modifier),
                insideMargin = PaddingValues(com.comfyui.mobile.ui.designsystem.PocketTokens.Space.M)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(kind.title, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.SemiBold)
                        Text(if (reading) "正在导入…" else if (document == null) "未导入" else "已导入",
                            style = MiuixTheme.textStyles.footnote2, color = scheme.onSurfaceVariantSummary)
                    }
                    Text(if (kind == AgentRuleKind.DIALOGUE) "用于聊天与绘图对话中的行为、语气和任务偏好"
                        else "用于绘图提示词的写法、构图和分镜要求",
                        style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                    if (document != null) {
                        Text("${document.name} · ${document.content.length} 字",
                            modifier = Modifier.testTag("agent-rules-$key-name"), maxLines = 1,
                            overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.body2)
                    }
                    // Wrap on narrow screens instead of making small action buttons.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(if (document == null) "导入文件" else "替换文件", enabled = !reading,
                            modifier = Modifier.testTag("agent-rules-$key-import"),
                            onClick = {
                                val types = arrayOf("text/*", "application/octet-stream")
                                if (kind == AgentRuleKind.DIALOGUE) dialoguePicker.launch(types) else drawingPicker.launch(types)
                            }, colors = ButtonDefaults.textButtonColorsPrimary())
                        if (document != null) {
                            TextButton("查看", modifier = Modifier.testTag("agent-rules-$key-view"),
                                onClick = { preview = kind to document })
                            TextButton("移除", modifier = Modifier.testTag("agent-rules-$key-remove"),
                                onClick = { removal = kind to document })
                        }
                    }
                }
            }
        }
    }
    preview?.let { (kind, document) ->
        val dismissal = rememberDialogDismiss { preview = null }
        OverlayDialog(show = dismissal.show, modifier = dismissal.modifier, title = "查看${kind.title}",
            onDismissRequest = dismissal::dismiss, onDismissFinished = dismissal::onDismissFinished) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(document.name, style = MiuixTheme.textStyles.footnote1)
                SelectionContainer {
                    Text(document.content, Modifier.fillMaxWidth().heightIn(max = 340.dp)
                        .verticalScroll(rememberScrollState()).testTag("agent-rules-preview-content"),
                        style = MiuixTheme.textStyles.body2)
                }
                TextButton("关闭", onClick = dismissal::dismiss, modifier = Modifier.fillMaxWidth())
            }
        }
    }
    removal?.let { (kind, document) ->
        val dismissal = rememberDialogDismiss { removal = null }
        OverlayDialog(show = dismissal.show, modifier = dismissal.modifier, title = "移除${kind.title}",
            onDismissRequest = dismissal::dismiss, onDismissFinished = dismissal::onDismissFinished) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("移除「${document.name}」后，此项规则留空。原文件保留，可再次导入。",
                    style = MiuixTheme.textStyles.body2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton("取消", modifier = Modifier.weight(1f), onClick = dismissal::dismiss)
                    TextButton("确认移除", modifier = Modifier.weight(1f), onClick = {
                        if (!dismissal.show) return@TextButton
                        if (files.document(kind) == document) onRemove(kind)
                        dismissal.dismiss()
                    })
                }
            }
        }
    }
}
