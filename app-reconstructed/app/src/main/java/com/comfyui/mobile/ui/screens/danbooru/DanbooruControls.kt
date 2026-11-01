package com.comfyui.mobile.ui.screens.danbooru

import androidx.compose.foundation.background
import com.comfyui.mobile.ui.designsystem.PocketTokens
import com.comfyui.mobile.ui.designsystem.PocketSegmented
import com.comfyui.mobile.ui.designsystem.PocketSegmentedHeight
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comfyui.mobile.danbooru.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Search
import com.comfyui.mobile.ui.components.rememberDialogDismiss
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Search and the rating filter share one line: rating narrows the search, it is not a sort.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(
                input, onInput, label = "搜索标签", useLabelAsPlaceholder = true,
                // Same fill as the sort track and rating pill (the default is white in the light theme).
                colors = TextFieldDefaults.textFieldColors(backgroundColor = scheme.surfaceContainerHigh),
                singleLine = true, textStyle = MiuixTheme.textStyles.body2,
                insideMargin = DpSize(16.dp, 10.dp), cornerRadius = PocketSegmentedHeight / 2,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                trailingIcon = {
                    IconButton(onClick = onSearch, minHeight = 36.dp, minWidth = 36.dp) {
                        Icon(MiuixIcons.Search, contentDescription = "搜索", modifier = Modifier.size(20.dp), tint = scheme.primary)
                    }
                },
                modifier = Modifier.weight(1f).heightIn(min = PocketSegmentedHeight)
            )
            DanbooruRatingFilter(query.filter, onFilter)
        }
        val sorts = listOf(DanbooruSort.LATEST, DanbooruSort.HOT, DanbooruSort.RANKING, DanbooruSort.SCORE, DanbooruSort.FAVORITES) +
            if (query.sort == DanbooruSort.CUSTOM) listOf(DanbooruSort.CUSTOM) else emptyList()
        PocketSegmented(sorts.map { if (it == DanbooruSort.CUSTOM) "自定义" else it.title }, sorts.indexOf(query.sort),
            { if (sorts[it] != DanbooruSort.CUSTOM) onSort(sorts[it]) },
            Modifier.fillMaxWidth().testTag("danbooru-browse-filters"))
        if (query.sort == DanbooruSort.RANKING) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val periods = RankingPeriod.entries
                PocketSegmented(periods.map { it.title }, periods.indexOf(query.rankingPeriod), { onPeriod(periods[it]) },
                    Modifier.weight(1f))
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
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(query.rankingPeriod.label(query.rankingDate) + " · 站点日期",
                    style = MiuixTheme.textStyles.footnote2, color = scheme.onSurfaceSecondary, modifier = Modifier.weight(1f))
                if (query.rankingPeriod.start(query.rankingDate) != query.rankingPeriod.start(danbooruToday()))
                    DanbooruInlineAction("回到本期") { onDate(danbooruToday()) }
            }
        }
    }
}

@Composable
internal fun DanbooruRatingFilter(filter: ContentFilter, onFilter: (ContentFilter) -> Unit) {
    var filtersOpen by remember { mutableStateOf(false) }
    DanbooruBrowseChip(filter.title, false, dropdown = true,
        modifier = Modifier.testTag("danbooru-rating-filter")) { filtersOpen = true }
    if (filtersOpen) {
        val dismissal = rememberDialogDismiss { filtersOpen = false }
        OverlayDialog(
            show = dismissal.show, modifier = dismissal.modifier, title = "内容评级",
            onDismissRequest = dismissal::dismiss, onDismissFinished = dismissal::onDismissFinished
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ContentFilter.entries.filter { it != ContentFilter.CUSTOM }.forEach { value ->
                    DanbooruPill(value.title, filter == value) {
                        if (dismissal.show) { onFilter(value); dismissal.dismiss() }
                    }
                }
            }
        }
    }
}

@Composable
private fun DanbooruBrowseChip(
    text: String, selected: Boolean, dropdown: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit
) {
    val scheme = MiuixTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(
        if (pressed) scheme.primary.copy(alpha = 0.12f) else scheme.surfaceContainerHigh, label = "danbooru-filter-state")
    // Same pill and fill as the search field and the sort track beside it.
    val touch = if (dropdown) Modifier.clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
        else Modifier.selectable(selected = selected, interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
    Row(modifier.heightIn(min = PocketSegmentedHeight).widthIn(min = 48.dp).clip(PocketTokens.Shape.Pill)
            .background(background).then(touch).padding(start = 14.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text, style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium,
            color = if (selected) scheme.primary else scheme.onSurface, maxLines = 1)
        if (dropdown) Icon(Icons.Default.ArrowDropDown, contentDescription = null,
            modifier = Modifier.size(18.dp), tint = scheme.onSurfaceSecondary)
    }
}

@Composable
internal fun DanbooruPill(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    Box(
        modifier.clip(PocketTokens.Shape.Medium)
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
        cornerRadius = PocketTokens.Radius.Medium, insideMargin = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
        textStyle = MiuixTheme.textStyles.footnote1,
        colors = if (primary) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors())
}

@Composable
internal fun DanbooruInlineAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.clip(PocketTokens.Shape.Medium).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .heightIn(min = 40.dp).padding(horizontal = 10.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.4f))
    }
}
