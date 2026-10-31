package com.comfyui.mobile.ui.screens.modelcenter

import androidx.compose.animation.core.animateFloatAsState
import com.comfyui.mobile.ui.designsystem.PocketTokens
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.Coil
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.comfyui.mobile.modelcenter.LocalModel
import com.comfyui.mobile.modelcenter.remoteModelPreviews
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Photos
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Secondary-page chrome, not another expanded home-page header. */
@Composable
fun ModelCenterToolbar(onBack: () -> Unit, onSettings: () -> Unit, onRefresh: () -> Unit, title: String = "模型中心") {
    Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 56.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
            Icon(MiuixIcons.Back, "返回", modifier = Modifier.size(24.dp))
        }
        Text(title, style = MiuixTheme.textStyles.title4,
            modifier = Modifier.weight(1f).padding(start = 4.dp))
        IconButton(onClick = onSettings, modifier = Modifier.size(44.dp)) {
            Icon(MiuixIcons.Settings, "集市连接、代理与 Key 设置", modifier = Modifier.size(23.dp))
        }
        IconButton(onClick = onRefresh, modifier = Modifier.size(44.dp)) {
            Icon(MiuixIcons.Refresh, "刷新", modifier = Modifier.size(23.dp))
        }
    }
}

@Composable
fun ModelCenterTabs(selected: Int, onSelect: (Int) -> Unit) {
    val colors = MiuixTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        .background(colors.outline.copy(alpha = .12f))) {
        listOf("本地模型", "在线集市", "下载任务").forEachIndexed { index, title ->
            val active = index == selected
            Column(Modifier.weight(1f).background(colors.background)
                .selectable(active, role = Role.Tab, onClick = { onSelect(index) }),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.heightIn(min = 46.dp).padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center) {
                    Text(title, style = MiuixTheme.textStyles.body2,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) colors.primary else colors.onSecondaryContainer,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.width(28.dp).height(3.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (active) colors.primary else colors.background))
            }
        }
    }
}

/** Fixed-height single-line search avoids Miuix's floating label wrapping on narrow screens. */
@Composable
fun ModelCenterSearch(
    value: String, onValueChange: (String) -> Unit, placeholder: String,
    modifier: Modifier = Modifier, onSearch: () -> Unit = {}
) {
    val colors = MiuixTheme.colorScheme
    BasicTextField(value, onValueChange, singleLine = true,
        textStyle = MiuixTheme.textStyles.body2.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = modifier.heightIn(min = 48.dp).clip(PocketTokens.Shape.Medium)
            .background(colors.surfaceContainer),
        decorationBox = { input ->
            Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(MiuixIcons.Search, null, tint = colors.onSecondaryContainer, modifier = Modifier.size(20.dp))
                Box(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 12.dp)) {
                    if (value.isEmpty()) Text(placeholder, style = MiuixTheme.textStyles.body2,
                        color = colors.onSecondaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    input()
                }
                if (value.isNotEmpty()) IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Close, "清空搜索", modifier = Modifier.size(18.dp))
                }
            }
        })
}

@Composable
internal fun ModelAction(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Box(modifier.heightIn(min = 44.dp).clip(PocketTokens.Shape.Medium)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium,
            color = colors.primary.copy(alpha = if (enabled) 1f else .4f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun ModelStatusBadge(text: String, positive: Boolean = false) {
    val colors = MiuixTheme.colorScheme
    Text(text, style = MiuixTheme.textStyles.footnote2,
        color = if (positive) colors.primary else colors.onSecondaryContainer,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clip(PocketTokens.Shape.Small)
            .background(if (positive) colors.primary.copy(alpha = .12f) else colors.surfaceContainerHigh)
            .padding(horizontal = 7.dp, vertical = 3.dp))
}

@Composable
internal fun ModelProgressTrack(progress: Float, modifier: Modifier = Modifier) {
    val fraction by animateFloatAsState(progress.coerceIn(0f, 1f), label = "model-task-progress")
    val colors = MiuixTheme.colorScheme
    Box(modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
        .background(colors.primary.copy(alpha = .12f))) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(colors.primary))
    }
}

internal data class PhoneModelImages(
    val loader: ImageLoader, val namespace: String, val request: (String) -> ImageRequest
)
internal val LocalPhoneModelImages = staticCompositionLocalOf<PhoneModelImages?> { null }

/** Computer-offline is not phone-offline. Only local sidecar URLs become cache-only. */
@Composable
internal fun LocalModelCover(
    item: LocalModel, url: String?, offline: Boolean, modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop, bridgeFallback: String? = null
) {
    val context = LocalContext.current
    val colors = MiuixTheme.colorScheme
    val phone = LocalPhoneModelImages.current
    val remotes = remoteModelPreviews(item)
    // Try the chosen cover, then every other provider still the phone can fetch, and only then
    // the computer's cached copy. One dead CDN link or a leading video no longer blanks the card.
    val candidates = remember(url, remotes, bridgeFallback) {
        (listOfNotNull(url) + remotes + listOfNotNull(bridgeFallback)).distinct()
    }
    var attempt by remember(candidates, phone?.namespace) { mutableIntStateOf(0) }
    var step by remember(candidates, phone?.namespace, attempt) { mutableIntStateOf(0) }
    val actualUrl = candidates.getOrNull(step)
    val direct = actualUrl != null && actualUrl in remotes
    var loaded by remember(actualUrl, phone?.namespace, attempt) { mutableStateOf(false) }
    var failed by remember(candidates, phone?.namespace, attempt) { mutableStateOf(false) }
    val request = remember(context, actualUrl, offline, phone?.namespace, attempt) {
        actualUrl?.let {
            val builder = if (direct && phone != null) phone.request(it).newBuilder()
            else ImageRequest.Builder(context).data(it).memoryCacheKey(it).diskCacheKey(it)
            builder.networkCachePolicy(if (offline && !direct) CachePolicy.DISABLED else CachePolicy.ENABLED)
                .crossfade(150).build()
        }
    }
    Box(modifier.clip(PocketTokens.Shape.Medium).background(Brush.linearGradient(listOf(
        colors.primary.copy(alpha = .10f), colors.surfaceContainerHigh)))
        .testTag("model-cover-${item.id}").semantics {
            stateDescription = if (loaded) "预览已加载" else if (failed) "预览加载失败"
                else if (actualUrl == null) "暂无预览" else "预览加载中"
        }.then(if (failed) Modifier.clickable { attempt++ } else Modifier),
        contentAlignment = Alignment.Center) {
        if (!loaded) Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(MiuixIcons.Photos, null, tint = colors.onSecondaryContainer.copy(alpha = .45f),
                modifier = Modifier.size(28.dp))
            Text(if (failed) "加载失败 · 点按重试" else if (actualUrl == null) "暂无预览" else "加载预览…",
                style = MiuixTheme.textStyles.footnote2,
                color = colors.onSecondaryContainer.copy(alpha = .7f), maxLines = 1)
        }
        // Coil considers an identical URL/cache key the same model. A transport-only setting
        // change needs a new painter too, while keeping the successful image's disk cache key.
        key(actualUrl, phone?.namespace, attempt) {
            if (request != null) AsyncImage(model = request,
                imageLoader = if (direct && phone != null) phone.loader else Coil.imageLoader(context),
                contentDescription = "${item.displayName}预览",
                contentScale = contentScale, onSuccess = { loaded = true; failed = false },
                onError = { if (step < candidates.lastIndex) step++ else failed = true },
                modifier = Modifier.fillMaxSize().testTag("model-preview-${item.id}"))
        }
    }
}
