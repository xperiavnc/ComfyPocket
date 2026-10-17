@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.comfyui.mobile.ui.material

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.comfyui.mobile.danbooru.*

@Composable
internal fun MaterialDanbooruDetail(
    vm: DanbooruViewModel, state: DanbooruState, loader: ImageLoader, namespace: String,
    online: Boolean, busy: Boolean, onTags: () -> Unit, onFullscreen: (String, String) -> Unit,
    onMedia: (DanbooruPost, Boolean) -> Unit, onWeb: (String) -> Unit, modifier: Modifier = Modifier
) {
    val post = state.detail
    if (post == null) {
        Column(modifier.fillMaxWidth().padding(24.dp)) {
            if (state.detailLoading) CircularProgressIndicator()
            state.detailError?.let { MaterialListFeedback(it, "重试详情", vm::retryDetail) }
        }
        return
    }
    val url = post.sampleUrl ?: post.previewUrl
    val context = LocalContext.current
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val key = url?.let { post.mediaKey(namespace, if (url == post.sampleUrl) "sample" else "thumbnail", it) }
        if (url != null) {
            var failed by remember(post.id, url) { mutableStateOf(false) }
            if (!failed) AsyncImage(
                ImageRequest.Builder(context).data(url).diskCacheKey(key).memoryCacheKey(key).build(),
                "放大图片", loader, modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 460.dp)
                    .clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .clickable { onFullscreen(url, key.orEmpty()) },
                contentScale = ContentScale.Fit, onError = { failed = true })
            else MaterialListFeedback("预览暂不可用，标签仍可查看。", "重新读取", vm::retryDetail)
        }
        if (state.detailLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.detailError?.let { MaterialListFeedback(it, "重试详情", vm::retryDetail) }
        Button(onClick = onTags, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("标签与提示词") }
        if (online && post.fileUrl != null && !post.isVideo) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onMedia(post, false) }, enabled = !busy) { Text(if (busy) "正在读取…" else "保存原图") }
                OutlinedButton(onClick = { onMedia(post, true) }, enabled = !busy) { Text("分享原图") }
                TextButton(onClick = { onFullscreen(post.fileUrl, post.mediaKey(namespace, "original", post.fileUrl)) }) { Text("查看原图") }
            }
        } else if (!online) Text("离线风景图仅用于布局演示；真实画廊保留原图保存与分享。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(post.tags[TagGroup.ARTIST].orEmpty().joinToString(" / ").ifBlank { "未标注画师" }, style = MaterialTheme.typography.titleMedium)
                Text("#${post.id} · ${post.width ?: "?"} × ${post.height ?: "?"} · ${post.extension.uppercase()} · ${post.rating.uppercase()}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (post.source.isNotBlank()) Text(post.source, style = MaterialTheme.typography.bodySmall)
                if (post.isVideo) Text("视频暂以封面和标签展示，播放请打开原帖。", style = MaterialTheme.typography.bodySmall)
                if (online) FlowRow {
                    TextButton(onClick = { onWeb(post.postUrl) }) { Text("打开原帖") }
                    if (post.source.startsWith("https://") || post.source.startsWith("http://"))
                        TextButton(onClick = { onWeb(post.source) }) { Text("查看来源") }
                }
            }
        }
    }
}

