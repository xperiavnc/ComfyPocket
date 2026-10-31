package com.comfyui.mobile.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.comfyui.mobile.network.RhTaskRecord
import com.comfyui.mobile.ui.LocalFloatingNavClearance
import com.comfyui.mobile.ui.MainViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Display labels of non-terminal task states (RhTaskRecord.taskStatus = TaskState.label). */
private val UNFINISHED_LABELS: Set<String> =
    com.comfyui.mobile.backend.TaskState.entries.filter { !it.isTerminal }.map { it.label }.toSet()

@Composable
fun RhTaskRecordsScreen(navController: NavController, viewModel: MainViewModel) {
    val rhTaskRecords by viewModel.rhTaskRecords.collectAsState()
    val rhAccountData by viewModel.rhAccountData.collectAsState()
    val isRefreshingCloudTasks by viewModel.isRefreshingCloudTasks.collectAsState()
    val rhAppRunning by viewModel.rhAppRunning.collectAsState()
    val servers by viewModel.servers.collectAsState()
    val currentServerId by viewModel.currentServerId.collectAsState()
    val currentServer = servers.firstOrNull { it.effectiveId == currentServerId || it.id == currentServerId }

    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    val floatingNavClearance = LocalFloatingNavClearance.current
    val scheme = MiuixTheme.colorScheme

    // 仅展示本机真实记录的云端任务（不再用 ComfyUI 历史合成记录、默认价格或当前时间）
    val combinedTasks = remember(rhTaskRecords) {
        rhTaskRecords.sortedByDescending { it.timestamp }
    }

    // The page lists ALL RunningHub records (every RH profile + imported legacy ones); 清除 removes exactly these.
    val unfinishedCount = remember(combinedTasks) {
        combinedTasks.count { it.taskStatus in UNFINISHED_LABELS }
    }
    var showClearDialog by remember { mutableStateOf(false) }
    val isRh = currentServer?.isRunningHub == true

    // 累计消耗只统计实测（余额差值）得到的消耗；未知消耗不计入
    val totalCoinsSpent = remember(combinedTasks) {
        combinedTasks.filter { !it.costEstimated }.sumOf { it.costCoins }
    }

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            top.yukonga.miuix.kmp.basic.TopAppBar(
                title = "任务记录",
                color = scheme.background,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = "返回"
    )
                    }
                },
                actions = {
                    // 刷新云端任务 (current RH profile): re-query unfinished tasks, resume polling, download outputs,
                    // mark old proxy-mode ones lost; the balance is refreshed too. Result comes as a toast.
                    IconButton(
                        onClick = {
                            viewModel.refreshCloudTasks()
                            viewModel.fetchRunningHubAccountStatus()
                        },
                        enabled = isRh && !isRefreshingCloudTasks
    ) {
                        if (isRefreshingCloudTasks) {
                            CircularProgressIndicator(strokeWidth = 2.dp, size = 20.dp)
                        } else {
                            Icon(
                                imageVector = MiuixIcons.Refresh,
                                contentDescription = "刷新云端任务"
    )
                        }
                    }
                    IconButton(
                        onClick = { showClearDialog = true },
                        enabled = combinedTasks.isNotEmpty()
    ) {
                        Icon(
                            imageVector = MiuixIcons.Delete,
                            contentDescription = "清除全部任务记录"
    )
                    }
                }
    )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = 24.dp + floatingNavClearance
    ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
            // 概览卡片
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.defaultColors(color = scheme.secondaryContainer)
    ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
    ) {
                            Column {
                                Text(
                                    text = "余额",
                                    style = MiuixTheme.textStyles.body2,
                                    color = scheme.onSecondaryContainer.copy(alpha = 0.72f)
    )
                                Spacer(modifier = Modifier.height(4.dp))
                                val coins = rhAccountData?.displayCoins
                                val coinsStr = if (coins != null && coins.isFinite()) {
                                    if (coins % 1.0 == 0.0) "${coins.toLong()} RH 币" else String.format(Locale.US, "%.1f RH 币", coins)
                                } else "--"
                                Text(
                                    text = coinsStr,
                                    style = MiuixTheme.textStyles.title2.copy(fontWeight = FontWeight.Bold),
                                    color = scheme.onSecondaryContainer
    )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "累计消耗",
                                    style = MiuixTheme.textStyles.body2,
                                    color = scheme.onSecondaryContainer.copy(alpha = 0.72f)
    )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "-${String.format(Locale.US, "%.1f", totalCoinsSpent)} 币",
                                    style = MiuixTheme.textStyles.title2.copy(fontWeight = FontWeight.Bold),
                                    color = scheme.primary
    )
                            }
                        }
                    }
                }
            }

            // 任务列表标题
            item {
                SmallTitle(
                    text = "任务明细 (${combinedTasks.size})"
    )
            }

            if (combinedTasks.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.defaultColors(color = scheme.surfaceContainer)
    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp, horizontal = 20.dp),
                            contentAlignment = Alignment.Center
    ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "暂无任务记录",
                                    style = MiuixTheme.textStyles.title3.copy(fontWeight = FontWeight.SemiBold)
    )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "跑过任务后会出现在这里",
                                    style = MiuixTheme.textStyles.body2,
                                    color = scheme.onSecondaryContainer.copy(alpha = 0.7f)
    )
                            }
                        }
                    }
                }
            } else {
                items(combinedTasks, key = { it.localId ?: it.taskId }) { task ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.defaultColors(color = scheme.surfaceContainer),
                        pressFeedbackType = PressFeedbackType.Sink,
                        showIndication = true,
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Task ID", task.taskId))
                            viewModel.showToast("已复制任务 ID: ${task.taskId}")
                        }
    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
    ) {
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
    ) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(scheme.primary.copy(alpha = 0.12f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
                                        Text(
                                            text = task.taskStatus,
                                            style = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.Medium),
                                            color = if (task.taskStatus in setOf("已完成", "运行中", "排队中", "提交中", "已暂停")) scheme.primary else scheme.error,
                                            fontSize = 11.sp
    )
                                    }
                                    val displayName = task.taskName
                                        ?.removePrefix("Agent · ")
                                        ?.removePrefix("Agent ·")
                                        ?.removePrefix("Agent: ")
                                        ?.removePrefix("Agent:")
                                        ?.removeSuffix(".json")
                                        ?.removeSuffix(".JSON")
                                    if (!displayName.isNullOrBlank()) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = displayName,
                                            style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
    )
                                    }
                                }

                                // 明确展示消耗的余额：官方 API 返回的真实扣费优先，其次为余额差值；否则显示未知
                                val inProgress = task.taskStatus in setOf("运行中", "排队中", "提交中", "已暂停")
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = when {
                                            !task.costEstimated -> "-${String.format(Locale.US, "%.2f", task.costCoins)} RH 币"
                                            task.taskStatus == "已暂停" -> "已暂停"
                                            inProgress -> "进行中"
                                            else -> "消耗未知"
                                        },
                                        style = MiuixTheme.textStyles.title3.copy(fontWeight = FontWeight.Bold),
                                        color = if (task.costEstimated) scheme.onSecondaryContainer.copy(alpha = 0.6f) else scheme.primary
    )
                                    val costNote = buildList {
                                        when (task.costSource) {
                                            "API" -> if (!task.costEstimated) add("官方扣费")
                                            "BALANCE_DELTA" -> if (!task.costEstimated) add("余额差值")
                                        }
                                        task.costMoney?.takeIf { it > 0.0 }?.let { add(String.format(Locale.US, "¥%.3f", it)) }
                                        task.durationSec?.takeIf { it > 0.0 }?.let { add(String.format(Locale.US, "%.0f 秒", it)) }
                                    }.joinToString(" · ")
                                    if (costNote.isNotBlank()) {
                                        Text(
                                            text = costNote,
                                            style = MiuixTheme.textStyles.body2,
                                            color = scheme.onSecondaryContainer.copy(alpha = 0.6f),
                                            fontSize = 11.sp
    )
                                    }
                                }
                            }

                            if (!task.errorMessage.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = task.errorMessage,
                                    style = MiuixTheme.textStyles.body2,
                                    color = scheme.error,
                                    fontSize = 12.sp,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
    )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // 任务 ID 展示区 (点击整张卡片即可复制)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(scheme.surfaceContainerHigh)
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
    ) {
                                    Text(
                                        text = task.taskId,
                                        style = MiuixTheme.textStyles.body2.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp
    ),
                                        color = scheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "轻触复制",
                                        style = MiuixTheme.textStyles.footnote2,
                                        color = scheme.onSecondaryContainer.copy(alpha = 0.6f),
                                        fontSize = 10.sp
    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
    ) {
                                val timeStr = remember(task.timestamp) {
                                    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                                    sdf.format(Date(task.timestamp))
                                }
                                Text(
                                    text = timeStr,
                                    style = MiuixTheme.textStyles.body2,
                                    color = scheme.onSecondaryContainer.copy(alpha = 0.65f),
                                    fontSize = 11.sp
    )
                                val tierInfo = listOfNotNull(
                                    task.gpuTier?.takeIf { it.isNotBlank() },
                                    if (task.channel != "official_api") "旧版记录" else null
    ).joinToString(" · ")
                                if (tierInfo.isNotBlank()) {
                                    Text(
                                        text = tierInfo,
                                        style = MiuixTheme.textStyles.body2,
                                        color = scheme.onSecondaryContainer.copy(alpha = 0.65f),
                                        fontSize = 11.sp
    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    OverlayDialog(
        show = showClearDialog,
        title = "清除任务记录？",
        onDismissRequest = { showClearDialog = false }
    ) {
        Column {
            Text(
                text = "清除本页 ${combinedTasks.size} 条记录。相册文件保留。",
                style = MiuixTheme.textStyles.body1
    )
            if (unfinishedCount > 0 || rhAppRunning) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = buildString {
                        if (unfinishedCount > 0) append("有 $unfinishedCount 个进行中，清除后不再跟踪。")
                        if (rhAppRunning) append("批量提交也会停止。")
                    },
                    style = MiuixTheme.textStyles.body2,
                    color = scheme.error
    )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = "取消",
                    onClick = { showClearDialog = false },
                    modifier = Modifier.weight(1f),
                    )
                Spacer(modifier = Modifier.width(12.dp))
                TextButton(
                    text = "清除",
                    colors = ButtonDefaults.textButtonColorsPrimary(
                        color = scheme.error,
                        textColor = scheme.onError
    ),
                    onClick = {
                        showClearDialog = false
                        viewModel.clearRhTaskRecords()
                    },
                    modifier = Modifier.weight(1f),
                    )
            }
        }
    }
}
