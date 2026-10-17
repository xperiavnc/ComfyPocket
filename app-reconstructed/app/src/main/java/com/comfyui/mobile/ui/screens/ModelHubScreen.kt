package com.comfyui.mobile.ui.screens

import com.comfyui.mobile.ui.designsystem.PocketIcons

import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
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
import coil.Coil
import com.comfyui.mobile.market.MarketConnectionMode
import com.comfyui.mobile.market.MarketModelFilters
import com.comfyui.mobile.market.MarketQuery
import com.comfyui.mobile.market.MarketViewModel
import com.comfyui.mobile.network.ConnectionState
import com.comfyui.mobile.model.CivitaiModelItem
import com.comfyui.mobile.model.CivitaiFileItem
import com.comfyui.mobile.model.CivitaiVersionItem
import com.comfyui.mobile.ui.MainViewModel
import com.comfyui.mobile.Screen
import com.comfyui.mobile.diagnostics.NavigationDiagnostics
import com.comfyui.mobile.ui.navigation.MarketBackNavigationGate
import com.comfyui.mobile.ui.screens.modelbrowser.ModelBrowserFilterChipBar
import com.comfyui.mobile.ui.screens.modelbrowser.ModelBrowserManageFilterDialog
import com.comfyui.mobile.ui.screens.modelbrowser.ModelBrowserTypeTabs
import com.comfyui.mobile.ui.screens.modelbrowser.loadPinnedFilterTags
import com.comfyui.mobile.ui.screens.market.MarketModelInformation
import com.comfyui.mobile.ui.screens.market.MarketDownloadQueueDialog
import com.comfyui.mobile.ui.screens.market.MarketSettingsDialog
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import com.comfyui.mobile.ui.designsystem.PocketButtonDefaults as ButtonDefaults
import com.comfyui.mobile.ui.designsystem.PocketCard as Card
import com.comfyui.mobile.ui.designsystem.PocketCardDefaults as CardDefaults
import androidx.compose.material3.Icon
import com.comfyui.mobile.ui.designsystem.PocketIconButton as IconButton

import com.comfyui.mobile.ui.designsystem.PocketScaffold as Scaffold
import androidx.compose.material3.Text
import com.comfyui.mobile.ui.designsystem.PocketTextButton as TextButton
import com.comfyui.mobile.ui.designsystem.PocketTextField as TextField
import com.comfyui.mobile.ui.designsystem.PocketTopAppBar as TopAppBar


import com.comfyui.mobile.ui.designsystem.PocketDialog as OverlayDialog
import androidx.compose.material3.MaterialTheme

