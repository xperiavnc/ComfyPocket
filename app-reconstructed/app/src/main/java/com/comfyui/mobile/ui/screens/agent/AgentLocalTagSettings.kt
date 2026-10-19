package com.comfyui.mobile.ui.screens.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.comfyui.mobile.agent.AgentPromptSettings
import com.comfyui.mobile.taglib.LocalTagEntry
import com.comfyui.mobile.taglib.LocalTagLibraryState
import com.comfyui.mobile.ui.components.rememberDialogDismiss
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AgentLocalTagSettings(
    settings: AgentPromptSettings,
    libraryState: LocalTagLibraryState,
    search: suspend (String, String) -> List<LocalTagEntry>,
    onImport: () -> Unit,
    onRemove: () -> Unit,
    onSave: (AgentPromptSettings) -> Unit
) {
    val scheme = MiuixTheme.colorScheme
    val info = libraryState.installed
    val ready = info != null && !libraryState.busy && !libraryState.checking
    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf("tag") }
    var lookupExpanded by rememberSaveable { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<LocalTagEntry>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    LaunchedEffect(query, kind, info?.sha256, ready, lookupExpanded) {
        results = emptyList()
        status = ""
        if (!ready || !lookupExpanded || query.isBlank()) return@LaunchedEffect
        status = "正在查询…"
        delay(250)
        try {
            results = search(query, kind)
            status = if (results.isEmpty()) "未找到匹配词，可继续使用自定义提示词" else "找到 ${results.size} 项"
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            status = "词库读取失败，请重新导入"
        }
    }
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("本地词库", Modifier.weight(1f), style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.SemiBold)
                Text(if (info == null) "未安装" else "已安装", style = MiuixTheme.textStyles.footnote1,
                    color = if (info == null) scheme.onSurfaceVariantSummary else scheme.primary)
            }
            when {
                libraryState.checking -> Text("正在检查本地资源…", style = MiuixTheme.textStyles.footnote1)
                libraryState.busy -> Text(libraryState.operation, style = MiuixTheme.textStyles.footnote1, color = scheme.primary)
                info == null -> Text("按需导入标签与角色词库，支持中文、英文和别名离线查词。",
                    style = MiuixTheme.textStyles.body2, color = scheme.onSurfaceVariantSummary)
                else -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(String.format(Locale.ROOT, "%,d", info.tags), style = MiuixTheme.textStyles.title3)
                            Text("标签", style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(String.format(Locale.ROOT, "%,d", info.characters), style = MiuixTheme.textStyles.title3)
                            Text("角色", style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("绘图前查词", style = MiuixTheme.textStyles.body1)
                            Text("修改后立即保存", style = MiuixTheme.textStyles.footnote2, color = scheme.onSurfaceVariantSummary)
                        }
                        Switch(settings.useLocalLibrary, onCheckedChange = { onSave(settings.copy(useLocalLibrary = it)) },
                            enabled = ready, modifier = Modifier.testTag("agent-local-tags-enabled"))
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = onImport, enabled = !libraryState.busy && !libraryState.checking,
                    colors = if (info == null) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()) {
                    Text(if (info == null) "导入词库" else "更新词库")
                }
                if (info != null) {
                    TextButton(if (lookupExpanded) "收起查词" else "离线查词", enabled = ready,
                        onClick = { lookupExpanded = !lookupExpanded })
                    TextButton("移除", enabled = ready, onClick = { confirmRemove = true })
                }
            }
            AnimatedVisibility(lookupExpanded && info != null) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("tag" to "普通词", "character" to "角色").forEach { (id, title) ->
                            TextButton((if (kind == id) "✓ " else "") + title, onClick = { kind = id }, enabled = ready,
                                modifier = Modifier.testTag("agent-tag-kind-$id"))
                        }
                    }
                    TextField(query, { query = it.take(96) }, label = "中文 / 英文 / 别名", singleLine = true,
                        modifier = Modifier.fillMaxWidth(), enabled = ready)
                    if (status.isNotEmpty()) Text(status, style = MiuixTheme.textStyles.footnote1,
                        color = scheme.onSurfaceVariantSummary)
                    if (results.isNotEmpty()) Column(
                        Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) { results.forEach { LocalTagEntryText(it) } }
                }
            }
            AgentSettingsDisclosure("资源说明", if (info == null) "词库单独安装，不增加默认 APK 体积" else
                "${String.format(Locale.ROOT, "%,d", info.translatedEntries)} 项有中文对照") {
                Text("选择 ComfyPocket-tag-library.zip 即可安装或更新。未安装时 Agent 照常工作；移除只删除词库，不影响人设、聊天或模型配置。",
                    style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                if (info != null) Text("版本 ${info.revision}\n${info.untranslatedEntries} 项保留英文待补译。来源：ai-tag-toolbox、ffdkj Danbooru 中英对照。候选仅供参考，未命中保留原词。",
                    style = MiuixTheme.textStyles.footnote2, color = scheme.onSurfaceVariantSummary)
            }
        }
    }
    if (confirmRemove) {
        val dismissal = rememberDialogDismiss { confirmRemove = false }
        OverlayDialog(show = dismissal.show, modifier = dismissal.modifier, title = "移除本地词库？",
            onDismissRequest = dismissal::dismiss, onDismissFinished = dismissal::onDismissFinished) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("移除后可随时重新导入，Agent 的其他设置会保留。", style = MiuixTheme.textStyles.body2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton("取消", modifier = Modifier.weight(1f), onClick = dismissal::dismiss)
                    TextButton("确认移除", modifier = Modifier.weight(1f), onClick = {
                        if (dismissal.show) { onRemove(); dismissal.dismiss() }
                    })
                }
            }
        }
    }
}
