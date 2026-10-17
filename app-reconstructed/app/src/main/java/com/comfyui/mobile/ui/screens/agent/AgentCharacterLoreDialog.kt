package com.comfyui.mobile.ui.screens.agent

import com.comfyui.mobile.ui.designsystem.PocketIcons

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comfyui.mobile.model.CharacterLore
import com.comfyui.mobile.ui.designsystem.PocketButton as Button
import com.comfyui.mobile.ui.designsystem.PocketButtonDefaults as ButtonDefaults
import com.comfyui.mobile.ui.designsystem.PocketCard as Card
import com.comfyui.mobile.ui.designsystem.PocketCardDefaults as CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import com.comfyui.mobile.ui.designsystem.PocketTextButton as TextButton
import com.comfyui.mobile.ui.designsystem.PocketTextField as TextField



import com.comfyui.mobile.ui.designsystem.PocketDialog as OverlayDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.comfyui.mobile.ui.components.DialogFormColumn

/**
 * 角色 / LoRA 触发词库管理弹窗
 * - 顶部为添加角色区（独立录入，不再与列表编辑混杂）
 * - 紧凑单行独立卡片列表，右侧集成：编辑、删除、开关
 * - 点击角色卡片或编辑按钮直接弹出独立编辑弹窗，无需回滚至顶部
 */
