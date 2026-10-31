package com.comfyui.mobile.ui.screens

import com.comfyui.mobile.ui.screens.control.ControlWorkflowHeaderCard
import com.comfyui.mobile.ui.designsystem.PocketTokens
import com.comfyui.mobile.ui.screens.control.ControlRunCard
import com.comfyui.mobile.ui.screens.control.ControlLatestMediaCard
import com.comfyui.mobile.ui.components.NodeIdChip
import com.comfyui.mobile.ui.screens.control.ControlQueueCard
import com.comfyui.mobile.ui.screens.control.StackedParamEditorList
import com.comfyui.mobile.ui.screens.control.compoundKeyOf
import com.comfyui.mobile.ui.screens.control.CloudModelBrowserDialog
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.icon.extended.Ok
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.comfyui.mobile.model.EditableNode
import com.comfyui.mobile.ui.ControlUi
import kotlinx.coroutines.launch
import androidx.compose.ui.input.nestedscroll.nestedScroll
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.utils.overScrollVertical
import com.comfyui.mobile.ui.LocalFloatingNavClearance
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import com.comfyui.mobile.ui.components.DialogFormColumn

private val ControlCardRadius = 28.dp
private val KsuInnerRadius = 22.dp
private val ControlCardShape = RoundedCornerShape(ControlCardRadius)
private val KsuInnerShape = RoundedCornerShape(KsuInnerRadius)
private val PillShape = PocketTokens.Shape.Pill
private val PillCornerRadius = 999.dp


