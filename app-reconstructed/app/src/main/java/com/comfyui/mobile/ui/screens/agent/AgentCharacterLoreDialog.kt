package com.comfyui.mobile.ui.screens.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comfyui.mobile.model.CharacterLore
import com.comfyui.mobile.ui.components.DialogFormColumn
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Searchable library. Editing is a separate, staged form with a fixed footer. */
@Composable
fun AgentCharacterLoreDialog(
    show: Boolean,
    characterLores: List<CharacterLore>,
    initialLore: CharacterLore? = null,
    onDismissRequest: () -> Unit,
    onSaveLore: (CharacterLore) -> Unit,
    onToggleLore: (id: String) -> Unit,
    onRemoveLore: (id: String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var target by remember { mutableStateOf<CharacterLore?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var isNew by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CharacterLore?>(null) }
    var deleteOpen by remember { mutableStateOf(false) }
    LaunchedEffect(show, initialLore) {
        deleteOpen = false
        if (show) {
            query = ""
            target = initialLore
            editorOpen = initialLore != null
            isNew = false
        } else {
            editorOpen = false
        }
    }
    val scheme = MiuixTheme.colorScheme
    OverlayDialog(show = show, title = "Agent 角色档案库", onDismissRequest = onDismissRequest) {
        DialogFormColumn(
            scrollMaxFraction = .58f, scrollMaxCap = 500.dp,
            footer = {
                TextButton("关闭", onClick = onDismissRequest,
                    modifier = Modifier.fillMaxWidth().testTag("lore-library-close"))
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("角色名称和别名只匹配 AI 对话，不是绘画标签。服装预设按对话触发词自动匹配；绘画标签与识别词分开保存。",
                    style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(query, { query = it }, modifier = Modifier.weight(1f).testTag("lore-search"),
                        label = "对话名称 / 别名", useLabelAsPlaceholder = true, singleLine = true)
                    Button(onClick = {
                        target = CharacterLore(name = "", triggerWords = "")
                        isNew = true
                        editorOpen = true
                    }, modifier = Modifier.heightIn(min = 44.dp).testTag("lore-add"),
                        colors = ButtonDefaults.buttonColorsPrimary()) { Text("添加") }
                }
                Text("已登记 ${characterLores.size} · 可用 ${characterLores.count { it.isEnabled }}",
                    style = MiuixTheme.textStyles.footnote1)
                val visible = characterLores.filter {
                    query.isBlank() || (listOf(it.name) + it.aliases.orEmpty()).any { name ->
                        name.contains(query.trim(), ignoreCase = true)
                    }
                }
                if (visible.isEmpty()) {
                    Text(if (characterLores.isEmpty()) "还没有角色，点击添加开始登记。" else "没有匹配的角色",
                        color = scheme.onSurfaceVariantSummary, modifier = Modifier.padding(vertical = 16.dp))
                }
                visible.forEach { lore ->
                    Card(
                        modifier = Modifier.fillMaxWidth().testTag("lore-card-${lore.id}"),
                        cornerRadius = 16.dp, insideMargin = PaddingValues(12.dp),
                        colors = CardDefaults.defaultColors(color = scheme.surfaceContainerHigh),
                        onClick = { target = lore; isNew = false; editorOpen = true }
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(lore.name, fontWeight = FontWeight.SemiBold, maxLines = 2,
                                        overflow = TextOverflow.Ellipsis)
                                    Text(
                                        if (!lore.isEnabled) "已停用" else "调用方式在画风与辅助参数设置",
                                        style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                                }
                                Switch(lore.isEnabled, { onToggleLore(lore.id) },
                                    modifier = Modifier.testTag("lore-enabled-${lore.id}"))
                            }
                            val outfit = lore.outfits.orEmpty().firstOrNull { it.id == lore.defaultOutfitId }
                            Text("${lore.outfits.orEmpty().size} 套服装 · ${outfit?.let { "默认：${it.name}" } ?: "未指定默认"}",
                                style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton("编辑", onClick = { target = lore; isNew = false; editorOpen = true },
                                    modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("lore-edit-${lore.id}"))
                                TextButton("删除", onClick = { deleting = lore; deleteOpen = true },
                                    modifier = Modifier.heightIn(min = 44.dp).testTag("lore-delete-${lore.id}"))
                            }
                        }
                    }
                }
            }
        }
    }
    target?.let { lore ->
        AgentCharacterEditor(show = show && editorOpen, lore = lore, isNew = isNew,
            otherNames = characterLores.filter { it.id != lore.id }.map { it.name },
            onDismiss = { editorOpen = false },
            onSave = { onSaveLore(it); editorOpen = false })
    }
    deleting?.let { lore ->
        OverlayDialog(show = show && deleteOpen, title = "删除角色「${lore.name}」",
            onDismissRequest = { deleteOpen = false }) {
            Text("同时删除该角色保存的服装预设，不影响模型文件。", color = scheme.onSurfaceVariantSummary)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton("取消", { deleteOpen = false }, Modifier.weight(1f))
                TextButton("删除角色", { onRemoveLore(lore.id); deleteOpen = false },
                    Modifier.weight(1f).testTag("lore-delete-confirm"))
            }
        }
    }
}
