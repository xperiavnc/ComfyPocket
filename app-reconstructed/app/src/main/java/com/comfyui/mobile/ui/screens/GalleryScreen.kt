package com.comfyui.mobile.ui.screens

import com.comfyui.mobile.data.AppModule
import com.comfyui.mobile.ui.designsystem.PocketCardColors
import com.comfyui.mobile.ui.designsystem.PocketTokens
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.gestures.detectTapGestures

import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Pause
import top.yukonga.miuix.kmp.icon.extended.AppRecording
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Rename
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Remove
import top.yukonga.miuix.kmp.icon.extended.Share

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.comfyui.mobile.model.MediaOutput
import com.comfyui.mobile.model.MediaType
import com.comfyui.mobile.ui.GalleryCategory
import com.comfyui.mobile.ui.GalleryUi
import com.comfyui.mobile.ui.components.ensureFilenameForMedia
import com.comfyui.mobile.ui.components.mimeTypeForMedia
import com.comfyui.mobile.ui.components.performShareMedia
import com.comfyui.mobile.ui.MediaPreviewHost
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.FloatingToolbar
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.input.nestedscroll.nestedScroll
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.utils.overScrollVertical
import com.comfyui.mobile.ui.LocalFloatingNavClearance
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
fun GalleryScreen(navController: NavController, viewModel: GalleryUi, sourcePicker: @Composable () -> Unit = {}) {
    val mediaList by viewModel.sessionMediaList.collectAsState()
    val localMediaList by viewModel.localMediaList.collectAsState()
    val gallerySource by viewModel.gallerySource.collectAsState()
    val isLocalGallery = gallerySource == GalleryUi.GALLERY_LOCAL
    val baseUrl by viewModel.baseUrl.collectAsState()
    val categories by viewModel.galleryCategories.collectAsState()
    val categoryAssignments by viewModel.galleryCategoryAssignments.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(isLocalGallery, Unit) {
        if (isLocalGallery) viewModel.refreshLocalMedia(context)
    }
    var selectedMedia by remember { mutableStateOf<MediaOutput?>(null) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedKeys by rememberSaveable { mutableStateOf(setOf<String>()) }
    var shareBusy by remember { mutableStateOf(false) }
    var saveBusy by remember { mutableStateOf(false) }
    val shareClient = remember { AppModule.provideRepository().hostAwareCallFactory }
    val gridState = rememberLazyStaggeredGridState()
    // Learned image ratios for the masonry grid; unknown tiles start square.
    val tileAspects = remember { androidx.compose.runtime.mutableStateMapOf<String, Float>() }
    var activeCategoryId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(isLocalGallery) {
        if (!isLocalGallery) activeCategoryId = null
    }
    LaunchedEffect(categories) {
        if (activeCategoryId != null && categories.none { it.id == activeCategoryId }) {
            activeCategoryId = null
        }
    }
    var observedCategoryId by rememberSaveable { mutableStateOf(activeCategoryId) }
    LaunchedEffect(activeCategoryId) {
        if (observedCategoryId != activeCategoryId) {
            observedCategoryId = activeCategoryId
            gridState.scrollToItem(0)
        }
    }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showCategorySheet by remember { mutableStateOf(false) }
    var newCategoryName by remember { mutableStateOf("") }
    var renamingCategory by remember { mutableStateOf<GalleryCategory?>(null) }
    var renameCategoryText by remember { mutableStateOf("") }

    fun exitSelection() {
        selectionMode = false
        selectedKeys = emptySet()
    }
    PredictiveBackHandler(enabled = selectionMode && selectedMedia == null &&
        !showOverflowMenu && !showCategorySheet && renamingCategory == null) { progress ->
        progress.collect { }
        exitSelection()
    }

    fun keyFor(media: MediaOutput): String {
        return viewModel.mediaKey(media)
    }

    LaunchedEffect(renamingCategory?.id) {
        renameCategoryText = renamingCategory?.name.orEmpty()
    }

    val sourceMediaList = if (isLocalGallery) localMediaList else mediaList
    val displayMediaList = remember(sourceMediaList, categoryAssignments, activeCategoryId, isLocalGallery) {
        if (!isLocalGallery) {
            sourceMediaList
        } else when (activeCategoryId) {
            // 「全部」= 未归入任何自定义分类的媒体
            null -> sourceMediaList.filter { categoryAssignments[keyFor(it)] == null }
            else -> sourceMediaList.filter { categoryAssignments[keyFor(it)] == activeCategoryId }
        }
    }

    fun mimeTypeFor(media: MediaOutput): String = mimeTypeForMedia(media)

    fun ensureFilename(media: MediaOutput): String = ensureFilenameForMedia(media)

    fun performShare(targets: List<MediaOutput>) {
        performShareMedia(
            context = context,
            viewModel = viewModel,
            targets = targets,
            scope = scope,
            onBusyChange = { shareBusy = it },
            onSuccess = {
                if (selectionMode) {
                    selectionMode = false
                    selectedKeys = emptySet()
                }
            },
            shareClient = shareClient
        )
    }

    val scheme = MiuixTheme.colorScheme
    val cardColor = scheme.secondaryContainer

    val galleryTitle = when {
        selectionMode -> "已选 ${selectedKeys.size}"
        isLocalGallery -> "画廊·本地"
        else -> "画廊·服务器"
    }

    val scrollBehavior = MiuixScrollBehavior()
    val floatingNavClearance = LocalFloatingNavClearance.current

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                title = galleryTitle,
                largeTitle = galleryTitle,
                color = scheme.background,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    if (selectionMode) IconButton(onClick = ::exitSelection) {
                        Icon(MiuixIcons.Back, contentDescription = "退出多选")
                    }
                },
                actions = {
                    if (selectionMode) {
                        val allSelected = displayMediaList.isNotEmpty() &&
                            selectedKeys.size == displayMediaList.size
                        TextButton(
                            text = if (allSelected) "全不选" else "全选",
                            onClick = {
                                selectedKeys = if (allSelected) {
                                    emptySet()
                                } else {
                                    displayMediaList.map { keyFor(it) }.toSet()
                                }
                            },
                            colors = ButtonDefaults.textButtonColorsPrimary()
    )
                    } else {
                        Box {
                            IconButton(onClick = { showOverflowMenu = true }) {
                                Icon(MiuixIcons.More, contentDescription = "更多")
                            }
                            OverlayListPopup(
                                show = showOverflowMenu,
                                popupPositionProvider = ListPopupDefaults.dropdownPositionProvider(),
                                alignment = PopupPositionProvider.Align.TopEnd,
                                enableWindowDim = false,
                                renderInRootScaffold = false,
                                minWidth = 120.dp,
                                onDismissRequest = { showOverflowMenu = false }
    ) {
                                val menuItems = buildList {
                                    if (isLocalGallery) add("分类管理")
                                    add("刷新")
                                }
                                ListPopupColumn {
                                    menuItems.forEachIndexed { index, label ->
                                        DropdownImpl(
                                            text = label,
                                            optionSize = menuItems.size,
                                            isSelected = false,
                                            index = index,
                                            onSelectedIndexChange = {
                                                showOverflowMenu = false
                                                when (label) {
                                                    "切换到服务器" -> viewModel.setGallerySource(GalleryUi.GALLERY_SERVER)
                                                    "切换到本地" -> viewModel.setGallerySource(GalleryUi.GALLERY_LOCAL)
                                                    "分类管理" -> showCategorySheet = true
                                                    "刷新" -> {
                                                        if (isLocalGallery) viewModel.refreshLocalMedia(context)
                                                        else viewModel.checkLatestHistory()
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
    )
        }
    ) { innerPadding ->
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = PocketTokens.PagePadding)
                .padding(top = innerPadding.calculateTopPadding() + 12.dp)
    ) {
            sourcePicker()
            if (isLocalGallery) {
                val uncategorizedCount = remember(localMediaList, categoryAssignments) {
                    localMediaList.count { categoryAssignments[keyFor(it)] == null }
                }
                val categoryChipItems = remember(categories, localMediaList, categoryAssignments, uncategorizedCount) {
                    buildList {
                        add(null to "全部 $uncategorizedCount")
                        categories.forEach { cat ->
                            val count = localMediaList.count { categoryAssignments[keyFor(it)] == cat.id }
                            add(cat.id to "${cat.name} $count")
                        }
                    }
                }
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(end = 4.dp)
    ) {
                    items(categoryChipItems.size, key = { idx -> categoryChipItems[idx].first ?: "__all__" }) { idx ->
                        val (id, label) = categoryChipItems[idx]
                        CategoryChip(
                            label = label,
                            isSelected = activeCategoryId == id,
                            onClick = { activeCategoryId = id }
    )
                    }
                }
            }

            if (displayMediaList.isNotEmpty()) {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2),
                    state = gridState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("legacy-gallery-grid")
                        .padding(top = 4.dp)
                        .overScrollVertical()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    contentPadding = PaddingValues(
                        bottom = (if (selectionMode && selectedKeys.isNotEmpty()) 100.dp else 24.dp) + floatingNavClearance
                    ),
                    verticalItemSpacing = PocketTokens.Space.XS,
                    horizontalArrangement = Arrangement.spacedBy(PocketTokens.Space.XS)
                ) {
                    items(displayMediaList, key = { keyFor(it) }) { media ->
                        val key = keyFor(media)
                        val isSelected = selectedKeys.contains(key)
                        MediaGridItem(
                            media = media,
                            previewUrl = viewModel.previewUrl(media),
                            selectionMode = selectionMode,
                            isSelected = isSelected,
                            aspect = tileAspects[key] ?: 1f,
                            onAspectKnown = { ratio -> if (tileAspects[key] != ratio) tileAspects[key] = ratio },
                            onClick = {
                                if (selectionMode) {
                                    val newSet = if (isSelected) selectedKeys - key else selectedKeys + key
                                    selectedKeys = newSet
                                    if (newSet.isEmpty()) {
                                        selectionMode = false
                                    }
                                } else {
                                    selectedMedia = media
                                }
                            },
                            onLongClick = {
                                if (!selectionMode) {
                                    selectionMode = true
                                    selectedKeys = setOf(key)
                                } else {
                                    val newSet = if (isSelected) selectedKeys - key else selectedKeys + key
                                    selectedKeys = newSet
                                    if (newSet.isEmpty()) {
                                        selectionMode = false
                                    }
                                }
                            }
    )
                    }
                }
            } else {
                val emptyTitle = if (isLocalGallery && activeCategoryId != null) "当前分类为空" else "暂无内容"
                val emptySubtitle = if (isLocalGallery && activeCategoryId != null) {
                    "这个分类是空的"
                } else if (isLocalGallery) {
                    "还没有文件"
                } else {
                    "跑过任务后会出现在这里"
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
    ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = PocketTokens.Radius.XLarge,
                        colors = CardDefaults.defaultColors(
                            color = cardColor,
                            contentColor = scheme.onSecondaryContainer
    ),
                        onClick = null,
                        pressFeedbackType = PressFeedbackType.Sink,
                        showIndication = false
    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 40.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
                            Icon(
                                MiuixIcons.Image,
                                contentDescription = null,
                                modifier = Modifier.size(44.dp),
                                tint = scheme.onSecondaryContainer.copy(alpha = 0.5f)
    )
                            Text(
                                emptyTitle,
                                style = MiuixTheme.textStyles.title3.copy(fontWeight = FontWeight.SemiBold),
                                color = scheme.onSecondaryContainer
    )
                            Text(
                                emptySubtitle,
                                style = MiuixTheme.textStyles.body2,
                                color = scheme.onSecondaryContainer.copy(alpha = 0.7f)
    )
                            if (!isLocalGallery) {
                                Spacer(modifier = Modifier.height(12.dp))
                                TextButton(
                                    text = "切换到本地",
                                    onClick = { viewModel.setGallerySource(GalleryUi.GALLERY_LOCAL) },
                                    colors = ButtonDefaults.textButtonColorsPrimary()
    )
                            }
                        }
                    }
                }
            }
        }

        if (selectionMode && selectedKeys.isNotEmpty()) {
            val selectedMediaList = remember(displayMediaList, selectedKeys) {
                displayMediaList.filter { selectedKeys.contains(keyFor(it)) }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 12.dp + floatingNavClearance)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
    ) {
                FloatingToolbar(
                    color = scheme.surfaceContainer.copy(alpha = 0.94f),
                    cornerRadius = PocketTokens.Radius.XLarge,
                    shadowElevation = 6.dp,
                    outSidePadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)
    ) {
                    Row(
                        modifier = Modifier
                            .widthIn(max = 360.dp)
                            .padding(horizontal = 4.dp, vertical = 0.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
                        if (isLocalGallery) {
                            PreviewActionButton(
                                icon = MiuixIcons.Remove,
                                label = "解散",
                                tint = scheme.primary,
                                enabled = !shareBusy && !saveBusy,
                                onClick = {
                                    val assigned = selectedKeys.filter { categoryAssignments[it] != null }
                                    if (assigned.isEmpty()) {
                                        viewModel.showToast("所选内容未归属分类")
                                    } else {
                                        viewModel.unassignMediaFromGalleryCategory(assigned.toSet())
                                        viewModel.showToast("已移出分类")
                                        selectionMode = false
                                        selectedKeys = emptySet()
                                    }
                                }
    )
                            PreviewActionButton(
                                icon = MiuixIcons.Folder,
                                label = "分类",
                                tint = scheme.primary,
                                enabled = !shareBusy && !saveBusy,
                                onClick = { showCategorySheet = true }
    )
                        } else {
                            PreviewActionButton(
                                icon = MiuixIcons.Download,
                                label = if (saveBusy) "保存中" else "保存",
                                tint = scheme.primary,
                                enabled = !shareBusy && !saveBusy,
                                busy = saveBusy,
                                onClick = {
                                    saveBusy = true
                                    viewModel.saveBatchMediaToAlbum(context, selectedMediaList) {
                                        saveBusy = false
                                        selectionMode = false
                                        selectedKeys = emptySet()
                                    }
                                }
    )
                        }
                        PreviewActionButton(
                            icon = MiuixIcons.Share,
                            label = "分享",
                            tint = scheme.primary,
                            enabled = !shareBusy && !saveBusy,
                            busy = shareBusy,
                            onClick = { performShare(selectedMediaList) }
    )
                        PreviewActionButton(
                            icon = MiuixIcons.Delete,
                            label = "删除",
                            tint = scheme.error,
                            enabled = !shareBusy && !saveBusy,
                            onClick = {
                                val targets = selectedMediaList
                                if (targets.isNotEmpty()) {
                                    if (isLocalGallery) {
                                        viewModel.deleteLocalMedia(context, targets)
                                    } else {
                                        viewModel.deleteMedia(targets)
                                    }
                                }
                                selectionMode = false
                                selectedKeys = emptySet()
                            }
    )
                        PreviewActionButton(
                            icon = MiuixIcons.Close,
                            label = "取消",
                            tint = scheme.primary,
                            enabled = !saveBusy && !shareBusy,
                            onClick = {
                                selectionMode = false
                                selectedKeys = emptySet()
                            }
    )
                    }
                }
            }
        }

        OverlayDialog(
            show = showCategorySheet,
            title = "分类",
            summary = if (selectionMode && selectedKeys.isNotEmpty()) {
                "分配已选 ${selectedKeys.size} 项"
            } else {
                "点选筛选"
            },
            onDismissRequest = { showCategorySheet = false }
    ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
                // 新建分类：圆角输入 + 主色文字按钮
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = PocketTokens.Radius.Large,
                    colors = PocketCardColors.raised(),
                    insideMargin = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
                        TextField(
                            value = newCategoryName,
                            onValueChange = { newCategoryName = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = "新建分类",
                            useLabelAsPlaceholder = true
    )
                        TextButton(
                            text = "添加",
                            onClick = {
                                viewModel.createGalleryCategory(newCategoryName)
                                newCategoryName = ""
                            },
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            enabled = newCategoryName.isNotBlank()
    )
                    }
                }

                if (categories.isNotEmpty()) {
                    SmallTitle(
                        text = if (selectionMode && selectedKeys.isNotEmpty()) {
                            "选择目标分类"
                        } else {
                            "全部分类"
                        },
                        insideMargin = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
    )
                }

                // 每个分类独立 Card，间距更明显
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
                    items(categories, key = { it.id }) { cat ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = PocketTokens.Radius.Medium,
                            colors = PocketCardColors.raised(),
                            pressFeedbackType = PressFeedbackType.Sink
    ) {
                            BasicComponent(
                                title = cat.name,
                                summary = if (selectionMode && selectedKeys.isNotEmpty()) {
                                    "分配到此分类"
                                } else if (activeCategoryId == cat.id) {
                                    "当前筛选"
                                } else {
                                    null
                                },
                                holdDownState = activeCategoryId == cat.id,
                                onClick = {
                                    if (selectionMode && selectedKeys.isNotEmpty()) {
                                        val selectedMediaList = displayMediaList.filter {
                                            selectedKeys.contains(keyFor(it))
                                        }
                                        viewModel.assignMediaToGalleryCategoryAndSave(
                                            selectedMediaList,
                                            cat.id
    )
                                        selectionMode = false
                                        selectedKeys = emptySet()
                                        showCategorySheet = false
                                    } else {
                                        activeCategoryId = cat.id
                                        showCategorySheet = false
                                    }
                                },
                                endActions = {
                                    IconButton(onClick = { renamingCategory = cat }) {
                                        Icon(
                                            MiuixIcons.Rename,
                                            contentDescription = "重命名",
                                            tint = scheme.onSurfaceVariantActions,
                                            modifier = Modifier.size(20.dp)
    )
                                    }
                                    IconButton(
                                        onClick = {
                                            if (activeCategoryId == cat.id) {
                                                activeCategoryId = null
                                            }
                                            viewModel.deleteGalleryCategory(cat.id)
                                        }
    ) {
                                        Icon(
                                            MiuixIcons.Delete,
                                            contentDescription = "删除",
                                            tint = scheme.error,
                                            modifier = Modifier.size(20.dp)
    )
                                    }
                                }
    )
                        }
                    }
                }

                TextButton(
                    text = "关闭",
                    onClick = { showCategorySheet = false },
                    modifier = Modifier.fillMaxWidth()
    )
            }
        }

        OverlayDialog(
            show = renamingCategory != null,
            title = "重命名分类",
            summary = "输入新的分类名称后确认",
            onDismissRequest = { renamingCategory = null }
    ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = PocketCardColors.raised(),
                    insideMargin = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
                    TextField(
                        value = renameCategoryText,
                        onValueChange = { renameCategoryText = it },
                        singleLine = true,
                        label = "分类名称",
                        useLabelAsPlaceholder = true,
                        modifier = Modifier.fillMaxWidth()
    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
                    TextButton(
                        text = "取消",
                        onClick = { renamingCategory = null },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = "确定",
                        onClick = {
                            val target = renamingCategory
                            if (target != null) {
                                viewModel.renameGalleryCategory(target.id, renameCategoryText)
                            }
                            renamingCategory = null
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        enabled = renameCategoryText.isNotBlank()
    )
                }
            }
        }
        
        // Fullscreen Preview Dialog
        if (selectedMedia != null && displayMediaList.isNotEmpty()) {
            val selectedKey = keyFor(selectedMedia!!)
            val initialIndex = displayMediaList.indexOfFirst { keyFor(it) == selectedKey }.coerceAtLeast(0)
            FullScreenPreview(
                initialIndex = initialIndex,
                mediaList = displayMediaList,
                viewModel = viewModel,
                isLocalGallery = isLocalGallery,
                onShare = { performShare(listOf(it)) },
                onDismiss = { selectedMedia = null }
            )
        }
    }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FullScreenPreview(
    initialIndex: Int,
    mediaList: List<MediaOutput>,
    viewModel: MediaPreviewHost,
    isLocalGallery: Boolean,
    onShare: ((MediaOutput) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { mediaList.size })
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isSharing by remember { mutableStateOf(false) }
    var isSavingMedia by remember { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }
    val currentMedia = mediaList.getOrNull(pagerState.currentPage)
    val scheme = MiuixTheme.colorScheme
    // Swipe-to-dismiss state: the page follows the finger, backdrop fades with distance.
    val dismissDrag = remember { androidx.compose.animation.core.Animatable(0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val dismissThresholdPx = with(density) { 120.dp.toPx() }
    val dismissFadePx = with(density) { 360.dp.toPx() }
    val dismissFraction = (kotlin.math.abs(dismissDrag.value) / dismissFadePx).coerceIn(0f, 1f)
    val onDismissDrag: (Float) -> Unit = { total -> scope.launch { dismissDrag.snapTo(total) } }
    val onDismissDragEnd: (Float) -> Unit = { total ->
        scope.launch {
            if (kotlin.math.abs(total) > dismissThresholdPx) {
                onDismiss()
            } else {
                dismissDrag.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 500f))
            }
        }
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            decorFitsSystemWindows = false
    )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 1f - dismissFraction * 0.85f))
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = dismissDrag.value
                        val s = 1f - dismissFraction * 0.12f
                        scaleX = s
                        scaleY = s
                    },
                userScrollEnabled = dismissDrag.value == 0f
    ) { page ->
                val media = mediaList.getOrNull(page)
                if (media != null) {
                    val url = viewModel.previewUrl(media)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (media.mediaType != MediaType.IMAGE) {
                                    Modifier.pointerInput(Unit) {
                                        detectTapGestures(onTap = { chromeVisible = !chromeVisible })
                                    }
                                } else Modifier
                            )
                    ) {
                        when (media.mediaType) {
                            MediaType.VIDEO -> {
                                androidx.compose.ui.viewinterop.AndroidView(
                                    factory = { ctx ->
                                        android.widget.VideoView(ctx).apply {
                                            setMediaController(android.widget.MediaController(ctx))
                                            setVideoURI(android.net.Uri.parse(url))
                                            if (pagerState.currentPage == page) {
                                                start()
                                            }
                                        }
                                    },
                                    update = { videoView ->
                                        if (pagerState.currentPage == page) {
                                            if (!videoView.isPlaying) {
                                                videoView.start()
                                            }
                                        } else {
                                            if (videoView.isPlaying) {
                                                videoView.pause()
                                            }
                                        }
                                    },
                                    onReset = { it.stopPlayback() },
                                    onRelease = { it.stopPlayback() },
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(bottom = if (chromeVisible) 72.dp else 24.dp)
                                )
                            }
                            MediaType.AUDIO -> {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        MiuixIcons.Music,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(100.dp)
    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        media.filename,
                                        color = Color.White,
                                        style = MiuixTheme.textStyles.body2
    )
                                    Spacer(modifier = Modifier.height(32.dp))
                                    AudioPlayerView(
                                        url = url,
                                        isCurrentPage = pagerState.currentPage == page
    )
                                }
                            }
                            MediaType.TEXT -> {
                                TextViewer(media, viewModel)
                            }
                            MediaType.FILE -> {
                                com.comfyui.mobile.ui.components.FileOutputPage(media)
                            }
                            else -> {
                                ZoomableAsyncImage(
                                    model = url,
                                    contentDescription = "Fullscreen Image",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit,
                                    onSingleTap = { chromeVisible = !chromeVisible },
                                    onDismissDrag = onDismissDrag,
                                    onDismissDragEnd = onDismissDragEnd
    )
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = chromeVisible && dismissDrag.value == 0f,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
    ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.72f),
                                    Color.Black.copy(alpha = 0.28f),
                                    Color.Transparent
    )
                            )
                        )
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
    ) {
                        IconButton(
                            onClick = onDismiss,
                            backgroundColor = Color.White.copy(alpha = 0.14f),
                            minWidth = 40.dp,
                            minHeight = 40.dp
    ) {
                            Icon(
                                MiuixIcons.Close,
                                contentDescription = "关闭",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
    )
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp)
                        ) {
                            Text(
                                text = currentMedia?.filename.orEmpty(),
                                color = Color.White,
                                style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
    )
                            val typeLabel = when (currentMedia?.mediaType) {
                                MediaType.IMAGE -> "图片"
                                MediaType.VIDEO -> "视频"
                                MediaType.AUDIO -> "音频"
                                MediaType.TEXT -> "文本"
                                MediaType.FILE -> "文件"
                                null -> ""
                            }
                            Text(
                                text = buildString {
                                    append("${pagerState.currentPage + 1} / ${mediaList.size}")
                                    if (typeLabel.isNotEmpty()) append(" · $typeLabel")
                                },
                                color = Color.White.copy(alpha = 0.72f),
                                style = MiuixTheme.textStyles.footnote1,
                                maxLines = 1
    )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = chromeVisible && dismissDrag.value == 0f,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
    ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.35f),
                                    Color.Black.copy(alpha = 0.78f)
    )
                            )
                        )
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(bottom = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
    ) {
                    FloatingToolbar(
                        color = scheme.surfaceContainer.copy(alpha = 0.94f),
                        cornerRadius = PocketTokens.Radius.XLarge,
                        shadowElevation = 6.dp,
                        outSidePadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)
    ) {
                        Row(
                            modifier = Modifier
                                .widthIn(max = 380.dp)
                                .padding(horizontal = 4.dp, vertical = 0.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceEvenly
    ) {
                            if (!isLocalGallery) {
                                PreviewActionButton(
                                    icon = MiuixIcons.Download,
                                    label = if (isSavingMedia) "保存中" else "保存",
                                    tint = scheme.primary,
                                    enabled = !isSavingMedia && !isSharing,
                                    busy = isSavingMedia,
                                    onClick = {
                                        currentMedia?.let { media ->
                                            isSavingMedia = true
                                            viewModel.saveMediaToAlbum(context, media) {
                                                isSavingMedia = false
                                            }
                                        }
                                    }
    )
                            }
                            PreviewActionButton(
                                icon = MiuixIcons.Share,
                                label = "分享",
                                tint = scheme.primary,
                                enabled = !isSharing && !isSavingMedia,
                                busy = isSharing,
                                onClick = {
                                    if (currentMedia != null && !isSharing && !isSavingMedia) {
                                        if (onShare != null) {
                                            onShare(currentMedia)
                                        } else {
                                            performShareMedia(
                                                context = context,
                                                viewModel = viewModel,
                                                targets = listOf(currentMedia),
                                                scope = scope,
                                                onBusyChange = { isSharing = it }
    )
                                        }
                                    }
                                }
    )
                            PreviewActionButton(
                                icon = MiuixIcons.Delete,
                                label = "删除",
                                tint = scheme.error,
                                enabled = !isSharing && !isSavingMedia,
                                onClick = {
                                    if (currentMedia != null) {
                                        if (isLocalGallery) {
                                            viewModel.deleteLocalMedia(context, listOf(currentMedia))
                                        } else {
                                            viewModel.deleteMedia(listOf(currentMedia))
                                        }
                                        if (mediaList.size <= 1) {
                                            onDismiss()
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
}

@Composable
private fun PreviewActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    enabled: Boolean = true,
    busy: Boolean = false,
    onClick: () -> Unit
    ) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .widthIn(min = 44.dp)
            .clip(PocketTokens.Shape.Medium)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        if (busy) {
            CircularProgressIndicator(strokeWidth = 1.5.dp, size = 18.dp)
        } else {
            Icon(
                icon,
                contentDescription = label,
                tint = if (enabled) tint else tint.copy(alpha = 0.4f),
                modifier = Modifier.size(18.dp)
    )
        }
        Spacer(modifier = Modifier.height(1.dp))
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1.copy(fontSize = 10.sp),
            color = if (enabled) {
                MiuixTheme.colorScheme.onSurface.copy(alpha = 0.85f)
            } else {
                MiuixTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            },
            maxLines = 1
    )
    }
}

@Composable
fun ZoomableAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    error: androidx.compose.ui.graphics.painter.Painter? = null,
    onSingleTap: (() -> Unit)? = null,
    // Swipe-to-dismiss: vertical one-finger drags while not zoomed. Horizontal drags stay with the pager.
    onDismissDrag: ((Float) -> Unit)? = null,
    onDismissDragEnd: ((Float) -> Unit)? = null
    ) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val currentDismissDrag by androidx.compose.runtime.rememberUpdatedState(onDismissDrag)
    val currentDismissDragEnd by androidx.compose.runtime.rememberUpdatedState(onDismissDragEnd)

    Box(
        modifier = modifier
            .clipToBounds()
            .pointerInput(onSingleTap) {
                detectTapGestures(
                    onTap = { onSingleTap?.invoke() },
                    onDoubleTap = {
                        if (scale > 1.05f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                            offset = Offset.Zero
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    // 0 = undecided, 1 = vertical dismiss drag, -1 = not ours (horizontal / multi-touch)
                    var dismissMode = 0
                    var travel = Offset.Zero
                    var dismissTotal = 0f
                    val slop = viewConfiguration.touchSlop
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed }
                        if (pressedCount == 0) {
                            if (dismissMode == 1) currentDismissDragEnd?.invoke(dismissTotal)
                            break
                        }

                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        if (currentDismissDrag != null && dismissMode == 0) {
                            if (pressedCount > 1 || scale > 1.05f) {
                                dismissMode = -1
                            } else {
                                travel += panChange
                                if (travel.getDistance() > slop) {
                                    dismissMode = if (kotlin.math.abs(travel.y) > kotlin.math.abs(travel.x) * 1.4f) 1 else -1
                                }
                            }
                        }
                        if (dismissMode == 1) {
                            if (pressedCount > 1) {
                                // A second finger cancels the dismiss and hands over to pinch zoom.
                                currentDismissDragEnd?.invoke(0f)
                                dismissMode = -1
                            } else {
                                dismissTotal += panChange.y
                                currentDismissDrag?.invoke(dismissTotal)
                                event.changes.forEach { it.consume() }
                                continue
                            }
                        }
                        val shouldHandle = pressedCount > 1 || scale > 1.05f

                        if (shouldHandle) {
                            val newScale = (scale * zoomChange).coerceIn(1f, 5f)
                            scale = newScale
                            if (newScale <= 1.05f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                val maxOffsetX = (size.width * (scale - 1f)) / 2f
                                val maxOffsetY = (size.height * (scale - 1f)) / 2f
                                val nextOffset = offset + panChange
                                offset = Offset(
                                    nextOffset.x.coerceIn(-maxOffsetX, maxOffsetX),
                                    nextOffset.y.coerceIn(-maxOffsetY, maxOffsetY)
                                )
                            }
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
    ) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                ),
            contentScale = contentScale,
            error = error
        )
    }
}

/** Masonry tile ratio (width / height), clamped so panoramas and tall strips stay readable. */
internal fun galleryTileAspect(raw: Float): Float =
    if (raw.isFinite() && raw > 0f) raw.coerceIn(0.62f, 1.6f) else 1f

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaGridItem(
    modifier: Modifier = Modifier,
    media: MediaOutput,
    previewUrl: String,
    selectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    aspect: Float = 1f,
    onAspectKnown: (Float) -> Unit = {}
    ) {
    val url = previewUrl
    val scheme = MiuixTheme.colorScheme
    val corner = PocketTokens.Radius.Large
    // Only selection draws an outline; idle tiles stay borderless like the rest of the app.
    val borderColor = if (isSelected) scheme.primary else Color.Transparent
    val borderWidth = if (isSelected) 2.5.dp else 0.dp
    val reportAspect: (coil.compose.AsyncImagePainter.State.Success) -> Unit = { state ->
        val size = state.painter.intrinsicSize
        if (size != androidx.compose.ui.geometry.Size.Unspecified && size.width > 0f && size.height > 0f) onAspectKnown(size.width / size.height)
    }

    val shape = RoundedCornerShape(corner)
    Card(
        modifier = modifier
            .testTag("legacy-media-${media.filename}")
            .aspectRatio(galleryTileAspect(aspect))
            .clip(shape)
            .border(borderWidth, borderColor, shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
    ),
        cornerRadius = corner,
        pressFeedbackType = PressFeedbackType.Sink,
        showIndication = false,
        onClick = null,
        colors = CardDefaults.defaultColors(
            color = scheme.secondaryContainer,
            contentColor = scheme.onSecondaryContainer
    )
    ) {
        Box(modifier = Modifier.fillMaxSize().clip(shape)) {
            when (media.mediaType) {
                MediaType.IMAGE -> {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(url)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        onSuccess = reportAspect
    )
                }
                MediaType.VIDEO -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(url)
                                .videoFrameMillis(1000)
                                .crossfade(true)
                                .build(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            error = rememberVectorPainter(MiuixIcons.AppRecording),
                            onSuccess = reportAspect
    )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.18f))
    )
                        Icon(
                            MiuixIcons.Play,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.92f),
                            modifier = Modifier.size(40.dp)
    )
                    }
                }
                MediaType.AUDIO -> {
                     Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(MiuixIcons.Music, contentDescription = null, tint = scheme.onSecondaryContainer)
                        Text("AUDIO", modifier = Modifier.align(Alignment.BottomCenter).padding(4.dp), style = MiuixTheme.textStyles.footnote1)
                    }
                }
                MediaType.TEXT -> {
                     Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("TXT", style = MiuixTheme.textStyles.title2, color = scheme.onSecondaryContainer)
                        Text(media.filename, modifier = Modifier.align(Alignment.BottomCenter).padding(4.dp), style = MiuixTheme.textStyles.footnote1, maxLines = 1)
                    }
                }
                MediaType.FILE -> {
                     Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(media.filename.substringAfterLast('.', "FILE").uppercase().take(6), style = MiuixTheme.textStyles.title2, color = scheme.onSecondaryContainer)
                        Text(media.filename, modifier = Modifier.align(Alignment.BottomCenter).padding(4.dp), style = MiuixTheme.textStyles.footnote1, maxLines = 1)
                    }
                }
            }

            if (selectionMode && isSelected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(scheme.primary.copy(alpha = 0.22f), shape)
    )
            }
        }
    }
}

