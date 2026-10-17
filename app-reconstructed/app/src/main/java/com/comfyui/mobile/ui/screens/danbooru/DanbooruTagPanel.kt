package com.comfyui.mobile.ui.screens.danbooru

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.comfyui.mobile.danbooru.*
import com.comfyui.mobile.ui.designsystem.PocketCard as Card
import com.comfyui.mobile.ui.designsystem.PocketCardDefaults as CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch

/** Original names remain the selection/clipboard keys; display decoration never enters prompts. */
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
    var optionsOpen by rememberSaveable(post.id) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val options = TagCopyOptions(mode, spaces, escape)
    val promptGroups = setOf(TagGroup.GENERAL, TagGroup.CHARACTER) +
        listOfNotNull(TagGroup.ARTIST.takeIf { artists }, TagGroup.COPYRIGHT.takeIf { copyright }, TagGroup.META.takeIf { meta })
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("标签与提示词", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("点按复制 · 长按多选", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            TagCopyMode.entries.forEach { value -> DanbooruPill(value.title, mode == value) { mode = value } }
            Spacer(Modifier.weight(1f))
            DanbooruPill("选项", optionsOpen) { optionsOpen = !optionsOpen }
        }
        if (optionsOpen) Card(colors = CardDefaults.defaultColors(color = scheme.surfaceContainerHigh), cornerRadius = 14.dp) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DanbooruSettingSwitch("下划线转空格", spaces) { spaces = it }
                DanbooruSettingSwitch("括号转义", escape) { escape = it }
                Text("快捷提示词默认包含通用与角色，可附加：", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DanbooruPill("画师", artists) { artists = !artists }
                    DanbooruPill("作品", copyright) { copyright = !copyright }
                    DanbooruPill("元信息", meta) { meta = !meta }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selected.isEmpty()) {
                DanbooruSmallAction("复制提示词", Modifier.weight(1f), primary = true) {
                    onCopy(TagCopyFormatter.format(post, options.copy(mode = TagCopyMode.PROMPT), promptGroups))
                }
                DanbooruSmallAction("复制全部", Modifier.weight(1f)) { onCopy(TagCopyFormatter.format(post, options)) }
            } else {
                DanbooruSmallAction("复制选中 ${selected.size}", Modifier.weight(1f), primary = true) {
                    onCopy(TagCopyFormatter.format(post, options, selected = selected))
                }
                DanbooruSmallAction("清空选择", Modifier.weight(1f), onClick = onClear)
            }
        }
        TagGroup.entries.forEach { group ->
            val tags = post.tags[group].orEmpty()
            if (tags.isNotEmpty()) {
                var expanded by rememberSaveable(post.id, group) { mutableStateOf(true) }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { expanded = !expanded }
                            .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${if (expanded) "▾" else "▸"}  ${group.title}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text("  ${tags.size}", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                        DanbooruInlineAction("全选${group.title}") { onGroup(group) }
                    }
                    if (expanded) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        tags.forEach { tag ->
                            val checked = tag in selected
                            Box(Modifier.clip(RoundedCornerShape(8.dp))
                                .background(if (checked) scheme.primary.copy(alpha = 0.16f) else scheme.surfaceContainerHigh)
                                .semantics { this.selected = checked }
                                .combinedClickable(
                                    onClick = {
                                        if (selected.isNotEmpty()) onSelect(tag)
                                        else onCopy(TagCopyFormatter.format(post, options, selected = setOf(tag)))
                                    }, onLongClick = { onSelect(tag) })
                                .padding(horizontal = 10.dp, vertical = 8.dp)) {
                                Text((if (checked) "✓ " else "") + tag, style = MaterialTheme.typography.bodySmall,
                                    color = if (checked) scheme.primary else scheme.onSurface)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DanbooruSettingSwitch(
    title: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
