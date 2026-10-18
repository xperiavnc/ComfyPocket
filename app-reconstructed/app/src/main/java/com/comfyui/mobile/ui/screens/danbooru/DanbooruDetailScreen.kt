package com.comfyui.mobile.ui.screens.danbooru

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.background
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    var busy by remember { mutableStateOf(false) }
    var original by rememberSaveable(post.id) { mutableStateOf(false) }
    val namespace = runtime.settings.namespace()
    var playVideo by rememberSaveable(post.id, namespace) { mutableStateOf(false) }
    fun toast(text: String) { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    fun download(share: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val file = runtime.original(post)
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(post.extension.lowercase()) ?: "application/octet-stream"
                if (share) {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = mime; putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri("Danbooru 原图", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "分享 Danbooru 原图"))
                } else {
                    withContext(Dispatchers.IO) {
                        val values = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, "danbooru_${post.id}.${post.extension.lowercase()}")
                            put(MediaStore.Images.Media.MIME_TYPE, mime)
                            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ComfyPocket")
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                        val resolver = context.contentResolver
                        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                            ?: throw DanbooruException("相册创建失败，请检查存储空间")
                        try {
                            resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                                ?: throw DanbooruException("相册写入失败，请重试")
                            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                        } catch (e: Exception) {
                            resolver.delete(uri, null, null); throw e
                        }
                    }
                    toast("原图已保存到 Pictures/ComfyPocket")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                toast(if (e is DanbooruException) e.message ?: "原图读取失败" else "原图操作失败，请检查网络或存储空间")
            } finally { busy = false }
        }
    }
    LazyColumn(modifier.testTag("danbooru-detail-scroll"), contentPadding = PaddingValues(bottom = 20.dp + bottomClearance),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            val ratio = if ((post.width ?: 0) > 0 && (post.height ?: 0) > 0)
                (post.width!!.toFloat() / post.height!!).coerceIn(0.6f, 1.8f) else 1.2f
            Box(Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 440.dp).aspectRatio(ratio)
                .clip(RoundedCornerShape(18.dp)).background(MiuixTheme.colorScheme.surfaceContainerHigh)) {
                if (post.isVideo && playVideo) {
                    DanbooruVideoPlayer(post, runtime.videoCalls, namespace, Modifier.fillMaxSize())
                } else {
                    DanbooruMediaPreview(post, runtime.imageLoader, namespace, Modifier.fillMaxSize(),
                        ContentScale.Fit, originalOnly = original)
                    if (post.isVideo && post.playbackCandidates().isNotEmpty()) {
                        DanbooruSmallAction("播放视频",
                            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                            primary = true) { playVideo = true }
                    }
                }
            }
            if (post.isVideo && post.playbackCandidates().isEmpty()) {
                Text("暂未提供视频地址；仍可复制标签或打开原帖查看")
            } else if (!post.isImage && !post.isVideo) {
                Text("${post.extension.uppercase()} 动态格式暂提供封面与标签，播放请打开原帖")
            }
            state.detailError?.let { Feedback(it + "；保留已读 Tag", "重试详情", vm::retryDetail) }
            if (state.detailLoading) Text("正在更新帖子信息…", style = MiuixTheme.textStyles.footnote1)
        }
        item {
            Card(colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHigh), cornerRadius = 14.dp) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("画师  ${post.tags[TagGroup.ARTIST].orEmpty().joinToString(" / ").ifBlank { "未标注" }}",
                        style = MiuixTheme.textStyles.body2)
                    Text("#${post.id} · ${post.width ?: "?"} × ${post.height ?: "?"} · ${post.extension.uppercase()} · ${post.rating.uppercase()}",
                        style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSecondaryContainer)
                    if (post.source.isNotBlank()) Text(post.source, style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSecondaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (post.fileUrl != null && post.isImage) {
                            DanbooruSmallAction(if (busy) "读取中…" else "保存原图", enabled = !busy) { download(false) }
                            DanbooruSmallAction("分享原图", enabled = !busy) { download(true) }
                            DanbooruSmallAction(if (original) "显示预览" else "查看原图") { original = !original }
                        }
                        DanbooruSmallAction("原帖") { openWeb(context, post.postUrl) }
                        if (post.source.startsWith("https://") || post.source.startsWith("http://"))
                            DanbooruSmallAction("来源") { openWeb(context, post.source) }
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
}

private fun openWeb(context: Context, url: String) {
    val uri = Uri.parse(url)
    if (uri.scheme != "https" && uri.scheme != "http") return
    try { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    catch (_: Exception) { Toast.makeText(context, "没有可用的浏览器", Toast.LENGTH_SHORT).show() }
}