@Composable
internal fun MaterialTagPanel(
    post: DanbooruPost, initial: TagCopyOptions, selected: Set<String>,
    onSelect: (String) -> Unit, onGroup: (TagGroup) -> Unit, onClear: () -> Unit, onCopy: (CopiedTags) -> Unit
) {
    var mode by rememberSaveable(post.id) { mutableStateOf(initial.mode) }
    var spaces by rememberSaveable(post.id) { mutableStateOf(initial.underscoresToSpaces) }
    var escape by rememberSaveable(post.id) { mutableStateOf(initial.escapeParentheses) }
    var advanced by rememberSaveable(post.id) { mutableStateOf(false) }
    var artists by rememberSaveable(post.id) { mutableStateOf(false) }
    var copyright by rememberSaveable(post.id) { mutableStateOf(false) }
    var meta by rememberSaveable(post.id) { mutableStateOf(false) }
    val options = TagCopyOptions(mode, spaces, escape)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (selected.isEmpty()) "标签与提示词" else "已选 ${selected.size} 个标签", style = MaterialTheme.typography.titleLarge)
        Text("点按复制，长按多选；复制始终使用原始标签值。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TagCopyMode.entries.forEach { value ->
                FilterChip(mode == value, onClick = { mode = value }, label = { Text(value.title) })
            }
            AssistChip(onClick = { advanced = !advanced }, label = { Text(if (advanced) "收起选项" else "格式选项") })
        }
        if (advanced) {
            SettingSwitch("下划线转空格", spaces) { spaces = it }
            SettingSwitch("括号转义", escape) { escape = it }
            Text("快捷提示词默认包含通用与角色，可附加：", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(artists, onClick = { artists = !artists }, label = { Text("画师") })
                FilterChip(copyright, onClick = { copyright = !copyright }, label = { Text("作品") })
                FilterChip(meta, onClick = { meta = !meta }, label = { Text("元信息") })
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selected.isNotEmpty()) {
                Button(onClick = { onCopy(TagCopyFormatter.format(post, options, selected = selected)) }) { Text("复制选中 ${selected.size}") }
                TextButton(onClick = onClear) { Text("清空选择") }
            } else {
                Button(onClick = {
                    val groups = setOf(TagGroup.GENERAL, TagGroup.CHARACTER) +
                        listOfNotNull(TagGroup.ARTIST.takeIf { artists }, TagGroup.COPYRIGHT.takeIf { copyright }, TagGroup.META.takeIf { meta })
                    onCopy(TagCopyFormatter.format(post, options.copy(mode = TagCopyMode.PROMPT), groups))
                }) { Text("复制提示词") }
                OutlinedButton(onClick = { onCopy(TagCopyFormatter.format(post, options)) }) { Text("复制全部") }
            }
        }
        TagGroup.entries.forEach { group ->
            val tags = post.tags[group].orEmpty()
            if (tags.isNotEmpty()) {
                var expanded by rememberSaveable(post.id, group) { mutableStateOf(true) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.weight(1f)) {
                        Text("${group.title} · ${tags.size}")
                    }
                    TextButton(onClick = { onGroup(group) }) { Text("全选${group.title}") }
                }
                if (expanded) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tags.forEach { tag ->
                        val checked = tag in selected
                        Surface(shape = MaterialTheme.shapes.small,
                            color = if (checked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(1.dp, if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.semantics { this.selected = checked }.combinedClickable(
                                onClick = {
                                    if (selected.isEmpty()) onCopy(TagCopyFormatter.format(post, options, selected = setOf(tag)))
                                    else onSelect(tag)
                                }, onLongClick = { onSelect(tag) })) {
                            Box(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                                Text(tag, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MaterialFullscreenImage(url: String, key: String, loader: ImageLoader, onClose: () -> Unit) {
    var scale by remember(url) { mutableFloatStateOf(1f) }
    var pan by remember(url) { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, offset, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        pan = if (scale <= 1f) Offset.Zero else pan + offset
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).clipToBounds()) {
            AsyncImage(ImageRequest.Builder(LocalContext.current).data(url).memoryCacheKey(key).diskCacheKey(key).build(),
                "全屏图片，可双指缩放", loader, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = pan.x, translationY = pan.y)
                    .transformable(transform))
            IconButton(onClick = onClose, modifier = Modifier.statusBarsPadding().align(Alignment.TopStart).padding(8.dp)) {
                Icon(Icons.Default.Close, "关闭全屏", tint = Color.White)
            }
            TextButton(onClick = { scale = 1f; pan = Offset.Zero },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp)) {
                Text("重置缩放", color = Color.White)
            }
        }
    }
}
