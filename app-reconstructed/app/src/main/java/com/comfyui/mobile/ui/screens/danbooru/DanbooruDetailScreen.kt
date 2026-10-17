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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.comfyui.mobile.danbooru.*
import com.comfyui.mobile.ui.screens.CategoryChip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun DanbooruDetailScreen(
    state: DanbooruState, runtime: DanbooruRuntime, vm: DanbooruViewModel, modifier: Modifier = Modifier
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
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            val url = if (original && !post.isVideo) post.fileUrl else post.sampleUrl ?: post.previewUrl
            var failure by remember(post.id, url) { mutableStateOf<String?>(null) }
            if (url != null && failure == null) {
                val representation = if (url == post.fileUrl && !post.isVideo) "original" else "sample"
                val cacheKey = post.mediaKey(runtime.settings.namespace(), representation, url)
                AsyncImage(ImageRequest.Builder(context).data(url).diskCacheKey(cacheKey).memoryCacheKey(cacheKey).build(),
                    contentDescription = "帖子预览", imageLoader = runtime.imageLoader,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 440.dp),
                    contentScale = ContentScale.Fit, onError = { failure = danbooruMediaError(it.result.throwable) })
            } else Text((failure ?: "图片暂不可用") + "；仍可查看和复制下方 Tag")
            if (post.isVideo) Text("视频 / 动态资源（${post.extension}）：首版提供封面与 Tag，播放请打开原帖")
            state.detailError?.let { Feedback(it + "；保留已读 Tag", "重试详情", vm::retryDetail) }
            if (state.detailLoading) Text("正在更新帖子信息…", style = MiuixTheme.textStyles.footnote1)
        }
        item {
            Text("#${post.id} · 评级 ${post.rating.uppercase()} · ${post.width ?: "?"} × ${post.height ?: "?"} · ${post.extension}")
            Text("画师：${post.tags[TagGroup.ARTIST].orEmpty().joinToString(" / ").ifBlank { "未标注" }}")
            if (post.source.isNotBlank()) Text("来源：${post.source}", style = MiuixTheme.textStyles.footnote1)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(text = "打开原帖", onClick = { openWeb(context, post.postUrl) })
                if (post.source.startsWith("https://") || post.source.startsWith("http://"))
                    TextButton(text = "外部来源", onClick = { openWeb(context, post.source) })
            }
            if (post.fileUrl != null && !post.isVideo) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(text = if (original) "显示预览" else "查看原图", onClick = { original = !original })
                TextButton(text = if (busy) "读取原图中…" else "保存原图", onClick = { download(false) })
                TextButton(text = "分享原图", onClick = { download(true) })
            } else Text("原图不可用；不会猜测下载地址", style = MiuixTheme.textStyles.footnote1)
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

/** Display names are never fed back into formatting; selection always contains original names. */
@Composable
internal fun DanbooruTagPanel(
    post: DanbooruPost, initialOptions: TagCopyOptions, selected: Set<String>,
    onSelect: (String) -> Unit, onGroup: (TagGroup) -> Unit, onClear: () -> Unit,
    onCopy: (CopiedTags) -> Unit
) {
    var mode by rememberSaveable(post.id) { mutableStateOf(initialOptions.mode) }
    var spaces by rememberSaveable(post.id) { mutableStateOf(initialOptions.underscoresToSpaces) }
    var escape by rememberSaveable(post.id) { mutableStateOf(initialOptions.escapeParentheses) }
    var artists by rememberSaveable(post.id) { mutableStateOf(false) }
    var copyright by rememberSaveable(post.id) { mutableStateOf(false) }
    var meta by rememberSaveable(post.id) { mutableStateOf(false) }
    val options = TagCopyOptions(mode, spaces, escape)
    val promptGroups = setOf(TagGroup.GENERAL, TagGroup.CHARACTER) +
        listOfNotNull(TagGroup.ARTIST.takeIf { artists }, TagGroup.COPYRIGHT.takeIf { copyright }, TagGroup.META.takeIf { meta })
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Tag · 单击复制，长按多选", style = MiuixTheme.textStyles.title4)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TagCopyMode.entries.forEach { value -> CategoryChip(value.title, mode == value) { mode = value } }
        }
        if (mode == TagCopyMode.PROMPT) {
            DanbooruSettingSwitch("下划线转空格", spaces) { spaces = it }
            DanbooruSettingSwitch("括号转义（默认关闭）", escape) { escape = it }
        }
        Text("快捷提示词默认：通用＋角色；可附加以下分组", style = MiuixTheme.textStyles.footnote1)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CategoryChip("画师", artists) { artists = !artists }
            CategoryChip("作品", copyright) { copyright = !copyright }
            CategoryChip("元信息", meta) { meta = !meta }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(text = "复制提示词", onClick = { onCopy(TagCopyFormatter.format(post, options.copy(mode = TagCopyMode.PROMPT), promptGroups)) })
            TextButton(text = "复制全部", onClick = { onCopy(TagCopyFormatter.format(post, options)) })
            if (selected.isNotEmpty()) {
                TextButton(text = "复制选中 ${selected.size}", onClick = { onCopy(TagCopyFormatter.format(post, options, selected = selected)) })
                TextButton(text = "清空选择", onClick = onClear)
            }
        }
        TagGroup.entries.forEach { group ->
            val tags = post.tags[group].orEmpty()
            var expanded by rememberSaveable(post.id, group) { mutableStateOf(true) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(text = "${if (expanded) "▾" else "▸"} ${group.title}（${tags.size}）", onClick = { expanded = !expanded })
                if (tags.isNotEmpty()) TextButton(text = "全选${group.title}", onClick = { onGroup(group) })
            }
            if (expanded) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.forEach { tag ->
                    Card(Modifier.combinedClickable(
                        onClick = {
                            if (selected.isNotEmpty()) onSelect(tag)
                            else onCopy(TagCopyFormatter.format(post, options, selected = setOf(tag)))
                        }, onLongClick = { onSelect(tag) })) {
                        Text((if (tag in selected) "✓ " else "") + tag, Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MiuixTheme.textStyles.footnote1)
                    }
                }
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
