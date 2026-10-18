package com.comfyui.mobile.ui.screens

import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings
import com.comfyui.mobile.ui.screens.dashboard.UserProfileCard
import com.comfyui.mobile.ui.screens.dashboard.EditUserProfileDialog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.lifecycle.repeatOnLifecycle
import com.comfyui.mobile.ui.screens.dashboard.LocalSystemStatusPreview
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.comfyui.mobile.network.ConnectionState
import com.comfyui.mobile.ui.MainViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import com.comfyui.mobile.ui.MainViewModelRhConn
import com.comfyui.mobile.ui.RhConnState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.input.nestedscroll.nestedScroll
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.utils.overScrollVertical
import com.comfyui.mobile.ui.LocalFloatingNavClearance
import top.yukonga.miuix.kmp.utils.PressFeedbackType

private val DashCardRadius = 28.dp
private val DashInnerRadius = 22.dp

@Composable
fun DashboardScreen(navController: NavController, viewModel: MainViewModel) {
    val connectionState by viewModel.connectionState.collectAsState()
    val stats by viewModel.systemStats.collectAsState()
    val queueSize by viewModel.queueSize.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val workflowName by viewModel.currentWorkflowName.collectAsState()
    val latestGenerationList by viewModel.latestGenerationList.collectAsState()
    val savedIp by viewModel.savedIp.collectAsState()
    val servers by viewModel.servers.collectAsState()
    val currentServerId by viewModel.currentServerId.collectAsState()
    val currentServer = servers.firstOrNull { it.effectiveId == currentServerId || it.id == currentServerId }
    val isRh = currentServer?.isRunningHub == true
    val serverDisplayName = currentServer?.displayName ?: savedIp
    val isRunning by viewModel.isRunning.collectAsState()
    val lastMsgType by viewModel.lastMessageType.collectAsState()
    val rhAccountData by viewModel.rhAccountData.collectAsState()
    val isRefreshingRhAccount by viewModel.isRefreshingRhAccount.collectAsState()
    val rhStatus by viewModel.rhStatusLabel.collectAsState()
    val rhConnState by viewModel.rhConnState.collectAsState()
    val rhConnMessage by viewModel.rhConnMessage.collectAsState()
    val rhQueueStatus by viewModel.rhQueueStatus.collectAsState()
    val rhActive by viewModel.rhActiveTask.collectAsState()
    val rhTaskRecords by viewModel.rhTaskRecords.collectAsState()
    val assetsSummary by viewModel.assetsSummary.collectAsState()

    // RunningHub: keep the running / queued counts fresh while the home page is visible (silent, 30 s).
    LaunchedEffect(isRh, currentServerId, rhConnState) {
        if (isRh && rhConnState == RhConnState.AUTHORIZED) {
            while (true) {
                MainViewModelRhConn.refreshQueueStatus(viewModel)
                kotlinx.coroutines.delay(30_000L)
            }
        }
    }

    var showFullScreen by remember { mutableStateOf(false) }
    var initialPage by remember { mutableIntStateOf(0) }

    val userNickname by viewModel.userNickname.collectAsState()
    val userBio by viewModel.userBio.collectAsState()
    val userQq by viewModel.userQq.collectAsState()
    var showEditProfileDialog by remember { mutableStateOf(false) }

    val connected = connectionState == ConnectionState.CONNECTED
    // RunningHub: derived from the real API key check (account status), never from a WebSocket.
    val statusOk = if (isRh) rhStatus.second else connected
    val statusText = if (isRh) rhStatus.first else if (connected) "已连接" else "未连接"

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(isRh, currentServerId, connected, lifecycleOwner) {
        if (!isRh && connected) {
            viewModel.fetchAssetsSummary()
            lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                while (true) {
                    viewModel.fetchSystemStats(refreshAssets = false)
                    kotlinx.coroutines.delay(5_000L)
                }
            }
        }
    }
    val busy = isRunning || (progress > 0f && progress < 1f) || queueSize > 0
    val scheme = MiuixTheme.colorScheme
    val cardColor = scheme.secondaryContainer
    val onCard = scheme.onSecondaryContainer
    val cardColors = CardDefaults.defaultColors(color = cardColor, contentColor = onCard)

    val scrollBehavior = MiuixScrollBehavior()
    val floatingNavClearance = LocalFloatingNavClearance.current

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                title = if (isRh) "RunningHub" else "ComfyUI",
                largeTitle = if (isRh) "RunningHub" else "ComfyUI",
                color = scheme.background,
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = {
                        if (isRh && rhConnState != RhConnState.AUTHORIZED) {
                            viewModel.retryRhConnection()
                        } else {
                            viewModel.ensureConnected(forceProbe = true)
                        }
                        viewModel.fetchQueue()
                        viewModel.fetchSystemStats()
                    }) {
                        Icon(
                            MiuixIcons.Refresh,
                            contentDescription = "刷新"
    )
                    }
                    IconButton(onClick = { navController.navigate("settings") }) {
                        Icon(
                            MiuixIcons.Settings,
                            contentDescription = "设置"
    )
                    }
                }
    )
        }
    ) { innerPadding ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .overScrollVertical()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = innerPadding.calculateTopPadding() + 8.dp, bottom = 24.dp + floatingNavClearance)
    ) {

        UserProfileCard(
            nickname = userNickname,
            bio = userBio,
            qq = userQq,
            onAvatarClick = { showEditProfileDialog = true },
            isRh = isRh,
            rhUserName = rhAccountData?.displayUser,
            statusText = statusText,
            statusOk = statusOk,
            serverDisplayName = serverDisplayName,
            onServerClick = { navController.navigate("servers") },
            queueSize = queueSize,
            isBusy = busy,
            latestCount = latestGenerationList.size,
            onLatestClick = if (latestGenerationList.isNotEmpty()) {
                {
                    initialPage = 0
                    showFullScreen = true
                }
            } else null,
            totalModels = assetsSummary?.totalModels,
            totalPlugins = assetsSummary?.totalPlugins,
            onAssetsClick = { navController.navigate("model_hub") },
            onLocalModelsClick = { navController.navigate("local_models") },
            onPluginsClick = { navController.navigate("plugins") },
            authorized = rhConnState == RhConnState.AUTHORIZED,
            rhCoins = rhAccountData?.displayCoins,
            rhMoney = rhAccountData?.displayMoney,
            rhCurrency = rhAccountData?.currency,
            rhRunning = rhQueueStatus?.runningCount,
            rhQueued = rhQueueStatus?.queuedCount,
            rhRefreshing = isRefreshingRhAccount,
            onRhTasksClick = { navController.navigate("cloud_tasks") },
            cardColors = cardColors,
            onCardColor = onCard
    )

        val activeTask = rhActive.first
        if (isRh && activeTask != null) {
            // Cloud: current official task (排队中 / 运行中) with the time since it was submitted.
            var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(activeTask.localId) {
                while (true) {
                    nowMs = System.currentTimeMillis()
                    kotlinx.coroutines.delay(1_000L)
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = DashCardRadius,
                colors = cardColors,
                onClick = { navController.navigate("cloud_tasks") },
                pressFeedbackType = PressFeedbackType.Tilt,
                showIndication = true
    ) {
                Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
    ) {
                        val activeTitle = activeTask.title
                            ?.removePrefix("Agent · ")
                            ?.removePrefix("Agent ·")
                            ?.removePrefix("Agent: ")
                            ?.removePrefix("Agent:")
                            ?.removeSuffix(".json")
                            ?.removeSuffix(".JSON")
                            ?.takeIf { it.isNotBlank() } ?: "云端任务"
                        Text(
                            text = activeTitle,
                            style = MiuixTheme.textStyles.body1,
                            color = onCard,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(end = 8.dp)
    )
                        Text(
                            text = activeTask.taskState.label,
                            style = MiuixTheme.textStyles.subtitle,
                            color = scheme.primary
    )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    val elapsed = if (activeTask.createdAt > 0L) formatElapsed(nowMs - activeTask.createdAt) else "—"
                    Text(
                        text = "已用时 $elapsed" + if (rhActive.second > 1) " · ${rhActive.second} 个进行中" else "",
                        style = MiuixTheme.textStyles.body2,
                        color = onCard.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
    )
                }
            }
        }

        if (!isRh && connected && busy) {
            Spacer(modifier = Modifier.height(14.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = DashCardRadius,
                colors = cardColors,
            
                onClick = null,
                pressFeedbackType = PressFeedbackType.Tilt,
                showIndication = true
    ) {
                Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
    ) {
                        val cleanWorkflowName = workflowName
                            ?.removeSuffix(".json")
                            ?.removeSuffix(".JSON")
                            ?: "当前任务"
                        Text(
                            text = cleanWorkflowName,
                            style = MiuixTheme.textStyles.body1,
                            color = onCard,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(end = 8.dp)
    )
                        Text(
                            text = "运行中",
                            style = MiuixTheme.textStyles.subtitle,
                            color = scheme.primary
    )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = if (progress > 0f) progress.coerceIn(0f, 1f) else 0.35f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(99.dp)),
                        colors = ProgressIndicatorDefaults.progressIndicatorColors(
                            foregroundColor = scheme.primary,
                            backgroundColor = scheme.surfaceContainer.copy(alpha = 0.92f)
    )
    )
                    if (lastMsgType.isNotBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = lastMsgType,
                            style = MiuixTheme.textStyles.body2,
                            color = onCard.copy(alpha = 0.72f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
    )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        SmallTitle(text = if (isRh) "云端算力" else "系统状态")
        Card(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = DashCardRadius,
            colors = cardColors,
        
            onClick = null,
            pressFeedbackType = PressFeedbackType.Tilt,
            showIndication = true
    ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                if (isRh) {
                    // Only real data: profile settings + /uc/openapi/accountStatus + /openapi/v2/queue/status.
                    if (currentServer != null) {
                        // Only what the cards above don't show: GPU tier (profile), concurrency limit and key type
                        // (/openapi/v2/queue/status, account fallback), site. Balances / tasks live in the top row.
                        val authorized = rhConnState == RhConnState.AUTHORIZED
                        val q = rhQueueStatus.takeIf { authorized }
                        val acc = rhAccountData.takeIf { authorized }

                        // 1. 动态并发占用进度条（真实反映 /openapi/v2/queue/status 的运行中与并发配额）
                        if (authorized && q != null && (q.concurrentLimit ?: 0) > 0) {
                            val running = (q.runningCount ?: 0).coerceAtLeast(0)
                            val limit = q.concurrentLimit ?: 1
                            val concPct = (running.toFloat() / limit.toFloat()).coerceIn(0f, 1f)
                            val concLabel = "$running / $limit" + if ((q.queuedCount ?: 0) > 0) " · ${q.queuedCount} 排队" else if (running > 0) " · 运行中" else " · 空闲"
                            StatProgressRow(
                                label = "并发占用",
                                progress = concPct,
                                valueText = concLabel,
                                barColor = if (concPct >= 1f) scheme.error else scheme.primary,
                                contentColor = onCard
    )
                            Spacer(modifier = Modifier.height(4.dp))
                        }

                        // 2. 算力硬件规格与节点（恢复原版文案与标准规格显示）
                        RhSpecList(
                            items = listOf(
                                "GPU 档位" to currentServer.effectiveRhGpuTier.displayName,
                                "并发上限" to (q?.concurrentLimit?.toString() ?: "—"),
                                "Key 类型" to (q?.apiKeyType?.takeIf { it.isNotBlank() } ?: acc?.apiType?.takeIf { it.isNotBlank() } ?: "—"),
                                "站点" to currentServer.effectiveRhSite.displayName
    ),
                            contentColor = onCard
    )

                        // 3. 真实云端任务统计（仅展示成功率与平均耗时）
                        if (authorized && rhTaskRecords.isNotEmpty()) {
                            val totalCount = rhTaskRecords.size
                            val successCount = rhTaskRecords.count { it.taskStatus == "已完成" }
                            val successRate = if (totalCount > 0) (successCount * 100 / totalCount) else 100
                            val avgDuration = rhTaskRecords.mapNotNull { it.durationSec }.filter { it > 0 }.takeIf { it.isNotEmpty() }?.average()

                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
                                RhMetricTile(label = "任务成功率", value = "$successRate%", modifier = Modifier.weight(1f), contentColor = onCard)
                                RhMetricTile(label = "平均耗时", value = if (avgDuration != null) String.format(java.util.Locale.US, "%.1f 秒", avgDuration) else "—", modifier = Modifier.weight(1f), contentColor = onCard)
                            }
                        }
                        if (!authorized) {
                            val hint = when (rhConnState) {
                                RhConnState.VALIDATING -> "正在验证…"
                                RhConnState.UNAUTHORIZED -> rhConnMessage ?: "Key 无效"
                                RhConnState.NETWORK_ERROR -> rhConnMessage ?: "连接失败"
                                else -> "尚未验证 Key"
                            }
                            Text(
                                text = hint,
                                style = MiuixTheme.textStyles.body1,
                                color = if (rhConnState == RhConnState.UNAUTHORIZED || rhConnState == RhConnState.NETWORK_ERROR) scheme.error else onCard.copy(alpha = 0.7f),
                                modifier = Modifier.padding(vertical = 8.dp)
    )
                            if (rhConnState != RhConnState.VALIDATING) {
                                TextButton(
                                    text = "重新验证",
                                    onClick = { viewModel.retryRhConnection() },
                                    modifier = Modifier.fillMaxWidth()
    )
                            }
                        }
                    } else {
                        Text(
                            text = "未选择服务器",
                            style = MiuixTheme.textStyles.body1,
                            color = onCard.copy(alpha = 0.7f),
                            modifier = Modifier.padding(vertical = 12.dp)
    )
                    }
                } else {
                    val s = stats
                    if (connected && s != null) {
                        LocalSystemStatusPreview(s, onCard)
                    } else if (connected) {
                        Text(
                            text = "同步中…",
                            style = MiuixTheme.textStyles.body1,
                            color = onCard.copy(alpha = 0.7f),
                            modifier = Modifier.padding(vertical = 12.dp)
    )
                    } else {
                        Text(
                            text = "未连接",
                            style = MiuixTheme.textStyles.body1,
                            color = onCard.copy(alpha = 0.7f),
                            modifier = Modifier.padding(vertical = 12.dp)
    )
                    }
                }
            }
        }
    }

    if (showFullScreen && latestGenerationList.isNotEmpty()) {
        FullScreenPreview(
            initialIndex = initialPage.coerceIn(0, latestGenerationList.lastIndex),
            mediaList = latestGenerationList,
            viewModel = viewModel,
            isLocalGallery = false,
            onDismiss = { showFullScreen = false }
    )
    }

    EditUserProfileDialog(
        show = showEditProfileDialog,
        currentNickname = userNickname,
        currentBio = userBio,
        currentQq = userQq,
        onDismiss = { showEditProfileDialog = false },
        onSave = { nick, bio, qq ->
            viewModel.updateUserProfile(nick, bio, qq)
        }
    )
    }
}

