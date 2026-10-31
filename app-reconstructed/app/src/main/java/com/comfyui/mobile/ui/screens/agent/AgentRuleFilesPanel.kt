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
    onRemove: (AgentRuleKind) -> Unit,
    onSelect: (AgentRuleKind, String?) -> Unit = { _, _ -> }
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
        Text("导入自己的 TXT / Markdown 文件，可导入多个并随时切换；启用的规则会作为必须遵守的要求发送给模型。",
            style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
        AgentRuleKind.entries.forEach { kind ->
            val document = files.document(kind)
            val library = files.library(kind)
            val key = if (kind == AgentRuleKind.DIALOGUE) "system" else "draw"
            val reading = kind in importing
            Card(Modifier.fillMaxWidth().testTag("agent-prompt-$key")
                .then(if (document != null) Modifier.clickable { preview = kind to document } else Modifier),
                insideMargin = PaddingValues(com.comfyui.mobile.ui.designsystem.PocketTokens.Space.M)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(kind.title, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.SemiBold)
                        Text(when {
                                reading -> "正在导入…"
                                document != null -> "已启用"
                                library.isNotEmpty() -> "未启用"
                                else -> "未导入"
                            },
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
                    // Switch between imported files; "不使用" keeps them but sends no rule.
                    if (library.size > 1 || (library.isNotEmpty() && document == null)) {
                        FlowRow(Modifier.testTag("agent-rules-$key-library"),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            library.forEach { item ->
                                val label = item.name.substringBeforeLast('.').let {
                                    if (it.length > 16) it.take(15) + "…" else it }
                                com.comfyui.mobile.ui.designsystem.PocketPill(label,
                                    selected = document?.name == item.name, enabled = !reading,
                                    onClick = { if (document?.name != item.name) onSelect(kind, item.name) })
                            }
                            com.comfyui.mobile.ui.designsystem.PocketPill("不使用",
                                selected = document == null, enabled = !reading,
                                onClick = { if (document != null) onSelect(kind, null) })
                        }
                    }
                    // Wrap on narrow screens instead of making small action buttons.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(if (library.isEmpty()) "导入文件" else "导入新文件",
                            enabled = !reading && library.size < AgentRuleFiles.MAX_LIBRARY,
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
                Text("从列表移除「${document.name}」，此项规则停用。手机里的原文件不受影响，可再次导入。",
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
