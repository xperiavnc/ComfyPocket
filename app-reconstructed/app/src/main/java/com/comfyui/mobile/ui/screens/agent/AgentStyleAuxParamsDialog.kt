package com.comfyui.mobile.ui.screens.agent

import com.comfyui.mobile.ui.feedback.AppToast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comfyui.mobile.model.CharacterLore
import com.comfyui.mobile.model.EditableNode
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll

/**
 * 画风与辅助参数弹窗
 * 1. 画师串与常用候选画师
 * 2. 角色触发词库快捷入口（横向胶囊徽章，单击切换启用/停用，长按直达回填编辑）
 * 3. 辅助参数动态调节面板（使用 AgentAuxParamWidgets 高度适配）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AgentStyleAuxParamsDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    boundArtistNode: EditableNode.TextInput?,
    favoriteArtists: List<String>,
    onSelectArtistNodeClick: () -> Unit,
    onArtistChange: (nodeId: String, key: String, text: String) -> Unit,
    onAddFavoriteArtist: (String) -> Unit,
    onRemoveFavoriteArtist: (String) -> Unit,
    characterLores: List<CharacterLore>,
    onManageCharacterLoresClick: () -> Unit,
    onSelectCharacterLoreToEdit: (CharacterLore) -> Unit,
    onToggleCharacterLore: (String) -> Unit,
    auxDisplayNodes: List<EditableNode>,
    onManageAuxNodesClick: () -> Unit,
    onAuxParamChange: (nodeId: String, key: String, value: Any) -> Unit,
    onResetWorkflowClick: () -> Unit,
    isCloudMode: Boolean = false,
    onBrowseCloudModel: ((nodeId: String, key: String, resourceType: String) -> Unit)? = null
    ) {
    val context = LocalContext.current
    val scheme = MiuixTheme.colorScheme

    OverlayDialog(
        show = show,
        title = "画风与辅助参数",
        onDismissRequest = onDismissRequest
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 440.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
            // 1. 画师串与常用候选画师栏
            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 18.dp,
                colors = CardDefaults.defaultColors(color = scheme.surfaceContainerHigh),
                insideMargin = PaddingValues(12.dp)
    ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
    ) {
                        Text(
                            text = "画师串",
                            style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold)
    )
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(scheme.primary.copy(alpha = 0.12f))
                                .clickable(onClick = onSelectArtistNodeClick)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
                            Text(
                                text = if (boundArtistNode != null) "#${boundArtistNode.nodeId} ${boundArtistNode.title}" else "点击绑定节点",
                                style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp),
                                color = scheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
    )
                            Icon(
                                MiuixIcons.ExpandMore,
                                contentDescription = "切换画师节点",
                                modifier = Modifier.size(14.dp),
                                tint = scheme.primary
    )
                        }
                    }

                    if (boundArtistNode != null) {
                        val artistInteractionSource = remember { MutableInteractionSource() }
                        val isArtistFocused by artistInteractionSource.collectIsFocusedAsState()
                        var artistInput by rememberSaveable(
                            boundArtistNode.nodeId,
                            boundArtistNode.key,
                            stateSaver = TextFieldValue.Saver
    ) {
                            mutableStateOf(TextFieldValue(boundArtistNode.text, TextRange(boundArtistNode.text.length)))
                        }

                        LaunchedEffect(boundArtistNode.text) {
                            if (!isArtistFocused && artistInput.text != boundArtistNode.text) {
                                artistInput = TextFieldValue(boundArtistNode.text, TextRange(boundArtistNode.text.length))
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
                            TextField(
                                value = artistInput,
                                onValueChange = {
                                    artistInput = it
                                    onArtistChange(boundArtistNode.nodeId, boundArtistNode.key, it.text)
                                },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                interactionSource = artistInteractionSource
    )
                            Button(
                                onClick = {
                                    val cur = artistInput.text.trim()
                                    if (cur.isNotBlank()) {
                                        onAddFavoriteArtist(cur)
                                    } else {
                                        AppToast.show(context, "请先在左侧输入画师名")
                                    }
                                },
                                modifier = Modifier.height(42.dp),
                                cornerRadius = 12.dp,
                                colors = ButtonDefaults.buttonColorsPrimary()
    ) {
                                Text("收藏", fontSize = 13.sp)
                            }
                        }

                        Text(
                            text = "常用候选画师 (点击一键填入，点击×删除)",
                            style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp),
                            color = scheme.onSurfaceVariantSummary
    )

                        // 横向滚动候选画师胶囊栏
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
    ) {
                            favoriteArtists.forEach { artist ->
                                val isChosen = artistInput.text.contains(artist, ignoreCase = true)
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (isChosen) scheme.primary.copy(alpha = 0.15f)
                                            else scheme.surface
                                        )
                                        .border(
                                            1.dp,
                                            if (isChosen) scheme.primary else scheme.outline.copy(alpha = 0.2f),
                                            RoundedCornerShape(8.dp)
    )
                                        .clickable {
                                            onArtistChange(boundArtistNode.nodeId, boundArtistNode.key, artist)
                                            artistInput = TextFieldValue(artist, TextRange(artist.length))
                                            AppToast.show(context, "已切换画师: $artist")
                                        }
                                        .padding(start = 8.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
                                    Text(
                                        text = artist,
                                        fontSize = 12.sp,
                                        color = if (isChosen) scheme.primary else scheme.onSurface,
                                        fontWeight = if (isChosen) FontWeight.SemiBold else FontWeight.Normal
    )
                                    Icon(
                                        MiuixIcons.Close,
                                        contentDescription = "删除候选",
                                        modifier = Modifier
                                            .size(13.dp)
                                            .clip(RoundedCornerShape(50))
                                            .clickable {
                                                onRemoveFavoriteArtist(artist)
                                            },
                                        tint = scheme.onSurfaceVariantSummary
    )
                                }
                            }
                        }
                    } else {
                        Text(
                            text = "未绑定画师节点，点击右上角绑定工作流文本节点。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = scheme.onSurfaceVariantSummary
    )
                    }
                }
            }

            // 2. 角色 / LoRA 触发词库
            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 18.dp,
                colors = CardDefaults.defaultColors(color = scheme.surfaceContainerHigh),
                insideMargin = PaddingValues(12.dp)
    ) {
                val enabledCount = remember(characterLores) { characterLores.count { it.isEnabled } }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
                            Text(
                                text = "角色触发词库",
                                style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold)
    )
                            Text(
                                text = "($enabledCount/${characterLores.size})",
                                style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp),
                                color = if (enabledCount > 0) scheme.primary else scheme.onSurfaceVariantSummary
    )
                        }
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(scheme.secondaryContainer)
                                .clickable(onClick = onManageCharacterLoresClick)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
                            Text(
                                text = "管理词库",
                                style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp),
                                color = scheme.primary
    )
                            Icon(
                                MiuixIcons.Edit,
                                contentDescription = "管理词库",
                                modifier = Modifier.size(12.dp),
                                tint = scheme.primary
    )
                        }
                    }

                    if (characterLores.isEmpty()) {
                        Text(
                            text = "暂无角色。点击“管理词库”登记，开启并在对话中提到角色名即可自动触发。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = scheme.onSurfaceVariantSummary
    )
                    } else {
                        Text(
                            text = "💡 单击切换启用/停用，长按修改触发词",
                            style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp),
                            color = scheme.onSurfaceVariantSummary
    )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
    ) {
                            characterLores.forEach { lore ->
                                val active = lore.isEnabled
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(
                                            if (active) scheme.primaryContainer.copy(alpha = 0.85f)
                                            else scheme.surface
                                        )
                                        .border(
                                            1.dp,
                                            if (active) scheme.primary else scheme.outline.copy(alpha = 0.25f),
                                            RoundedCornerShape(50)
                                        )
                                        .combinedClickable(
                                            onClick = { onToggleCharacterLore(lore.id) },
                                            onLongClick = { onSelectCharacterLoreToEdit(lore) }
    )
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center
    ) {
                                    Text(
                                        text = lore.name,
                                        fontSize = 12.sp,
                                        color = if (active) scheme.primary else scheme.onSurfaceVariantSummary,
                                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 120.dp)
    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. 辅助参数区域（支持用户自由勾选显示）
            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 18.dp,
                colors = CardDefaults.defaultColors(color = scheme.surfaceContainerHigh),
                insideMargin = PaddingValues(12.dp)
    ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
    ) {
                        Text(
                            text = "辅助参数调节 (${auxDisplayNodes.size})",
                            style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold)
    )
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(scheme.secondaryContainer)
                                .clickable(onClick = onManageAuxNodesClick)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
                            Text(
                                text = "添加显示",
                                style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp),
                                color = scheme.primary
    )
                            Icon(
                                MiuixIcons.Edit,
                                contentDescription = "添加显示",
                                modifier = Modifier.size(12.dp),
                                tint = scheme.primary
    )
                        }
                    }

                    if (auxDisplayNodes.isEmpty()) {
                        Text(
                            text = "未添加辅助参数。点击右上角“添加显示”挑选常用节点参数。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = scheme.onSurfaceVariantSummary
    )
                    } else {
                        auxDisplayNodes.forEach { node ->
                            AgentAuxParamItem(
                                node = node,
                                isCloudMode = isCloudMode,
                                onBrowseCloudModel = onBrowseCloudModel,
                                onValueChange = onAuxParamChange
    )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
    ) {
                TextButton(
                    text = "重载",
                    onClick = onResetWorkflowClick,
                    modifier = Modifier.weight(1f)
    )
                TextButton(
                    text = "关闭",
                    onClick = onDismissRequest,
                    modifier = Modifier.weight(1f)
    )
            }
        }
    }
}
