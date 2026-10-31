package com.comfyui.mobile.ui.screens.danbooru

import android.content.ClipData
import com.comfyui.mobile.ui.designsystem.PocketCardColors
import com.comfyui.mobile.ui.designsystem.PocketTokens
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import com.comfyui.mobile.ui.feedback.AppToast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.core.content.FileProvider
import com.comfyui.mobile.danbooru.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun DanbooruDetailScreen(
    state: DanbooruState, runtime: DanbooruRuntime, vm: DanbooruViewModel, modifier: Modifier = Modifier,
    bottomClearance: Dp = 0.dp
) {
    val post = state.detail
    if (post == null) {
        Column(modifier) {
            if (state.detailLoading) CircularProgressIndicator()
            state.detailError?.let { Feedback(it, "重试帖子", vm::retryDetail) }
        }
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val namespace = runtime.settings.namespace()
    var busy by remember(post.id, namespace) { mutableStateOf(false) }
    var transfer by remember(post.id, namespace) { mutableStateOf<Job?>(null) }
    var received by remember(post.id, namespace) { mutableLongStateOf(0L) }
    var total by remember(post.id, namespace) { mutableStateOf<Long?>(null) }
    var original by rememberSaveable(post.id, namespace) { mutableStateOf(false) }
    var imageFullscreen by rememberSaveable(post.id, namespace) { mutableStateOf(false) }
    var playVideo by rememberSaveable(post.id, namespace) { mutableStateOf(false) }
    var videoFullscreen by rememberSaveable(post.id, namespace) { mutableStateOf(false) }
    DisposableEffect(post.id, namespace) {
        onDispose { transfer?.cancel() }
    }
    fun toast(text: String) { AppToast.show(context, text) }
    fun download(share: Boolean) {
        if (busy) return
        busy = true
        received = 0; total = null
        transfer = scope.launch {
            try {
                val video = if (post.isVideo) runtime.video(post) { done, length -> received = done; total = length } else null
                val file = video?.file ?: runtime.original(post)
                val ext = video?.extension ?: post.extension.lowercase()
                val mime = video?.mimeType ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                if (share) {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = mime; putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri("Danbooru 原图", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "分享 Danbooru 原图"))
                } else {
                    saveDanbooruMedia(context, file, post.id, ext, mime, video = video != null)
                    toast(if (video != null) "视频已保存到 Movies/ComfyPocket" else "原图已保存到 Pictures/ComfyPocket")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                toast(if (e is DanbooruException) e.message ?: "媒体读取失败" else "媒体操作失败，请检查网络或存储空间")
            } finally { busy = false; transfer = null }
        }
    }
    LazyColumn(modifier.testTag("danbooru-detail-scroll"), contentPadding = PaddingValues(bottom = 20.dp + bottomClearance),
        verticalArrangement = Arrangement.spacedBy(PocketTokens.Space.S)) {
        item {
            val ratio = if ((post.width ?: 0) > 0 && (post.height ?: 0) > 0)
                (post.width!!.toFloat() / post.height!!).coerceIn(0.6f, 1.8f) else 1.2f
            Box(Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 440.dp).aspectRatio(ratio)
                .clip(PocketTokens.Shape.Large).background(MiuixTheme.colorScheme.surfaceContainerHigh)
                .then(if (post.isImage) Modifier.clickable(onClickLabel = "全屏查看图片") { imageFullscreen = true } else Modifier)) {
                if (post.isVideo && playVideo) {
                    DanbooruVideoPlayer(post, runtime.videoCalls, namespace, videoFullscreen,
                        { videoFullscreen = it }, Modifier.fillMaxSize())
                } else {
                    DanbooruMediaPreview(post, runtime.imageLoader, namespace, Modifier.fillMaxSize(),
                        ContentScale.Fit, originalOnly = original)
                    if (post.isVideo && post.playbackCandidates().isNotEmpty()) {
                        Row(Modifier.align(Alignment.BottomCenter).padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DanbooruSmallAction("播放视频", primary = true) { playVideo = true }
                            DanbooruSmallAction("全屏播放") { videoFullscreen = true; playVideo = true }
                        }
                    }
                }
                // Refresh status sits on the picture instead of pushing the info card down.
                if (state.detailLoading) {
                    Text("正在更新帖子信息…", Modifier.align(Alignment.TopEnd).padding(PocketTokens.Space.XS)
                        .clip(PocketTokens.Shape.Pill).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                        color = androidx.compose.ui.graphics.Color.White, style = MiuixTheme.textStyles.footnote2)
                }
            }
            if (post.isVideo && post.playbackCandidates().isEmpty()) {
                Text("暂未提供视频地址；仍可复制标签或打开原帖查看")
            } else if (!post.isImage && !post.isVideo) {
                Text("${post.extension.uppercase()} 动态格式暂提供封面与标签，播放请打开原帖")
            }
            state.detailError?.let { Feedback(it + "；保留已读 Tag", "重试详情", vm::retryDetail) }
        }
        item {
            Card(colors = PocketCardColors.raised(), cornerRadius = PocketTokens.Radius.Large) {
                Column(Modifier.fillMaxWidth().padding(PocketTokens.Space.M),
                    verticalArrangement = Arrangement.spacedBy(PocketTokens.Space.XS)) {
                    Text("画师", style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Text(post.tags[TagGroup.ARTIST].orEmpty().joinToString(" / ").ifBlank { "未标注" },
                        style = MiuixTheme.textStyles.title4, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("#${post.id} · ${post.width ?: "?"} × ${post.height ?: "?"} · ${post.extension.uppercase()} · ${post.rating.uppercase()}",
                        style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    // Main actions share one even row; links are lighter so a lone button never wraps.
                    if (post.fileUrl != null && post.isImage) {
                        Row(Modifier.fillMaxWidth().padding(top = PocketTokens.Space.XS),
                            horizontalArrangement = Arrangement.spacedBy(PocketTokens.Space.XS)) {
                            DanbooruSmallAction(if (busy) "读取中…" else "保存原图", Modifier.weight(1f),
                                primary = true, enabled = !busy) { download(false) }
                            DanbooruSmallAction("分享原图", Modifier.weight(1f), enabled = !busy) { download(true) }
                            DanbooruSmallAction(if (original) "显示预览" else "查看原图", Modifier.weight(1f)) { original = !original }
                        }
                    }
                    if (post.videoDownloadSource() != null) {
                        Row(Modifier.fillMaxWidth().padding(top = PocketTokens.Space.XS),
                            horizontalArrangement = Arrangement.spacedBy(PocketTokens.Space.XS)) {
                            DanbooruSmallAction(if (busy) "下载中…" else "下载视频", Modifier.weight(1f),
                                primary = true, enabled = !busy) { download(false) }
                            if (busy) DanbooruSmallAction("取消下载", Modifier.weight(1f)) { transfer?.cancel(); toast("已取消下载") }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DanbooruInlineAction("原帖") { openWeb(context, post.postUrl) }
                        if (post.source.startsWith("https://") || post.source.startsWith("http://")) {
                            DanbooruInlineAction("来源") { openWeb(context, post.source) }
                            Text(post.source.substringAfter("://").substringBefore('/'),
                                Modifier.weight(1f, fill = false), style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        } else if (post.source.isNotBlank()) {
                            Text(post.source, Modifier.weight(1f, fill = false).padding(start = PocketTokens.Space.XS),
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (busy && post.isVideo) {
                        val length = total
                        Text(if (length != null && length > 0)
                            "下载进度 ${(received.toDouble() / length * 100).toInt().coerceIn(0, 100)}%"
                            else "已下载 ${received / (1024 * 1024)} MB",
                            style = MiuixTheme.textStyles.footnote2)
                    }
                }
            }
        }
        item {
            DanbooruTagPanel(post, runtime.settings.copyOptions, state.selected,
                vm::select, vm::selectGroup, vm::clearSelection) { copied ->
                if (copied.text.isNotBlank()) {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("Danbooru Tag", copied.text))
                    toast("已复制 ${copied.count} 个 Tag")
                } else toast("尚未选中 Tag，剪贴板保持不变")
            }
        }
    }
    if (imageFullscreen && post.isImage) {
        DanbooruImageFullscreen(post, runtime.imageLoader, namespace, original) { imageFullscreen = false }
    }
}

private fun openWeb(context: Context, url: String) {
    val uri = Uri.parse(url)
    if (uri.scheme != "https" && uri.scheme != "http") return
    try { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    catch (_: Exception) { AppToast.show(context, "没有可用的浏览器") }
}
