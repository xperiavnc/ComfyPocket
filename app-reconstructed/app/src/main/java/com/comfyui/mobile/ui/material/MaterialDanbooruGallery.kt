@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.comfyui.mobile.ui.material

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.comfyui.mobile.danbooru.*

@Composable
internal fun MaterialBrowseControls(vm: DanbooruViewModel, state: DanbooruState, input: String, onInput: (String) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    var filters by remember { mutableStateOf(false) }
    fun submit() { keyboard?.hide(); vm.search(input) }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(input, onInput, modifier = Modifier.fillMaxWidth().testTag("material-search"),
            placeholder = { Text("搜索标签或 Danbooru 语法") }, singleLine = true, shape = MaterialTheme.shapes.large,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }),
            trailingIcon = { IconButton(onClick = ::submit) { Icon(Icons.Default.Search, "搜索") } })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Danbooru", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { filters = true }) { Text("评级 · ${state.query.filter.title}") }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(DanbooruSort.LATEST, DanbooruSort.HOT, DanbooruSort.RANKING, DanbooruSort.SCORE, DanbooruSort.FAVORITES).forEach { value ->
                FilterChip(selected = state.query.sort == value,
                    onClick = { keyboard?.hide(); vm.sort(input, value) }, label = { Text(value.title) })
            }
        }
        if (state.query.sort == DanbooruSort.CUSTOM)
            Text("自定义排序 · ${state.query.order}", style = MaterialTheme.typography.labelMedium)
        if (state.query.sort == DanbooruSort.RANKING) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RankingPeriod.entries.forEach { period ->
                    FilterChip(state.query.rankingPeriod == period,
                        onClick = { vm.rankingPeriod(input, period) }, label = { Text(period.title) })
                }
            }
            val q = state.query
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.rankingDate(q.rankingPeriod.shift(q.rankingDate, -1)) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "上一期")
                }
                Text(q.rankingPeriod.label(q.rankingDate), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                IconButton(enabled = q.rankingPeriod.start(q.rankingDate) < q.rankingPeriod.start(danbooruToday()),
                    onClick = { vm.rankingDate(q.rankingPeriod.shift(q.rankingDate, 1)) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, "下一期")
                }
            }
            if (q.rankingPeriod.start(q.rankingDate) != q.rankingPeriod.start(danbooruToday()))
                TextButton(onClick = { vm.rankingDate(danbooruToday()) }) { Text("回到本期 · 站点日期") }
        }
    }
    if (filters) ModalBottomSheet(onDismissRequest = { filters = false }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("内容评级", style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ContentFilter.entries.filter { it != ContentFilter.CUSTOM }.forEach { filter ->
                    FilterChip(state.query.filter == filter,
                        onClick = { vm.filter(input, filter); filters = false }, label = { Text(filter.title) })
                }
            }
        }
    }
}

/** Every list state uses the same scroll + refresh owner, including empty and failed results. */
@Composable
internal fun MaterialDanbooruGrid(
    vm: DanbooruViewModel, state: DanbooruState, grid: LazyStaggeredGridState,
    imageLoader: ImageLoader, namespace: String, modifier: Modifier = Modifier
) {
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = vm::refresh, modifier = modifier.testTag("material-refresh")) {
        LazyVerticalStaggeredGrid(columns = StaggeredGridCells.Fixed(2), state = grid,
            modifier = Modifier.fillMaxSize().testTag("material-grid"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalItemSpacing = 8.dp, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if ((state.pages.firstOrNull()?.number ?: 1) > 1) item(span = StaggeredGridItemSpan.FullLine, key = "previous") {
                TextButton(onClick = vm::previous) { Text("加载前一页") }
            }
            if (state.posts.isEmpty()) item(span = StaggeredGridItemSpan.FullLine, key = "empty") {
                Column(Modifier.fillMaxWidth().heightIn(min = 320.dp).padding(24.dp),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    if (state.loading) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text("正在加载图片", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(if (state.error != null) "暂时没有加载成功" else "没有找到图片", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(state.error ?: "试试其他标签、评级或日期，也可以下拉刷新。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = vm::refresh) { Text(if (state.error != null) "重试" else "重新刷新") }
                    }
                }
            } else if (state.error != null) item(span = StaggeredGridItemSpan.FullLine, key = "refresh-error") {
                MaterialListFeedback(state.error + "；已有图片已保留", "重新刷新", vm::refresh)
            }
            items(state.posts, key = { it.id }, contentType = { "image" }) { post ->
                MaterialImageTile(post, imageLoader, namespace) { vm.open(post) }
            }
            if (state.posts.isNotEmpty()) item(span = StaggeredGridItemSpan.FullLine, key = "footer") {
                Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    when {
                        state.appending -> CircularProgressIndicator(Modifier.size(24.dp))
                        state.appendError != null -> MaterialListFeedback(state.appendError, "重试本页", vm::retry)
                        state.ended -> Text("已经看到最后了", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> TextButton(onClick = { vm.loadNext() }) { Text("加载更多") }
                    }
                }
            }
        }
    }
}

@Composable
private fun MaterialImageTile(post: DanbooruPost, imageLoader: ImageLoader, namespace: String, onOpen: () -> Unit) {
    val url = post.sampleUrl ?: post.previewUrl
    val context = LocalContext.current
    val ratio = if ((post.width ?: 0) > 0 && (post.height ?: 0) > 0)
        (post.width!!.toFloat() / post.height!!).coerceIn(0.6f, 1.6f) else 0.8f
    var failed by remember(post.id, url, namespace) { mutableStateOf(false) }
    var loaded by remember(post.id, url, namespace) { mutableStateOf(false) }
    val cacheKey = url?.let { post.mediaKey(namespace, if (it == post.sampleUrl) "sample" else "thumbnail", it) }
    Box(Modifier.fillMaxWidth().aspectRatio(ratio).clip(MaterialTheme.shapes.medium)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onOpen)
        .testTag("material-image-${post.id}")
        .semantics { stateDescription = if (failed) "图片加载失败" else if (loaded) "图片已加载" else "图片加载中" },
        contentAlignment = Alignment.Center) {
        if (url != null && !failed) AsyncImage(
            ImageRequest.Builder(context).data(url).size(720).diskCacheKey(cacheKey).memoryCacheKey(cacheKey).build(),
            contentDescription = "查看图片", imageLoader = imageLoader, modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop, onSuccess = { loaded = true }, onError = { failed = true })
        else Text("点按查看详情", Modifier.padding(16.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun MaterialListFeedback(message: String, action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onAction) { Text(action) }
    }
}
