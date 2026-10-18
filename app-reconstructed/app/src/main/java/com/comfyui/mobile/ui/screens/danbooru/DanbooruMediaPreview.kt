package com.comfyui.mobile.ui.screens.danbooru

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.comfyui.mobile.danbooru.DanbooruPost
import com.comfyui.mobile.danbooru.previewCandidates
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun DanbooruMediaPreview(
    post: DanbooruPost, imageLoader: ImageLoader, namespace: String,
    modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit,
    originalOnly: Boolean = false, compact: Boolean = false,
    imageModifier: Modifier = Modifier, decodeSize: Int? = null
) {
    val context = LocalContext.current
    val candidates = remember(post, originalOnly) { post.previewCandidates(originalOnly) }
    key(post.id, post.md5, namespace, candidates) {
        var index by remember { mutableIntStateOf(0) }
        var attempt by remember { mutableIntStateOf(0) }
        var failure by remember { mutableStateOf<String?>(null) }
        Box(modifier, contentAlignment = Alignment.Center) {
            val source = candidates.getOrNull(index)
            if (source != null) key(source, attempt) {
                val cacheKey = post.mediaKey(namespace, source.representation, source.url)
                val request = remember(source, cacheKey, compact, decodeSize) {
                    ImageRequest.Builder(context).data(source.url)
                        .apply { if (decodeSize != null) size(decodeSize) else if (compact) size(720) }
                        .diskCacheKey(cacheKey).memoryCacheKey(cacheKey).crossfade(true).build()
                }
                AsyncImage(
                    request, imageLoader = imageLoader,
                    contentDescription = if (post.isVideo) "视频封面" else "帖子预览",
                    modifier = Modifier.fillMaxSize().then(imageModifier), contentScale = contentScale,
                    onError = {
                        failure = danbooruMediaError(it.result.throwable)
                        // Actual decode/HTTP failure also advances, not just a null URL.
                        index++
                    }
                )
            } else {
                Column(Modifier.padding(12.dp).testTag("danbooru-preview-unavailable"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(MiuixIcons.Image, contentDescription = null, modifier = Modifier.size(28.dp),
                        tint = MiuixTheme.colorScheme.onSecondaryContainer)
                    Text(when {
                        post.isVideo -> if (compact) "视频 · 点按查看" else "视频封面暂不可用"
                        !post.isImage -> "动态资源 · 点按查看标签"
                        compact -> if (failure == null) "点按查看标签" else "预览加载失败\n点按查看详情"
                        else -> failure ?: "图片暂不可用；仍可查看和复制下方 Tag"
                    }, style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSecondaryContainer)
                    if (!compact && candidates.isNotEmpty()) {
                        DanbooruSmallAction("重试预览") { attempt++; failure = null; index = 0 }
                    }
                }
            }
        }
    }
}
