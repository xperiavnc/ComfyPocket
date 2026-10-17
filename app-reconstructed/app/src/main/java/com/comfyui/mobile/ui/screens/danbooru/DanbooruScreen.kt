package com.comfyui.mobile.ui.screens.danbooru

import androidx.compose.material3.CardColors

import androidx.compose.material3.Slider

import androidx.compose.material3.Switch

import androidx.compose.material3.Icon

import androidx.compose.material3.Text

import com.comfyui.mobile.ui.designsystem.PocketCircularProgressIndicator as CircularProgressIndicator

import com.comfyui.mobile.ui.designsystem.PocketProgressDefaults as ProgressIndicatorDefaults

import com.comfyui.mobile.ui.designsystem.PocketLinearProgressIndicator as LinearProgressIndicator

import com.comfyui.mobile.ui.designsystem.PocketListRow as BasicComponent

import com.comfyui.mobile.ui.designsystem.PocketSmallTitle as SmallTitle

import com.comfyui.mobile.ui.designsystem.PocketFloatingToolbar as FloatingToolbar

import com.comfyui.mobile.ui.designsystem.PocketDialog as OverlayDialog

import com.comfyui.mobile.ui.designsystem.PocketTopAppBar as TopAppBar

import com.comfyui.mobile.ui.designsystem.PocketScaffold as Scaffold

import com.comfyui.mobile.ui.designsystem.PocketTextField as TextField

import com.comfyui.mobile.ui.designsystem.PocketIconButton as IconButton

import com.comfyui.mobile.ui.designsystem.PocketTextButton as TextButton

import com.comfyui.mobile.ui.designsystem.PocketButtonDefaults as ButtonDefaults

import com.comfyui.mobile.ui.designsystem.PocketButton as Button

import com.comfyui.mobile.ui.designsystem.PocketCardDefaults as CardDefaults

import com.comfyui.mobile.ui.designsystem.PocketCard as Card

import com.comfyui.mobile.ui.designsystem.PocketIcons

import android.widget.Toast
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.comfyui.mobile.danbooru.*
import com.comfyui.mobile.ui.LocalFloatingNavClearance
import kotlinx.coroutines.flow.distinctUntilChanged


import androidx.compose.material3.MaterialTheme

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
        state.message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.consumeMessage() }
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
    if (state.settingsOpen) {
        DanbooruSettingsDialog(vm.settings, state, vm::closeSettings, vm::save, vm::test, vm::logout, vm::clearCache)
        return
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 56.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (vm.hasDetail) IconButton(onClick = vm::back) {
                Icon(PocketIcons.Back, contentDescription = if (state.selected.isNotEmpty()) "退出多选" else "返回图片列表")
            }
            Text(if (state.selected.isNotEmpty()) "已选 ${state.selected.size} 个标签" else if (vm.hasDetail) "图片详情" else "画廊",
                Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = vm::showSettings) { Icon(PocketIcons.Settings, contentDescription = "Danbooru 设置") }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).padding(horizontal = 16.dp)) {
            if (vm.hasDetail) {
                DanbooruDetailScreen(state, runtime, vm, Modifier.weight(1f).padding(bottom = clearance))
            } else {
                sourcePicker()
                DanbooruBrowseControls(input, { input = it }, state.query,
                    onSearch = { keyboard?.hide(); vm.search(input) },
                    onSort = { keyboard?.hide(); vm.sort(input, it) },
                    onFilter = { vm.filter(input, it) },
                    onPeriod = { vm.rankingPeriod(input, it) }, onDate = vm::rankingDate)
                DanbooruGalleryGrid(state, grid, runtime.imageLoader, scope, clearance, Modifier.weight(1f),
                    vm::open, vm::previous, vm::retry, { vm.loadNext() }, vm::refresh)
            }
        }
    }
}

@Composable
internal fun Feedback(message: String, action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        DanbooruSmallAction(action, onClick = onAction)
    }
}

internal fun danbooruMediaError(error: Throwable): String = when (error) {
    is coil.network.HttpException -> DanbooruHttp.statusMessage(error.response.code)
    is java.io.IOException -> DanbooruHttp.networkMessage(error)
    else -> "图片格式暂未支持或图片读取失败"
}
