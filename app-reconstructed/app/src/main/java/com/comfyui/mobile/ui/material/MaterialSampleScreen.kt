@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.comfyui.mobile.ui.material

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.ImageLoader
import com.comfyui.mobile.danbooru.*
import com.comfyui.mobile.ui.designsystem.PocketMaterialTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
internal fun MaterialSampleScreen(
    vm: DanbooruViewModel, editor: MaterialConnectionEditor, loader: ImageLoader, online: Boolean,
    onOnline: (Boolean) -> Unit, onScenario: (SampleScenario) -> Unit, onExit: () -> Unit,
    onCopy: (CopiedTags) -> Unit, onWeb: (String) -> Unit,
    onOriginal: suspend (DanbooruPost, Boolean) -> String,
    onDarkChanged: (Boolean) -> Unit = {}
) {
    var theme by rememberSaveable { mutableStateOf("系统") }
    var dynamic by rememberSaveable { mutableStateOf(false) }
    val dark = when (theme) { "深色" -> true; "浅色" -> false; else -> isSystemInDarkTheme() }
    SideEffect { onDarkChanged(dark) }
    PocketMaterialTheme(dark, dynamic) {
        MaterialSampleContent(vm, editor, loader, online, onOnline, onScenario, onExit, onCopy, onWeb,
            onOriginal, theme, { theme = it }, dynamic, { dynamic = it })
    }
}