@Composable
fun AgentCharacterLoreDialog(
    show: Boolean,
    characterLores: List<CharacterLore>,
    initialLore: CharacterLore? = null,
    onDismissRequest: () -> Unit,
    onSaveLore: (name: String, triggerWords: String, existingId: String?, enabled: Boolean?) -> Unit,
    onToggleLore: (id: String) -> Unit,
    onRemoveLore: (id: String) -> Unit
    ) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme

    // 顶部新增角色的输入草稿
    var newCharName by remember(show) { mutableStateOf("") }
    var newCharTriggers by remember(show) { mutableStateOf("") }

    // 点击列表项或编辑按钮弹出的编辑目标角色
    var editingLoreTarget by remember(show, initialLore) { mutableStateOf(initialLore) }

    LaunchedEffect(show, initialLore) {
        if (show) {
            newCharName = ""
            newCharTriggers = ""
            editingLoreTarget = initialLore
        } else {
            editingLoreTarget = null
        }
    }

    OverlayDialog(
        show = show,
        title = "角色 / LoRA 触发词库",
        onDismissRequest = onDismissRequest
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
            Text(
                text = "登记角色外貌与触发词，在对话中自动注入或点名激活：",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
    )

            // 添加新角色卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 14.dp,
                colors = CardDefaults.defaultColors(color = scheme.surfaceContainerHigh),
                insideMargin = PaddingValues(10.dp)
    ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(
                        value = newCharName,
                        onValueChange = { newCharName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = "角色名",
                        singleLine = true
    )

                    TextField(
                        value = newCharTriggers,
                        onValueChange = { newCharTriggers = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = "触发词与外貌特征",
                        singleLine = false,
                        minLines = 2,
                        maxLines = 3
    )

                    Button(
                        onClick = {
                            val name = newCharName.trim()
                            val triggers = newCharTriggers.trim()
                            if (name.isNotBlank() && triggers.isNotBlank()) {
                                onSaveLore(name, triggers, null, true)
                                newCharName = ""
                                newCharTriggers = ""
                            } else {
                                Toast.makeText(context, "请填写角色名与触发词", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColorsPrimary()
    ) {
                        Text(
                            text = "添加角色",
                            fontSize = 13.sp
    )
                    }
                }
            }

            // 角色列表（每位角色均为独立卡片样式）
            if (characterLores.isNotEmpty()) {
                Text(
                    text = "已登记角色 (${characterLores.size})",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
    )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
                    characterLores.forEach { lore ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    1.dp,
                                    scheme.outline.copy(alpha = 0.18f),
                                    RoundedCornerShape(14.dp)
                                ),
                            cornerRadius = 14.dp,
                            colors = CardDefaults.defaultColors(color = scheme.surfaceContainerHigh),
                            insideMargin = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            onClick = {
                                editingLoreTarget = lore
                            }
    ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
    ) {
                                // 左侧：状态圆点 + 角色名
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .background(
                                                if (lore.isEnabled) scheme.primary
                                                else scheme.onSurfaceVariant.copy(alpha = 0.35f),
                                                CircleShape
                                            )
                                    )
                                    Text(
                                        text = lore.name,
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                        color = if (lore.isEnabled) scheme.onSurface else scheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
    )
                                }

                                // 右侧：编辑 - 删除 - 开关
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
                                    // 1. 编辑图标（点击直接弹出编辑弹窗）
                                    Box(
                                        modifier = Modifier
                                            .size(30.dp)
                                            .clip(CircleShape)
                                            .background(scheme.secondaryContainer.copy(alpha = 0.6f))
                                            .clickable {
                                                editingLoreTarget = lore
                                            },
                                        contentAlignment = Alignment.Center
    ) {
                                        Icon(
                                            PocketIcons.Edit,
                                            contentDescription = "编辑角色",
                                            modifier = Modifier.size(14.dp),
                                            tint = scheme.primary
    )
                                    }

                                    // 2. 删除图标
                                    Box(
                                        modifier = Modifier
                                            .size(30.dp)
                                            .clip(CircleShape)
                                            .background(scheme.error.copy(alpha = 0.12f))
                                            .clickable {
                                                if (editingLoreTarget?.id == lore.id) {
                                                    editingLoreTarget = null
                                                }
                                                onRemoveLore(lore.id)
                                            },
                                        contentAlignment = Alignment.Center
    ) {
                                        Icon(
                                            PocketIcons.Delete,
                                            contentDescription = "删除角色",
                                            modifier = Modifier.size(14.dp),
                                            tint = scheme.error
    )
                                    }

                                    // 3. 开关
                                    Switch(
                                        checked = lore.isEnabled,
                                        onCheckedChange = { onToggleLore(lore.id) }
    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            TextButton(
                text = "关闭",
                onClick = onDismissRequest,
                modifier = Modifier.fillMaxWidth()
    )
        }
    }

    // 点击角色列表编辑按钮直接弹出的独立编辑弹窗
    if (show) {
        editingLoreTarget?.let { targetLore ->
            var editName by remember(targetLore.id) { mutableStateOf(targetLore.name) }
            var editTriggers by remember(targetLore.id) { mutableStateOf(targetLore.triggerWords) }

            OverlayDialog(
                show = true,
                title = "编辑角色「${targetLore.name}」",
                onDismissRequest = { editingLoreTarget = null }
            ) {
                DialogFormColumn(
                    footer = {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
                            TextButton(
                                text = "取消",
                                onClick = { editingLoreTarget = null },
                                modifier = Modifier.weight(1f),
                    )
                            Button(
                                onClick = {
                                    val name = editName.trim()
                                    val triggers = editTriggers.trim()
                                    if (name.isNotBlank() && triggers.isNotBlank()) {
                                        onSaveLore(name, triggers, targetLore.id, targetLore.isEnabled)
                                        editingLoreTarget = null
                                    } else {
                                        Toast.makeText(context, "请填写角色名与触发词", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColorsPrimary()
    ) {
                                Text(
                                    text = "保存修改",
                                    fontSize = 13.sp
    )
                            }
                        }
                    }
    ) {
                    TextField(
                        value = editName,
                        onValueChange = { editName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = "角色名",
                        singleLine = true
    )

                    TextField(
                        value = editTriggers,
                        onValueChange = { editTriggers = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = "触发词与外貌特征",
                        singleLine = false,
                        minLines = 3,
                        maxLines = 6
    )
                }
            }
        }
    }
}
