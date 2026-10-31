package com.comfyui.mobile.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import com.comfyui.mobile.ui.designsystem.PocketTokens
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.consumeAllChanges
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.navigation.NavController
import com.comfyui.mobile.model.AiApp
import com.comfyui.mobile.ui.LocalFloatingNavClearance
import com.comfyui.mobile.ui.MainViewModel
import com.comfyui.mobile.ui.screens.aiapps.AiAppCard
import com.comfyui.mobile.ui.screens.aiapps.AiAppDeleteConfirmDialog
import com.comfyui.mobile.ui.screens.aiapps.AiAppEditTitleDialog
import com.comfyui.mobile.ui.screens.aiapps.AiAppEmptyCard
import com.comfyui.mobile.ui.screens.aiapps.AiAppSettingsMenuDialog
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
fun AiAppsScreen(navController: NavController, viewModel: MainViewModel) {
    val allApps by viewModel.aiApps.collectAsState()
    val servers by viewModel.servers.collectAsState()
    val currentServerId by viewModel.currentServerId.collectAsState()
    val currentServer = servers.firstOrNull { it.effectiveId == currentServerId || it.id == currentServerId }
    val isCloudMode = currentServer?.isRunningHub == true
    // Cloud: only apps of the current profile's site (中国站 / 国际站) plus unmarked older ones.
    val currentSite = currentServer?.takeIf { it.isRunningHub }?.effectiveRhSite
    val apps = remember(allApps, currentSite) {
        allApps.filter { com.comfyui.mobile.ui.MainViewModelAiApps.isVisibleOnSite(it, currentSite?.id) }
    }

    var showSyncDialog by remember { mutableStateOf(false) }
    var syncInput by remember { mutableStateOf("") }
    var isSyncing by remember { mutableStateOf(false) }
    var syncError by remember { mutableStateOf<String?>(null) }

    var editTitleApp by remember { mutableStateOf<AiApp?>(null) }
    var deleteApp by remember { mutableStateOf<AiApp?>(null) }
    var coverPickAppId by remember { mutableStateOf<String?>(null) }
    var settingsMenuAppId by remember { mutableStateOf<String?>(null) }

    var editingTitle by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }

    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val localApps = remember { mutableStateListOf<AiApp>() }
    var isDragging by remember { mutableStateOf(false) }
    var draggingAppId by remember { mutableStateOf<String?>(null) }
    var draggingOffset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(apps) {
        if (!isDragging) {
            localApps.clear()
            localApps.addAll(apps)
        }
    }

    val coverPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val id = coverPickAppId
        if (uri != null && id != null) {
            viewModel.updateAiAppCover(id, uri)
        }
        coverPickAppId = null
    }

    LaunchedEffect(editTitleApp?.id) {
        val app = editTitleApp ?: return@LaunchedEffect
        editingTitle = TextFieldValue(app.title, TextRange(app.title.length))
    }

    AiAppEditTitleDialog(
        show = editTitleApp != null,
        titleValue = editingTitle,
        onValueChange = { editingTitle = it },
        onDismiss = { editTitleApp = null },
        onConfirm = {
            val app = editTitleApp ?: return@AiAppEditTitleDialog
            viewModel.updateAiAppTitle(app.id, editingTitle.text)
            editTitleApp = null
        }
    )

    AiAppDeleteConfirmDialog(
        app = deleteApp,
        onDismiss = { deleteApp = null },
        onConfirm = { a ->
            viewModel.deleteAiApp(a.id)
            deleteApp = null
        }
    )

    val menuApp = (if (isDragging) localApps else apps).firstOrNull { it.id == settingsMenuAppId }
    AiAppSettingsMenuDialog(
        show = settingsMenuAppId != null,
        onDismiss = { settingsMenuAppId = null },
        onEditTitle = {
            editTitleApp = menuApp
            settingsMenuAppId = null
        },
        onEditCover = {
            if (menuApp != null) {
                coverPickAppId = menuApp.id
                coverPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            settingsMenuAppId = null
        },
        onDelete = {
            deleteApp = menuApp
            settingsMenuAppId = null
        }
    )

    val scheme = MiuixTheme.colorScheme
    val scrollBehavior = MiuixScrollBehavior()
    val floatingNavClearance = LocalFloatingNavClearance.current

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                title = if (isCloudMode) "云端应用" else "AI应用",
                largeTitle = if (isCloudMode) "云端应用" else "AI应用",
                color = scheme.background,
                scrollBehavior = scrollBehavior,
                actions = {
                    if (isCloudMode) {
                        IconButton(onClick = { showSyncDialog = true; syncError = null }) {
                            Icon(MiuixIcons.Add, contentDescription = "导入应用")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
    ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = PocketTokens.PagePadding)
                    .padding(top = 12.dp)
    ) {
                val list = if (isDragging) localApps else apps
                if (list.isEmpty()) {
                    AiAppEmptyCard(isCloudMode = isCloudMode)
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(160.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .overScrollVertical()
                            .nestedScroll(scrollBehavior.nestedScrollConnection),
                        state = gridState,
                        contentPadding = PaddingValues(
                            top = 8.dp,
                            bottom = 24.dp + floatingNavClearance
    ),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
                        items(list, key = { it.id }) { app ->
                            val cover = viewModel.getAiAppCoverFile(app)
                            AiAppCard(
                                app = app,
                                coverFile = cover,
                                isDragging = isDragging,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(
                                        1.dp,
                                        scheme.outline.copy(alpha = 0.38f),
                                        PocketTokens.Shape.XLarge
    )
                                    .pointerInput(app.id) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                isDragging = true
                                                draggingAppId = app.id
                                                draggingOffset = Offset.Zero
                                            },
                                            onDragCancel = {
                                                isDragging = false
                                                draggingAppId = null
                                                draggingOffset = Offset.Zero
                                            },
                                            onDragEnd = {
                                                val ordered = localApps.map { it.id }
                                                viewModel.reorderAiApps(ordered)
                                                isDragging = false
                                                draggingAppId = null
                                                draggingOffset = Offset.Zero
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consumeAllChanges()
                                                val id = draggingAppId ?: return@detectDragGesturesAfterLongPress
                                                draggingOffset += Offset(dragAmount.x, dragAmount.y)

                                                val layoutInfo = gridState.layoutInfo
                                                val draggedInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }
                                                    ?: return@detectDragGesturesAfterLongPress

                                                val draggedCenter = Offset(
                                                    x = draggedInfo.offset.x + draggedInfo.size.width / 2f + draggingOffset.x,
                                                    y = draggedInfo.offset.y + draggedInfo.size.height / 2f + draggingOffset.y
                                                )

                                                val target = layoutInfo.visibleItemsInfo.firstOrNull { info ->
                                                    val key = info.key as? String ?: return@firstOrNull false
                                                    if (key == id) return@firstOrNull false
                                                    val left = info.offset.x.toFloat()
                                                    val top = info.offset.y.toFloat()
                                                    val right = left + info.size.width
                                                    val bottom = top + info.size.height
                                                    draggedCenter.x in left..right && draggedCenter.y in top..bottom
                                                }

                                                if (target != null) {
                                                    val fromIndex = localApps.indexOfFirst { it.id == id }
                                                    val toKey = target.key as? String
                                                    val toIndex = if (toKey != null) localApps.indexOfFirst { it.id == toKey } else -1
                                                    if (fromIndex != -1 && toIndex != -1 && fromIndex != toIndex) {
                                                        val dx = target.offset.x.toFloat() - draggedInfo.offset.x.toFloat()
                                                        val dy = target.offset.y.toFloat() - draggedInfo.offset.y.toFloat()
                                                        draggingOffset -= Offset(dx, dy)
                                                        localApps.add(toIndex, localApps.removeAt(fromIndex))
                                                    }
                                                }

                                                val topEdge = layoutInfo.viewportStartOffset + 140
                                                val bottomEdge = layoutInfo.viewportEndOffset - 140
                                                if (draggedCenter.y < topEdge) {
                                                    scope.launch { gridState.scrollBy(-24f) }
                                                } else if (draggedCenter.y > bottomEdge) {
                                                    scope.launch { gridState.scrollBy(24f) }
                                                }
                                            }
                                        )
                                    }
                                    .graphicsLayer {
                                        if (draggingAppId == app.id) {
                                            translationX = draggingOffset.x
                                            translationY = draggingOffset.y
                                            shape = PocketTokens.Shape.XLarge
                                            clip = true
                                        }
                                    }
                                    .zIndex(if (draggingAppId == app.id) 10f else 0f)
                                    .then(if (!isDragging) Modifier.animateItem() else Modifier),
                                onClick = { navController.navigate("ai_app/${app.id}") },
                                onSettingsClick = { settingsMenuAppId = app.id }
    )
                        }
                    }
                }
            }
        }
    }

    OverlayDialog(
        show = showSyncDialog,
        title = if (currentSite != null) "导入应用 (${currentSite.displayName})" else "导入应用",
        onDismissRequest = { if (!isSyncing) showSyncDialog = false }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
    ) {
            Text(
                text = "粘贴应用链接或 ID：",
                style = MiuixTheme.textStyles.body2,
                color = scheme.onSecondaryContainer
    )
            Spacer(modifier = Modifier.height(8.dp))
            TextField(
                value = syncInput,
                onValueChange = { syncInput = it; syncError = null },
                label = if (currentSite != null) "${currentSite.displayName} 链接或 ID" else "链接或 ID",
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
                    text = if (isSyncing) "导入中..." else "导入",
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        if (syncInput.isBlank()) {
                            syncError = "请输入链接或 ID"
                            return@TextButton
                        }
                        isSyncing = true
                        viewModel.syncRunningHubResource(
                            inputUrlOrId = syncInput,
                            onSuccess = { msg ->
                                isSyncing = false
                                showSyncDialog = false
                                syncInput = ""
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
}
