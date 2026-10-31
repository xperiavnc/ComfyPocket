package com.comfyui.mobile.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import com.comfyui.mobile.ui.designsystem.PocketTokens
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.navigation.NavController
import coil.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.comfyui.mobile.model.EditableNode
import com.comfyui.mobile.model.MediaOutput
import com.comfyui.mobile.ui.LocalFloatingNavClearance
import com.comfyui.mobile.ui.AiAppUi
import com.comfyui.mobile.ui.components.coverRequest
import com.comfyui.mobile.ui.screens.aiapps.AiAppDeleteConfirmDialog
import com.comfyui.mobile.ui.screens.aiapps.AiAppEditTitleDialog
import com.comfyui.mobile.ui.screens.aiapps.AiAppEmptyCard
import com.comfyui.mobile.ui.screens.aiapps.AiAppParamVisibilityDialog
import com.comfyui.mobile.ui.screens.aiapps.AiAppSettingsMenuDialog
import com.comfyui.mobile.ui.screens.control.CloudModelBrowserDialog
import com.comfyui.mobile.ui.screens.control.ControlLatestMediaCard
import com.comfyui.mobile.ui.screens.control.FullScreenTextEditorDialog
import com.comfyui.mobile.ui.screens.control.CompactParamCard
import com.comfyui.mobile.ui.screens.control.FullWidthParamCard
import com.comfyui.mobile.ui.screens.control.ParamRowItem
import com.comfyui.mobile.ui.screens.control.buildParamRowItems
import com.comfyui.mobile.ui.screens.control.compoundKeyOf
import com.comfyui.mobile.ui.screens.control.rememberSyncedEditableNodes
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Pause
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import com.comfyui.mobile.ui.components.DialogFormColumn




