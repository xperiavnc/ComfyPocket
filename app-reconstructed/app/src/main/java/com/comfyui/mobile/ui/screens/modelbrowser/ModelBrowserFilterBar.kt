package com.comfyui.mobile.ui.screens.modelbrowser

import com.comfyui.mobile.ui.designsystem.PocketIcons

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
import com.comfyui.mobile.ui.designsystem.PocketButtonDefaults as ButtonDefaults
import com.comfyui.mobile.ui.designsystem.PocketCard as Card
import com.comfyui.mobile.ui.designsystem.PocketCardDefaults as CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import com.comfyui.mobile.ui.designsystem.PocketTextButton as TextButton


import com.comfyui.mobile.ui.designsystem.PocketDialog as OverlayDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.FlowRow

/** Shared resource-type tab row (大模型 / UNet / GGUF / LoRA). */
@Composable
fun ModelBrowserTypeTabs(
    selectedType: String,
    onTypeSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MODEL_RESOURCE_TYPE_TABS.forEach { (key, label) ->
            FilterChip(selected = selectedType == key, onClick = { onTypeSelected(key) },
                modifier = Modifier.heightIn(min = 48.dp), label = { Text(label) })
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
    val scheme = MaterialTheme.colorScheme
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
                .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = PocketIcons.Settings,
                    contentDescription = "管理标签",
                    tint = scheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = "管理",
                    style = MaterialTheme.typography.bodySmall,
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
    FilterChip(selected = selected, onClick = onClick, label = { Text(text) },
        modifier = Modifier.heightIn(min = 48.dp))
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
    val scheme = MaterialTheme.colorScheme

    OverlayDialog(
        show = true,
        title = title,
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 500.dp)
        ) {
            Text(
                text = "点击标签直接以此筛选；打开右侧开关可在上方胶囊栏常驻展示。",
                style = MaterialTheme.typography.bodySmall,
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
                            style = MaterialTheme.typography.labelLarge,
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
                                                onFilterTagSelected(tag)
                                                onDismiss()
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
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                                ),
                                                color = if (isSelected) scheme.primary else scheme.onSurface
                                            )
                                            if (isSelected) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = "(当前选中)",
                                                    style = MaterialTheme.typography.labelSmall,
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
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
