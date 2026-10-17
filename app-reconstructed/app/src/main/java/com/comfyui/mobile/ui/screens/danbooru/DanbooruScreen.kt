package com.comfyui.mobile.ui.screens.danbooru

import android.widget.Toast
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.comfyui.mobile.danbooru.*
import com.comfyui.mobile.ui.LocalFloatingNavClearance
import com.comfyui.mobile.ui.screens.CategoryChip
import kotlinx.coroutines.flow.distinctUntilChanged
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
    // Platform Dialog owns settings Back; otherwise exactly one completed gesture consumes one layer.
    PredictiveBackHandler(enabled = !state.settingsOpen && (vm.hasDetail || state.selected.isNotEmpty())) { events ->
        events.collect { }
        vm.back()
    }
    val grid = rememberLazyGridState(vm.scrollIndex, vm.scrollOffset)
    LaunchedEffect(grid) {
        snapshotFlow { grid.firstVisibleItemIndex to grid.firstVisibleItemScrollOffset }.collect {
            vm.scrollIndex = it.first; vm.scrollOffset = it.second
        }
    }
    LaunchedEffect(state.revision) { if (state.revision > 0) grid.scrollToItem(0) }
    var input by rememberSaveable(state.query.text) { mutableStateOf(state.query.text) }
    val keyboard = LocalSoftwareKeyboardController.current
    val clearance = LocalFloatingNavClearance.current
    val scope = runtime.settings.namespace()
    val posts = remember(state.pages) { state.posts }
    val latestState by rememberUpdatedState(state)
    LaunchedEffect(grid, vm) {
        snapshotFlow {
            val s = latestState
            val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val nearEnd = last >= grid.layoutInfo.totalItemsCount - 5 && s.posts.isNotEmpty()
            Triple(nearEnd, s.pages.lastOrNull()?.number, s.appending)
        }.distinctUntilChanged().collect { (near, _, busy) ->
            val s = vm.state.value
            if (near && !busy && !vm.hasDetail && !s.settingsOpen && s.appendError == null && s.error == null) vm.loadNext()
        }
    }
    Scaffold(containerColor = MiuixTheme.colorScheme.background, topBar = {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (vm.hasDetail) TextButton(text = if (state.selected.isNotEmpty()) "退出多选" else "返回", onClick = vm::back)
            Text(if (vm.hasDetail) "帖子 #${state.detail?.id ?: "…"}" else "画廊·Danbooru",
                Modifier.weight(1f), style = MiuixTheme.textStyles.title3)
            TextButton(text = "设置", onClick = vm::showSettings)
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).padding(horizontal = 16.dp)) {
            if (vm.hasDetail) {
                DanbooruDetailScreen(state, runtime, vm, Modifier.weight(1f).padding(bottom = clearance))
            } else {
                sourcePicker()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextField(input, { input = it }, label = "搜索 Tag / Danbooru 语法", singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); vm.search(input) }),
                        modifier = Modifier.weight(1f))
                    TextButton(text = "搜索", onClick = { keyboard?.hide(); vm.search(input) })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${state.account.title}${state.identity?.let { " · ${it.name}" }.orEmpty()}",
                        Modifier.weight(1f), style = MiuixTheme.textStyles.footnote1)
                    TextButton(text = "刷新", onClick = vm::refresh)
                }
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(ContentFilter.GENERAL, ContentFilter.ALL).forEach { filter ->
                        CategoryChip(filter.title, state.query.filter == filter) { vm.filter(input, filter) }
                    }
                }
                Text("评级：${state.query.filter.title} · ${state.query.order} · ${if (state.query.cursorPaging) "ID 游标" else "数字页"}",
                    style = MiuixTheme.textStyles.footnote1)
                state.accountError?.let { Text(it, style = MiuixTheme.textStyles.footnote1) }
                if (state.loading) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (state.error != null) {
                    Feedback(state.error!!, "重试", vm::retry)
                } else if (posts.isEmpty()) {
                    Feedback(if (state.ended) "当前查询没有结果；这不是网络错误" else "准备读取帖子", "刷新", vm::refresh)
                } else {
                    var pull by remember { mutableFloatStateOf(0f) }
                    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
                    val refresh by rememberUpdatedState<() -> Unit>(vm::refresh)
                    val connection = remember(grid) { object : NestedScrollConnection {
                        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                            if (source != NestedScrollSource.UserInput) return Offset.Zero
                            if (available.y > 0 && !grid.canScrollBackward) {
                                pull = (pull + available.y * 0.5f).coerceAtMost(threshold * 2)
                                return Offset(0f, available.y)
                            }
                            return Offset.Zero
                        }
                        override suspend fun onPreFling(available: Velocity): Velocity {
                            if (pull >= threshold) refresh()
                            pull = 0f
                            return Velocity.Zero
                        }
                    } }
                    if (pull > 0) Text(if (pull >= threshold) "松开刷新" else "下拉刷新")
                    LazyVerticalGrid(GridCells.Fixed(2), state = grid,
                        modifier = Modifier.weight(1f).nestedScroll(connection),
                        contentPadding = PaddingValues(top = 8.dp, bottom = clearance + 20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if ((state.pages.firstOrNull()?.number ?: 1) > 1) item(span = { GridItemSpan(2) }, key = "previous") {
                            TextButton(text = "加载前一页（当前窗口从第 ${state.pages.first().number} 页开始）", onClick = vm::previous)
                        }
                        items(posts, key = { it.id }) { post ->
                            Card(Modifier.fillMaxWidth().clickable { vm.open(post) }) {
                                Column {
                                    var failure by remember(post.id, post.previewUrl, scope) { mutableStateOf<String?>(null) }
                                    val url = post.previewUrl
                                    Box(Modifier.fillMaxWidth().aspectRatio(0.85f).background(MiuixTheme.colorScheme.secondaryContainer),
                                        contentAlignment = Alignment.Center) {
                                        if (url != null && failure == null) AsyncImage(
                                            ImageRequest.Builder(context).data(url)
                                                .diskCacheKey(post.mediaKey(scope, "thumbnail", url))
                                                .memoryCacheKey(post.mediaKey(scope, "thumbnail", url)).build(),
                                            contentDescription = "帖子 ${post.id}", imageLoader = runtime.imageLoader,
                                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                                            onError = { failure = danbooruMediaError(it.result.throwable) })
                                        else Text((failure ?: "预览不可用") + "\nTag 仍可查看", Modifier.padding(8.dp),
                                            style = MiuixTheme.textStyles.footnote1)
                                    }
                                    Text("#${post.id} · ${post.rating.uppercase()}${if (post.isVideo) " · 视频" else ""}",
                                        Modifier.padding(8.dp), style = MiuixTheme.textStyles.footnote1)
                                }
                            }
                        }
                        item(span = { GridItemSpan(2) }, key = "footer") {
                            when {
                                state.appending -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                                state.appendError != null -> Feedback(state.appendError!!, "重试本页", vm::retry)
                                state.ended -> Text("已到底（成功空页）", Modifier.padding(16.dp))
                                else -> TextButton(text = "加载下一页", onClick = { vm.loadNext() })
                            }
                        }
                    }
                }
            }
        }
    }
    if (state.settingsOpen) DanbooruSettingsDialog(vm.settings, state, vm::closeSettings, vm::save, vm::test, vm::logout, vm::clearCache)
}

@Composable
internal fun Feedback(message: String, action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(message)
        TextButton(text = action, onClick = onAction)
    }
}

internal fun danbooruMediaError(error: Throwable): String = when (error) {
    is coil.network.HttpException -> DanbooruHttp.statusMessage(error.response.code)
    is java.io.IOException -> DanbooruHttp.networkMessage(error)
    else -> "图片格式暂未支持或图片读取失败"
}
