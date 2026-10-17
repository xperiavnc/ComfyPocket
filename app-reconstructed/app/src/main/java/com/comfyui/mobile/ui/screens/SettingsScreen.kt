package com.comfyui.mobile.ui.screens

import com.comfyui.mobile.ui.designsystem.PocketIcons

import android.content.Intent
import android.os.Build
import com.comfyui.mobile.diagnostics.NavigationDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.comfyui.mobile.model.BackupExportOptions
import com.comfyui.mobile.model.BackupRestoreOptions
import com.comfyui.mobile.model.ParsedBackup
import com.comfyui.mobile.ui.MainViewModelBackup
import com.comfyui.mobile.ui.designsystem.PocketButton as Button
import com.comfyui.mobile.ui.designsystem.PocketButtonDefaults as ButtonDefaults
import com.comfyui.mobile.ui.designsystem.PocketCardDefaults as CardDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import com.comfyui.mobile.ui.designsystem.PocketTextButton as TextButton

import com.comfyui.mobile.ui.designsystem.PocketDialog as OverlayDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import com.comfyui.mobile.ui.LocalFloatingNavClearance
import com.comfyui.mobile.ui.MainViewModel
import com.comfyui.mobile.ui.rememberAppHaptics
import com.comfyui.mobile.ui.designsystem.PocketCard as Card
import androidx.compose.material3.Icon
import com.comfyui.mobile.ui.designsystem.PocketIconButton as IconButton

import com.comfyui.mobile.ui.designsystem.PocketScaffold as Scaffold
import com.comfyui.mobile.ui.designsystem.PocketTopAppBar as TopAppBar

import com.comfyui.mobile.ui.designsystem.PocketArrowPreference as ArrowPreference
import com.comfyui.mobile.ui.designsystem.PocketSwitchPreference as SwitchPreference
import androidx.compose.material3.MaterialTheme