@Composable
fun AudioPlayerView(url: String, isCurrentPage: Boolean = true) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    
    DisposableEffect(url) {
        val mp = android.media.MediaPlayer().apply {
            setDataSource(context, android.net.Uri.parse(url))
            setOnCompletionListener { isPlaying = false }
            prepareAsync()
        }
        mediaPlayer = mp
        onDispose {
            mp.release()
        }
    }

    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage && isPlaying) {
            mediaPlayer?.pause()
            isPlaying = false
        }
    }
    
    IconButton(
        onClick = {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    mp.pause()
                    isPlaying = false
                } else {
                    mp.start()
                    isPlaying = true
                }
            }
        },
        modifier = Modifier.size(64.dp)
    ) {
        Icon(
            if (isPlaying) MiuixIcons.Pause else MiuixIcons.Play, 
            contentDescription = if (isPlaying) "Pause" else "Play",
            tint = Color.White,
            modifier = Modifier.size(64.dp)
        )
    }
}

@Composable
fun TextViewer(media: MediaOutput, viewModel: MediaPreviewHost) {
    var text by remember { mutableStateOf("Loading...") }
    LaunchedEffect(media) {
        text = viewModel.fetchTextContent(media)
    }
    Box(modifier = Modifier.fillMaxSize().padding(32.dp).verticalScroll(rememberScrollState())) {
        Text(text, color = Color.White, style = MiuixTheme.textStyles.body2)
    }
}

@Composable
fun CategoryChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
    ) {
    val scheme = MiuixTheme.colorScheme
    val shape = PocketTokens.Shape.Pill
    val bgColor = if (isSelected) {
        scheme.primary
    } else {
        scheme.surfaceContainerHigh
    }
    val textColor = if (isSelected) {
        scheme.onPrimary
    } else {
        scheme.onSurface.copy(alpha = 0.88f)
    }
    val borderColor = if (isSelected) {
        Color.Transparent
    } else {
        scheme.outline.copy(alpha = 0.55f)
    }
    Box(
        modifier = Modifier
            .clip(shape)
            .background(bgColor, shape)
            .border(width = 1.dp, color = borderColor, shape = shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1.copy(
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                fontSize = 13.sp
    ),
            color = textColor,
            maxLines = 1
    )
    }
}