@Composable
internal fun StatProgressRow(
    label: String,
    progress: Float,
    valueText: String,
    barColor: Color,
    contentColor: Color
    ) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
    ) {
            Text(
                text = label,
                style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold),
                color = contentColor
    )
            Text(
                text = valueText,
                style = MiuixTheme.textStyles.body2,
                color = contentColor.copy(alpha = 0.72f)
    )
        }
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = progress.coerceIn(0f, 1f),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("system-progress:$label")
                .clip(RoundedCornerShape(99.dp)),
            colors = ProgressIndicatorDefaults.progressIndicatorColors(
                foregroundColor = barColor,
                backgroundColor = contentColor.copy(alpha = 0.12f)
    )
    )
    }
}

/** "—" for unknown values (never a fake 0). */
private fun formatRhCoins(v: Double?): String {
    if (v == null || !v.isFinite()) return "—"
    return if (v % 1.0 == 0.0) v.toLong().toString() else String.format(java.util.Locale.US, "%.2f", v)
}

private fun formatRhMoney(v: Double?, currency: String?): String {
    if (v == null || !v.isFinite()) return "—"
    val amount = String.format(java.util.Locale.US, "%.2f", v)
    return when (currency?.trim()?.uppercase()) {
        null, "", "CNY", "RMB" -> "¥$amount"
        "USD" -> "\$" + amount
        else -> amount + " " + currency.orEmpty().trim()
    }
}

