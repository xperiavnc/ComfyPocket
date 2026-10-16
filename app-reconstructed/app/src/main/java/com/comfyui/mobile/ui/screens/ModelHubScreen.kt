package com.comfyui.mobile.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.comfyui.mobile.model.CivitaiModelItem
import com.comfyui.mobile.model.CivitaiFileItem
import com.comfyui.mobile.model.CivitaiVersionItem
import com.comfyui.mobile.ui.MainViewModel
import com.comfyui.mobile.ui.screens.modelbrowser.ModelBrowserFilterChipBar
import com.comfyui.mobile.ui.screens.modelbrowser.ModelBrowserManageFilterDialog
import com.comfyui.mobile.ui.screens.modelbrowser.ModelBrowserTypeTabs
import com.comfyui.mobile.ui.screens.modelbrowser.loadPinnedFilterTags
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
fun ModelHubScreen(
    navController: NavController,
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    val scheme = MiuixTheme.colorScheme
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()

    val servers by viewModel.servers.collectAsState()
    val currentServerId by viewModel.currentServerId.collectAsState()
    val currentServer = servers.firstOrNull { it.effectiveId == currentServerId || it.id == currentServerId }

    val assetsSummary by viewModel.assetsSummary.collectAsState()

    var selectedTabIndex by remember { mutableIntStateOf(0) }

    // Tab 0: Civitai
    var searchKeyword by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf("LORA") }
    var selectedFilterTag by remember { mutableStateOf("全部") }
    val pinnedFilterTags = remember { mutableStateListOf<String>().apply { addAll(loadPinnedFilterTags(context)) } }
    var showManageFilterDialog by remember { mutableStateOf(false) }

    var civitaiPage by remember { mutableIntStateOf(1) }
    var nextCursor by remember { mutableStateOf<String?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    val civitaiModels = remember { mutableStateListOf<CivitaiModelItem>() }
    var hasNextPage by remember { mutableStateOf(false) }

    var selectedModelDetail by remember { mutableStateOf<CivitaiModelItem?>(null) }
    var selectedVersion by remember { mutableStateOf<CivitaiVersionItem?>(null) }
    var selectedFile by remember { mutableStateOf<CivitaiFileItem?>(null) }
    var isSubmittingDownload by remember { mutableStateOf(false) }

    fun resolveImageUrl(rawUrl: String?): String? {
        if (rawUrl.isNullOrBlank()) return null
        return if (rawUrl.startsWith("http://", ignoreCase = true) || rawUrl.startsWith("https://", ignoreCase = true)) {
            rawUrl
        } else {
            val base = currentServer?.baseUrl?.trimEnd('/') ?: ""
            if (base.isNotBlank()) "$base/" + rawUrl.trimStart('/') else rawUrl
        }
    }

    fun loadCivitai(reset: Boolean = true) {
        val targetPage = if (reset) 1 else civitaiPage + 1
        val targetCursor = if (reset) null else nextCursor
        isSearching = true
        searchError = null
        scope.launch {
            val baseParam = if (selectedFilterTag == "全部") null else selectedFilterTag
            val res = viewModel.searchCivitaiModels(
                query = searchKeyword,
                type = selectedType,
                baseModel = baseParam,
                page = targetPage,
                cursor = targetCursor,
                limit = 20
            )
            isSearching = false
            res.onSuccess { pageData ->
                if (reset) {
                    civitaiModels.clear()
                    civitaiPage = 1
                } else {
                    civitaiPage = targetPage
                }
                nextCursor = pageData.nextCursor
                civitaiModels.addAll(pageData.items)
                hasNextPage = !nextCursor.isNullOrBlank() || (civitaiPage < pageData.totalPages)
                if (civitaiModels.isEmpty()) {
                    searchError = "未找到相关模型，请尝试更换关键词"
                }
            }.onFailure { err ->
                if (reset) civitaiModels.clear()
                searchError = err.message ?: "搜索失败，请确认 ComfyUI 服务正常"
            }
        }
    }

    // Auto poll assets
    LaunchedEffect(selectedTabIndex) {
        viewModel.fetchAssetsSummary()
        if (selectedTabIndex == 0 && civitaiModels.isEmpty()) {
            loadCivitai(reset = true)
        }
    }

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                title = "集市",
                largeTitle = "集市",
                color = scheme.background,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(MiuixIcons.Back, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.fetchAssetsSummary()
                        if (selectedTabIndex == 0) loadCivitai(reset = true)
                    }) {
                        Icon(MiuixIcons.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            // 顶部分类 Tab
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "Civitai 市场",
                    "插件 (${assetsSummary?.totalPlugins ?: 0})"
                ).forEachIndexed { index, title ->
                    val isSelected = selectedTabIndex == index
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) scheme.primary else scheme.surfaceContainerHigh)
                            .clickable { selectedTabIndex = index }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = title,
                            style = MiuixTheme.textStyles.body2.copy(
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                            ),
                            color = if (isSelected) scheme.onPrimary else scheme.onSurface
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            when (selectedTabIndex) {
                // Tab 0: Civitai
                0 -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                    ) {
                        ModelBrowserTypeTabs(
                            selectedType = selectedType,
                            onTypeSelected = {
                                selectedType = it
                                loadCivitai(reset = true)
                            }
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // 2. 搜索框
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextField(
                                value = searchKeyword,
                                onValueChange = { searchKeyword = it },
                                label = "搜索模型",
                                useLabelAsPlaceholder = true,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { loadCivitai(reset = true) }),
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Card(
                                modifier = Modifier.size(46.dp),
                                cornerRadius = 12.dp,
                                colors = CardDefaults.defaultColors(color = scheme.primary),
                                onClick = { loadCivitai(reset = true) }
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Icon(MiuixIcons.Search, contentDescription = "搜索", tint = scheme.onPrimary)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                                                ModelBrowserFilterChipBar(
                            selectedFilterTag = selectedFilterTag,
                            pinnedFilterTags = pinnedFilterTags,
                            onFilterTagChange = {
                                selectedFilterTag = it
                                loadCivitai(reset = true)
                            },
                            onManageClick = { showManageFilterDialog = true }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // 4. 模型列表
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (isSearching && civitaiModels.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("正在搜索...", style = MiuixTheme.textStyles.body2, color = scheme.onSecondaryContainer)
                                }
                            } else if (searchError != null && civitaiModels.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(searchError ?: "未找到模型", style = MiuixTheme.textStyles.body2, color = scheme.error)
                                        Spacer(modifier = Modifier.height(8.dp))
                                        TextButton(text = "重试", onClick = { loadCivitai(reset = true) })
                                    }
                                }
                            } else {
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(2),
                                    contentPadding = PaddingValues(bottom = 16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(civitaiModels, key = { it.id }) { item ->
                                        Card(
                                            modifier = Modifier.fillMaxWidth(),
                                            cornerRadius = 12.dp,
                                            colors = CardDefaults.defaultColors(color = scheme.surfaceContainer),
                                            pressFeedbackType = PressFeedbackType.Tilt,
                                            showIndication = true,
                                            onClick = {
                                                selectedModelDetail = item
                                                selectedVersion = item.versions.firstOrNull()
                                                selectedFile = item.versions.firstOrNull()?.preferredFile()
                                            }
                                        ) {
                                            Column(modifier = Modifier.padding(6.dp)) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(115.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(scheme.surfaceContainerHigh)
                                                ) {
                                                    val displayImgUrl = resolveImageUrl(item.previewUrl)
                                                    if (!displayImgUrl.isNullOrBlank()) {
                                                        AsyncImage(
                                                            model = displayImgUrl,
                                                            contentDescription = null,
                                                            contentScale = ContentScale.Crop,
                                                            modifier = Modifier.fillMaxSize()
                                                        )
                                                    }
                                                    val bm = item.versions.firstOrNull()?.baseModel
                                                    if (!bm.isNullOrBlank()) {
                                                        Box(
                                                            modifier = Modifier
                                                                .align(Alignment.TopEnd)
                                                                .padding(4.dp)
                                                                .clip(RoundedCornerShape(4.dp))
                                                                .background(scheme.surface.copy(alpha = 0.85f))
                                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                                        ) {
                                                            Text(
                                                                text = bm,
                                                                fontSize = 9.sp,
                                                                fontWeight = FontWeight.Medium,
                                                                color = scheme.onSurface
                                                            )
                                                        }
                                                    }
                                                }
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Text(
                                                    text = item.name,
                                                    style = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.SemiBold),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "${item.creator} · ⬇${item.downloadCount}",
                                                    style = MiuixTheme.textStyles.body2,
                                                    fontSize = 10.sp,
                                                    color = scheme.onSecondaryContainer,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }

                                    if (hasNextPage) {
                                        item(span = { GridItemSpan(2) }) {
                                            TextButton(
                                                text = if (isSearching) "正在加载..." else "加载更多",
                                                onClick = { loadCivitai(reset = false) },
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Tab 1: 已装插件
                1 -> {
                    val plugins = assetsSummary?.plugins.orEmpty()
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                    ) {
                        if (plugins.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("未检测到插件", style = MiuixTheme.textStyles.body2, color = scheme.onSecondaryContainer)
                            }
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(plugins, key = { it.id.ifBlank { it.name } }) { plugin ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        cornerRadius = 12.dp,
                                        colors = CardDefaults.defaultColors(color = scheme.surfaceContainer)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(14.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                                Text(
                                                    text = plugin.name,
                                                    style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                if (plugin.id.isNotBlank() && plugin.id != plugin.name) {
                                                    Text(
                                                        text = plugin.id,
                                                        style = MiuixTheme.textStyles.footnote1,
                                                        fontSize = 11.sp,
                                                        color = scheme.onSecondaryContainer,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                            Text(
                                                text = if (plugin.enabled) "已启用" else "已禁用",
                                                style = MiuixTheme.textStyles.footnote1,
                                                color = if (plugin.enabled) scheme.primary else scheme.onSecondaryContainer.copy(alpha = 0.5f)
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
    }

        ModelBrowserManageFilterDialog(
        show = showManageFilterDialog,
        title = "管理标签",
        selectedFilterTag = selectedFilterTag,
        pinnedFilterTags = pinnedFilterTags,
        onFilterTagSelected = {
            selectedFilterTag = it
            loadCivitai(reset = true)
        },
        onDismiss = { showManageFilterDialog = false }
    )

    // 模型详情与下载弹窗（结构优化：自选目录、固定底部下载栏，描述独立高度受限滚动）
    selectedModelDetail?.let { model ->
        val version = selectedVersion ?: model.versions.firstOrNull()
        val file = selectedFile ?: version?.preferredFile()
        val effectiveSizeKB = when {
            file != null && file.sizeKB > 0 -> file.sizeKB
            version != null && version.sizeKB > 0 -> version.sizeKB
            else -> 0.0
        }
        val vDesc = version?.description.orEmpty()
        val descText = when {
            vDesc.isBlank() -> model.description
            model.description.isBlank() -> vDesc
            else -> "${model.description}\n\n【版本说明】\n$vDesc"
        }

        var availableFolders by remember(model.id, model.type) { mutableStateOf<List<com.comfyui.mobile.model.ModelFolderItem>>(emptyList()) }
        var selectedFolder by remember(model.id, model.type) { mutableStateOf<com.comfyui.mobile.model.ModelFolderItem?>(null) }
        var showFolderPicker by remember { mutableStateOf(false) }
        var isAddingFolder by remember { mutableStateOf(false) }
        var newFolderNameInput by remember { mutableStateOf("") }
        var downloadErrorDialogMsg by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(model.type) {
            val res = viewModel.getModelFolders(model.type)
            res.onSuccess { list ->
                availableFolders = list
                if (selectedFolder == null && list.isNotEmpty()) {
                    selectedFolder = list.first()
                }
            }
        }

        OverlayDialog(
            show = true,
            title = "模型详情",
            onDismissRequest = { selectedModelDetail = null }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
            ) {
                // 1. 顶部基础信息 (封面 + 标题 + 版本 + 大小)
                val displayCoverUrl = resolveImageUrl(version?.previewUrl ?: model.previewUrl)
                if (!displayCoverUrl.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(scheme.surfaceContainerHigh)
                    ) {
                        AsyncImage(
                            model = displayCoverUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Text(
                    text = model.name,
                    style = MiuixTheme.textStyles.title4.copy(fontWeight = FontWeight.Bold),
                    color = scheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "作者: ${model.creator} · 类型: ${model.type}",
                    style = MiuixTheme.textStyles.footnote1,
                    color = scheme.onSecondaryContainer
                )

                // 版本选择胶囊
                if (model.versions.size > 1) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "版本选择：", style = MiuixTheme.textStyles.footnote1.copy(fontWeight = FontWeight.SemiBold))
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        model.versions.forEach { ver ->
                            val isVerSel = ver.id == version?.id
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isVerSel) scheme.primary.copy(alpha = 0.15f) else scheme.surfaceContainerHigh)
                                    .border(1.dp, if (isVerSel) scheme.primary else Color.Transparent, RoundedCornerShape(8.dp))
                                    .clickable { selectedVersion = ver; selectedFile = ver.preferredFile() }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = ver.name,
                                    style = MiuixTheme.textStyles.footnote1.copy(fontWeight = if (isVerSel) FontWeight.Bold else FontWeight.Normal),
                                    color = if (isVerSel) scheme.primary else scheme.onSurface
                                )
                            }
                        }
                    }
                }

                // File chips: author model files only (VAE/TE components hidden)
                val authorFiles = version?.authorFiles.orEmpty()
                if (authorFiles.size > 1) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "文件选择：", style = MiuixTheme.textStyles.footnote1.copy(fontWeight = FontWeight.SemiBold))
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        authorFiles.forEach { f ->
                            val isFileSel = f.id == file?.id
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isFileSel) scheme.primary.copy(alpha = 0.15f) else scheme.surfaceContainerHigh)
                                    .border(1.dp, if (isFileSel) scheme.primary else Color.Transparent, RoundedCornerShape(8.dp))
                                    .clickable { selectedFile = f }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = f.displayLabel,
                                    style = MiuixTheme.textStyles.footnote1.copy(fontWeight = if (isFileSel) FontWeight.Bold else FontWeight.Normal),
                                    color = if (isFileSel) scheme.primary else scheme.onSurface,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                if (effectiveSizeKB > 0) {
                    Spacer(modifier = Modifier.height(6.dp))
                    val sizeMB = effectiveSizeKB / 1024.0
                    val sizeStr = if (sizeMB >= 1024.0) String.format(java.util.Locale.US, "%.2f GB", sizeMB / 1024.0)
                    else String.format(java.util.Locale.US, "%.1f MB", sizeMB)
                    Text(text = "文件大小: $sizeStr", style = MiuixTheme.textStyles.footnote1, color = scheme.onSecondaryContainer)
                }

                // 保存目录
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(scheme.surfaceContainerHigh)
                        .clickable { showFolderPicker = true }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(MiuixIcons.Folder, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "保存目录：",
                        style = MiuixTheme.textStyles.footnote1.copy(fontWeight = FontWeight.SemiBold),
                        color = scheme.onSurface
                    )
                    Text(
                        text = selectedFolder?.name ?: "获取中...",
                        style = MiuixTheme.textStyles.footnote1,
                        color = scheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(MiuixIcons.ExpandMore, contentDescription = null, tint = scheme.onSecondaryContainer, modifier = Modifier.size(16.dp))
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 2. 模型描述（独立高度受限滚动区，彻底解决描述过长需划到底的问题）
                Text(text = "模型描述：", style = MiuixTheme.textStyles.footnote1.copy(fontWeight = FontWeight.SemiBold))
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .heightIn(min = 60.dp, max = 135.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(scheme.surfaceContainerHigh)
                        .padding(10.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = descText.ifBlank { "暂无模型描述" },
                        style = MiuixTheme.textStyles.body2,
                        color = scheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 3. 底部固定操作栏（始终贴底可见，无需滚动）
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 12.dp,
                    colors = CardDefaults.defaultColors(color = scheme.primary),
                    pressFeedbackType = PressFeedbackType.Tilt,
                    showIndication = true,
                    onClick = {
                        if (version == null) return@Card
                        isSubmittingDownload = true
                        scope.launch {
                            val res = viewModel.downloadCivitaiModel(
                                modelId = model.id,
                                modelVersionId = version.id,
                                modelType = model.type,
                                modelRoot = selectedFolder?.root,
                                subfolder = selectedFolder?.subfolder,
                                fileId = file?.id?.takeIf { it != 0L },
                                downloadUrl = file?.downloadUrl?.takeIf { it.isNotBlank() },
                                fileType = file?.type?.takeIf { it.isNotBlank() },
                                role = "model"
                            )
                            isSubmittingDownload = false
                            res.onSuccess { msg ->
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                selectedModelDetail = null
                            }.onFailure { err ->
                                downloadErrorDialogMsg = err.message ?: "下载提交失败"
                            }
                        }
                    }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(MiuixIcons.Download, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isSubmittingDownload) "正在提交任务..." else "远程下载到电脑",
                            style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold),
                            color = scheme.onPrimary
                        )
                    }
                }
            }
        }

        // 保存目录选择弹窗
        if (showFolderPicker) {
            OverlayDialog(
                show = true,
                title = "选择保存目录",
                onDismissRequest = {
                    showFolderPicker = false
                    isAddingFolder = false
                }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                ) {
                    Text(
                        text = "选择要保存到的文件夹：",
                        style = MiuixTheme.textStyles.footnote1,
                        color = scheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(availableFolders, key = { it.name }) { fItem ->
                            val isSelected = selectedFolder?.name == fItem.name
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) scheme.primary.copy(alpha = 0.12f) else scheme.surfaceContainerHigh)
                                    .border(1.dp, if (isSelected) scheme.primary else Color.Transparent, RoundedCornerShape(8.dp))
                                    .clickable {
                                        selectedFolder = fItem
                                        showFolderPicker = false
                                        isAddingFolder = false
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    MiuixIcons.Folder,
                                    contentDescription = null,
                                    tint = if (isSelected) scheme.primary else scheme.onSecondaryContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = fItem.name,
                                    style = MiuixTheme.textStyles.body2.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal),
                                    color = if (isSelected) scheme.primary else scheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isSelected) {
                                    Icon(
                                        MiuixIcons.Ok,
                                        contentDescription = null,
                                        tint = scheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (isAddingFolder) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextField(
                                value = newFolderNameInput,
                                onValueChange = { newFolderNameInput = it },
                                label = "子目录名称，如: style",
                                useLabelAsPlaceholder = true,
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            TextButton(
                                text = "确定",
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                                onClick = {
                                    val trimmed = newFolderNameInput.trim().replace("\\", "/")
                                    if (trimmed.isNotBlank()) {
                                        val baseRoot = availableFolders.firstOrNull()?.root.orEmpty()
                                        val baseName = availableFolders.firstOrNull()?.name?.substringBefore("/") ?: "models"
                                        val newItem = com.comfyui.mobile.model.ModelFolderItem(
                                            name = "$baseName/$trimmed",
                                            root = baseRoot,
                                            subfolder = trimmed
                                        )
                                        if (availableFolders.none { it.name == newItem.name }) {
                                            availableFolders = availableFolders + newItem
                                        }
                                        selectedFolder = newItem
                                        isAddingFolder = false
                                        newFolderNameInput = ""
                                        showFolderPicker = false
                                    }
                                }
                            )
                            TextButton(
                                text = "取消",
                                onClick = { isAddingFolder = false }
                            )
                        }
                    } else {
                        TextButton(
                            text = "+ 新建子目录",
                            onClick = { isAddingFolder = true },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // 提交异常提示弹窗（防止 Toast 长文本被系统省略号截断）
        downloadErrorDialogMsg?.let { errMsg ->
            OverlayDialog(
                show = true,
                title = "下载提示",
                onDismissRequest = { downloadErrorDialogMsg = null }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                ) {
                    Text(
                        text = errMsg,
                        style = MiuixTheme.textStyles.body2,
                        color = scheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    TextButton(
                        text = "我知道了",
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        onClick = { downloadErrorDialogMsg = null },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