@Composable
fun SettingsScreen(navController: NavController, viewModel: MainViewModel) {
    val haptics = rememberAppHaptics()

    val context = LocalContext.current
    val floatingNavClearance = LocalFloatingNavClearance.current
    val scheme = MaterialTheme.colorScheme

    // 服务器状态
    val servers by viewModel.servers.collectAsState()
    val currentServerId by viewModel.currentServerId.collectAsState()
    val savedIp by viewModel.savedIp.collectAsState()
    val serverSummary = remember(servers, currentServerId, savedIp) {
        servers.firstOrNull { it.effectiveId == currentServerId || it.id == currentServerId }?.displayName?.ifBlank { savedIp }
            ?: savedIp.ifBlank { "未连接" }
    }

    // 界面外观概要（二级页面详细配置）
    val themeMode by viewModel.themeMode.collectAsState()
    val themePaletteId by viewModel.themePaletteId.collectAsState()
    val systemDark = isSystemInDarkTheme()

    val themeSummary = remember(themeMode, themePaletteId, systemDark) {
        val modeStr = when (themeMode) {
            MainViewModel.THEME_FOLLOW_SYSTEM -> "跟随系统 (${if (systemDark) "深色" else "浅色"})"
            MainViewModel.THEME_LIGHT -> "浅色模式"
            else -> "深色模式"
        }
        val paletteName = com.comfyui.mobile.ui.theme.themePaletteById(themePaletteId).displayName
        "$modeStr · $paletteName"
    }

    // Agent 状态
    val llmModel by viewModel.llmModel.collectAsState()
    val agentSummary = remember(llmModel) {
        if (llmModel.isNotBlank()) llmModel else "未配置模型"
    }

    // 偏好与存储状态
    val isAutoSave by viewModel.isAutoSave.collectAsState()
    val cacheSizeText by viewModel.cacheSizeText.collectAsState()
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var isClearingCache by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.refreshCacheSize()
    }

    // 日志状态
    val consoleLogs by viewModel.consoleMessages.collectAsState()

    // 版本号
    val versionName = remember {
        try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0)
            }
            info.versionName ?: "2.4.5-beta"
        } catch (_: Exception) {
            "2.4.5-beta"
        }
    }

    val savedWorkflowFiles by viewModel.savedWorkflowFiles.collectAsState()
    val llmProfiles by viewModel.llmProfiles.collectAsState()
    val characterLores by viewModel.characterLores.collectAsState()

    var showExportDialog by remember { mutableStateOf(false) }
    var exportIncludeWorkflows by remember { mutableStateOf(true) }
    var exportIncludeKeys by remember { mutableStateOf(true) }
    var exportIncludeAiApps by remember { mutableStateOf(true) }
    var exportIncludeTaskHistory by remember { mutableStateOf(true) }
    var pendingImportBackup by remember { mutableStateOf<ParsedBackup?>(null) }
    var importRestoreWorkflows by remember { mutableStateOf(true) }
    var importRestoreAiApps by remember { mutableStateOf(true) }
    var importRestoreTaskHistory by remember { mutableStateOf(true) }

    fun currentExportOptions() = BackupExportOptions(
        includeKeys = exportIncludeKeys,
        includeWorkflows = exportIncludeWorkflows,
        includeAiApps = exportIncludeAiApps,
        includeTaskHistory = exportIncludeTaskHistory
    )

    val exportFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(MainViewModelBackup.BACKUP_MIME)
    ) { uri ->
        if (uri != null) {
            viewModel.writeBackupToUri(context, uri, currentExportOptions()).fold(
                onSuccess = { viewModel.showUserMessage("已保存") },
                onFailure = { viewModel.showUserMessage("保存失败：${it.message}") }
            )
            showExportDialog = false
        }
    }

    val importFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.parseBackupFromUri(context, uri).fold(
                onSuccess = { parsed ->
                    pendingImportBackup = parsed
                    val dto = parsed.dto
                    importRestoreWorkflows = dto.includeWorkflows || parsed.hasPackage
                    importRestoreAiApps = dto.includeAiApps || parsed.hasPackage
                    importRestoreTaskHistory = dto.includeTaskHistory || parsed.hasPackage
                },
                onFailure = { err ->
                    viewModel.showUserMessage("解析备份失败：${err.message}")
                }
            )
        }
    }

    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var exportingDiagnostics by remember { mutableStateOf(false) }
    val diagnosticExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) scope.launch {
            exportingDiagnostics = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val report = NavigationDiagnostics.report(context)
                    val output = checkNotNull(context.contentResolver.openOutputStream(uri))
                    output.bufferedWriter(Charsets.UTF_8).use { it.write(report) }
                }
            }
            exportingDiagnostics = false
            viewModel.showUserMessage(if (result.isSuccess) "返回诊断已导出" else "诊断导出失败，请重试")
        }
    }

    fun shareBackup() {
        try {
            val file = viewModel.exportBackupToFile(currentExportOptions())
            val uri = FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = MainViewModelBackup.BACKUP_MIME
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "分享备份文件"))
            showExportDialog = false
        } catch (e: Exception) {
            viewModel.showUserMessage("分享失败：${e.message}")
        }
    }

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                title = "设置",
                color = scheme.background,
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(PocketIcons.Back, contentDescription = "返回")
                    }
                }
    )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    bottom = 28.dp + floatingNavClearance
    ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
            Spacer(modifier = Modifier.height(4.dp))

            // 1. 服务与连接管理
            Card {
                ArrowPreference(
                    title = "服务器管理",
                    summary = serverSummary,
                    startAction = {
                        Icon(
                            PocketIcons.Link,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                            tint = scheme.onBackground
    )
                    },
                    onClick = { navController.navigate("servers") }
    )
            }

            // 2. 界面与外观
            Card {
                ArrowPreference(
                    title = "主题与外观",
                    summary = themeSummary,
                    startAction = {
                        Icon(
                            PocketIcons.Theme,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                            tint = scheme.onBackground
    )
                    },
                    onClick = { navController.navigate("theme") }
    )
                ArrowPreference(
                    title = "Material 预览",
                    summary = "独立样板 · 正式应用已接入 P2 导航外壳",
                    onClick = {
                        context.startActivity(Intent(context, com.comfyui.mobile.ui.material.MaterialSampleActivity::class.java))
                    }
                )
            }

            // 3. 智能助手 Agent
            Card {
                ArrowPreference(
                    title = "Agent 设置",
                    summary = agentSummary,
                    startAction = {
                        Icon(
                            PocketIcons.Create,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                            tint = scheme.onBackground
    )
                    },
                    onClick = { navController.navigate("agent_settings") }
    )
                ArrowPreference(
                    title = "重置对话历史",
                    summary = "清空对话",
                    startAction = {
                        Icon(
                            PocketIcons.Delete,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                            tint = scheme.onBackground
    )
                    },
                    onClick = {
                        viewModel.clearAgentChat()
                        viewModel.showUserMessage("已清空对话")
                    }
    )
            }

            // 4. 偏好与存储
            Card {
                SwitchPreference(
                    title = "自动保存",
                    summary = "完成后写入相册",
                    startAction = {
                        Icon(
                            PocketIcons.Photos,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                            tint = scheme.onBackground
    )
                    },
                    checked = isAutoSave,
                    onCheckedChange = { haptics.toggle(); viewModel.toggleAutoSave(it) }
    )
                ArrowPreference(
                    title = "保存位置",
                    summary = "系统相册",
                    startAction = {
                        Icon(
                            PocketIcons.Folder,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                            tint = scheme.onBackground
    )
                    }
    )
                ArrowPreference(
                    title = "缓存清理",
                    summary = cacheSizeText,
                    startAction = {
                        Icon(
                            PocketIcons.Delete,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                            tint = scheme.onBackground
    )
                    },
                    onClick = {
                        viewModel.refreshCacheSize()
                        showClearCacheDialog = true
                    }
    )
            }

            // 5. 备份与数据管理
            Card {
                ArrowPreference(
                    title = "导出备份",
                    summary = ".cui.bak",
                    startAction = {
                        Icon(
                            PocketIcons.Download,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                            tint = scheme.onBackground
    )
                    },
                    onClick = {
                        viewModel.refreshSavedWorkflows()
                        showExportDialog = true
                    }
    )
                ArrowPreference(
                    title = "导入备份",
                    summary = ".cui.bak",
                    startAction = {
                        Icon(
                            PocketIcons.Import,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                            tint = scheme.onBackground
    )
                    },
                    onClick = {
                        importFileLauncher.launch(arrayOf(MainViewModelBackup.BACKUP_MIME, "application/octet-stream", "*/*"))
                    }
    )
            }

            // 5. 诊断与运行日志
            Card {
                ArrowPreference(
                    title = "运行日志",
                    summary = if (consoleLogs.isNotEmpty()) "${consoleLogs.size} 条日志" else "查看运行日志",
                    startAction = {
                        Icon(
                            PocketIcons.Recent,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                            tint = scheme.onBackground
    )
                    },
                    onClick = { navController.navigate("console") }
    )
            }

            Card {
                ArrowPreference(
                    title = "导出返回/退出诊断",
                    summary = if (exportingDiagnostics) "正在导出…" else "返回路径、崩溃类型与系统退出原因，不含 Key",
                    onClick = {
                        if (!exportingDiagnostics) diagnosticExportLauncher.launch(
                            "ComfyPocket-return-diagnostic-" +
                                SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
                        )
                    }
                )
            }

            // 6. 关于应用
            Card {
                ArrowPreference(
                    title = "关于应用",
                    summary = if (versionName.contains("beta", ignoreCase = true)) "v$versionName" else "v$versionName · Beta",
                    startAction = {
                        Icon(
                            PocketIcons.Info,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                            tint = scheme.onBackground
    )
                    },
                    onClick = { navController.navigate("about") }
    )
            }
        }
    }

    OverlayDialog(
        show = showClearCacheDialog,
        title = "清理应用缓存",
        onDismissRequest = { if (!isClearingCache) showClearCacheDialog = false }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
            Text(
                text = "当前缓存占用：$cacheSizeText",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.onBackground
    )
            Text(
                text = "包含图片与视频加载缓存与节点预览图。\n清理后不会影响相册中的作品、已保存工作流及服务器配置。",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
    )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
                Button(
                    onClick = { showClearCacheDialog = false },
                    modifier = Modifier.weight(1f),
                    enabled = !isClearingCache
    ) {
                    Text(text = "取消")
                }
                Button(
                    onClick = {
                        haptics.confirm()
                        isClearingCache = true
                        viewModel.clearAppCache(includeUploadPreviews = true) {
                            isClearingCache = false
                            showClearCacheDialog = false
                            viewModel.showUserMessage("缓存已全部清理完毕")
                        }
                    },
                    modifier = Modifier.weight(1.2f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    enabled = !isClearingCache
    ) {
                    Text(text = if (isClearingCache) "清理中…" else "确认清理")
                }
            }
        }
    }

    OverlayDialog(
        show = showExportDialog,
        title = "导出备份",
        onDismissRequest = { showExportDialog = false }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
            val wfCount = remember { viewModel.countBackupWorkflows() }
            val aiCount = remember { viewModel.countBackupAiAppFiles() }
            val hasTasks = remember { viewModel.hasBackupTaskHistory() }
            val nick by viewModel.userNickname.collectAsState()

            Text(
                text = buildString {
                    append("${servers.size} 服务器 · ${llmProfiles.size} 模型 · ${characterLores.size} 角色")
                    append(" · ${wfCount} 工作流 · ${aiCount} AI应用")
                    if (hasTasks) append(" · 任务记录")
                    if (nick.isNotBlank()) append(" · 个人信息")
                },
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
    )

            BackupToggleRow(
                title = "包含密钥",
                checked = exportIncludeKeys,
                onCheckedChange = { haptics.toggle(); exportIncludeKeys = it }
    )
            BackupToggleRow(
                title = "工作流",
                checked = exportIncludeWorkflows && wfCount > 0,
                enabled = wfCount > 0,
                onCheckedChange = { haptics.toggle(); exportIncludeWorkflows = it }
    )
            BackupToggleRow(
                title = "AI 应用",
                checked = exportIncludeAiApps && aiCount > 0,
                enabled = aiCount > 0,
                onCheckedChange = { haptics.toggle(); exportIncludeAiApps = it }
    )
            BackupToggleRow(
                title = "任务记录",
                checked = exportIncludeTaskHistory && hasTasks,
                enabled = hasTasks,
                onCheckedChange = { haptics.toggle(); exportIncludeTaskHistory = it }
    )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
                Button(
                    onClick = { shareBackup() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors()
    ) {
                    Text("分享", fontSize = 13.sp)
                }
                Button(
                    onClick = {
                        val sdf = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
                        exportFileLauncher.launch(
                            "ComfyUI_Backup_${sdf.format(Date())}${MainViewModelBackup.BACKUP_EXTENSION}"
    )
                    },
                    modifier = Modifier.weight(1.2f),
                    colors = ButtonDefaults.buttonColorsPrimary()
    ) {
                    Text("保存", fontSize = 13.sp)
                }
            }
            TextButton(
                text = "取消",
                onClick = { showExportDialog = false },
                modifier = Modifier.fillMaxWidth()
    )
        }
    }

    val currentBackup = pendingImportBackup
    if (currentBackup != null) {
        val parsed = currentBackup
        val backup = parsed.dto
        val sdf = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
        val dateStr = remember(backup.exportTime) {
            if (backup.exportTime > 0) sdf.format(Date(backup.exportTime)) else "未知时间"
        }
        val workflowCount = backup.workflows?.size
            ?: if (parsed.hasPackage) -1 else 0

        OverlayDialog(
            show = true,
            title = "恢复备份",
            onDismissRequest = {
                viewModel.discardParsedBackup(pendingImportBackup)
                pendingImportBackup = null
            }
    ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
                Text(
                    text = dateStr,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.primary,
                    fontWeight = FontWeight.Medium
    )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 14.dp,
                    colors = CardDefaults.defaultColors(color = scheme.surfaceContainerHigh),
                    insideMargin = PaddingValues(12.dp)
    ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val serverKeyExtra = if (backup.rhKeysCount > 0) " (含 ${backup.rhKeysCount} 个密钥)" else ""
                        Text(
                            text = "• 服务器 ${backup.servers?.size ?: 0}$serverKeyExtra",
                            style = MaterialTheme.typography.bodyMedium
    )
                        val llmKeyExtra = if (backup.llmKeysCount > 0) " (含 ${backup.llmKeysCount} 个密钥)" else ""
                        Text(
                            text = "• 模型 ${backup.llmProfiles?.size ?: 0}$llmKeyExtra",
                            style = MaterialTheme.typography.bodyMedium
    )
                        Text(
                            text = "• 角色 ${backup.characterLores?.size ?: 0}",
                            style = MaterialTheme.typography.bodyMedium
    )
                        Text(
                            text = "• Agent ${backup.agentPrefBag?.size ?: 0}",
                            style = MaterialTheme.typography.bodyMedium
    )
                        if (!backup.profileNickname.isNullOrBlank() ||
                            !backup.profileBio.isNullOrBlank() ||
                            !backup.profileQq.isNullOrBlank()
                        ) {
                            Text(
                                text = "• 个人信息",
                                style = MaterialTheme.typography.bodyMedium
    )
                        }
                        Text(
                            text = when {
                                workflowCount < 0 -> "• 工作流"
                                else -> "• 工作流：$workflowCount 个"
                            },
                            style = MaterialTheme.typography.bodyMedium
    )
                        if (backup.includeAiApps || parsed.hasPackage) {
                            Text(
                                text = "• AI 应用",
                                style = MaterialTheme.typography.bodyMedium
    )
                        }
                        if (backup.includeTaskHistory || parsed.hasPackage) {
                            Text(
                                text = "• 任务记录",
                                style = MaterialTheme.typography.bodyMedium
    )
                        }
                    }
                }

                if (backup.totalKeysCount > 0) {
                    Text(
                        text = "含 ${backup.totalKeysCount} 个密钥",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.primary
    )
                } else {
                    Text(
                        text = "不含密钥",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
    )
                }

                if (importRestoreWorkflows || workflowCount != 0 || parsed.hasPackage) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 14.dp,
                        colors = CardDefaults.defaultColors(color = scheme.surfaceContainerHigh),
                        insideMargin = PaddingValues(12.dp)
    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
    ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "工作流",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
    )
                            }
                            Switch(
                                checked = importRestoreWorkflows,
                                onCheckedChange = { haptics.toggle(); importRestoreWorkflows = it }
    )
                        }
                    }
                }

                if (backup.includeAiApps || parsed.hasPackage) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 14.dp,
                        colors = CardDefaults.defaultColors(color = scheme.surfaceContainerHigh),
                        insideMargin = PaddingValues(12.dp)
    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
    ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "AI 应用",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
    )
                            }
                            Switch(
                                checked = importRestoreAiApps,
                                onCheckedChange = { haptics.toggle(); importRestoreAiApps = it }
    )
                        }
                    }
                }

                if (backup.includeTaskHistory || parsed.hasPackage) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 14.dp,
                        colors = CardDefaults.defaultColors(color = scheme.surfaceContainerHigh),
                        insideMargin = PaddingValues(12.dp)
    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
    ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "任务记录",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
    )
                            }
                            Switch(
                                checked = importRestoreTaskHistory,
                                onCheckedChange = { haptics.toggle(); importRestoreTaskHistory = it }
    )
                        }
                    }
                }

                Text(
                    text = "将覆盖同名数据",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
    )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
                    TextButton(
                        modifier = Modifier.weight(1f),
                        text = "取消",
                        onClick = {
                            viewModel.discardParsedBackup(pendingImportBackup)
                            pendingImportBackup = null
                        }
    )
                    Button(
                        modifier = Modifier.weight(1.3f),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        onClick = {
                            haptics.confirm()
                            val toRestore = pendingImportBackup
                            pendingImportBackup = null
                            if (toRestore != null) {
                                viewModel.restoreParsedBackup(
                                    toRestore,
                                    BackupRestoreOptions(
                                        restoreWorkflows = importRestoreWorkflows,
                                        restoreAiApps = importRestoreAiApps,
                                        restoreTaskHistory = importRestoreTaskHistory,
                                        restoreAgentBindings = true
    )
    )
                            }
                        }
    ) {
                        Text("导入", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun BackupToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
    ) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 14.dp,
        colors = CardDefaults.defaultColors(color = scheme.surfaceContainerHigh),
        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
    ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
                    )
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange
    )
        }
    }
}
