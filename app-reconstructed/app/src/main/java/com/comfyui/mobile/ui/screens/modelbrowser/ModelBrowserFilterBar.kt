package com.comfyui.mobile.ui.screens.modelbrowser

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Settings
import com.comfyui.mobile.ui.components.rememberDialogDismiss
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Shared resource-type tab row (大模型 / UNet / GGUF / LoRA). */
@Composable
fun ModelBrowserTypeTabs(
    selectedType: String,
    onTypeSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MODEL_RESOURCE_TYPE_TABS.forEach { (typeKey, label) ->
            val isSelected = selectedType == typeKey
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isSelected) scheme.primary else scheme.surfaceContainerHigh
                    )
                    .clickable { onTypeSelected(typeKey) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MiuixTheme.textStyles.body2.copy(
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                    ),
                    color = if (isSelected) scheme.onPrimary else scheme.onSurface
                )
            }
        }
    }
}

/**
 * Horizontal capsule bar: 全部 + optional orphan selected tag + pinned tags + 管理.
 * Callers own reload side-effects via [onFilterTagChange].
 */
@Composable
fun ModelBrowserFilterChipBar(
    selectedFilterTag: String,
    pinnedFilterTags: List<String>,
    onFilterTagChange: (String) -> Unit,
    onManageClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val isAllSelected = selectedFilterTag == "全部"
        FilterCapsule(
            text = "全部",
            selected = isAllSelected,
            onClick = { onFilterTagChange("全部") }
        )

        if (selectedFilterTag != "全部" && !pinnedFilterTags.contains(selectedFilterTag)) {
            FilterCapsule(
                text = selectedFilterTag,
                selected = true,
                onClick = { onFilterTagChange("全部") }
            )
        }

        pinnedFilterTags.forEach { tag ->
            val isTagSelected = selectedFilterTag == tag
            FilterCapsule(
                text = tag,
                selected = isTagSelected,
                onClick = { onFilterTagChange(if (isTagSelected) "全部" else tag) }
            )
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(scheme.surfaceContainerHigh)
                .clickable(onClick = onManageClick)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = MiuixIcons.Settings,
                    contentDescription = "管理标签",
                    tint = scheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = "管理",
                    style = MiuixTheme.textStyles.footnote1,
                    color = scheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun FilterCapsule(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val scheme = MiuixTheme.colorScheme
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (selected) scheme.primary.copy(alpha = 0.15f)
                else scheme.surfaceContainerHigh
            )
            .border(
                width = 1.dp,
                color = if (selected) scheme.primary else Color.Transparent,
                shape = RoundedCornerShape(999.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            ),
            color = if (selected) scheme.primary else scheme.onSurface
        )
    }
}

/**
 * Manage pinned filter tags dialog.
 * [title] differs by call site (集市 vs 云端对话框) — keep Chinese copy as-is at each site.
 * Selecting a tag calls [onFilterTagSelected] then dismisses; pin toggles persist immediately.
 */
@Composable
fun ModelBrowserManageFilterDialog(
    show: Boolean,
    title: String,
    selectedFilterTag: String,
    pinnedFilterTags: SnapshotStateList<String>,
    onFilterTagSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    context: Context = LocalContext.current
) {
    if (!show) return
    val dismissal = rememberDialogDismiss(onDismiss)
    val scheme = MiuixTheme.colorScheme

    OverlayDialog(
        show = dismissal.show,
        modifier = dismissal.modifier,
        title = title,
        onDismissRequest = dismissal::dismiss,
        onDismissFinished = dismissal::onDismissFinished
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 500.dp)
        ) {
            Text(
                text = "点击标签直接以此筛选；打开右侧开关可在上方胶囊栏常驻展示。",
                style = MiuixTheme.textStyles.footnote1,
                color = scheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ALL_BASE_MODEL_GROUPS.forEach { group ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = group.categoryName,
                            style = MiuixTheme.textStyles.subtitle,
                            fontWeight = FontWeight.SemiBold,
                            color = scheme.primary
                        )
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 12.dp,
                            colors = CardDefaults.defaultColors(color = scheme.surfaceContainerHigh)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                group.items.forEachIndexed { index, tag ->
                                    val isPinned = pinnedFilterTags.contains(tag)
                                    val isSelected = selectedFilterTag == tag
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (!dismissal.show) return@clickable
                                                onFilterTagSelected(tag)
                                                dismissal.dismiss()
                                            }
                                            .padding(vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(
                                                text = tag,
                                                style = MiuixTheme.textStyles.body2.copy(
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                                ),
                                                color = if (isSelected) scheme.primary else scheme.onSurface
                                            )
                                            if (isSelected) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = "(当前选中)",
                                                    style = MiuixTheme.textStyles.footnote2,
                                                    color = scheme.primary
                                                )
                                            }
                                        }
                                        Switch(
                                            checked = isPinned,
                                            onCheckedChange = { checked ->
                                                if (checked) {
                                                    if (!pinnedFilterTags.contains(tag)) {
                                                        pinnedFilterTags.add(tag)
                                                    }
                                                } else {
                                                    pinnedFilterTags.remove(tag)
                                                }
                                                savePinnedFilterTags(context, pinnedFilterTags.toList())
                                            }
                                        )
                                    }
                                    if (index < group.items.size - 1) {
                                        Spacer(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(0.5.dp)
                                                .background(scheme.outline.copy(alpha = 0.15f))
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    text = "恢复默认",
                    onClick = {
                        pinnedFilterTags.clear()
                        pinnedFilterTags.addAll(DEFAULT_FILTER_TAGS)
                        savePinnedFilterTags(context, pinnedFilterTags.toList())
                    },
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    text = "完成",
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = dismissal::dismiss,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
