package com.comfyui.mobile.ui.screens.danbooru

import androidx.compose.foundation.background
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
        // Lightweight text tabs instead of five tall slabs and a clipped scrolling row.
        // Wrap on small screens/large fonts; every choice remains visible and tappable.
        Row(Modifier.fillMaxWidth().testTag("danbooru-browse-filters"),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Top) {
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                listOf(DanbooruSort.LATEST, DanbooruSort.HOT, DanbooruSort.RANKING, DanbooruSort.SCORE, DanbooruSort.FAVORITES).forEach {
                    DanbooruBrowseChip(it.title, query.sort == it) { onSort(it) }
                }
                if (query.sort == DanbooruSort.CUSTOM) DanbooruBrowseChip("自定义", true) {}
            }
            // Rating always stays on the first sort line; only the sort group may wrap.
            DanbooruRatingFilter(query.filter, onFilter)
        }
        if (query.sort == DanbooruSort.RANKING) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                RankingPeriod.entries.forEach { value ->
                    DanbooruBrowseChip(value.title, query.rankingPeriod == value) { onPeriod(value) }
                }
                Row {
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
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
        if (selected) scheme.primary.copy(alpha = if (pressed) 0.2f else 0.14f)
        else if (pressed) scheme.primary.copy(alpha = 0.08f) else Color.Transparent,
        label = "danbooru-filter-state")
    // A single painted surface replaces the default outer indication plus inner selected slab.
    val touch = if (dropdown) Modifier.clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
        else Modifier.selectable(selected = selected, interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
    Box(modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).then(touch),
        contentAlignment = Alignment.Center) {
        Row(Modifier.clip(RoundedCornerShape(12.dp))
            .background(background)
            .then(if (dropdown) Modifier.border(1.dp, scheme.onSurfaceSecondary.copy(alpha = 0.22f), RoundedCornerShape(12.dp)) else Modifier)
            .padding(horizontal = if (dropdown) 8.dp else 4.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text, style = MiuixTheme.textStyles.footnote2.copy(fontSize = 14.sp),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) scheme.primary else scheme.onSurfaceSecondary, maxLines = 1)
            if (dropdown) Icon(Icons.Default.ArrowDropDown, contentDescription = null,
                modifier = Modifier.size(16.dp), tint = scheme.onSurfaceSecondary)
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
