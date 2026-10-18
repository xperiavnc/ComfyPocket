package com.comfyui.mobile.ui.screens.modelcenter

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import com.comfyui.mobile.market.MarketCatalogState
import com.comfyui.mobile.market.MarketCatalogSection
import com.comfyui.mobile.market.MarketOpenState
import com.comfyui.mobile.market.MarketSort
import com.comfyui.mobile.market.MarketPeriod
import com.comfyui.mobile.model.CivitaiModelItem
import com.comfyui.mobile.model.CivitaiContentChannel
import com.comfyui.mobile.ui.screens.modelbrowser.MODEL_RESOURCE_TYPE_TABS
import com.comfyui.mobile.ui.screens.modelbrowser.ModelBrowserFilterChipBar
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale

/** One scrolling surface: filters never pin a small/landscape screen into an empty sliver. */
@Composable
fun ModelMarketPage(
    state: MarketCatalogState, grid: LazyGridState, pinnedTags: List<String>,
    imageLoader: ImageLoader, previewUrl: (CivitaiModelItem) -> String?,
    imageRequest: (String) -> Any,
    onKeyword: (String) -> Unit, onType: (String) -> Unit, onBaseModel: (String) -> Unit,
    onManageTags: () -> Unit, onSearch: () -> Unit, onRetry: () -> Unit,
    onMore: () -> Unit, onOpen: (CivitaiModelItem) -> Unit,
    onAuthor: (String) -> Unit = {}, onSort: (MarketSort) -> Unit = {},
    onPeriod: (MarketPeriod) -> Unit = {}, onClearFilters: () -> Unit = {},
    openState: MarketOpenState = MarketOpenState(), onOpenLink: () -> Unit = {},
    onMoreSection: (CivitaiContentChannel) -> Unit = { onMore() },
    onRetrySection: (CivitaiContentChannel) -> Unit = { onRetry() }
) {
    val colors = MiuixTheme.colorScheme
    val sections = state.sections.ifEmpty {
        listOf(MarketCatalogSection(CivitaiContentChannel.STANDARD, state))
    }
    LazyVerticalGrid(columns = GridCells.Adaptive(156.dp), state = grid,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().testTag("market-grid")) {
        item(key = "market-controls", span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ModelCenterSearch(state.keyword, onKeyword, "名称 / 编号 / 链接",
                        Modifier.weight(1f).testTag("market-keyword"), onSearch)
                    ModelAction("搜索", onClick = onSearch)
                    ModelAction(if (openState.loading) "正在打开…" else "直达详情",
                        enabled = !openState.loading && state.keyword.isNotBlank(),
                        onClick = onOpenLink, modifier = Modifier.testTag("market-open-link"))
                }
                openState.error?.let {
                    Text(it, color = colors.error, style = MiuixTheme.textStyles.footnote2,
                        modifier = Modifier.testTag("market-open-error"))
                }
                ModelCenterSearch(state.author, onAuthor, "作者用户名",
                    Modifier.fillMaxWidth().testTag("market-author"), onSearch)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (listOf("ALL" to "全部类型") + MODEL_RESOURCE_TYPE_TABS).forEach { (key, title) ->
                        ModelChip(title, state.type == key) { onType(key) }
                    }
                }
                ModelBrowserFilterChipBar(state.baseModel, pinnedTags, onBaseModel, onManageTags)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MarketSort.entries.forEach { sort -> ModelChip(sort.label, state.sort == sort) { onSort(sort) } }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MarketPeriod.entries.forEach { period ->
                        ModelChip(period.label, state.period == period) { onPeriod(period) }
                    }
                }
                if (state.type != "ALL" || state.baseModel != "全部" || state.author.isNotBlank()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        ModelAction("清除筛选", onClick = onClearFilters)
                    }
                }
                (state.notice ?: state.error?.takeIf { state.items.isNotEmpty() })
                    ?.takeIf { state.sections.isEmpty() }?.let { message ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(message, style = MiuixTheme.textStyles.footnote2,
                            color = colors.onSecondaryContainer,
                            modifier = Modifier.weight(1f))
                        if (state.error != null) ModelAction("重试", onClick = onRetry)
                    }
                }
            }
        }
        sections.forEach { section ->
        val content = section.state
        val channel = section.channel
        if (state.sections.isNotEmpty()) item(key = "market-heading-$channel", span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth().testTag("market-section-$channel"),
                verticalAlignment = Alignment.CenterVertically) {
                Text(channel.label, style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("${content.items.size} 个", style = MiuixTheme.textStyles.footnote2,
                    color = colors.onSecondaryContainer)
            }
        }
        if (state.sections.isNotEmpty() && (content.notice != null || content.error != null && content.items.isNotEmpty())) {
            item(key = "market-notice-$channel", span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(listOfNotNull(content.notice, content.error).joinToString("\n"),
                        style = MiuixTheme.textStyles.footnote2, color = colors.onSecondaryContainer,
                        modifier = Modifier.weight(1f))
                    if (content.error != null) ModelAction("重试", onClick = { onRetrySection(channel) })
                }
            }
        }
        if (content.items.isEmpty()) item(key = "market-empty-$channel", span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (content.loading) "正在搜索模型…" else content.error ?:
                    if (content.hasNextPage) "本页没有新增模型，仍可继续加载" else "没有符合条件的模型",
                    style = MiuixTheme.textStyles.body2,
                    color = if (content.error == null) colors.onSecondaryContainer else colors.error)
                if (content.error != null) ModelAction("重试", onClick = { onRetrySection(channel) })
            }
        }
        items(content.items, key = { "market-model-${it.id}" }) { item ->
            Card(onClick = { onOpen(item) }, cornerRadius = 16.dp, modifier = Modifier.fillMaxWidth()) {
                Column {
                    Box(Modifier.padding(6.dp).fillMaxWidth().aspectRatio(.95f)
                        .clip(RoundedCornerShape(12.dp)).background(colors.surfaceContainerHigh)) {
                        previewUrl(item)?.let { url ->
                            AsyncImage(imageRequest(url), null, imageLoader = imageLoader,
                                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                        item.versions.firstOrNull()?.baseModel?.takeIf { it.isNotBlank() }?.let { base ->
                            Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { ModelStatusBadge(base) }
                        }
                        if (item.sfwOnly == true || item.minor == true) {
                            Box(Modifier.align(Alignment.BottomStart).padding(8.dp)) { ModelStatusBadge("仅普通内容") }
                        }
                    }
                    Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(item.name, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(item.creator.ifBlank { "作者未提供" }, style = MiuixTheme.textStyles.footnote2,
                            color = colors.onSecondaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val downloads = item.downloadCount
                        Text((if (downloads >= 10000) String.format(Locale.CHINA, "%.1f万", downloads / 10000.0)
                              else downloads.toString()) + " 次下载",
                            style = MiuixTheme.textStyles.footnote2, color = colors.onSecondaryContainer)
                    }
                }
            }
        }
        if (content.items.isNotEmpty() || content.hasNextPage) item(key = "market-footer-$channel", span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().testTag("market-footer-$channel"), contentAlignment = Alignment.Center) {
                if (content.hasNextPage) ModelAction(when {
                    content.loading -> "正在加载…"; content.error != null -> "加载失败，重试"
                    else -> "加载更多 · 已显示 ${content.items.size}"
                }, enabled = !content.loading, onClick = {
                    if (content.error != null) onRetrySection(channel) else onMoreSection(channel)
                })
                else Text("已显示 ${content.items.size} 个模型", style = MiuixTheme.textStyles.footnote2,
                    color = colors.onSecondaryContainer, modifier = Modifier.padding(12.dp))
            }
        }
        }
    }
}