@Composable
private fun MaterialSampleContent(
    vm: DanbooruViewModel, editor: MaterialConnectionEditor, loader: ImageLoader, online: Boolean,
    onOnline: (Boolean) -> Unit, onScenario: (SampleScenario) -> Unit, onExit: () -> Unit,
    onCopy: (CopiedTags) -> Unit, onWeb: (String) -> Unit, onOriginal: suspend (DanbooruPost, Boolean) -> String,
    theme: String, onTheme: (String) -> Unit, dynamic: Boolean, onDynamic: (Boolean) -> Unit
) {
    val state by vm.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(4) }
    var input by rememberSaveable(vm, state.query.text) { mutableStateOf(state.query.text) }
    var appearance by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var connect by remember { mutableStateOf(false) }
    var tags by rememberSaveable(vm) { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf<Pair<String, String>?>(null) }
    var mediaBusy by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val grid = rememberLazyStaggeredGridState(vm.scrollIndex, vm.scrollOffset)
    DisposableEffect(lifecycle, vm, tab) {
        fun active() { vm.setActive(tab == 4 && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
        active()
        val observer = LifecycleEventObserver { _, _ -> active() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); vm.setActive(false) }
    }
    LaunchedEffect(vm, grid) {
        snapshotFlow { grid.firstVisibleItemIndex to grid.firstVisibleItemScrollOffset }.collect {
            vm.scrollIndex = it.first; vm.scrollOffset = it.second
        }
    }
    var observedRevision by rememberSaveable(vm) { mutableLongStateOf(state.revision) }
    LaunchedEffect(vm, state.revision) {
        if (state.revision != observedRevision) {
            observedRevision = state.revision
            grid.scrollToItem(0)
        }
    }
    LaunchedEffect(vm, state.message) { if (state.message != null) vm.consumeMessage() }
    val latest by rememberUpdatedState(state)
    val latestTab by rememberUpdatedState(tab)
    LaunchedEffect(vm, grid) {
        snapshotFlow {
            val s = latest
            Triple(grid.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: -1,
                s.pages.lastOrNull()?.number, s.appending)
        }.distinctUntilChanged().collect { (last, _, busy) ->
            val s = vm.state.value
            if (latestTab == 4 && last >= grid.layoutInfo.totalItemsCount - 5 && s.posts.isNotEmpty() &&
                !busy && !s.refreshing && !vm.hasDetail && !s.settingsOpen && s.error == null && s.appendError == null) vm.loadNext()
        }
    }
    fun notice(value: String) { scope.launch { snackbar.showSnackbar(value) } }
    fun back() {
        when {
            state.selected.isNotEmpty() -> vm.clearSelection()
            tags -> tags = false
            vm.hasDetail -> vm.back()
            else -> onExit()
        }
    }
    PredictiveBackHandler(enabled = !state.settingsOpen && !appearance && !connect && fullscreen == null && !tags && !menu) {
        events -> events.collect {}; back()
    }
    if (state.settingsOpen) {
        MaterialConnectionScreen(editor, online, vm::closeSettings, vm::save, vm::test, vm::clearCache, ::notice)
    } else {
        val names = listOf("主页", "控制", "Agent", "应用", "画廊")
        val icons = listOf(Icons.Default.Home, Icons.Default.Build, Icons.Default.Person, Icons.AutoMirrored.Filled.List,
            androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.comfyui.mobile.R.drawable.material_symbol_gallery))
        Scaffold(modifier = Modifier.imePadding(),
            topBar = { TopAppBar(
                title = { Column {
                    Text(if (vm.hasDetail) "图片详情" else names[tab], maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Material 样板 · ${if (online) "真实 Danbooru" else "离线演示"}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } },
                navigationIcon = { IconButton(onClick = ::back) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, if (vm.hasDetail) "返回图片列表" else "退出样板")
                } },
                actions = {
                    IconButton(onClick = { editor.begin(vm.settings); vm.showSettings() }) { Icon(Icons.Default.Settings, "Danbooru 设置") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "样板选项") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("主题与外观") }, onClick = { menu = false; appearance = true })
                            DropdownMenuItem(text = { Text(if (online) "回到离线样板" else "连接现有 Danbooru") },
                                onClick = { menu = false; if (online) onOnline(false) else connect = true })
                            if (!online) SampleScenario.entries.forEach { value ->
                                DropdownMenuItem(text = { Text("演示 · ${value.title}") },
                                    onClick = { menu = false; tab = 4; tags = false; onScenario(value); vm.search("") })
                            }
                        }
                    }
                }
            ) },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (!vm.hasDetail && !WindowInsets.isImeVisible) NavigationBar {
                    names.forEachIndexed { index, name ->
                        NavigationBarItem(selected = tab == index, onClick = { tab = index },
                            icon = { Icon(icons[index], null) }, label = { Text(name, maxLines = 1) })
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                if (vm.hasDetail) MaterialDanbooruDetail(vm, state, loader, vm.settings.namespace(), online, mediaBusy,
                    onTags = { tags = true }, onFullscreen = { url, key -> fullscreen = url to key },
                    onMedia = { post, share ->
                        if (!mediaBusy) {
                            mediaBusy = true
                            scope.launch {
                                try { notice(onOriginal(post, share)) }
                                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (e: Exception) { notice(if (e is DanbooruException) e.message ?: "原图读取失败" else "操作失败，请检查网络或存储空间") }
                                finally { mediaBusy = false }
                            }
                        }
                    }, onWeb = onWeb, modifier = Modifier.weight(1f))
                else if (tab == 4) {
                    MaterialBrowseControls(vm, state, input) { input = it }
                    MaterialDanbooruGrid(vm, state, grid, loader, vm.settings.namespace(), Modifier.weight(1f))
                } else MaterialNavigationDemo(names[tab])
            }
        }
    }
    if (tags && state.detail != null) ModalBottomSheet(
        onDismissRequest = { tags = false; vm.clearSelection() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { tags = false; vm.clearSelection() }) { Text("完成标签浏览") }
        }
        MaterialTagPanel(state.detail!!, vm.settings.copyOptions, state.selected, vm::select, vm::selectGroup, vm::clearSelection) {
            if (it.text.isNotBlank()) { onCopy(it); notice("已复制 ${it.count} 个标签") }
        }
    }
    fullscreen?.let { (url, key) -> MaterialFullscreenImage(url, key, loader) { fullscreen = null } }
    if (appearance) ModalBottomSheet(onDismissRequest = { appearance = false }) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("主题与外观", style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = { appearance = false }) { Icon(Icons.Default.Close, "完成外观设置") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("系统", "浅色", "深色").forEach { choice ->
                    FilterChip(theme == choice, onClick = { onTheme(choice) }, label = { Text(choice) })
                }
            }
            SettingSwitch("动态取色", dynamic) { onDynamic(it) }
            Text("只改变样板外观；原主题偏好保持不变。字体大小遵循系统设置。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (connect) AlertDialog(onDismissRequest = { connect = false },
        title = { Text("连接现有 Danbooru？") },
        text = { Text("使用已保存的独立账号与代理读取实际画廊。此后在设置页主动保存会更新真实 Danbooru 配置；其他服务不受影响。") },
        confirmButton = { TextButton(onClick = { connect = false; onOnline(true) }) { Text("连接") } },
        dismissButton = { TextButton(onClick = { connect = false }) { Text("取消") } })
}

@Composable
private fun MaterialNavigationDemo(name: String) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("$name · 导航演示", style = MaterialTheme.typography.headlineSmall)
        Text("P1 仅确认导航、主题与内容层级。这里不连接生成服务，也不代替原来的$name 页面。",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (name == "主页") "工作区" else "主要内容", style = MaterialTheme.typography.titleLarge)
                Text(if (name == "Agent") "对话、输入与生成结果优先；模型与工作流进入次级配置。"
                    else "重要状态与主要操作集中，低频设置进入独立页面。")
                HorizontalDivider()
                Text("可运行的内容样板请切换到「画廊」。", style = MaterialTheme.typography.bodySmall)
            }
        }
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("次要信息", style = MaterialTheme.typography.titleMedium)
                Text("样板确认后再分模块迁移，不在本阶段改写既有业务。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
