package com.comfyui.mobile.ui.screens.market

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.comfyui.mobile.model.CivitaiDownloadTaskItem
import com.comfyui.mobile.model.CivitaiTasksData
import com.comfyui.mobile.ui.components.rememberDialogDismiss
import com.comfyui.mobile.ui.screens.modelcenter.ModelAction
import com.comfyui.mobile.ui.screens.modelcenter.ModelProgressTrack
import com.comfyui.mobile.ui.screens.modelcenter.ModelStatusBadge
import com.comfyui.mobile.ui.screens.modelcenter.modelBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale

@Composable
fun MarketDownloadQueueDialog(
    connected: Boolean, onDismiss: () -> Unit,
    load: suspend () -> Result<CivitaiTasksData>, action: suspend (String, String) -> Result<Unit>
) {
    val dismiss = rememberDialogDismiss(onDismiss)
    OverlayDialog(show = dismiss.show, title = "电脑下载队列", onDismissRequest = dismiss::dismiss,
        onDismissFinished = dismiss::onDismissFinished) {
        Column(dismiss.modifier) {
            MarketDownloadQueuePanel(connected, load, action)
            TextButton(text = "关闭", onClick = dismiss::dismiss,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}

/** One lazy scroll surface in the page; the legacy dialog remains height-bounded. */
@Composable
fun MarketDownloadQueuePanel(
    connected: Boolean, load: suspend () -> Result<CivitaiTasksData>,
    action: suspend (String, String) -> Result<Unit>, fullScreen: Boolean = false
) {
    var data by remember { mutableStateOf<CivitaiTasksData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var showHelp by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(connected, lifecycle) {
        data = null
        error = null
        if (!connected) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                try {
                    load().onSuccess { data = it; error = null }
                        .onFailure { error = it.message ?: "下载队列加载失败" }
                } catch (cancelled: CancellationException) { throw cancelled }
                delay(2_000)
            }
        }
    }
    val queue = data?.queue.orEmpty()
    val running = queue.count { it.status in setOf("queued", "downloading", "verifying") }
    Column(if (fullScreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = if (fullScreen) 16.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(if (connected) "$running 项进行中 · ${queue.count { it.status == "completed" }} 项已完成"
                else "电脑尚未连接", style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSecondaryContainer, modifier = Modifier.weight(1f))
            ModelAction("说明") { showHelp = !showHelp }
        }
        if (showHelp) Text("下载在电脑端执行。受限模型的 Key / 代理请在 ComfyUI 设置 → Mobile Bridge → Civitai 配置。",
            style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = if (fullScreen) 16.dp else 0.dp, vertical = 8.dp))
        LazyColumn(modifier = if (fullScreen) Modifier.weight(1f).fillMaxWidth()
            else Modifier.fillMaxWidth().heightIn(max = 360.dp),
            contentPadding = PaddingValues(horizontal = if (fullScreen) 16.dp else 0.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            error?.let { message -> item { Text(message, style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.error) } }
            if (queue.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(when { !connected -> "连接电脑后查看下载任务"
                        data == null && error == null -> "正在读取下载队列…"; else -> "暂无下载任务" },
                        style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSecondaryContainer)
                    if (connected && data != null) Text("从在线集市选择模型即可添加", style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(top = 6.dp))
                }
            }
            items(queue, key = { it.download_id ?: it.effectiveName }) { task ->
                DownloadTaskCard(task, connected, busy) { operation, id ->
                    busy = id
                    scope.launch {
                        try {
                            action(operation, id).onFailure { error = it.message ?: "任务操作失败" }
                                .onSuccess { load().onSuccess { data = it; error = null } }
                        } finally { busy = null }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DownloadTaskCard(
    task: CivitaiDownloadTaskItem, connected: Boolean, busy: String?,
    onAction: (operation: String, id: String) -> Unit
) {
    val colors = MiuixTheme.colorScheme
    val label = when (task.status) {
        "queued" -> "排队中"; "downloading" -> "下载中"; "paused" -> "已暂停"
        "verifying" -> "校验中"; "completed" -> "已完成"; "cancelled" -> "已取消"; "failed" -> "失败"
        else -> task.status.orEmpty().ifBlank { "状态未知" }
    }
    val completed = task.status == "completed"
    Card(cornerRadius = 16.dp, modifier = Modifier.fillMaxWidth().testTag("download-task-${task.download_id}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(task.effectiveName, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                ModelStatusBadge(label, completed)
            }
            if (!completed && task.status != "cancelled")
                ModelProgressTrack(((task.progress ?: 0.0) / 100.0).toFloat())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (completed) "已保存到电脑" else String.format(Locale.US, "%.1f%%", task.progress ?: 0.0),
                    style = MiuixTheme.textStyles.footnote1, color = colors.onSecondaryContainer)
                val size = task.total_size?.takeIf { it > 0 }?.toLong()
                Text(if (task.status == "downloading")
                    String.format(Locale.US, "%.2f MiB/s", (task.speed_bytes ?: 0.0) / (1024.0 * 1024.0))
                    else size?.let(::modelBytes).orEmpty(),
                    style = MiuixTheme.textStyles.footnote1, color = colors.onSecondaryContainer)
            }
            task.error?.takeIf { it.isNotBlank() }?.let { Text(it, style = MiuixTheme.textStyles.footnote2, color = colors.error) }
            task.index_status?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MiuixTheme.textStyles.footnote2, color = colors.onSecondaryContainer)
            }
            val id = task.download_id
            if (connected && !id.isNullOrBlank() && task.status !in setOf("completed", "cancelled")) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    val paused = task.status in setOf("paused", "failed")
                    ModelAction(if (busy == id) "处理中…" else if (paused) "继续" else "暂停", busy == null) {
                        onAction(if (paused) "resume" else "pause", id)
                    }
                    ModelAction("取消", busy == null) { onAction("cancel", id) }
                }
            }
        }
    }
}
