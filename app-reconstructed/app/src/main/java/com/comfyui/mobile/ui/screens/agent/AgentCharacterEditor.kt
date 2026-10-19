package com.comfyui.mobile.ui.screens.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comfyui.mobile.model.CharacterLore
import com.comfyui.mobile.model.CharacterOutfit
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

@Composable
internal fun AgentCharacterEditor(
    show: Boolean, lore: CharacterLore, isNew: Boolean, otherNames: List<String>,
    onDismiss: () -> Unit, onSave: (CharacterLore) -> Unit
) {
    var name by remember(lore.id) { mutableStateOf(lore.name) }
    var aliases by remember(lore.id) { mutableStateOf(lore.aliases.orEmpty().joinToString("，")) }
    var words by remember(lore.id) { mutableStateOf(lore.triggerWords) }
    var fixed by remember(lore.id) { mutableStateOf(lore.fixedTriggerWords.orEmpty()) }
    var pinned by remember(lore.id) { mutableStateOf(lore.alwaysActive == true) }
    var outfits by remember(lore.id) { mutableStateOf(lore.outfits.orEmpty()) }
    var defaultId by remember(lore.id) { mutableStateOf(lore.defaultOutfitId) }
    var advanced by remember(lore.id) { mutableStateOf(false) }
    var fallbackExpanded by remember(lore.id) { mutableStateOf(false) }
    var error by remember(lore.id) { mutableStateOf<String?>(null) }
    var outfitTarget by remember(lore.id) { mutableStateOf<CharacterOutfit?>(null) }
    var outfitOpen by remember(lore.id) { mutableStateOf(false) }
    LaunchedEffect(show, lore) {
        if (show) {
            name = lore.name; aliases = lore.aliases.orEmpty().joinToString("，"); words = lore.triggerWords
            fixed = lore.fixedTriggerWords.orEmpty(); pinned = lore.alwaysActive == true
            outfits = lore.outfits.orEmpty(); defaultId = lore.defaultOutfitId
            advanced = fixed.isNotBlank() || pinned
            fallbackExpanded = false
            error = null; outfitOpen = false
        }
    }
    val scheme = MiuixTheme.colorScheme
    OverlayDialog(show = show, title = if (isNew) "添加角色" else "编辑角色与服装", onDismissRequest = onDismiss) {
        DialogFormColumn(
            scrollMaxFraction = .55f, scrollMaxCap = 480.dp,
            footer = {
                error?.let { Text(it, color = scheme.error, style = MiuixTheme.textStyles.footnote1,
                    modifier = Modifier.padding(top = 6.dp).testTag("lore-edit-error")) }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton("取消", onClick = onDismiss, modifier = Modifier.weight(1f).testTag("lore-edit-cancel"))
                    Button(onClick = {
                        error = when {
                            name.isBlank() || words.isBlank() -> "请填写角色对话识别名称与绘画外貌参考"
                            otherNames.any { it.equals(name.trim(), ignoreCase = true) } -> "角色名已存在，请使用不同名称"
                            com.comfyui.mobile.agent.AgentCharacterPolicy.duplicateOutfitDialogueTriggers(outfits).isNotEmpty() ->
                                "不同服装预设不能使用相同的对话触发词"
                            else -> null
                        }
                        if (error == null) onSave(lore.copy(
                            name = name.trim(), triggerWords = words.trim(),
                            aliases = aliases.split(Regex("[,，、;；\\n]")).map(String::trim).filter(String::isNotBlank).distinct(),
                            fixedTriggerWords = fixed.trim(), alwaysActive = pinned,
                            outfits = outfits, defaultOutfitId = defaultId
                        ))
                    }, modifier = Modifier.weight(1f).testTag("lore-edit-save"),
                        colors = ButtonDefaults.buttonColorsPrimary()) { Text("保存角色") }
                }
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("角色名称和别名只用于识别对话中的角色，不会作为绘画标签写入提示词。",
                    style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                LoreFieldLabel("AI 对话识别名称")
                TextField(name, { name = it; error = null }, label = "对话中用于匹配的角色名称", useLabelAsPlaceholder = true,
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("lore-name"))
                LoreFieldLabel("AI 对话别名 / 昵称")
                TextField(aliases, { aliases = it }, label = "只用于对话匹配，多个用逗号分隔", useLabelAsPlaceholder = true,
                    minLines = 1, maxLines = 2, modifier = Modifier.fillMaxWidth().testTag("lore-aliases"))
                LoreFieldLabel("角色绘画外貌参考")
                TextField(words, { words = it; error = null }, label = "外貌描述或绘画标签（不含名字、别名和服装）",
                    useLabelAsPlaceholder = true, minLines = 2, maxLines = 4,
                    modifier = Modifier.fillMaxWidth().testTag("lore-words"))
                Text("仅这里的内容提供给 AI 作为角色外貌参考。名字 / 别名只触发角色匹配，不会复制到正向或反向提示词。服装单独设为预设。",
                    style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                TextButton(if (advanced) "收起调用设置" else "调用与漏词补齐",
                    { advanced = !advanced }, modifier = Modifier.fillMaxWidth().testTag("lore-advanced"))
                if (advanced) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("绘图时始终启用")
                            Text("不必点名；停用角色后不生效",
                                style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                        }
                        Switch(pinned, { pinned = it }, modifier = Modifier.testTag("lore-pinned"))
                    }
                    LoreFieldLabel("固定绘画标签 · 漏写时自动补齐")
                    TextField(fixed, { fixed = it }, label = "必须保留的绘画标签（可选）", useLabelAsPlaceholder = true,
                        minLines = 2, maxLines = 3, modifier = Modifier.fillMaxWidth().testTag("lore-fixed-words"))
                    Text("只填必须原样出现在正向提示词中的模型 / LoRA 标签。单角色漏写时自动补齐；不要填角色名称、别名或整段外貌。",
                        style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("服装预设 · ${outfits.size}", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    TextButton("添加服装", {
                        outfitTarget = CharacterOutfit(name = "", triggerWords = "")
                        outfitOpen = true
                    }, modifier = Modifier.testTag("lore-outfit-add"))
                }
                Text("对话命中预设名称或下方 AI 对话触发词时，App 自动选中预设并将绘画标签交给 AI；续改沿用已选预设。默认服装仅作未命中时的回退。",
                    style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                outfits.forEach { outfit ->
                    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 14.dp, insideMargin = PaddingValues(10.dp),
                        colors = CardDefaults.defaultColors(color = scheme.surfaceContainerHigh)) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(outfit.name, fontWeight = FontWeight.Medium)
                            Text("对话触发词：${outfit.dialogueTriggers.orEmpty().ifEmpty { listOf(outfit.name) }.joinToString("、")}",
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                            Text("绘画标签：${outfit.triggerWords}", maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton("编辑", { outfitTarget = outfit; outfitOpen = true },
                                    Modifier.weight(1f).testTag("lore-outfit-edit-${outfit.id}"))
                                TextButton("移除", {
                                    outfits = outfits.filterNot { it.id == outfit.id }
                                    if (defaultId == outfit.id) defaultId = null
                                }, Modifier.weight(1f).testTag("lore-outfit-delete-${outfit.id}"))
                            }
                        }
                    }
                }
                if (outfits.isNotEmpty()) {
                    TextButton(if (fallbackExpanded) "收起默认回退设置" else "默认服装回退 · 可选",
                        { fallbackExpanded = !fallbackExpanded },
                        Modifier.fillMaxWidth().testTag("lore-outfit-fallback"))
                    if (fallbackExpanded) {
                        Text("这不是本轮手选。首次绘图未命中对话触发词时才使用默认；续改仍沿用上一套。",
                            style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                        LoreDefaultChoice("不指定默认服装", defaultId == null,
                            Modifier.testTag("lore-outfit-none")) { defaultId = null }
                        outfits.forEach { outfit ->
                            LoreDefaultChoice(outfit.name, defaultId == outfit.id,
                                Modifier.testTag("lore-outfit-default-${outfit.id}")) { defaultId = outfit.id }
                        }
                    }
                    Text("新增、编辑、移除后，点击下方“保存角色”才会保存。",
                        style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                }
            }
        }
    }
    outfitTarget?.let { outfit ->
        AgentOutfitEditor(show && outfitOpen, outfit, outfits.filter { it.id != outfit.id }.map { it.name },
            onDismiss = { outfitOpen = false }, onSave = { saved ->
                outfits = if (outfits.any { it.id == saved.id }) outfits.map { if (it.id == saved.id) saved else it }
                    else outfits + saved
                outfitOpen = false
            })
    }
}

@Composable
private fun LoreFieldLabel(text: String) {
    Text(text, style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

@Composable
private fun LoreDefaultChoice(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    Row(modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
        .background(if (selected) scheme.primaryContainer else scheme.surface)
        .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (selected) "●" else "○", color = if (selected) scheme.primary else scheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(end = 8.dp))
        Text(label, color = if (selected) scheme.primary else scheme.onSurface,
            style = MiuixTheme.textStyles.body2)
    }
}

@Composable
private fun AgentOutfitEditor(
    show: Boolean, outfit: CharacterOutfit, otherNames: List<String>, onDismiss: () -> Unit,
    onSave: (CharacterOutfit) -> Unit
) {
    var name by remember(outfit.id) { mutableStateOf(outfit.name) }
    var dialogueTriggers by remember(outfit.id) { mutableStateOf(outfit.dialogueTriggers.orEmpty().joinToString("，")) }
    var words by remember(outfit.id) { mutableStateOf(outfit.triggerWords) }
    var error by remember(outfit.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(show, outfit) {
        if (show) {
            name = outfit.name
            dialogueTriggers = outfit.dialogueTriggers.orEmpty().joinToString("，")
            words = outfit.triggerWords
            error = null
        }
    }
    OverlayDialog(show = show, title = "编辑服装预设", onDismissRequest = onDismiss) {
        DialogFormColumn(footer = {
            error?.let { Text(it, color = MiuixTheme.colorScheme.error, modifier = Modifier.testTag("outfit-error")) }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton("取消", onDismiss, Modifier.weight(1f).testTag("outfit-cancel"))
                Button(onClick = {
                    error = when {
                        name.isBlank() || words.isBlank() -> "请填写预设名称与绘画标签"
                        otherNames.any { it.equals(name.trim(), ignoreCase = true) } -> "服装名称已存在"
                        else -> null
                    }
                    if (error == null) onSave(outfit.copy(
                        name = name.trim(),
                        triggerWords = words.trim(),
                        dialogueTriggers = dialogueTriggers.split(Regex("[,，、;；\\n]"))
                            .map(String::trim).filter(String::isNotBlank).distinct()
                    ))
                }, modifier = Modifier.weight(1f).testTag("outfit-save"),
                    colors = ButtonDefaults.buttonColorsPrimary()) { Text("确定") }
            }
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LoreFieldLabel("预设名称")
                TextField(name, { name = it; error = null }, label = "预设名称（也可作为对话匹配词）", useLabelAsPlaceholder = true,
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("outfit-name"))
                LoreFieldLabel("AI 对话触发词")
                TextField(dialogueTriggers, { dialogueTriggers = it }, label = "例如：换成巡演服、穿上演出服", useLabelAsPlaceholder = true,
                    minLines = 1, maxLines = 3, modifier = Modifier.fillMaxWidth().testTag("outfit-dialogue-triggers"))
                Text("对话出现任一词句时，App 自动选中这套；留空时使用预设名称匹配。触发词只用于识别，不会写入提示词。",
                    style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                LoreFieldLabel("本套服装的绘画标签")
                TextField(words, { words = it; error = null }, label = "只填要用于出图的标签", useLabelAsPlaceholder = true,
                    minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth().testTag("outfit-words"))
                Text("预设被自动选中后，AI 使用这组标签绘图；不要把对话触发词、角色名称或身份标签填在这里。",
                    style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}