@Composable
fun AiAppDetailScreen(navController: NavController, viewModel: AiAppUi, appId: String) {
    val apps by viewModel.aiApps.collectAsState()
    val editableNodes by viewModel.aiAppEditableNodes.collectAsState()
    val batchCount by viewModel.batchCount.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val executionStatus by viewModel.executionStatus.collectAsState()
    val latestGenerationList by viewModel.aiAppLatestResults.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val executingNodeName by viewModel.aiAppExecutingNodeName.collectAsState()
    val servers by viewModel.servers.collectAsState()
    val currentServerId by viewModel.currentServerId.collectAsState()
    val currentServer = servers.firstOrNull { it.effectiveId == currentServerId || it.id == currentServerId }
    val isCloudMode = currentServer?.isRunningHub == true
    val aiAppNeedsReimport by viewModel.aiAppNeedsReimport.collectAsState()
    val rhAppRunning by viewModel.rhAppRunning.collectAsState()
    val rhAppStatus by viewModel.rhAppStatus.collectAsState()

    val app = apps.firstOrNull { it.id == appId }
    val isRhWebApp = app?.isRhWebApp == true
    val cover = app?.let { viewModel.getAiAppCoverFile(it) }

    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    val floatingNavClearance = LocalFloatingNavClearance.current
    val scheme = MiuixTheme.colorScheme
    val cardColor = scheme.secondaryContainer
    val onCard = scheme.onSecondaryContainer
    val cardColors = CardDefaults.defaultColors(color = cardColor, contentColor = onCard)

    // Saveable state for upload picker
    var currentUploadNodeId by rememberSaveable { mutableStateOf<String?>(null) }
    var currentUploadKey by rememberSaveable { mutableStateOf<String?>(null) }

    // Dialog & UI states
    var showSettingsMenu by remember { mutableStateOf(false) }
    var showParamVisibilityDialog by remember { mutableStateOf(false) }
    var showEditTitleDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var editingTitleValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    var selectedResultMedia by remember { mutableStateOf<MediaOutput?>(null) }
    var renamingTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var renamingText by remember { mutableStateOf("") }
    var editingTextNode by remember { mutableStateOf<EditableNode.TextInput?>(null) }
    var dropdownTargetNode by remember { mutableStateOf<EditableNode.TextInput?>(null) }

    var showCloudModelBrowser by remember { mutableStateOf(false) }
    var cloudModelTargetNodeId by remember { mutableStateOf<String?>(null) }
    var cloudModelTargetKey by remember { mutableStateOf<String?>(null) }
    var cloudModelInitialType by remember { mutableStateOf("CHECKPOINT") }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        val nodeId = currentUploadNodeId
        if (uri != null && nodeId != null) {
            viewModel.uploadImageToNode(uri, nodeId, currentUploadKey, forAiApp = true)
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val nodeId = currentUploadNodeId
        if (uri != null && nodeId != null) {
            viewModel.uploadImageToNode(uri, nodeId, currentUploadKey, forAiApp = true)
        }
    }

    val coverPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null && app != null) {
            viewModel.updateAiAppCover(app.id, uri)
        }
    }

    LaunchedEffect(appId) {
        viewModel.openAiAppSession(appId)
    }

    val coverModel = remember(cover?.absolutePath, app?.updatedAt, context) {
        if (cover != null) {
            coverRequest(context, cover, "${cover.absolutePath}:${app.updatedAt}", crossfade = false)
        } else null
    }

    val listState = rememberLazyListState()
    var draggingKey by remember { mutableStateOf<String?>(null) }
    val localEditableNodes = rememberSyncedEditableNodes(editableNodes, draggingKey)
    var hoverTargetKey by remember { mutableStateOf<String?>(null) }
    var draggingOffset by remember { mutableStateOf(Offset.Zero) }
    var dragStartPointerRoot by remember { mutableStateOf(Offset.Zero) }
    val itemBounds = remember { mutableStateMapOf<String, Rect>() }
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    var listBounds by remember { mutableStateOf<Rect?>(null) }
    var scrollJob by remember { mutableStateOf<Job?>(null) }


    val visibleNodes = localEditableNodes.filter { it.isVisible }
    val rhBusy = isRhWebApp && rhAppRunning

    val rowItems = remember(visibleNodes) { buildParamRowItems(visibleNodes) }

    val makeDragModifier: (String) -> Modifier = { compound ->
        val isDragging = draggingKey == compound
        val isHoverTarget = hoverTargetKey == compound
        Modifier
            .onGloballyPositioned { coords ->
                if (draggingKey != compound) {
                    val pos = coords.positionInRoot()
                    val size = coords.size
                    itemBounds[compound] = Rect(
                        left = pos.x,
                        top = pos.y,
                        right = pos.x + size.width.toFloat(),
                        bottom = pos.y + size.height.toFloat()
    )
                }
            }
            .pointerInput(compound) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        draggingKey = compound
                        hoverTargetKey = null
                        draggingOffset = Offset.Zero
                        val bounds = itemBounds[compound]
                        dragStartPointerRoot = if (bounds != null) {
                            Offset(bounds.left + offset.x, bounds.top + offset.y)
                        } else {
                            offset
                        }
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDragEnd = {
                        val activeKey = draggingKey
                        val targetKey = hoverTargetKey
                        if (activeKey != null && targetKey != null && activeKey != targetKey) {
                            val fromIndex = localEditableNodes.indexOfFirst { compoundKeyOf(it) == activeKey }
                            if (targetKey.startsWith("empty_slot_after_")) {
                                val anchorKey = targetKey.removePrefix("empty_slot_after_")
                                val anchorIndex = localEditableNodes.indexOfFirst { compoundKeyOf(it) == anchorKey }
                                if (fromIndex != -1 && anchorIndex != -1 && fromIndex != anchorIndex) {
                                    val moved = localEditableNodes.removeAt(fromIndex)
                                    val newAnchorIndex = localEditableNodes.indexOfFirst { compoundKeyOf(it) == anchorKey }
                                    if (newAnchorIndex != -1) {
                                        val insertIndex = (newAnchorIndex + 1).coerceAtMost(localEditableNodes.size)
                                        localEditableNodes.add(insertIndex, moved)
                                        viewModel.aiAppUpdateParamOrder(localEditableNodes.map { compoundKeyOf(it) })
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                }
                            } else {
                                val toIndex = localEditableNodes.indexOfFirst { compoundKeyOf(it) == targetKey }
                                if (fromIndex != -1 && toIndex != -1 && fromIndex != toIndex) {
                                    val moved = localEditableNodes.removeAt(fromIndex)
                                    localEditableNodes.add(toIndex, moved)
                                    viewModel.aiAppUpdateParamOrder(localEditableNodes.map { compoundKeyOf(it) })
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            }
                        }
                        scrollJob?.cancel()
                        scrollJob = null
                        draggingKey = null
                        hoverTargetKey = null
                        draggingOffset = Offset.Zero
                    },
                    onDragCancel = {
                        scrollJob?.cancel()
                        scrollJob = null
                        draggingKey = null
                        hoverTargetKey = null
                        draggingOffset = Offset.Zero
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        draggingOffset += dragAmount
                        val currentPointer = dragStartPointerRoot + draggingOffset

                        val bounds = listBounds
                        if (bounds != null) {
                            val scrollThreshold = 180f
                            val topScrollZone = bounds.top + scrollThreshold
                            val bottomScrollZone = bounds.bottom - scrollThreshold

                            if (currentPointer.y < topScrollZone) {
                                val speedFactor = ((topScrollZone - currentPointer.y) / scrollThreshold).coerceIn(0.2f, 1f)
                                if (scrollJob == null || scrollJob?.isActive == false) {
                                    scrollJob = coroutineScope.launch {
                                        val scrolled = listState.scrollBy(-24f * speedFactor)
                                        if (scrolled != 0f) {
                                            draggingOffset += Offset(0f, scrolled)
                                        }
                                    }
                                }
                            } else if (currentPointer.y > bottomScrollZone) {
                                val speedFactor = ((currentPointer.y - bottomScrollZone) / scrollThreshold).coerceIn(0.2f, 1f)
                                if (scrollJob == null || scrollJob?.isActive == false) {
                                    scrollJob = coroutineScope.launch {
                                        val scrolled = listState.scrollBy(24f * speedFactor)
                                        if (scrolled != 0f) {
                                            draggingOffset += Offset(0f, scrolled)
                                        }
                                    }
                                }
                            }
                        }

                        val draggedBounds = itemBounds[compound]
                        val draggedCenter = (draggedBounds?.center ?: currentPointer) + draggingOffset

                        val allKeys = itemBounds.keys.filter { it != compound && it != "empty_slot_after_$compound" }

                        val candidate = allKeys.firstOrNull { k ->
                            val rect = itemBounds[k] ?: return@firstOrNull false
                            rect.contains(currentPointer) || rect.contains(draggedCenter)
                        } ?: allKeys.firstOrNull { k ->
                            val rect = itemBounds[k] ?: return@firstOrNull false
                            val tol = 24f
                            (currentPointer.x in (rect.left - tol)..(rect.right + tol) &&
                             currentPointer.y in (rect.top - tol)..(rect.bottom + tol)) ||
                            (draggedCenter.x in (rect.left - tol)..(rect.right + tol) &&
                             draggedCenter.y in (rect.top - tol)..(rect.bottom + tol))
                        }

                        val newHoverKey = candidate
                        if (newHoverKey != hoverTargetKey) {
                            hoverTargetKey = newHoverKey
                            if (newHoverKey != null) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                    }
                )
            }
            .zIndex(if (isDragging) 100f else if (isHoverTarget) 10f else 0f)
            .graphicsLayer {
                if (isDragging) {
                    translationX = draggingOffset.x
                    translationY = draggingOffset.y
                    scaleX = 1.05f
                    scaleY = 1.05f
                    shadowElevation = 24f
                    alpha = 0.95f
                } else if (isHoverTarget) {
                    scaleX = 0.97f
                    scaleY = 0.97f
                }
                shape = PocketTokens.Shape.Medium
                clip = false
            }
            .then(
                if (isDragging || isHoverTarget) {
                    Modifier.border(2.dp, scheme.primary, PocketTokens.Shape.Medium)
                } else {
                    Modifier
                }
            )
    }

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                title = app?.title ?: "AI应用",
                largeTitle = app?.title ?: "AI应用",
                color = scheme.background,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(MiuixIcons.Back, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showSettingsMenu = true }) {
                        Icon(MiuixIcons.Settings, contentDescription = "应用设置")
                    }
                }
    )
        }
    ) { innerPadding ->
        if (app == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .overScrollVertical()
                    .padding(horizontal = 20.dp)
                    .padding(top = innerPadding.calculateTopPadding() + 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
    ) {
                AiAppEmptyCard(isCloudMode = isCloudMode)
                Button(
                    onClick = { navController.popBackStack() },
                    colors = ButtonDefaults.buttonColorsPrimary()
    ) {
                    Text("返回上一页")
                }
            }
            return@Scaffold
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .onGloballyPositioned { coords ->
                    val pos = coords.positionInRoot()
                    val size = coords.size
                    listBounds = Rect(pos.x, pos.y, pos.x + size.width.toFloat(), pos.y + size.height.toFloat())
                },
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = 24.dp + floatingNavClearance
    ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
            // 1. Reimport Alert (only if legacy cloud import)
            if (aiAppNeedsReimport) {
                item(key = "reimport_alert") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = PocketTokens.Radius.Medium,
                        colors = CardDefaults.defaultColors(
                            color = scheme.errorContainer.copy(alpha = 0.4f),
                            contentColor = onCard
    )
    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "请重新导入此应用",
                                style = MiuixTheme.textStyles.subtitle.copy(fontWeight = FontWeight.Bold),
                                color = scheme.error
    )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isCloudMode)
                                    "旧版导入结构缺少必须的云端应用元数据，无法直接运行。请重新从云端导入后删除此项。"
                                else
                                    "此应用属于 RunningHub 云端应用，请切换到 RunningHub 服务器后再导入运行。",
                                style = MiuixTheme.textStyles.footnote1,
                                color = onCard.copy(alpha = 0.85f)
    )
                        }
                    }
                }
            }

            // 2. Run & Execution Control Card (compact, unified)
            item(key = "run_card") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = PocketTokens.Radius.Large,
                    colors = cardColors
    ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
                            // Cover thumbnail (if set)
                            if (coverModel != null) {
                                AsyncImage(
                                    model = coverModel,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(PocketTokens.Shape.Medium)
                                        .border(1.dp, scheme.outline.copy(alpha = 0.2f), PocketTokens.Shape.Medium),
                                    contentScale = ContentScale.Crop
    )
                            }

                            // Status badge
                            val statusLabel = when {
                                isRhWebApp -> if (rhAppRunning) (rhAppStatus ?: "运行中") else "就绪"
                                executionStatus == "RUNNING" || isRunning -> "运行中"
                                executionStatus == "COMPLETED" -> "运行完成"
                                else -> "就绪"
                            }
                            val statusColor = if (executionStatus == "RUNNING" || rhBusy || isRunning) scheme.primary else onCard.copy(alpha = 0.7f)
                            Card(
                                cornerRadius = 999.dp,
                                insideMargin = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
                                colors = CardDefaults.defaultColors(color = statusColor.copy(alpha = 0.12f))
    ) {
                                Text(
                                    text = statusLabel,
                                    style = MiuixTheme.textStyles.footnote1.copy(fontWeight = FontWeight.Medium),
                                    color = statusColor
    )
                            }

                            if (isRhWebApp) {
                                Card(
                                    cornerRadius = 999.dp,
                                    insideMargin = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
                                    colors = CardDefaults.defaultColors(color = scheme.surfaceVariant)
    ) {
                                    Text(
                                        text = "云端",
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = onCard.copy(alpha = 0.75f)
    )
                                }
                            }

                            Spacer(modifier = Modifier.weight(1f))

                            // Batch Stepper: - ×1 +
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
                                IconButton(
                                    onClick = { viewModel.setBatchCount((batchCount - 1).coerceAtLeast(1)) },
                                    enabled = batchCount > 1,
                                    modifier = Modifier.size(26.dp)
    ) {
                                    Text(
                                        text = "-",
                                        style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.Bold),
                                        color = if (batchCount > 1) onCard else onCard.copy(alpha = 0.25f)
    )
                                }
                                Text(
                                    text = "×$batchCount",
                                    style = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.SemiBold),
                                    color = onCard,
                                    modifier = Modifier.padding(horizontal = 4.dp)
    )
                                IconButton(
                                    onClick = { viewModel.setBatchCount((batchCount + 1).coerceAtMost(8)) },
                                    enabled = batchCount < 8,
                                    modifier = Modifier.size(26.dp)
    ) {
                                    Text(
                                        text = "+",
                                        style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.Bold),
                                        color = if (batchCount < 8) onCard else onCard.copy(alpha = 0.25f)
    )
                                }
                            }
                        }

                        // Big Action Button
                        Button(
                            onClick = {
                                if (rhBusy) viewModel.cancelRhAppRun() else viewModel.runAiApp(batchCount)
                            },
                            enabled = !(aiAppNeedsReimport && isCloudMode),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(42.dp),
                            cornerRadius = PocketTokens.Radius.Medium,
                            colors = if (rhBusy) {
                                ButtonDefaults.buttonColors(
                                    color = scheme.error.copy(alpha = 0.15f),
                                    contentColor = scheme.error
    )
                            } else {
                                ButtonDefaults.buttonColorsPrimary()
                            }
    ) {
                            Icon(
                                if (rhBusy) MiuixIcons.Pause else MiuixIcons.Play,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
    )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = when {
                                    rhBusy -> "取消运行"
                                    isRhWebApp -> if (batchCount > 1) "运行应用 ×$batchCount" else "运行应用"
                                    executionStatus == "RUNNING" -> "加入队列 ×$batchCount"
                                    else -> if (batchCount > 1) "运行应用 ×$batchCount" else "运行应用"
                                },
                                style = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.SemiBold)
    )
                        }

                        // Running progress bar
                        if (rhBusy) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                LinearProgressIndicator(
                                    progress = 0.35f,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(PocketTokens.Shape.Pill),
                                    colors = ProgressIndicatorDefaults.progressIndicatorColors(
                                        foregroundColor = scheme.primary,
                                        backgroundColor = scheme.surfaceContainerHigh
    )
    )
                                if (!rhAppStatus.isNullOrBlank()) {
                                    Text(
                                        text = rhAppStatus ?: "",
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = onCard.copy(alpha = 0.7f)
    )
                                }
                            }
                        } else if (!isRhWebApp && (executionStatus == "RUNNING" || isRunning)) {
                            val p = progress.coerceIn(0f, 1f)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                LinearProgressIndicator(
                                    progress = if (p > 0f) p else 0.35f,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(PocketTokens.Shape.Pill),
                                    colors = ProgressIndicatorDefaults.progressIndicatorColors(
                                        foregroundColor = scheme.primary,
                                        backgroundColor = scheme.surfaceContainerHigh
    )
    )
                                if (executingNodeName.isNotBlank()) {
                                    Text(
                                        text = executingNodeName,
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = onCard.copy(alpha = 0.7f)
    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. Parameters Section Header
            item(key = "parameters_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
    ) {
                    Text(
                        text = "参数 (${visibleNodes.size})",
                        style = MiuixTheme.textStyles.title3.copy(fontWeight = FontWeight.SemiBold),
                        color = onCard
    )
                    TextButton(
                        text = "显示管理",
                        onClick = { showParamVisibilityDialog = true }
    )
                }
            }

            // 4. Parameter Cards (Dual-column for compact nodes, full-width for prompt/media)
            item(key = "parameters_list") {
                if (visibleNodes.isEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = PocketTokens.Radius.Medium,
                        colors = cardColors
    ) {
                        Text(
                            text = "暂无显示的参数，点击右上角设置或“显示管理”进行勾选",
                            style = MiuixTheme.textStyles.body2,
                            color = onCard.copy(alpha = 0.6f),
                            modifier = Modifier.padding(16.dp)
    )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .zIndex(if (draggingKey != null) 999f else 0f)
                            .graphicsLayer { clip = false },
                        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
                        rowItems.forEach { rowItem ->
                            when (rowItem) {
                                is ParamRowItem.Full -> {
                                    val k = compoundKeyOf(rowItem.node)
                                    val isRowDragging = draggingKey == k
                                    key(k) {
                                        FullWidthParamCard(
                                            node = rowItem.node,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .zIndex(if (isRowDragging) 999f else 0f)
                                                .graphicsLayer { clip = false }
                                                .then(makeDragModifier(k)),
                                            isDragging = isRowDragging,
                                            isHoverTarget = hoverTargetKey == k,
                                            onCard = onCard,
                                            isCloudMode = isCloudMode,
                                            onRenameClick = { nodeId, key, title ->
                                                renamingTarget = nodeId to key
                                                renamingText = title
                                            },
                                            onValueChange = { nodeId, key, value ->
                                                viewModel.aiAppUpdateNodeValue(nodeId, key, value)
                                            },
                                            onOpenFullScreenText = { node -> editingTextNode = node },
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

                                is ParamRowItem.Pair -> {
                                    val k1 = compoundKeyOf(rowItem.first)
                                    val k2 = compoundKeyOf(rowItem.second)
                                    val isRowDragging = draggingKey == k1 || draggingKey == k2
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .zIndex(if (isRowDragging) 999f else 0f)
                                            .graphicsLayer { clip = false },
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
                                        key(k1) {
                                            CompactParamCard(
                                                node = rowItem.first,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .then(makeDragModifier(k1)),
                                                isDragging = draggingKey == k1,
                                                isHoverTarget = hoverTargetKey == k1,
                                                onCard = onCard,
                                                onRenameClick = { nodeId, key, title ->
                                                    renamingTarget = nodeId to key
                                                    renamingText = title
                                                },
                                                onValueChange = { nodeId, key, value ->
                                                    viewModel.aiAppUpdateNodeValue(nodeId, key, value)
                                                },
                                                onDropdownClick = { dropdownTargetNode = it }
    )
                                        }
                                        key(k2) {
                                            CompactParamCard(
                                                node = rowItem.second,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .then(makeDragModifier(k2)),
                                                isDragging = draggingKey == k2,
                                                isHoverTarget = hoverTargetKey == k2,
                                                onCard = onCard,
                                                onRenameClick = { nodeId, key, title ->
                                                    renamingTarget = nodeId to key
                                                    renamingText = title
                                                },
                                                onValueChange = { nodeId, key, value ->
                                                    viewModel.aiAppUpdateNodeValue(nodeId, key, value)
                                                },
                                                onDropdownClick = { dropdownTargetNode = it }
    )
                                        }
                                    }
                                }

                                is ParamRowItem.Half -> {
                                    val kh = compoundKeyOf(rowItem.node)
                                    val emptySlotKey = "empty_slot_after_$kh"
                                    val isRowDragging = draggingKey == kh || draggingKey == emptySlotKey
                                    val isSlotHover = hoverTargetKey == emptySlotKey
                                    val density = LocalDensity.current
                                    val cardHeight = itemBounds[kh]?.height
                                    val heightModifier = if (cardHeight != null && cardHeight > 0f) {
                                        Modifier.height(with(density) { cardHeight.toDp() })
                                    } else {
                                        Modifier.heightIn(min = 72.dp)
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .zIndex(if (isRowDragging) 999f else 0f)
                                            .graphicsLayer { clip = false },
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
                                        key(kh) {
                                            CompactParamCard(
                                                node = rowItem.node,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .then(makeDragModifier(kh)),
                                                isDragging = draggingKey == kh,
                                                isHoverTarget = hoverTargetKey == kh,
                                                onCard = onCard,
                                                onRenameClick = { nodeId, key, title ->
                                                    renamingTarget = nodeId to key
                                                    renamingText = title
                                                },
                                                onValueChange = { nodeId, key, value ->
                                                    viewModel.aiAppUpdateNodeValue(nodeId, key, value)
                                                },
                                                onDropdownClick = { dropdownTargetNode = it }
    )
                                        }

                                        // Empty slot drop target
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .then(heightModifier)
                                                .onGloballyPositioned { coords ->
                                                    val pos = coords.positionInRoot()
                                                    val size = coords.size
                                                    itemBounds[emptySlotKey] = Rect(
                                                        left = pos.x,
                                                        top = pos.y,
                                                        right = pos.x + size.width.toFloat(),
                                                        bottom = pos.y + size.height.toFloat()
    )
                                                }
                                                .graphicsLayer {
                                                    if (isSlotHover) {
                                                        scaleX = 0.98f
                                                        scaleY = 0.98f
                                                    }
                                                }
                                                .then(
                                                    if (isSlotHover) {
                                                        Modifier
                                                            .border(2.dp, scheme.primary, PocketTokens.Shape.Medium)
                                                            .background(scheme.primaryContainer.copy(alpha = 0.45f), PocketTokens.Shape.Medium)
                                                    } else if (draggingKey != null && draggingKey != kh) {
                                                        Modifier
                                                            .border(1.5.dp, scheme.outline.copy(alpha = 0.3f), PocketTokens.Shape.Medium)
                                                            .background(scheme.surfaceContainerHigh.copy(alpha = 0.35f), PocketTokens.Shape.Medium)
                                                    } else {
                                                        Modifier
                                                    }
                                                )
                                                .padding(10.dp),
                                            contentAlignment = Alignment.Center
    ) {
                                            if (isSlotHover) {
                                                Text(
                                                    text = "放置于此",
                                                    style = MiuixTheme.textStyles.footnote2.copy(fontWeight = FontWeight.Bold),
                                                    color = scheme.primary
    )
                                            } else if (draggingKey != null && draggingKey != kh) {
                                                Text(
                                                    text = "+ 放置空位",
                                                    style = MiuixTheme.textStyles.footnote2,
                                                    color = onCard.copy(alpha = 0.45f)
    )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 5. Results Section
            if (latestGenerationList.isNotEmpty()) {
                item(key = "latest_results") {
                    ControlLatestMediaCard(
                        latestGenerationList = latestGenerationList,
                        cardColors = cardColors,
                        onCard = onCard,
                        cornerRadius = PocketTokens.Radius.Large,
                        getViewUrl = { viewModel.getViewUrl(it) },
                        onItemClick = { index ->
                            selectedResultMedia = latestGenerationList.getOrNull(index)
                        }
    )
                }
            }
        }

        // --- Dialogs ---
        AiAppSettingsMenuDialog(
            show = showSettingsMenu,
            onDismiss = { showSettingsMenu = false },
            onManageParams = {
                showSettingsMenu = false
                showParamVisibilityDialog = true
            },
            onEditTitle = {
                showSettingsMenu = false
                editingTitleValue = TextFieldValue(app.title)
                showEditTitleDialog = true
            },
            onEditCover = {
                showSettingsMenu = false
                coverPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onDelete = {
                showSettingsMenu = false
                showDeleteConfirm = true
            }
    )

        AiAppEditTitleDialog(
            show = showEditTitleDialog,
            titleValue = editingTitleValue,
            onValueChange = { editingTitleValue = it },
            onDismiss = { showEditTitleDialog = false },
            onConfirm = {
                if (editingTitleValue.text.isNotBlank()) {
                    viewModel.updateAiAppTitle(app.id, editingTitleValue.text.trim())
                }
                showEditTitleDialog = false
            }
    )

        AiAppDeleteConfirmDialog(
            app = if (showDeleteConfirm) app else null,
            onDismiss = { showDeleteConfirm = false },
            onConfirm = { toDelete ->
                viewModel.deleteAiApp(toDelete.id)
                showDeleteConfirm = false
                navController.popBackStack()
            }
    )

        AiAppParamVisibilityDialog(
            show = showParamVisibilityDialog,
            editableNodes = editableNodes,
            onDismiss = { showParamVisibilityDialog = false },
            onRenameClick = { nodeId, key, currentTitle ->
                renamingTarget = nodeId to key
                renamingText = currentTitle
            },
            onToggleVisibility = { nodeId, key, visible ->
                viewModel.aiAppToggleNodeVisibility(nodeId, key, visible)
            }
    )

        // Dropdown selection dialog
        if (dropdownTargetNode != null) {
            val target = dropdownTargetNode!!
            val options = target.options.orEmpty()
            OverlayDialog(
                show = true,
                title = target.title,
                onDismissRequest = { dropdownTargetNode = null }
    ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp)
                ) {
                    items(options, key = { it }) { opt ->
                        val selected = opt == target.text
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.aiAppUpdateNodeValue(target.nodeId, target.key, opt)
                                    dropdownTargetNode = null
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
    ) {
                            Text(
                                text = opt,
                                style = MiuixTheme.textStyles.body1,
                                color = if (selected) scheme.primary else onCard
    )
                            if (selected) {
                                Icon(
                                    MiuixIcons.Ok,
                                    contentDescription = null,
                                    tint = scheme.primary,
                                    modifier = Modifier.size(18.dp)
    )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    text = "取消",
                    onClick = { dropdownTargetNode = null },
                    modifier = Modifier.fillMaxWidth()
    )
            }
        }

        if (editingTextNode != null) {
            FullScreenTextEditorDialog(
                title = editingTextNode!!.title,
                initialText = editingTextNode!!.text,
                onConfirm = { text ->
                    val node = editingTextNode!!
                    viewModel.aiAppUpdateNodeValue(node.nodeId, node.key, text)
                    editingTextNode = null
                },
                onDismiss = { editingTextNode = null }
    )
        }

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
                                val target = renamingTarget ?: return@TextButton
                                val (nodeId, key) = target
                                viewModel.aiAppSetParamCustomName(nodeId, key, renamingText)
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
                    viewModel.aiAppUpdateNodeValue(targetNodeId, targetKey, item.effectiveModelName)
                    viewModel.showToast("已选择模型: ${item.resourceName}")
                }
                showCloudModelBrowser = false
            },
            onSelectModelWithTriggerNode = { item, textNode, triggers ->
                val targetNodeId = cloudModelTargetNodeId
                val targetKey = cloudModelTargetKey
                if (targetNodeId != null && targetKey != null) {
                    viewModel.aiAppUpdateNodeValue(targetNodeId, targetKey, item.effectiveModelName)
                    val cur = textNode.text.trim()
                    val next = if (cur.isBlank()) triggers else "$cur, $triggers"
                    viewModel.aiAppUpdateNodeValue(textNode.nodeId, textNode.key, next)
                    viewModel.showToast("已选择模型并写入 #${textNode.nodeId}")
                }
                showCloudModelBrowser = false
            }
    )

        if (selectedResultMedia != null && latestGenerationList.isNotEmpty()) {
            fun keyFor(media: MediaOutput): String = "${media.subfolder}|${media.filename}|${media.type}"
            val selectedKey = keyFor(selectedResultMedia!!)
            val initialIndex = latestGenerationList.indexOfFirst { keyFor(it) == selectedKey }.coerceAtLeast(0)
            FullScreenPreview(
                initialIndex = initialIndex,
                mediaList = latestGenerationList,
                viewModel = viewModel,
                isLocalGallery = false,
                onDismiss = { selectedResultMedia = null }
    )
        }
    }
}