@Composable
fun ControlScreen(viewModel: ControlUi) {
    val queueList by viewModel.queueList.collectAsState()
    val queueRemaining by viewModel.queueSize.collectAsState()
    val editableNodes by viewModel.editableNodes.collectAsState()
    val workflowName by viewModel.currentWorkflowName.collectAsState()
    val latestGenerationList by viewModel.latestGenerationList.collectAsState()
    val savedWorkflowFiles by viewModel.savedWorkflowFiles.collectAsState()
    val baseUrl by viewModel.baseUrl.collectAsState()
    val batchCount by viewModel.batchCount.collectAsState()
    val focusManager = LocalFocusManager.current
    val scheme = MiuixTheme.colorScheme
    val cardColor = scheme.secondaryContainer
    val onCard = scheme.onSecondaryContainer
    val cardColors = CardDefaults.defaultColors(color = cardColor, contentColor = onCard)

    var showSaveDialog by remember { mutableStateOf(false) }
    var aiAppTitle by rememberSaveable { mutableStateOf("") }
    var aiAppCoverUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val context = LocalContext.current
    val aiAppCoverPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        aiAppCoverUri = uri
    }
    
    // Saveable: the picker may recreate the activity; the target node/key must survive it.
    var currentUploadNodeId by rememberSaveable { mutableStateOf<String?>(null) }
    var currentUploadKey by rememberSaveable { mutableStateOf<String?>(null) }
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val nodeId = currentUploadNodeId
        if (uri != null && nodeId != null) {
            viewModel.uploadImageToNode(uri, nodeId, currentUploadKey)
        }
    }
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        val nodeId = currentUploadNodeId
        if (uri != null && nodeId != null) {
            viewModel.uploadImageToNode(uri, nodeId, currentUploadKey)
        }
    }

    LaunchedEffect(showSaveDialog, workflowName) {
        if (showSaveDialog) {
            val raw = workflowName?.trim().orEmpty()
            aiAppTitle = raw.removeSuffix(".json").removeSuffix(".JSON")
            aiAppCoverUri = null
        }
    }

    OverlayDialog(
        show = showSaveDialog,
        title = "保存",
        onDismissRequest = { showSaveDialog = false }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
    ) {
            TextField(
                value = aiAppTitle,
                onValueChange = { aiAppTitle = it },
                singleLine = true,
                label = "AI应用标题",
                useLabelAsPlaceholder = true,
                modifier = Modifier.fillMaxWidth()
    )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
    ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(scheme.secondaryContainer, PocketTokens.Shape.Small),
                        contentAlignment = Alignment.Center
    ) {
                        val uri = aiAppCoverUri
                        if (uri != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(uri)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
    )
                        } else {
                            Icon(MiuixIcons.Image, contentDescription = null)
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("封面", style = MiuixTheme.textStyles.body2)
                }
                TextButton(
                    text = "选择封面",
                    onClick = {
                        aiAppCoverPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
    )
            }
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(
                text = "保存为AI应用",
                onClick = {
                    viewModel.saveCurrentWorkflowAsAiApp(aiAppTitle, aiAppCoverUri)
                    showSaveDialog = false
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary()
    )
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = "覆盖",
                    onClick = {
                        viewModel.overwriteCurrentWorkflowToLocal()
                        showSaveDialog = false
                    },
                    modifier = Modifier.weight(1f),
                    )
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = "取消",
                    onClick = { showSaveDialog = false },
                    modifier = Modifier.weight(1f),
                    )
            }
        }
    }
    
    LaunchedEffect(Unit) {
        viewModel.fetchQueue() // Init fetch
        viewModel.refreshSavedWorkflows()
    }
    
    val servers by viewModel.servers.collectAsState()
    val currentServerId by viewModel.currentServerId.collectAsState()
    val currentServer = servers.firstOrNull { it.effectiveId == currentServerId || it.id == currentServerId }
    val isCloudMode = currentServer?.isRunningHub == true
    val rhAppStatus by viewModel.rhAppStatus.collectAsState()
    val rhAppRunning by viewModel.rhAppRunning.collectAsState()
    val rhOfficialBusy by viewModel.rhOfficialBusy.collectAsState()
    val rhWorkflowMeta by viewModel.currentRhWorkflowMeta.collectAsState()
    var showRhIdDialog by remember { mutableStateOf(false) }
    var rhIdInput by remember { mutableStateOf("") }
    var rhIdError by remember { mutableStateOf<String?>(null) }
    // Cloud run refused because the workflow has no RunningHub workflowId: open the ID dialog.
    LaunchedEffect(viewModel) {
        viewModel.rhWorkflowIdRequest.collect {
            rhIdInput = viewModel.currentRhWorkflowMeta.value?.workflowId.orEmpty()
            rhIdError = null
            showRhIdDialog = true
        }
    }

    OverlayDialog(
        show = showRhIdDialog,
        title = "RunningHub 工作流 ID",
        onDismissRequest = { showRhIdDialog = false }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
    ) {
            Text(
                text = "粘贴工作流链接或 ID。留空则清除。",
                style = MiuixTheme.textStyles.body2
    )
            Spacer(modifier = Modifier.height(10.dp))
            TextField(
                value = rhIdInput,
                onValueChange = {
                    rhIdInput = it
                    rhIdError = null
                },
                singleLine = true,
                label = "工作流 ID 或链接",
                useLabelAsPlaceholder = true,
                modifier = Modifier.fillMaxWidth()
    )
            rhIdError?.let { err ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = err, style = MiuixTheme.textStyles.body2, color = scheme.error)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = "取消",
                    onClick = { showRhIdDialog = false },
                    modifier = Modifier.weight(1f),
                    )
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = "保存",
                    onClick = {
                        val err = viewModel.setCurrentRhWorkflowId(rhIdInput)
                        if (err == null) showRhIdDialog = false else rhIdError = err
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
    )
            }
        }
    }

    var showSyncDialog by remember { mutableStateOf(false) }
    var syncInput by remember { mutableStateOf("") }
    var syncNameInput by remember { mutableStateOf("") }
    var isSyncing by remember { mutableStateOf(false) }
    var syncError by remember { mutableStateOf<String?>(null) }

    var showCloudModelBrowser by remember { mutableStateOf(false) }
    var cloudModelTargetNodeId by remember { mutableStateOf<String?>(null) }
    var cloudModelTargetKey by remember { mutableStateOf<String?>(null) }
    var cloudModelInitialType by remember { mutableStateOf("CHECKPOINT") }

    var lastClickTime by remember { mutableLongStateOf(0L) }
    var showLocalDialog by remember { mutableStateOf(false) }
    var showFullScreen by remember { mutableStateOf(false) }
    var initialPreviewIndex by remember { mutableIntStateOf(0) }
    var renamingTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var renamingText by remember { mutableStateOf("") }

    
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            uri?.let { viewModel.loadWorkflow(it) }
        }
    )

    // Same source as before: sync read from saved_workflows dir
    // Cloud: only the current site's workflows plus unmarked (older) ones.
    val localWorkflowFiles = remember(showLocalDialog) {
        if (showLocalDialog) viewModel.getSiteSavedWorkflows() else emptyList()
    }
    var pendingDeleteWorkflow by remember { mutableStateOf<java.io.File?>(null) }
    OverlayDialog(
        show = pendingDeleteWorkflow != null,
        title = "删除工作流",
        onDismissRequest = {
            pendingDeleteWorkflow = null
            showLocalDialog = true
        }
    ) {
        Column {
            Text(
                text = if (isCloudMode) {
                    "确定删除「${pendingDeleteWorkflow?.name.orEmpty().removeSuffix(".json")}」吗？进行中的任务会取消，已完成记录和相册文件保留。"
                } else {
                    "确定删除「${pendingDeleteWorkflow?.name.orEmpty().removeSuffix(".json")}」吗？"
                },
                style = MiuixTheme.textStyles.body2
    )
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = "取消",
                    onClick = {
                        pendingDeleteWorkflow = null
                        showLocalDialog = true
                    },
                    modifier = Modifier.weight(1f),
                    )
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = "删除",
                    onClick = {
                        pendingDeleteWorkflow?.let { viewModel.deleteSavedWorkflow(it) }
                        pendingDeleteWorkflow = null
                        showLocalDialog = true
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
    )
            }
        }
    }
    val currentRhSite = currentServer?.takeIf { it.isRunningHub }?.effectiveRhSite

    OverlayDialog(
        show = showLocalDialog,
        title = if (isCloudMode) {
            val siteLabel = currentRhSite?.displayName
            if (siteLabel != null) "云端工作流 ($siteLabel)" else "云端工作流"
        } else "本地保存的工作流",
        onDismissRequest = { showLocalDialog = false }
    ) {
                if (localWorkflowFiles.isEmpty()) {
                    Text(
                        text = if (isCloudMode) "暂无云端存档" else "暂无本地存档",
                        style = MiuixTheme.textStyles.body2,
                        color = scheme.onSecondaryContainer
    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
                        items(localWorkflowFiles, key = { it.absolutePath }) { file ->
                            val current = workflowName == file.name
                            val displayName = file.name.removeSuffix(".json")
                            val sizeLabel = (file.length() / 1024f).let { kb ->
                                if (kb >= 1024f) String.format("%.1f MB", kb / 1024f)
                                else String.format("%.0f KB", kb)
                            }
                            val siteTag = if (isCloudMode) {
                                val s = viewModel.rhWorkflowSiteOf(file.name)
                                val name = viewModel.rhSiteName(s)
                                if (name.isNotBlank()) " · $name" else ""
                            } else ""
                            Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (current) {
                                        Modifier.border(
                                            1.5.dp,
                                            scheme.primary.copy(alpha = 0.5f),
                                            PocketTokens.Shape.Large
    )
                                    } else {
                                        Modifier
                                    }
                                ),
                            cornerRadius = PocketTokens.Radius.Large,
                            colors = CardDefaults.defaultColors(
                                color = if (current) {
                                    scheme.secondaryContainer.copy(alpha = 0.7f)
                                } else {
                                    scheme.secondaryContainer
                                }
    ),
                            pressFeedbackType = PressFeedbackType.Sink,
                            showIndication = true,
                            onClick = {
                                viewModel.loadLocalWorkflow(file)
                                showLocalDialog = false
                            }
    ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
                                    Icon(
                                        MiuixIcons.File,
                                        contentDescription = null,
                                        tint = if (current) {
                                            scheme.primary
                                        } else {
                                            scheme.onSecondaryContainer
                                        },
                                        modifier = Modifier.size(22.dp)
    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = displayName,
                                            style = MiuixTheme.textStyles.body1.copy(
                                                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium
    ),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
    )
                                        Text(
                                            text = "$sizeLabel$siteTag",
                                            style = MiuixTheme.textStyles.footnote1,
                                            color = scheme.onSecondaryContainer
    )
                                    }
                                    IconButton(
                                        onClick = {
                                            showLocalDialog = false
                                            pendingDeleteWorkflow = file
                                        }
    ) {
                                        Icon(
                                            MiuixIcons.Delete,
                                            contentDescription = "删除",
                                            tint = scheme.onSecondaryContainer
    )
                                    }
                                    if (current) {
                                        Icon(
                                            MiuixIcons.Ok,
                                            contentDescription = "当前",
                                            tint = scheme.primary,
                                            modifier = Modifier.size(22.dp)
    )
                                    }
                                }
                            }
                        }
                    }
                }
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(
            text = "关闭",
            onClick = { showLocalDialog = false },
            modifier = Modifier.fillMaxWidth()
    )
    }

    OverlayDialog(
        show = showSyncDialog,
        title = if (currentRhSite != null) "同步 (${currentRhSite.displayName})" else "同步",
        onDismissRequest = { if (!isSyncing) showSyncDialog = false }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
    ) {
            TextField(
                value = syncInput,
                onValueChange = { syncInput = it; syncError = null },
                label = if (currentRhSite != null) "${currentRhSite.displayName} 链接或 ID" else "链接或 ID",
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
    )
            Spacer(modifier = Modifier.height(8.dp))
            TextField(
                value = syncNameInput,
                onValueChange = { syncNameInput = it },
                label = "名称（可选）",
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
    )
            syncError?.let { err ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = err,
                    style = MiuixTheme.textStyles.body2,
                    color = scheme.error,
                    fontSize = 12.sp
    )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = "取消",
                    onClick = { showSyncDialog = false },
                    modifier = Modifier.weight(1f),
                    enabled = !isSyncing
    )
                Spacer(modifier = Modifier.width(12.dp))
                TextButton(
                    text = if (isSyncing) "同步中..." else "同步",
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        if (syncInput.isBlank()) {
                            syncError = "请输入链接或 ID"
                            return@TextButton
                        }
                        isSyncing = true
                        viewModel.syncRunningHubResource(
                            inputUrlOrId = syncInput,
                            customName = syncNameInput.ifBlank { null },
                            onSuccess = { msg ->
                                isSyncing = false
                                showSyncDialog = false
                                syncInput = ""
                                syncNameInput = ""
                                viewModel.showToast(msg)
                            },
                            onError = { err ->
                                isSyncing = false
                                syncError = err
                            }
                        )
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isSyncing
    )
            }
        }
    }

    var showFilterDialog by remember { mutableStateOf(false) }

    OverlayDialog(
        show = renamingTarget != null,
        title = "自定义名称",
        onDismissRequest = { renamingTarget = null }
    ) {
        DialogFormColumn(
            footer = {
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        text = "取消",
                        onClick = { renamingTarget = null },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    TextButton(
                        text = "保存",
                        onClick = {
                            val (nodeId, key) = renamingTarget!!
                            viewModel.setParamCustomName(nodeId, key, renamingText)
                            renamingTarget = null
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
    )
                }
            }
    ) {
            TextField(
                value = renamingText,
                onValueChange = { renamingText = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = "名称（留空恢复默认）",
                useLabelAsPlaceholder = true
    )
        }
    }

    var filterQuery by rememberSaveable { mutableStateOf("") }
    val localOrderNodes = remember { mutableStateListOf<EditableNode>() }
    val filterListState = rememberLazyListState()
    var filterDraggingKey by remember { mutableStateOf<String?>(null) }
    var filterDraggingOffsetY by remember { mutableFloatStateOf(0f) }
    var filterDraggingPointerY by remember { mutableFloatStateOf(0f) }
    var filterDraggingInitialIndex by remember { mutableIntStateOf(-1) }
    var filterDraggingHoverIndex by remember { mutableIntStateOf(-1) }
    var filterDraggedItemHeight by remember { mutableFloatStateOf(0f) }
    val filterItemBounds = remember { mutableStateMapOf<String, Rect>() }
    val filterDensity = LocalDensity.current
    val filterSpacingPx = with(filterDensity) { 8.dp.toPx() }

    LaunchedEffect(showFilterDialog) {
        if (showFilterDialog) {
            filterQuery = ""
        }
    }

    LaunchedEffect(editableNodes, filterDraggingKey) {
        if (filterDraggingKey != null) return@LaunchedEffect
        val currentKeys = localOrderNodes.map { compoundKeyOf(it) }
        val newKeys = editableNodes.map { compoundKeyOf(it) }
        if (localOrderNodes.isEmpty() || currentKeys.size != newKeys.size || currentKeys.toSet() != newKeys.toSet()) {
            localOrderNodes.clear()
            localOrderNodes.addAll(editableNodes)
        } else if (currentKeys != newKeys) {
            for (i in editableNodes.indices) {
                if (i < localOrderNodes.size) {
                    localOrderNodes[i] = editableNodes[i]
                }
            }
        } else {
            val newMap = editableNodes.associateBy { compoundKeyOf(it) }
            for (i in localOrderNodes.indices) {
                val key = compoundKeyOf(localOrderNodes[i])
                val updated = newMap[key]
                if (updated != null && updated != localOrderNodes[i]) {
                    localOrderNodes[i] = updated
                }
            }
        }
    }

    OverlayDialog(
        show = showFilterDialog,
        title = "管理节点参数",
        onDismissRequest = { showFilterDialog = false }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
    ) {
            TextField(
                value = filterQuery,
                onValueChange = { filterQuery = it },
                label = "搜索节点 / 参数",
                useLabelAsPlaceholder = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                singleLine = true,
                leadingIcon = {
                    Icon(
                        MiuixIcons.Search,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 14.dp, end = 10.dp)
    )
                }
    )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (filterQuery.isBlank()) "长按条目可拖动调整顺序" else "搜索时暂不可排序",
                style = MiuixTheme.textStyles.footnote1,
                color = scheme.onSecondaryContainer.copy(alpha = 0.75f)
    )
            Spacer(modifier = Modifier.height(10.dp))

            val q = filterQuery.trim().lowercase()
            val dragEnabled = q.isBlank()
            val displayNodes = if (q.isBlank()) {
                localOrderNodes.toList()
            } else {
                localOrderNodes.filter { node ->
                    val title = node.title
                    val key = when (node) {
                        is EditableNode.TextInput -> node.key
                        is EditableNode.NumberInput -> node.key
                        is EditableNode.BooleanInput -> node.key
                        is EditableNode.ImageInput -> node.key
                    }
                    title.lowercase().contains(q) || key.lowercase().contains(q) ||
                        node.nodeId.lowercase() == q.removePrefix("#")
                }
            }

            LazyColumn(
                state = filterListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                userScrollEnabled = filterDraggingKey == null,
                verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
                itemsIndexed(
                    items = displayNodes,
                    key = { _, n -> compoundKeyOf(n) }
    ) { index, node ->
                    val key = when (node) {
                        is EditableNode.TextInput -> node.key
                        is EditableNode.NumberInput -> node.key
                        is EditableNode.BooleanInput -> node.key
                        is EditableNode.ImageInput -> node.key
                    }
                    val compound = compoundKeyOf(node)
                    val isDragging = filterDraggingKey == compound
                    val currentNodes by rememberUpdatedState(displayNodes)

                    val typeLabel = when (node) {
                        is EditableNode.TextInput -> if (node.options.isNullOrEmpty()) "文本" else "选项"
                        is EditableNode.NumberInput -> "数值"
                        is EditableNode.BooleanInput -> "开关"
                        is EditableNode.ImageInput -> "图像"
                    }

                    val targetShift = if (filterDraggingKey == null || !dragEnabled || isDragging) {
                        0f
                    } else if (filterDraggingInitialIndex != -1 && filterDraggingHoverIndex != -1) {
                        when {
                            filterDraggingInitialIndex < filterDraggingHoverIndex &&
                                index > filterDraggingInitialIndex && index <= filterDraggingHoverIndex ->
                                -filterDraggedItemHeight - filterSpacingPx
                            filterDraggingInitialIndex > filterDraggingHoverIndex &&
                                index < filterDraggingInitialIndex && index >= filterDraggingHoverIndex ->
                                filterDraggedItemHeight + filterSpacingPx
                            else -> 0f
                        }
                    } else {
                        0f
                    }

                    val animatedShift by animateFloatAsState(
                        targetValue = targetShift,
                        animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f),
                        label = "dialogParamShift"
                    )
                    val realVisualOffset = if (isDragging) filterDraggingOffsetY else animatedShift

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .zIndex(if (isDragging) 10f else 0f)
                            .graphicsLayer {
                                translationY = realVisualOffset
                                shape = PocketTokens.Shape.Medium
                                clip = true
                                if (isDragging) {
                                    shadowElevation = 6f
                                }
                            }
                            .clip(PocketTokens.Shape.Medium)
                            .then(
                                if (isDragging) Modifier.border(1.5.dp, scheme.primary, PocketTokens.Shape.Medium)
                                else Modifier.border(1.dp, scheme.outline.copy(alpha = 0.28f), PocketTokens.Shape.Medium)
                            )
                            .onGloballyPositioned { coords ->
                                if (filterDraggingKey == null) {
                                    val pos = coords.positionInRoot()
                                    val size = coords.size
                                    filterItemBounds[compound] = Rect(
                                        left = pos.x,
                                        top = pos.y,
                                        right = pos.x + size.width,
                                        bottom = pos.y + size.height
    )
                                }
                            }
                            .pointerInput(compound, dragEnabled) {
                                if (!dragEnabled) return@pointerInput
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        filterDraggingKey = compound
                                        filterDraggingOffsetY = 0f
                                        val idx = currentNodes.indexOfFirst { compoundKeyOf(it) == compound }
                                        filterDraggingInitialIndex = idx
                                        filterDraggingHoverIndex = idx
                                        val bounds = filterItemBounds[compound]
                                        if (bounds != null) {
                                            filterDraggingPointerY = (bounds.top + bounds.bottom) / 2f
                                            filterDraggedItemHeight = bounds.height
                                        }
                                    },
                                    onDragEnd = {
                                        if (
                                            filterDraggingInitialIndex != -1 &&
                                            filterDraggingHoverIndex != -1 &&
                                            filterDraggingInitialIndex != filterDraggingHoverIndex
                                        ) {
                                            val fromKey = compoundKeyOf(currentNodes[filterDraggingInitialIndex])
                                            val toKey = compoundKeyOf(currentNodes[filterDraggingHoverIndex])
                                            val realFrom = localOrderNodes.indexOfFirst { compoundKeyOf(it) == fromKey }
                                            val realTo = localOrderNodes.indexOfFirst { compoundKeyOf(it) == toKey }
                                            if (realFrom != -1 && realTo != -1) {
                                                val moved = localOrderNodes.removeAt(realFrom)
                                                localOrderNodes.add(realTo, moved)
                                                viewModel.updateParamOrder(
                                                    localOrderNodes.map { compoundKeyOf(it) }
                                                )
                                            }
                                        }
                                        filterDraggingKey = null
                                        filterDraggingOffsetY = 0f
                                        filterDraggingPointerY = 0f
                                        filterDraggingInitialIndex = -1
                                        filterDraggingHoverIndex = -1
                                    },
                                    onDragCancel = {
                                        filterDraggingKey = null
                                        filterDraggingOffsetY = 0f
                                        filterDraggingPointerY = 0f
                                        filterDraggingInitialIndex = -1
                                        filterDraggingHoverIndex = -1
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        filterDraggingOffsetY += dragAmount.y
                                        filterDraggingPointerY += dragAmount.y

                                        val currentCenterY = filterDraggingPointerY
                                        var closestIndex = filterDraggingInitialIndex
                                        var minDiff = Float.MAX_VALUE
                                        currentNodes.forEachIndexed { i, n ->
                                            val rect = filterItemBounds[compoundKeyOf(n)] ?: return@forEachIndexed
                                            val slotCenter = (rect.top + rect.bottom) / 2f
                                            val diff = kotlin.math.abs(currentCenterY - slotCenter)
                                            if (diff < minDiff) {
                                                minDiff = diff
                                                closestIndex = i
                                            }
                                        }
                                        if (closestIndex != -1 && closestIndex != filterDraggingHoverIndex) {
                                            filterDraggingHoverIndex = closestIndex
                                        }
                                    }
    )
                            },
                        cornerRadius = PocketTokens.Radius.Medium,
                        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        colors = CardDefaults.defaultColors(
                            color = when {
                                isDragging -> scheme.primaryContainer.copy(alpha = 0.95f)
                                node.isVisible -> scheme.surfaceContainerHigh
                                else -> scheme.surfaceContainerHigh.copy(alpha = 0.45f)
                            }
    ),
                        holdDownState = false,
                        onClick = null,
                        pressFeedbackType = PressFeedbackType.None,
                        showIndication = false
    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                horizontalAlignment = Alignment.Start
    ) {
                                Box(
                                    modifier = Modifier
                                        .clip(PocketTokens.Shape.Small)
                                        .background(scheme.primary.copy(alpha = 0.12f))
                                        .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
                                    Text(
                                        text = typeLabel,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = scheme.primary
    )
                                }
                                if (node is EditableNode.TextInput || node is EditableNode.NumberInput) {
                                    NodeIdChip(nodeId = node.nodeId)
                                }
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = node.title,
                                    style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = scheme.onBackground.copy(
                                        alpha = if (node.isVisible) 1f else 0.5f
    )
    )
                                Text(
                                    text = node.classType,
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = scheme.onSecondaryContainer.copy(alpha = 0.7f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
    )
                            }
                            IconButton(
                                onClick = {
                                    renamingTarget = node.nodeId to key
                                    renamingText = node.title
                                }
    ) {
                                Icon(
                                    MiuixIcons.Edit,
                                    contentDescription = "重命名",
                                    tint = scheme.onSecondaryContainer
    )
                            }
                            Switch(
                                checked = node.isVisible,
                                onCheckedChange = { checked ->
                                    viewModel.toggleNodeVisibility(node.nodeId, key, checked)
                                }
    )
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(
            text = "关闭",
            onClick = { showFilterDialog = false },
            modifier = Modifier.fillMaxWidth()
    )
    }

    val scrollBehavior = MiuixScrollBehavior()
    val floatingNavClearance = LocalFloatingNavClearance.current
    val controlListState = rememberLazyListState()

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                title = "控制器",
                largeTitle = "控制器",
                color = scheme.background,
                scrollBehavior = scrollBehavior
    )
        }
    ) { innerPadding ->
    LazyColumn(
        state = controlListState,
        modifier = Modifier
            .fillMaxSize()
            .overScrollVertical()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentPadding = PaddingValues(
            start = PocketTokens.PagePadding,
            top = innerPadding.calculateTopPadding() + 8.dp,
            end = PocketTokens.PagePadding,
            bottom = 24.dp + floatingNavClearance
    ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        // --- Load Buttons ---
        item(key = "workflow_header") {
            ControlWorkflowHeaderCard(
                workflowName = workflowName,
                cardColors = cardColors,
                onCard = onCard,
                cornerRadius = ControlCardRadius,
                pillRadius = PillCornerRadius,
                isCloudMode = isCloudMode,
                onLocalClick = { showLocalDialog = true },
                onImportClick = { launcher.launch(arrayOf("application/json")) },
                onCloudSyncClick = { showSyncDialog = true; syncError = null }
    )
        }

        // --- Workflow Parameters ---
        item(key = "workflow_parameters") {
        if (editableNodes.isNotEmpty()) {
            Card(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = ControlCardRadius,
            colors = cardColors
    ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "工作流参数",
                            style = MiuixTheme.textStyles.title3.copy(fontWeight = FontWeight.SemiBold),
                            color = onCard,
                            modifier = Modifier.weight(1f),
                    )
                        IconButton(onClick = { showFilterDialog = true }) {
                            Icon(MiuixIcons.Tune, contentDescription = "管理参数", tint = onCard)
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    StackedParamEditorList(
                        sourceNodes = editableNodes,
                        onCard = onCard,
                        isCloudMode = isCloudMode,
                        onReorder = { keys -> viewModel.updateParamOrder(keys) },
                        onRenameClick = { nodeId, key, title ->
                            renamingTarget = nodeId to key
                            renamingText = title
                        },
                        onValueChange = { nodeId, key, value ->
                            viewModel.updateNodeValue(nodeId, key, value)
                        },
                        onModelBrowseClick = { nodeId, key, resType ->
                            cloudModelTargetNodeId = nodeId
                            cloudModelTargetKey = key
                            cloudModelInitialType = resType
                            showCloudModelBrowser = true
                        },
                        onUploadClick = { nodeId, key, mime ->
                            currentUploadNodeId = nodeId
                            currentUploadKey = key
                            if (mime == "image/*") {
                                imagePickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            } else {
                                filePickerLauncher.launch(mime)
                            }
                        },
                        getViewUrl = { filename, subfolder, type ->
                            viewModel.getViewUrl(filename, subfolder, type)
                        }
    )
                }
            }
        }
    Spacer(modifier = Modifier.height(16.dp))
}

        // --- Control Buttons ---
        item(key = "control_run_card") {
            ControlRunCard(
                batchCount = batchCount,
                cardColors = cardColors,
                onCard = onCard,
                onBatchCountChange = { viewModel.setBatchCount(it) },
                onRunClick = {
                    val now = System.currentTimeMillis()
                    if (now - lastClickTime > 500) {
                        lastClickTime = now
                        viewModel.runWorkflow(batchCount)
                    }
                },
                onStopClick = {
                    val now = System.currentTimeMillis()
                    if (now - lastClickTime > 500) {
                        lastClickTime = now
                        viewModel.stopAllWorkflows()
                    }
                },
                onSaveClick = {
                    val now = System.currentTimeMillis()
                    if (now - lastClickTime > 500) {
                        lastClickTime = now
                        showSaveDialog = true
                    }
                },
                statusText = if (isCloudMode && (rhAppRunning || rhOfficialBusy)) rhAppStatus else null,
                cloudIdText = rhWorkflowMeta?.takeIf { isCloudMode }?.let { meta ->
                    meta.workflowId?.takeIf { it.isNotBlank() } ?: "未设置"
                },
                onCloudIdClick = {
                    rhIdInput = rhWorkflowMeta?.workflowId.orEmpty()
                    rhIdError = null
                    showRhIdDialog = true
                },
                cloudIdLabel = "工作流 ID"
    )
        }

        if (latestGenerationList.isNotEmpty()) {
            item(key = "control_latest_media") {
                ControlLatestMediaCard(
                    latestGenerationList = latestGenerationList,
                    cardColors = cardColors,
                    onCard = onCard,
                    cornerRadius = ControlCardRadius,
                    getViewUrl = { viewModel.getViewUrl(it) },
                    onItemClick = { index ->
                        initialPreviewIndex = index
                        showFullScreen = true
                    }
    )
            }
        }

        // ComfyUI queue: local servers only (cloud tasks are shown in the cloud task records).
        if (!isCloudMode) {
            item(key = "control_queue_card") {
                ControlQueueCard(
                    queueRemaining = queueRemaining,
                    queueList = queueList,
                    cardColors = cardColors,
                    onCard = onCard,
                    onRefreshQueue = { viewModel.fetchQueue() }
    )
            }
        }
    }

    if (showFullScreen && latestGenerationList.isNotEmpty()) {
        FullScreenPreview(
            initialIndex = initialPreviewIndex.coerceIn(0, latestGenerationList.lastIndex),
            mediaList = latestGenerationList,
            viewModel = viewModel,
            isLocalGallery = false,
                onDismiss = { showFullScreen = false }
    )
    }

    CloudModelBrowserDialog(
        show = showCloudModelBrowser,
        initialResourceType = cloudModelInitialType,
        viewModel = viewModel,
        onDismiss = { showCloudModelBrowser = false },
        textNodes = editableNodes.filterIsInstance<EditableNode.TextInput>(),
        onSelectModel = { item ->
            val targetNodeId = cloudModelTargetNodeId
            val targetKey = cloudModelTargetKey
            if (targetNodeId != null && targetKey != null) {
                viewModel.updateNodeValue(targetNodeId, targetKey, item.effectiveModelName)
                viewModel.showToast("已选择模型: ${item.resourceName}")
            }
            showCloudModelBrowser = false
        },
        onSelectModelWithTriggerNode = { item, textNode, triggers ->
            val targetNodeId = cloudModelTargetNodeId
            val targetKey = cloudModelTargetKey
            if (targetNodeId != null && targetKey != null) {
                viewModel.updateNodeValue(targetNodeId, targetKey, item.effectiveModelName)
                val cur = textNode.text.trim()
                val next = if (cur.isBlank()) triggers else "$cur, $triggers"
                viewModel.updateNodeValue(textNode.nodeId, textNode.key, next)
                viewModel.showToast("已选择模型并写入 #${textNode.nodeId}")
            }
            showCloudModelBrowser = false
        }
    )
    }
}
