package com.comfyui.mobile.ui.screens

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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.comfyui.mobile.modelcenter.ModelCenterViewModel
import com.comfyui.mobile.ui.screens.modelcenter.LocalModelsPage
import com.comfyui.mobile.ui.screens.modelcenter.ModelTasksPage
import com.comfyui.mobile.ui.screens.modelcenter.ModelCenterToolbar
import com.comfyui.mobile.ui.screens.modelcenter.ModelCenterTabs
import com.comfyui.mobile.ui.screens.modelcenter.ModelMarketPage
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.comfyui.mobile.network.ConnectionState
import com.comfyui.mobile.model.CivitaiModelItem
import com.comfyui.mobile.model.CivitaiFileItem
import com.comfyui.mobile.model.CivitaiVersionItem
import com.comfyui.mobile.ui.MainViewModel
import com.comfyui.mobile.Screen
import com.comfyui.mobile.diagnostics.NavigationDiagnostics
import com.comfyui.mobile.ui.navigation.MarketBackNavigationGate
import com.comfyui.mobile.ui.screens.modelbrowser.ModelBrowserManageFilterDialog
import com.comfyui.mobile.ui.screens.modelbrowser.loadPinnedFilterTags
import com.comfyui.mobile.ui.screens.market.MarketModelInformation
import com.comfyui.mobile.ui.screens.market.MarketDownloadQueueDialog
import com.comfyui.mobile.ui.screens.market.MarketSettingsDialog
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
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
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
fun ModelHubScreen(
    navController: NavController,
    viewModel: MainViewModel,
    initialPage: Int = 1
) {
    val context = LocalContext.current
    val scheme = MiuixTheme.colorScheme
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()

    val servers by viewModel.servers.collectAsState()
    val currentServerId by viewModel.currentServerId.collectAsState()
    val currentServer = servers.firstOrNull { it.effectiveId == currentServerId || it.id == currentServerId }
    val connectionState by viewModel.connectionState.collectAsState()
    val remoteReady = currentServer != null && !currentServer.isRunningHub &&
        connectionState == ConnectionState.CONNECTED
    val market: MarketViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val models: ModelCenterViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val localState by models.state.collectAsState()
    val marketSettings by market.settings.collectAsState()
    val catalog by market.catalog.state.collectAsState()
    val gridState = rememberLazyGridState()
    val marketImageLoader = if (marketSettings.mode == MarketConnectionMode.DIRECT)
        market.repository.imageLoader else Coil.imageLoader(context)
    fun imageRequest(url: String): Any = if (marketSettings.mode == MarketConnectionMode.DIRECT)
        market.repository.imageRequest(url) else url

    val assetsSummary by viewModel.assetsSummary.collectAsState()

    var selectedTabIndex by rememberSaveable { mutableIntStateOf(initialPage) }
    val pluginPage = initialPage == 3

    // Online catalog retains its existing filter and pagination state.
    val selectedType = catalog.type
    val selectedFilterTag = catalog.baseModel
    val pinnedFilterTags = remember { mutableStateListOf<String>().apply { addAll(loadPinnedFilterTags(context)) } }
    var showManageFilterDialog by remember { mutableStateOf(false) }

    val isSearching = catalog.loading
    val searchError = catalog.error
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
        if (selectedTabIndex == 0 && localState.detail != null) {
            models.openDetail(null)
            return
        }
        NavigationDiagnostics.record(source, navController.currentDestination?.route)
        backNavigation.leave(
            isCurrentMarket = navController.currentDestination?.route in setOf("model_hub", "local_models", "plugins"),
            pop = { navController.popBackStack() },
            openDashboard = {
                navController.navigate(Screen.Dashboard.route) {
                    popUpTo(navController.currentDestination?.route ?: "model_hub") { inclusive = true }
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
        models.context("$currentServerId|${currentServer?.baseUrl.orEmpty()}",
            currentServer?.name.orEmpty(), currentServer?.baseUrl?.takeUnless { currentServer.isRunningHub }, remoteReady)
        if (remoteReady) viewModel.fetchAssetsSummary()
    }
    LaunchedEffect(selectedTabIndex) {
        if (selectedTabIndex == 1) market.catalog.ensureLoaded()
        if (selectedTabIndex == 3 && remoteReady) viewModel.fetchAssetsSummary()
    }
    LaunchedEffect(selectedTabIndex, selectedType, selectedFilterTag,
        hasNextPage, isSearching, searchError, catalog.loadedPage) {
        if (selectedTabIndex != 1 || !hasNextPage || isSearching || searchError != null) return@LaunchedEffect
        snapshotFlow {
            val layout = gridState.layoutInfo
            layout.totalItemsCount > 0 &&
                (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >= layout.totalItemsCount - 5
        }.distinctUntilChanged().collect { nearEnd ->
            if (nearEnd) market.catalog.load(reset = false)
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, remoteReady, selectedTabIndex, localState.identity) {
        if (!remoteReady || selectedTabIndex !in setOf(0, 2)) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                val before = models.state.value
                val hadWork = before.page.scan.active || before.tasks.scan.active ||
                    before.tasks.enrichment.any { it.status in setOf("queued", "hashing", "matching") }
                models.pollTasks()
                val live = models.state.value
                if (selectedTabIndex == 0 && (hadWork || live.page.scan.active || live.tasks.scan.active ||
                        live.tasks.enrichment.any { it.status in setOf("queued", "hashing", "matching") })) {
                    models.refresh()
                    live.detail?.let { models.openDetail(it.id) }
                }
                delay(2_000)
            }
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
            if (!pluginPage) ModelCenterToolbar(
                onBack = { returnFromMarket(NavigationDiagnostics.Event.MARKET_BACK_TOOLBAR) },
                onSettings = { showMarketSettings = true },
                onRefresh = {
                    if (remoteReady) viewModel.fetchAssetsSummary()
                    if (selectedTabIndex == 1) loadCivitai(reset = true, forceRefresh = true)
                    if (selectedTabIndex == 0) {
                        models.refresh()
                        localState.detail?.let { models.openDetail(it.id) }
                    }
                    if (selectedTabIndex == 2) scope.launch { models.pollTasks() }
                }
            ) else TopAppBar(
                title = if (pluginPage) "已装插件" else "模型中心",
                largeTitle = if (pluginPage) "已装插件" else "模型中心",
                color = scheme.background,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = { returnFromMarket(NavigationDiagnostics.Event.MARKET_BACK_TOOLBAR) }) {
                        Icon(MiuixIcons.Back, contentDescription = "返回")
                    }
                },
                actions = {
                    if (!pluginPage) IconButton(onClick = { models.openDetail(null); selectedTabIndex = 2 }) {
                        Icon(MiuixIcons.Download, contentDescription = "电脑下载队列")
                    }
                    if (!pluginPage) IconButton(onClick = { showMarketSettings = true }) {
                        Icon(MiuixIcons.Settings, contentDescription = "集市连接、代理与 Key 设置")
                    }
                    IconButton(onClick = {
                        if (remoteReady) viewModel.fetchAssetsSummary()
                        if (selectedTabIndex == 1) loadCivitai(reset = true, forceRefresh = true)
                        if (selectedTabIndex == 0) {
                            models.refresh()
                            localState.detail?.let { models.openDetail(it.id) }
                        }
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
                // Only the plugin page has a collapsing TopAppBar that measures a
                // height limit. Attaching it to the fixed model toolbar leaves the
                // default unbounded limit and consumes upward gestures indefinitely.
                .then(if (pluginPage) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else Modifier)
                .padding(top = innerPadding.calculateTopPadding(), bottom = innerPadding.calculateBottomPadding())
        ) {
            if (!pluginPage && localState.detail == null)
                ModelCenterTabs(selectedTabIndex) { models.openDetail(null); selectedTabIndex = it }

            when (selectedTabIndex) {
                0 -> androidx.compose.runtime.key(localState.identity) {
                    LocalModelsPage(models, localState)
                }
                2 -> ModelTasksPage(models, localState, market)
                1 -> ModelMarketPage(
                    state = catalog, grid = gridState, pinnedTags = pinnedFilterTags,
                    imageLoader = marketImageLoader, previewUrl = { resolveImageUrl(it.previewUrl) },
                    imageRequest = ::imageRequest, onKeyword = market.catalog::keyword,
                    onType = { market.catalog.filter(type = it); scope.launch { gridState.scrollToItem(0) } },
                    onBaseModel = { market.catalog.filter(baseModel = it); scope.launch { gridState.scrollToItem(0) } },
                    onManageTags = { showManageFilterDialog = true },
                    onSearch = { loadCivitai(reset = true) }, onRetry = market.catalog::retry,
                    onMore = { loadCivitai(reset = false) },
                    onOpen = { item ->
                        selectedModelDetail = item; selectedVersion = item.versions.firstOrNull()
                        selectedFile = item.versions.firstOrNull()?.preferredFile()
                        detailAttempt = 0; detailError = null
                    }
                )

                // Tab 1: 已装插件
                3 -> {
                    val plugins = assetsSummary?.plugins.orEmpty()
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                    ) {
                        if (plugins.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(if (remoteReady) "未检测到插件" else "连接 ComfyUI 电脑后查看已装插件",
                                    style = MiuixTheme.textStyles.body2, color = scheme.onSecondaryContainer)
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
        var installation by remember(model.id, version?.id, file?.id, currentServerId) {
            mutableStateOf("正在确认当前电脑的安装状态…")
        }
        LaunchedEffect(model.id, version?.id, file?.id, currentServerId, remoteReady) {
            installation = if (version != null && file != null)
                models.installation(model.id, version.id, file.id) else "请先选择具体版本与文件"
        }
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
                    style = MiuixTheme.textStyles.title4.copy(fontWeight = FontWeight.Bold),
                    color = scheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (detailLoading) Text("正在加载完整详情…", style = MiuixTheme.textStyles.footnote1)
                Text(installation, style = MiuixTheme.textStyles.footnote1, color = scheme.primary)
                detailError?.let { message ->
                    Text(message, style = MiuixTheme.textStyles.footnote1, color = scheme.onSecondaryContainer)
                    TextButton(text = "重试详情与提示词", onClick = { detailAttempt++ })
                }
                if (!version?.availability.isNullOrBlank()) {
                    Text("基础模型：${version.baseModel} · 访问状态：${version.availability}",
                        style = MiuixTheme.textStyles.footnote1, color = scheme.onSecondaryContainer)
                }
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
                file?.let {
                    val metadata = listOf(it.format, it.precision).filter(String::isNotBlank).joinToString(" · ")
                    if (metadata.isNotBlank()) Text(metadata, style = MiuixTheme.textStyles.footnote1)
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
                    Icon(MiuixIcons.Folder, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "保存目录：",
                        style = MiuixTheme.textStyles.footnote1.copy(fontWeight = FontWeight.SemiBold),
                        color = scheme.onSurface
                    )
                    Text(
                        text = selectedFolder?.name ?: if (remoteReady) folderError ?: "获取中…" else "连接电脑后可选",
                        style = MiuixTheme.textStyles.footnote1,
                        color = scheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(MiuixIcons.ExpandMore, contentDescription = null, tint = scheme.onSecondaryContainer, modifier = Modifier.size(16.dp))
                }

                Spacer(modifier = Modifier.height(8.dp))

                MarketModelInformation(model, version, marketImageLoader) { imageRequest(it) }
                Text("本机 Key 不发送到电脑。受限模型下载，请在 ComfyUI 设置 → Mobile Bridge → Civitai 配置电脑 Key。",
                    style = MiuixTheme.textStyles.footnote1, color = scheme.onSecondaryContainer,
                    modifier = Modifier.padding(top = 12.dp))
                Text("手机与电脑账号权限可能不同，下载资格由电脑 Key 验证；手机未列出文件时由电脑选择主文件。",
                    style = MiuixTheme.textStyles.footnote1, color = scheme.onSecondaryContainer)
                }
                Spacer(modifier = Modifier.height(12.dp))

                // 3. 底部固定操作栏（始终贴底可见，无需滚动）
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 12.dp,
                    colors = CardDefaults.defaultColors(color = if (downloadReady) scheme.primary else scheme.surfaceContainerHigh),
                    pressFeedbackType = PressFeedbackType.Tilt,
                    showIndication = true,
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
                        Icon(MiuixIcons.Download, contentDescription = null,
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
                            style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold),
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
