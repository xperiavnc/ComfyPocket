package com.comfyui.mobile.ui.screens.modelcenter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comfyui.mobile.market.MarketViewModel
import com.comfyui.mobile.modelcenter.*
import com.comfyui.mobile.ui.screens.market.MarketDownloadQueuePanel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ModelTasksPage(vm: ModelCenterViewModel, state: ModelCenterState, market: MarketViewModel) {
    var metadata by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModelChip("模型下载", !metadata) { metadata = false }
            ModelChip("资料补全" + state.tasks.enrichment.size.takeIf { it > 0 }?.let { " · $it" }.orEmpty(), metadata) {
                metadata = true
            }
        }
        if (!metadata) key(state.identity) {
            MarketDownloadQueuePanel(state.connected, market.repository::downloadTasks,
                market.repository::taskAction, fullScreen = true)
        } else LazyColumn(contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
            item {
                Text("轻量资料任务 · 与模型权重下载独立", style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSecondaryContainer)
                state.notice?.let { Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary) }
            }
            if (state.tasks.enrichment.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("暂无资料补全任务", style = MiuixTheme.textStyles.body2)
                    Text("在本地模型详情中补全，或批量补全本页资料",
                        style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(top = 6.dp))
                }
            }
            items(state.tasks.enrichment, key = { it.id }) { task ->
                val name = state.page.items.firstOrNull { it.id == task.locationId }?.displayName
                    ?: task.sha256?.take(16)?.plus("…") ?: "待识别模型"
                EnrichmentTaskCard(task, name, state.connected, vm::taskAction)
            }
        }
    }
}

@Composable
internal fun EnrichmentTaskCard(task: ModelEnrichmentTask, name: String, connected: Boolean,
                                action: (String, String) -> Unit) {
    val colors = MiuixTheme.colorScheme
    val label = when (task.status) {
        "queued" -> "排队中"; "hashing" -> "计算指纹"; "matching" -> "在线匹配"; "completed" -> "已补全"
        "unmatched" -> "未匹配"; "paused" -> "已暂停"; "cancelled" -> "已取消"; "failed" -> "失败"
        else -> task.status
    }
    Card(cornerRadius = 16.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(name, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                ModelStatusBadge(label, task.status == "completed")
            }
            if (task.message.isNotBlank()) Text(task.message, style = MiuixTheme.textStyles.footnote1,
                color = colors.onSecondaryContainer)
            if (task.retryAt > 0) Text("可重试时间：${modelTime(task.retryAt)}",
                style = MiuixTheme.textStyles.footnote2, color = colors.onSecondaryContainer)
            if (task.status !in setOf("completed", "cancelled")) Row(
                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                val paused = task.status in setOf("paused", "failed", "unmatched")
                ModelAction(if (paused) "继续" else "暂停",
                    connected && task.retryAt <= System.currentTimeMillis()) {
                    action(task.id, if (paused) "resume" else "pause")
                }
                ModelAction("取消", connected) { action(task.id, "cancel") }
            }
        }
    }
}