@Composable
fun ModelHubScreen(
    navController: NavController,
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    val servers by viewModel.servers.collectAsState()
    val currentServerId by viewModel.currentServerId.collectAsState()
    val currentServer = servers.firstOrNull { it.effectiveId == currentServerId || it.id == currentServerId }
    val connectionState by viewModel.connectionState.collectAsState()
    val remoteReady = currentServer != null && !currentServer.isRunningHub &&
        connectionState == ConnectionState.CONNECTED
    val market: MarketViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val marketSettings by market.settings.collectAsState()
    val catalog by market.catalog.state.collectAsState()
    val gridState = rememberLazyGridState()
    val marketImageLoader = if (marketSettings.mode == MarketConnectionMode.DIRECT)
        market.repository.imageLoader else Coil.imageLoader(context)
    fun imageRequest(url: String): Any = if (marketSettings.mode == MarketConnectionMode.DIRECT)
        market.repository.imageRequest(url) else url

    val assetsSummary by viewModel.assetsSummary.collectAsState()

    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }

    // Tab 0: Civitai
    val searchKeyword = catalog.keyword
    val selectedType = catalog.type
    val selectedFilterTag = catalog.baseModel
    val pinnedFilterTags = remember { mutableStateListOf<String>().apply { addAll(loadPinnedFilterTags(context)) } }
    var showManageFilterDialog by remember { mutableStateOf(false) }

    val isSearching = catalog.loading
    val searchError = catalog.error
    val civitaiModels = catalog.items
    val hasNextPage = catalog.hasNextPage
    var showMarketSettings by remember { mutableStateOf(false) }
    var showDownloadQueue by remember { mutableStateOf(false) }

    var selectedModelDetail by remember { mutableStateOf<CivitaiModelItem?>(null) }
    var selectedVersion by remember { mutableStateOf<CivitaiVersionItem?>(null) }
    var selectedFile by remember { mutableStateOf<CivitaiFileItem?>(null) }
    var isSubmittingDownload by remember { mutableStateOf(false) }
    var detailLoading by remember { mutableStateOf(false) }
    var detailError by remember { mutableStateOf<String?>(null) }
    var detailAttempt by remember { mutableIntStateOf(0) }
    val backNavigation = remember { MarketBackNavigationGate() }
    fun returnFromMarket(source: NavigationDiagnostics.Event) {
        NavigationDiagnostics.record(source, navController.currentDestination?.route)
        backNavigation.leave(
            isCurrentMarket = navController.currentDestination?.route == "model_hub",
            pop = { navController.popBackStack() },
            openDashboard = {
                navController.navigate(Screen.Dashboard.route) {
                    popUpTo("model_hub") { inclusive = true }
                    launchSingleTop = true
                }
            }
        )
    }
    // Leaf-level handler overrides NavHost's predictive pop only on this page.
    // Miuix keeps ownership while a dialog is open, so one back dismisses only that dialog.
    BackHandler(enabled = !showManageFilterDialog && !showMarketSettings &&
        !showDownloadQueue && selectedModelDetail == null) {
        returnFromMarket(NavigationDiagnostics.Event.MARKET_BACK_SYSTEM)
    }
    DisposableEffect(Unit) {
        onDispose {
            NavigationDiagnostics.record(NavigationDiagnostics.Event.MARKET_PAGE_DISPOSED)
        }
    }

    fun resolveImageUrl(rawUrl: String?): String? {
        if (rawUrl.isNullOrBlank()) return null
        return if (rawUrl.startsWith("http://", ignoreCase = true) || rawUrl.startsWith("https://", ignoreCase = true)) {
            rawUrl
        } else {
            val base = currentServer?.baseUrl?.trimEnd('/') ?: ""
            if (base.isNotBlank()) "$base/" + rawUrl.trimStart('/') else rawUrl
        }
    }

    fun loadCivitai(reset: Boolean = true, forceRefresh: Boolean = false) {
        market.catalog.load(reset, forceRefresh)
        if (reset) scope.launch { gridState.scrollToItem(0) }
    }

    LaunchedEffect(currentServerId, currentServer?.baseUrl, remoteReady) {
        market.bridgeContext("$currentServerId|${currentServer?.baseUrl.orEmpty()}", remoteReady)
        if (remoteReady) viewModel.fetchAssetsSummary()
    }
    LaunchedEffect(selectedTabIndex) {
        if (selectedTabIndex == 0) market.catalog.ensureLoaded()
        if (selectedTabIndex == 1 && remoteReady) viewModel.fetchAssetsSummary()
    }
    LaunchedEffect(selectedTabIndex, selectedType, selectedFilterTag,
        hasNextPage, isSearching, searchError, catalog.loadedPage) {
        if (selectedTabIndex != 0 || !hasNextPage || isSearching || searchError != null) return@LaunchedEffect
        snapshotFlow {
            val layout = gridState.layoutInfo
            layout.totalItemsCount > 0 &&
                (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >= layout.totalItemsCount - 5
        }.distinctUntilChanged().collect { nearEnd ->
            if (nearEnd) market.catalog.load(reset = false)
        }
    }
    LaunchedEffect(selectedModelDetail?.id, selectedVersion?.id, detailAttempt) {
        val model = selectedModelDetail ?: return@LaunchedEffect
        detailLoading = true
        detailError = null
        try {
            val result = market.details(model, selectedVersion?.id, detailAttempt > 0)
            val filtered = MarketModelFilters.model(
                result.model, MarketQuery(type = selectedType, baseModel = selectedFilterTag)
            ) ?: error("详情中没有符合当前分类与标签的版本，请返回更换筛选")
            selectedModelDetail = filtered
            val version = filtered.versions.firstOrNull { it.id == selectedVersion?.id }
                ?: filtered.versions.firstOrNull()
            selectedVersion = version
            selectedFile = version?.files?.firstOrNull { it.id == selectedFile?.id }
                ?: version?.preferredFile()
            detailError = result.warning
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            detailError = error.message ?: "模型详情加载失败，可点击重试"
        } finally {
            detailLoading = false
        }
    }

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                title = "集市",
                color = scheme.background,
                navigationIcon = {
                    IconButton(onClick = { returnFromMarket(NavigationDiagnostics.Event.MARKET_BACK_TOOLBAR) }) {
                        Icon(PocketIcons.Back, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showDownloadQueue = true }) {
                        Icon(PocketIcons.Download, contentDescription = "电脑下载队列")
                    }
                    IconButton(onClick = { showMarketSettings = true }) {
                        Icon(PocketIcons.Settings, contentDescription = "集市连接、代理与 Key 设置")
                    }
                    IconButton(onClick = {
                        if (remoteReady) viewModel.fetchAssetsSummary()
                        if (selectedTabIndex == 0) loadCivitai(reset = true, forceRefresh = true)
                    }) {
                        Icon(PocketIcons.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()

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
                            style = MaterialTheme.typography.bodyMedium.copy(
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
                                market.catalog.filter(type = it)
                                scope.launch { gridState.scrollToItem(0) }
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
                                onValueChange = market.catalog::keyword,
                                label = "搜索模型",
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
                                    Icon(PocketIcons.Search, contentDescription = "搜索", tint = scheme.onPrimary)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        ModelBrowserFilterChipBar(
                            selectedFilterTag = selectedFilterTag,
                            pinnedFilterTags = pinnedFilterTags,
                            onFilterTagChange = {
                                market.catalog.filter(baseModel = it)
                                scope.launch { gridState.scrollToItem(0) }
                            },
                            onManageClick = { showManageFilterDialog = true }
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        (catalog.notice ?: searchError?.takeIf { civitaiModels.isNotEmpty() })?.let { message ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(message, style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSecondaryContainer, modifier = Modifier.weight(1f))
                                if (searchError != null) TextButton(text = "重试",
                                    onClick = market.catalog::retry)
                            }
                        }

                        // 4. 模型列表
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (isSearching && civitaiModels.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("正在搜索...", style = MaterialTheme.typography.bodyMedium, color = scheme.onSecondaryContainer)
                                }
                            } else if (searchError != null && civitaiModels.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(searchError, style = MaterialTheme.typography.bodyMedium, color = scheme.error)
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
                                    state = gridState,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(civitaiModels, key = { it.id }) { item ->
                                        Card(
                                            modifier = Modifier.fillMaxWidth(),
                                            cornerRadius = 12.dp,
                                            colors = CardDefaults.defaultColors(color = scheme.surfaceContainer),
                                            onClick = {
                                                selectedModelDetail = item
                                                selectedVersion = item.versions.firstOrNull()
                                                selectedFile = item.versions.firstOrNull()?.preferredFile()
                                                detailAttempt = 0
                                                detailError = null
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
                                                            model = imageRequest(displayImgUrl),
                                                            imageLoader = marketImageLoader,
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
                                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "${item.creator} · ⬇${item.downloadCount}",
                                                    style = MaterialTheme.typography.bodyMedium,
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
                                                text = when {
                                                    isSearching -> "正在加载更多..."
                                                    searchError != null -> "加载失败，点击重试"
                                                    else -> "已加载 ${civitaiModels.size} 个 · 加载更多"
                                                },
                                                onClick = {
                                                    if (searchError != null) market.catalog.retry()
                                                    else loadCivitai(reset = false)
                                                },
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                                            )
                                        }
                                    } else if (civitaiModels.isNotEmpty()) {
                                        item(span = { GridItemSpan(2) }) {
                                            Text(
                                                "已显示 ${civitaiModels.size} 个模型，当前筛选已到底",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = scheme.onSecondaryContainer,
                                                modifier = Modifier.padding(vertical = 12.dp)
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
                                Text(if (remoteReady) "未检测到插件" else "连接 ComfyUI 电脑后查看已装插件",
                                    style = MaterialTheme.typography.bodyMedium, color = scheme.onSecondaryContainer)
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
                                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                if (plugin.id.isNotBlank() && plugin.id != plugin.name) {
                                                    Text(
                                                        text = plugin.id,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        fontSize = 11.sp,
                                                        color = scheme.onSecondaryContainer,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                            Text(
                                                text = if (plugin.enabled) "已启用" else "已禁用",
                                                style = MaterialTheme.typography.bodySmall,
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
            market.catalog.filter(baseModel = it)
            scope.launch { gridState.scrollToItem(0) }
        },
        onDismiss = { showManageFilterDialog = false }
    )

    if (showMarketSettings) {
        MarketSettingsDialog(
            initial = marketSettings,
            onDismiss = { showMarketSettings = false },
            onSave = { value ->
                market.saveSettings(value).onSuccess {
                    selectedModelDetail = null
                    gridState.scrollToItem(0)
                }
            },
            onTest = market.repository::testConnection
        )
    }
    if (showDownloadQueue) {
        androidx.compose.runtime.key(currentServerId, currentServer?.baseUrl) {
            MarketDownloadQueueDialog(
                connected = remoteReady,
                onDismiss = { showDownloadQueue = false },
                load = market.repository::downloadTasks,
                action = market.repository::taskAction
            )
        }
    }

    // 详情整体滚动；下载栏固定，长简介和示例提示词不会挤出操作区。
    selectedModelDetail?.let { model ->
        val version = selectedVersion ?: model.versions.firstOrNull()
        val file = selectedFile ?: version?.preferredFile()
        val effectiveSizeKB = when {
            file != null && file.sizeKB > 0 -> file.sizeKB
            version != null && version.sizeKB > 0 -> version.sizeKB
            else -> 0.0
        }
        val downloadReady = remoteReady && !detailLoading && !isSubmittingDownload &&
            version != null && version.id > 0 && file?.isRequiredComponent != true

        var availableFolders by remember(model.id, model.type) { mutableStateOf<List<com.comfyui.mobile.model.ModelFolderItem>>(emptyList()) }
        var selectedFolder by remember(model.id, model.type) { mutableStateOf<com.comfyui.mobile.model.ModelFolderItem?>(null) }
        var showFolderPicker by remember { mutableStateOf(false) }
        var isAddingFolder by remember { mutableStateOf(false) }
        var newFolderNameInput by remember { mutableStateOf("") }
        var downloadErrorDialogMsg by remember { mutableStateOf<String?>(null) }
        var folderError by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(model.type, currentServerId, remoteReady) {
            availableFolders = emptyList()
            selectedFolder = null
            folderError = null
            if (!remoteReady) return@LaunchedEffect
            val res = viewModel.getModelFolders(model.type)
            res.onSuccess { list ->
                availableFolders = list
                if (selectedFolder == null && list.isNotEmpty()) {
                    selectedFolder = list.first()
                }
            }.onFailure {
                folderError = "目录读取失败，请检查电脑上的桥接插件"
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
                Column(
                    modifier = Modifier.weight(1f, fill = false).fillMaxWidth()
                        .verticalScroll(rememberScrollState())
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
                            model = imageRequest(displayCoverUrl),
                            imageLoader = marketImageLoader,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Text(
                    text = model.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = scheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (detailLoading) Text("正在加载完整详情…", style = MaterialTheme.typography.bodySmall)
                detailError?.let { message ->
                    Text(message, style = MaterialTheme.typography.bodySmall, color = scheme.onSecondaryContainer)
                    TextButton(text = "重试详情与提示词", onClick = { detailAttempt++ })
                }
                if (!version?.availability.isNullOrBlank()) {
                    Text("基础模型：${version.baseModel} · 访问状态：${version.availability}",
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSecondaryContainer)
                }
                Text(
                    text = "作者: ${model.creator} · 类型: ${model.type}",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSecondaryContainer
                )

                // 版本选择胶囊
                if (model.versions.size > 1) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "版本选择：", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
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
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = if (isVerSel) FontWeight.Bold else FontWeight.Normal),
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
                    Text(text = "文件选择：", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
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
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = if (isFileSel) FontWeight.Bold else FontWeight.Normal),
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
                    Text(text = "文件大小: $sizeStr", style = MaterialTheme.typography.bodySmall, color = scheme.onSecondaryContainer)
                }
                file?.let {
                    val metadata = listOf(it.format, it.precision).filter(String::isNotBlank).joinToString(" · ")
                    if (metadata.isNotBlank()) Text(metadata, style = MaterialTheme.typography.bodySmall)
                }

                // 保存目录
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(scheme.surfaceContainerHigh)
                        .clickable { if (remoteReady) showFolderPicker = true }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(PocketIcons.Folder, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "保存目录：",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = scheme.onSurface
                    )
                    Text(
                        text = selectedFolder?.name ?: if (remoteReady) folderError ?: "获取中…" else "连接电脑后可选",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(PocketIcons.ExpandMore, contentDescription = null, tint = scheme.onSecondaryContainer, modifier = Modifier.size(16.dp))
                }

                Spacer(modifier = Modifier.height(8.dp))

                MarketModelInformation(model, version, marketImageLoader) { imageRequest(it) }
                Text("本机 Key 不发送到电脑。受限模型下载，请在 ComfyUI 设置 → Mobile Bridge → Civitai 配置电脑 Key。",
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSecondaryContainer,
                    modifier = Modifier.padding(top = 12.dp))
                Text("手机与电脑账号权限可能不同，下载资格由电脑 Key 验证；手机未列出文件时由电脑选择主文件。",
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSecondaryContainer)
                }
                Spacer(modifier = Modifier.height(12.dp))

                // 3. 底部固定操作栏（始终贴底可见，无需滚动）
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 12.dp,
                    colors = CardDefaults.defaultColors(color = if (downloadReady) scheme.primary else scheme.surfaceContainerHigh),
                    onClick = {
                        if (!remoteReady) {
                            Toast.makeText(context, "请先连接 ComfyUI 电脑", Toast.LENGTH_SHORT).show()
                            return@Card
                        }
                        if (!downloadReady) return@Card
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
                                 showDownloadQueue = true
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
                        Icon(PocketIcons.Download, contentDescription = null,
                            tint = if (downloadReady) scheme.onPrimary else scheme.onSecondaryContainer,
                            modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = when {
                                isSubmittingDownload -> "正在提交任务…"
                                !remoteReady -> "连接电脑后可远程下载"
                                detailLoading -> "正在读取文件信息…"
                                version == null -> "当前模型暂无可选版本"
                                file == null -> "由电脑验证并下载主文件"
                                version.canDownload == false -> "验证电脑账号权限并下载"
                                else -> "远程下载到电脑"
                            },
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = if (downloadReady) scheme.onPrimary else scheme.onSecondaryContainer
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
                        style = MaterialTheme.typography.bodySmall,
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
                                    PocketIcons.Folder,
                                    contentDescription = null,
                                    tint = if (isSelected) scheme.primary else scheme.onSecondaryContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = fItem.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal),
                                    color = if (isSelected) scheme.primary else scheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isSelected) {
                                    Icon(
                                        PocketIcons.Ok,
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
                        style = MaterialTheme.typography.bodyMedium,
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
