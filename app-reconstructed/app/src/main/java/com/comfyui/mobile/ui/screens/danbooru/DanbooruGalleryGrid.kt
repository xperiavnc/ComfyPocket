@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.comfyui.mobile.danbooru.DanbooruPost
import com.comfyui.mobile.danbooru.DanbooruState


import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox

@Composable
internal fun DanbooruGalleryGrid(
    state: DanbooruState, grid: LazyStaggeredGridState, imageLoader: ImageLoader, namespace: String,
    bottomPadding: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier,
    onOpen: (DanbooruPost) -> Unit, onPrevious: () -> Unit, onRetry: () -> Unit,
    onLoadMore: () -> Unit, onRefresh: () -> Unit
) {
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = modifier) {
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(2), state = grid,
            modifier = Modifier.fillMaxSize().testTag("danbooru-grid"),
            contentPadding = PaddingValues(top = 10.dp, bottom = bottomPadding + 16.dp),
            verticalItemSpacing = 8.dp, horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if ((state.pages.firstOrNull()?.number ?: 1) > 1) item(span = StaggeredGridItemSpan.FullLine, key = "previous") {
                DanbooruSmallAction("加载前一页", onClick = onPrevious)
            }
            if (state.posts.isEmpty()) item(span = StaggeredGridItemSpan.FullLine, key = "empty") {
                Column(Modifier.fillMaxWidth().heightIn(min = 320.dp).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    if (state.loading) {
                        CircularProgressIndicator()
                        Text("正在加载图片", Modifier.padding(top = 16.dp))
                    } else Feedback(state.error ?: "没有找到图片，试试其他标签或日期",
                        if (state.error != null) "重试" else "重新加载", onRefresh)
                }
            }
            if (state.posts.isNotEmpty() && state.error != null) item(span = StaggeredGridItemSpan.FullLine, key = "refresh-error") {
                Feedback(state.error + "；当前图片已保留", "重新刷新", onRefresh)
            }
            items(state.posts, key = { it.id }, contentType = { "image" }) { post ->
                DanbooruImageTile(post, imageLoader, namespace) { onOpen(post) }
            }
            if (state.posts.isNotEmpty()) item(span = StaggeredGridItemSpan.FullLine, key = "footer") {
                Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    when {
                        state.appending -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        state.appendError != null -> Feedback(state.appendError, "重试本页", onRetry)
                        state.ended -> Text("已经看到最后了", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer)
                        else -> DanbooruSmallAction("加载更多", onClick = onLoadMore)
                    }
                }
            }
        }
    }
}

/** A browser tile contains only the image; post metadata lives in the detail screen. */
@Composable
internal fun DanbooruImageTile(post: DanbooruPost, imageLoader: ImageLoader, namespace: String, onClick: () -> Unit) {
    val context = LocalContext.current
    // 180 px API thumbnails look blurred on high-density phones. Reuse the detail sample cache.
    val url = post.sampleUrl ?: post.previewUrl
    val representation = if (url == post.sampleUrl) "sample" else "thumbnail"
    val ratio = if ((post.width ?: 0) > 0 && (post.height ?: 0) > 0)
        (post.width!!.toFloat() / post.height!!).coerceIn(0.6f, 1.6f) else 0.8f
    var failure by remember(post.id, url, namespace) { mutableStateOf<String?>(null) }
    Box(
        Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick).testTag("danbooru-image-${post.id}"),
        contentAlignment = Alignment.Center
    ) {
        if (url != null && failure == null) {
            AsyncImage(
                ImageRequest.Builder(context).data(url).size(720)
                    .diskCacheKey(post.mediaKey(namespace, representation, url))
                    .memoryCacheKey(post.mediaKey(namespace, representation, url)).crossfade(true).build(),
                contentDescription = "查看图片", imageLoader = imageLoader, modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop, onError = { failure = danbooruMediaError(it.result.throwable) }
            )
        } else {
            Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(PocketIcons.Image, contentDescription = null, modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(if (failure == null) "点按查看标签" else "图片加载失败\n点按查看详情",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}
