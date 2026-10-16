package com.comfyui.mobile.ui.screens.market

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.comfyui.mobile.model.CivitaiTasksData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale

/** Scoped to the visible dialog; no permanent polling in MainViewModel. */
@Composable
fun MarketDownloadQueueDialog(
    connected: Boolean,
    onDismiss: () -> Unit,
    load: suspend () -> Result<CivitaiTasksData>,
    action: suspend (String, String) -> Result<Unit>
) {
    var data by remember { mutableStateOf<CivitaiTasksData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
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
                } catch (cancelled: CancellationException) {
                    throw cancelled
                }
                delay(2_000)
            }
        }
    }

    OverlayDialog(show = true, title = "电脑下载队列", onDismissRequest = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("由 Mobile Bridge 原生下载；受限模型请在 ComfyUI 设置 → Mobile Bridge → Civitai 配置电脑 Key。",
                style = MiuixTheme.textStyles.footnote1)
            if (!connected) Text("请先连接 ComfyUI 电脑")
            error?.let { Text(it, color = MiuixTheme.colorScheme.onSecondaryContainer) }
            if (connected && data == null && error == null) Text("正在读取下载队列…")
            if (connected && data?.queue?.isEmpty() == true) Text("暂无下载任务")
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(data?.queue.orEmpty(), key = { it.download_id.orEmpty() }) { task ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(task.effectiveName, style = MiuixTheme.textStyles.body1)
                        val label = when (task.status) {
                            "queued" -> "排队中"
                            "downloading" -> "下载中"
                            "paused" -> "已暂停"
                            "verifying" -> "校验 SHA-256"
                            "completed" -> "已完成"
                            "cancelled" -> "已取消"
                            "failed" -> "失败"
                            else -> task.status.orEmpty()
                        }
                        val speed = (task.speed_bytes ?: 0.0) / (1024.0 * 1024.0)
                        Text("$label · ${String.format(Locale.US, "%.1f", task.progress ?: 0.0)}% · " +
                            "${String.format(Locale.US, "%.2f", speed)} MiB/s",
                            style = MiuixTheme.textStyles.footnote1)
                        task.error?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MiuixTheme.textStyles.footnote1)
                        }
                        val id = task.download_id
                        if (connected && !id.isNullOrBlank() &&
                            task.status !in setOf("completed", "cancelled")) {
                            Row {
                                val operations = if (task.status in setOf("paused", "failed"))
                                    listOf("继续" to "resume", "取消" to "cancel")
                                else listOf("暂停" to "pause", "取消" to "cancel")
                                operations.forEach { (text, operation) ->
                                    TextButton(
                                        text = if (busy == id) "处理中…" else text,
                                        enabled = busy == null,
                                        onClick = {
                                            busy = id
                                            scope.launch {
                                                try {
                                                    action(operation, id).onFailure {
                                                        error = it.message ?: "任务操作失败"
                                                    }.onSuccess {
                                                        load().onSuccess { data = it; error = null }
                                                    }
                                                } finally {
                                                    busy = null
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            TextButton(text = "关闭", onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}
