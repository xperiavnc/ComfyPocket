package com.comfyui.mobile.ui.screens.modelcenter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.comfyui.mobile.market.MarketViewModel
import com.comfyui.mobile.modelcenter.ModelCenterState
import com.comfyui.mobile.modelcenter.ModelCenterViewModel
import com.comfyui.mobile.ui.screens.market.MarketDownloadQueuePanel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ModelTasksPage(vm: ModelCenterViewModel, state: ModelCenterState, market: MarketViewModel) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()) {
        item {
            Card(cornerRadius = 16.dp) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("模型权重下载", style = MiuixTheme.textStyles.title4)
                    MarketDownloadQueuePanel(state.connected, market.repository::downloadTasks, market.repository::taskAction)
                }
            }
        }
        item {
            Text("资料补全 · 独立轻量队列", style = MiuixTheme.textStyles.title4)
            Text("使用电脑 Civitai Key / HTTP 代理。资料失败不影响已下载的模型，也不会自动重下权重。",
                style = MiuixTheme.textStyles.footnote1)
            state.notice?.let { Text(it, color = MiuixTheme.colorScheme.primary) }
            if (!state.connected) Text("请连接电脑后查看任务实时状态")
            if (state.tasks.enrichment.isEmpty()) Text("暂无资料补全任务")
        }
        items(state.tasks.enrichment, key = { it.id }) { task ->
            Card(cornerRadius = 16.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(task.sha256?.take(16)?.plus("…") ?: "待计算文件指纹")
                    val label = when (task.status) {
                        "queued" -> "排队中"; "hashing" -> "计算指纹"; "matching" -> "在线匹配"
                        "completed" -> "已保存资料"; "unmatched" -> "未匹配来源"; "paused" -> "已暂停"
                        "cancelled" -> "已取消"; "failed" -> "失败"; else -> task.status
                    }
                    Text("$label · ${task.message}", style = MiuixTheme.textStyles.footnote1)
                    if (task.retryAt > 0) Text("下次可重试：${modelTime(task.retryAt)}",
                        style = MiuixTheme.textStyles.footnote1)
                    if (task.status !in setOf("completed", "cancelled")) Row {
                        val paused = task.status in setOf("paused", "failed", "unmatched")
                        TextButton(text = if (paused) "继续" else "暂停",
                            enabled = state.connected && task.retryAt <= System.currentTimeMillis(),
                            onClick = { vm.taskAction(task.id, if (paused) "resume" else "pause") })
                        TextButton(text = "取消", enabled = state.connected,
                            onClick = { vm.taskAction(task.id, "cancel") })
                    }
                }
            }
        }
    }
}
