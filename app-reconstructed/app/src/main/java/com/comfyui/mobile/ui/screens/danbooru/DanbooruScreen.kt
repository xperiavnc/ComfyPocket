package com.comfyui.mobile.ui.screens.danbooru

import com.comfyui.mobile.ui.feedback.AppToast
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.comfyui.mobile.danbooru.*
import com.comfyui.mobile.ui.LocalFloatingNavClearance
import com.comfyui.mobile.ui.navigation.PocketDestination
import com.comfyui.mobile.ui.navigation.pocketPageEnter
import com.comfyui.mobile.ui.navigation.pocketPageExit
import kotlinx.coroutines.flow.distinctUntilChanged
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Freeze the outgoing detail's tags/image while Back clears the live ViewModel. */
private data class DanbooruPageSnapshot(val detail: Boolean, val state: DanbooruState)

@Composable
fun DanbooruScreen(vm: DanbooruViewModel, runtime: DanbooruRuntime, sourcePicker: @Composable () -> Unit) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, vm) {
        vm.setActive(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.setActive(true)
            if (event == Lifecycle.Event.ON_PAUSE) vm.setActive(false)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); vm.setActive(false) }
    }
    LaunchedEffect(state.message) {
        state.message?.let { AppToast.show(context, it); vm.consumeMessage() }
    }
    // The settings overlay owns Back; otherwise one completed gesture consumes one layer.
    PredictiveBackHandler(enabled = !state.settingsOpen && (vm.hasDetail || state.selected.isNotEmpty())) { events ->
        events.collect { }
        vm.back()
    }
    val grid = rememberLazyStaggeredGridState(vm.scrollIndex, vm.scrollOffset)
    LaunchedEffect(grid) {
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
    var input by rememberSaveable(state.query.text) { mutableStateOf(state.query.text) }
    val keyboard = LocalSoftwareKeyboardController.current
    val clearance = LocalFloatingNavClearance.current
    val scope = runtime.settings.namespace()
    val page = DanbooruPageSnapshot(vm.hasDetail, state)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val latestState by rememberUpdatedState(state)
    LaunchedEffect(grid, vm) {
        snapshotFlow {
            val s = latestState
            val last = grid.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: -1
            val nearEnd = last >= grid.layoutInfo.totalItemsCount - 5 && s.posts.isNotEmpty()
            Triple(nearEnd, s.pages.lastOrNull()?.number, s.appending)
        }.distinctUntilChanged().collect { (near, _, busy) ->
            val s = vm.state.value
            if (near && !busy && !s.refreshing && !vm.hasDetail && !s.settingsOpen && s.appendError == null && s.error == null) vm.loadNext()
        }
    }
    Scaffold(containerColor = MiuixTheme.colorScheme.background, topBar = {
        Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 56.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (vm.hasDetail) IconButton(onClick = vm::back) {
                Icon(MiuixIcons.Back, contentDescription = if (state.selected.isNotEmpty()) "退出多选" else "返回图片列表")
            }
            Text(if (state.selected.isNotEmpty()) "已选 ${state.selected.size} 个标签"
                else if (vm.hasDetail) (if (state.detail?.isVideo == true) "视频详情" else "图片详情") else "画廊",
                Modifier.weight(1f), style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = vm::showSettings) { Icon(MiuixIcons.Settings, contentDescription = "Danbooru 设置") }
        }
    }) { padding ->
        AnimatedContent(
            targetState = page,
            contentKey = { it.detail },
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).clipToBounds(),
            transitionSpec = {
                val pop = !targetState.detail
                (pocketPageEnter(pop, rtl) togetherWith pocketPageExit(pop, rtl)).using(null)
            },
            label = "danbooru-list-detail"
        ) { snapshot ->
          PocketDestination(active = snapshot.detail == page.detail) {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)
                .testTag(if (snapshot.detail) "danbooru-detail-page" else "danbooru-browse-page")) {
              if (snapshot.detail) {
                DanbooruDetailScreen(snapshot.state, runtime, vm, Modifier.weight(1f), bottomClearance = clearance)
              } else {
                sourcePicker()
                DanbooruBrowseControls(input, { input = it }, snapshot.state.query,
                    onSearch = { keyboard?.hide(); vm.search(input) },
                    onSort = { keyboard?.hide(); vm.sort(input, it) },
                    onFilter = { vm.filter(input, it) },
                    onPeriod = { vm.rankingPeriod(input, it) }, onDate = vm::rankingDate)
                if (snapshot.state.loading && snapshot.state.posts.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (snapshot.state.error != null && snapshot.state.posts.isEmpty()) {
                    Feedback(snapshot.state.error, "重试", vm::refresh)
                } else if (snapshot.state.posts.isEmpty()) {
                    Feedback(if (snapshot.state.ended) "没有找到图片，试试其他标签或日期" else "正在准备画廊", "重新加载", vm::refresh)
                } else {
                    snapshot.state.error?.let { Feedback("$it；当前图片已保留", "重新刷新", vm::refresh) }
                    DanbooruGalleryGrid(snapshot.state, grid, runtime.imageLoader, scope, clearance, Modifier.weight(1f),
                        vm::open, vm::previous, vm::retry, { vm.loadNext() }, vm::refresh)
                }
              }
            }
          }
        }
        if (state.settingsOpen) DanbooruSettingsDialog(vm.settings, state, vm::closeSettings, vm::save, vm::test, vm::logout, vm::clearCache)
    }
}

@Composable
internal fun Feedback(message: String, action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(message, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSecondaryContainer)
        DanbooruSmallAction(action, onClick = onAction)
    }
}

internal fun danbooruMediaError(error: Throwable): String = when (error) {
    is coil.network.HttpException -> DanbooruHttp.statusMessage(error.response.code)
    is java.io.IOException -> DanbooruHttp.networkMessage(error)
    else -> "图片格式暂未支持或图片读取失败"
}
