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
import com.comfyui.mobile.market.MarketSort
import com.comfyui.mobile.market.MarketPeriod
import com.comfyui.mobile.model.CivitaiModelItem
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
    onPeriod: (MarketPeriod) -> Unit = {}, onClearFilters: () -> Unit = {}
) {
    val colors = MiuixTheme.colorScheme
    LazyVerticalGrid(columns = GridCells.Adaptive(156.dp), state = grid,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()) {
        item(key = "market-controls", span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ModelCenterSearch(state.keyword, onKeyword, "名称 / 模型编号 / Civitai 链接",
                        Modifier.weight(1f).testTag("market-keyword"), onSearch)
                    ModelAction("搜索", onClick = onSearch)
                }
                ModelCenterSearch(state.author, onAuthor, "按作者用户名筛选（可与名称组合）",
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("筛选：${if (state.type == "ALL") "全部类型" else state.type} · ${state.baseModel}" +
                        state.author.takeIf { it.isNotBlank() }?.let { " · 作者 $it" }.orEmpty(),
                        style = MiuixTheme.textStyles.footnote2, modifier = Modifier.weight(1f))
                    if (state.type != "ALL" || state.baseModel != "全部" || state.author.isNotBlank()) {
                        ModelAction("清除筛选", onClick = onClearFilters)
                    }
                }
                Text("网页与 API 的账号、内容设置及搜索索引可能不同；可粘贴模型链接或编号定位。作者使用个人主页的用户名。",
                    style = MiuixTheme.textStyles.footnote2, color = colors.onSecondaryContainer)
                (state.notice ?: state.error?.takeIf { state.items.isNotEmpty() })?.let { message ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(message, style = MiuixTheme.textStyles.footnote2,
                            color = colors.onSecondaryContainer,
                            modifier = Modifier.weight(1f))
                        if (state.error != null) ModelAction("重试", onClick = onRetry)
                    }
                }
            }
        }
        if (state.items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (state.loading) "正在搜索模型…" else state.error ?:
                    if (state.hasNextPage) "本页没有符合筛选的模型，仍可继续加载" else "没有符合条件的模型",
                    style = MiuixTheme.textStyles.body2,
                    color = if (state.error == null) colors.onSecondaryContainer else colors.error)
                if (state.error != null) ModelAction("重试", onClick = onRetry)
            }
        }
        items(state.items, key = { it.id }) { item ->
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
        if (state.items.isNotEmpty() || state.hasNextPage) item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (state.hasNextPage) ModelAction(when {
                    state.loading -> "正在加载…"; state.error != null -> "加载失败，重试"
                    else -> "加载更多 · 已显示 ${state.items.size}"
                }, enabled = !state.loading, onClick = if (state.error != null) onRetry else onMore)
                else Text("已显示 ${state.items.size} 个模型", style = MiuixTheme.textStyles.footnote2,
                    color = colors.onSecondaryContainer, modifier = Modifier.padding(12.dp))
            }
        }
    }
}