/** Elapsed time as mm:ss (h:mm:ss from one hour on). */
private fun formatElapsed(ms: Long): String {
    val s = (ms / 1000L).coerceAtLeast(0L)
    return if (s >= 3600L) {
        String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600L, (s % 3600L) / 60L, s % 60L)
    } else {
        String.format(java.util.Locale.US, "%02d:%02d", s / 60L, s % 60L)
    }
}

/** 云端算力: single-column list, one item per row (label left, value right). */
@Composable
private fun RhSpecList(
    items: List<Pair<String, String>>,
    contentColor: Color
    ) {
    Column(modifier = Modifier.fillMaxWidth()) {
        items.forEach { (label, value) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
    ) {
                Text(
                    text = label,
                    style = MiuixTheme.textStyles.body1,
                    color = contentColor,
                    maxLines = 1
    )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = value,
                    style = MiuixTheme.textStyles.body2,
                    color = contentColor.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    modifier = Modifier.weight(1f),
                    )
            }
        }
    }
}

@Composable
private fun RhMetricTile(
    label: String,
    value: String,
    contentColor: Color,
    modifier: Modifier = Modifier
    ) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp),
                color = contentColor.copy(alpha = 0.65f),
                maxLines = 1
    )
            Text(
                text = value,
                style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold),
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
    )
        }
    }
}

@Composable
internal fun InfoRow(
    label: String,
    value: String,
    contentColor: Color
    ) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold),
            color = contentColor
    )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = value,
            style = MiuixTheme.textStyles.body2,
            color = contentColor.copy(alpha = 0.72f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
    )
    }
}
