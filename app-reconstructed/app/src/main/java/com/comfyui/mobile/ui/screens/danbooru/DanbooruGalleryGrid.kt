package com.comfyui.mobile.ui.screens.danbooru

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import com.comfyui.mobile.danbooru.DanbooruPost
import com.comfyui.mobile.danbooru.DanbooruState
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun DanbooruGalleryGrid(
    state: DanbooruState, grid: LazyStaggeredGridState, imageLoader: ImageLoader, namespace: String,
    bottomPadding: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier,
    onOpen: (DanbooruPost) -> Unit, onPrevious: () -> Unit, onRetry: () -> Unit,
    onLoadMore: () -> Unit, onRefresh: () -> Unit
) {
    PullToRefresh(
        isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = modifier,
        color = MiuixTheme.colorScheme.primary,
        refreshTexts = listOf("下拉刷新", "松开刷新", "正在更新", "刷新结束"),
        refreshTextStyle = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSecondaryContainer)
    ) {
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(2), state = grid,
            modifier = Modifier.fillMaxSize().testTag("danbooru-grid"),
            contentPadding = PaddingValues(top = 10.dp, bottom = bottomPadding + 16.dp),
            verticalItemSpacing = 8.dp, horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if ((state.pages.firstOrNull()?.number ?: 1) > 1) item(span = StaggeredGridItemSpan.FullLine, key = "previous") {
                DanbooruSmallAction("加载前一页", onClick = onPrevious)
            }
            items(state.posts, key = { it.id }, contentType = { "image" }) { post ->
                DanbooruImageTile(post, imageLoader, namespace) { onOpen(post) }
            }
            item(span = StaggeredGridItemSpan.FullLine, key = "footer") {
                Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    when {
                        state.appending -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        state.appendError != null -> Feedback(state.appendError, "重试本页", onRetry)
                        state.ended -> Text("已经看到最后了", style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSecondaryContainer)
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
    val ratio = if ((post.width ?: 0) > 0 && (post.height ?: 0) > 0)
        (post.width!!.toFloat() / post.height!!).coerceIn(0.6f, 1.6f) else 0.8f
    Box(
        Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(14.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick).testTag("danbooru-image-${post.id}"),
        contentAlignment = Alignment.Center
    ) {
        DanbooruMediaPreview(post, imageLoader, namespace, Modifier.fillMaxSize(), ContentScale.Crop, compact = true)
        if (post.isVideo) {
            Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).size(28.dp)
                .clip(RoundedCornerShape(14.dp)).background(MiuixTheme.colorScheme.background.copy(alpha = 0.8f)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, contentDescription = "视频", modifier = Modifier.size(18.dp),
                    tint = MiuixTheme.colorScheme.onSurface)
            }
        }
    }
}
