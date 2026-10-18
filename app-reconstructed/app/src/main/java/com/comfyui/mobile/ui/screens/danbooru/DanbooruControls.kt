package com.comfyui.mobile.ui.screens.danbooru

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.comfyui.mobile.danbooru.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun DanbooruBrowseControls(
    input: String, onInput: (String) -> Unit, query: DanbooruQuery,
    onSearch: () -> Unit, onSort: (DanbooruSort) -> Unit,
    onFilter: (ContentFilter) -> Unit, onPeriod: (RankingPeriod) -> Unit,
    onDate: (java.time.LocalDate) -> Unit
) {
    val scheme = MiuixTheme.colorScheme
    var filtersOpen by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextField(
            input, onInput, label = "搜索标签 / Danbooru 语法", useLabelAsPlaceholder = true,
            singleLine = true, textStyle = MiuixTheme.textStyles.body2,
            insideMargin = DpSize(14.dp, 10.dp), cornerRadius = 16.dp,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            trailingIcon = {
                IconButton(onClick = onSearch) {
                    Icon(MiuixIcons.Search, contentDescription = "搜索", modifier = Modifier.size(20.dp), tint = scheme.primary)
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(DanbooruSort.LATEST, DanbooruSort.HOT, DanbooruSort.RANKING, DanbooruSort.SCORE, DanbooruSort.FAVORITES).forEach {
                    DanbooruPill(it.title, query.sort == it) { onSort(it) }
                }
                if (query.sort == DanbooruSort.CUSTOM) DanbooruPill("自定义", true) {}
            }
            DanbooruPill(query.filter.title + " ▾", false) { filtersOpen = true }
        }
        if (query.sort == DanbooruSort.RANKING) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RankingPeriod.entries.forEach { value ->
                    DanbooruPill(value.title, query.rankingPeriod == value) { onPeriod(value) }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onDate(query.rankingPeriod.shift(query.rankingDate, -1)) }) {
                    Icon(MiuixIcons.Back, contentDescription = "上一期", modifier = Modifier.size(16.dp))
                }
                val canAdvance = query.rankingPeriod.start(query.rankingDate) < query.rankingPeriod.start(danbooruToday())
                IconButton(
                    enabled = canAdvance,
                    onClick = { onDate(query.rankingPeriod.shift(query.rankingDate, 1)) }
                ) {
                    Icon(MiuixIcons.Back, contentDescription = "下一期", modifier = Modifier.size(16.dp).rotate(180f),
                        tint = scheme.onSurface.copy(alpha = if (canAdvance) 1f else 0.25f))
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(query.rankingPeriod.label(query.rankingDate) + " · 站点日期",
                    style = MiuixTheme.textStyles.footnote2, color = scheme.onSurfaceSecondary, modifier = Modifier.weight(1f))
                if (query.rankingPeriod.start(query.rankingDate) != query.rankingPeriod.start(danbooruToday()))
                    DanbooruInlineAction("回到本期") { onDate(danbooruToday()) }
            }
        }
    }
    if (filtersOpen) OverlayDialog(show = true, title = "内容评级", onDismissRequest = { filtersOpen = false }) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ContentFilter.entries.filter { it != ContentFilter.CUSTOM }.forEach { filter ->
                DanbooruPill(filter.title, query.filter == filter) { onFilter(filter); filtersOpen = false }
            }
        }
    }
}

@Composable
internal fun DanbooruPill(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    Box(
        modifier.clip(RoundedCornerShape(12.dp))
            .background(if (selected) scheme.primary.copy(alpha = 0.14f) else scheme.surfaceContainerHigh)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .defaultMinSize(minHeight = 40.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MiuixTheme.textStyles.footnote1,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) scheme.primary else scheme.onSurface, maxLines = 1)
    }
}

@Composable
internal fun DanbooruSmallAction(
    text: String, modifier: Modifier = Modifier, primary: Boolean = false,
    enabled: Boolean = true, onClick: () -> Unit
) {
    TextButton(text, onClick, modifier = modifier, enabled = enabled, minHeight = 40.dp, minWidth = 0.dp,
        cornerRadius = 12.dp, insideMargin = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
        textStyle = MiuixTheme.textStyles.footnote1,
        colors = if (primary) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors())
}

@Composable
internal fun DanbooruInlineAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .heightIn(min = 40.dp).padding(horizontal = 10.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.4f))
    }
}
