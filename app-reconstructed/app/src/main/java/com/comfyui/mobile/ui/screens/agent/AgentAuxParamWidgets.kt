package com.comfyui.mobile.ui.screens.agent

import com.comfyui.mobile.ui.designsystem.PocketIcons

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comfyui.mobile.model.EditableNode
import com.comfyui.mobile.ui.screens.modelbrowser.CloudModelBrowseRow
import com.comfyui.mobile.ui.screens.modelbrowser.cloudModelResourceTypeFor
import com.comfyui.mobile.ui.designsystem.PocketButton as Button
import com.comfyui.mobile.ui.designsystem.PocketButtonDefaults as ButtonDefaults
import com.comfyui.mobile.ui.designsystem.PocketCard as Card
import com.comfyui.mobile.ui.designsystem.PocketCardDefaults as CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import com.comfyui.mobile.ui.designsystem.PocketTextField as TextField

import androidx.compose.material3.MaterialTheme
import kotlin.random.Random

@Composable
fun AgentAuxParamItem(
    node: EditableNode,
    modifier: Modifier = Modifier,
    isCloudMode: Boolean = false,
    onBrowseCloudModel: ((nodeId: String, key: String, resourceType: String) -> Unit)? = null,
    onValueChange: (nodeId: String, key: String, value: Any) -> Unit
    ) {
    Column(modifier = modifier) {
    when (node) {
        is EditableNode.TextInput -> {
            val options = node.options
            if (!options.isNullOrEmpty()) {
                AgentAuxDropdownWidget(
                    node = node,
                    options = options,
                    onSelect = { onValueChange(node.nodeId, node.key, it) }
    )
            } else {
                AgentAuxTextWidget(
                    node = node,
                    onTextCommit = { onValueChange(node.nodeId, node.key, it) }
    )
            }
        }
        is EditableNode.NumberInput -> {
            val isSeed = remember(node.key, node.title) {
                val k = (node.key + " " + node.title).lowercase()
                k.contains("seed")
            }
            if (isSeed) {
                AgentAuxSeedWidget(
                    node = node,
                    onValueCommit = { onValueChange(node.nodeId, node.key, it) }
    )
            } else {
                AgentAuxNumberWidget(
                    node = node,
                    onValueCommit = { onValueChange(node.nodeId, node.key, it) }
    )
            }
        }
        is EditableNode.BooleanInput -> {
            AgentAuxBooleanWidget(
                node = node,
                onCheckedChange = { onValueChange(node.nodeId, node.key, it) }
    )
        }
        else -> {}
    }
    if (node is EditableNode.TextInput && onBrowseCloudModel != null) {
        val type = cloudModelResourceTypeFor(node.classType, node.key)
        if (type != null) {
            CloudModelBrowseRow(
                isCloud = isCloudMode,
                onClick = { onBrowseCloudModel(node.nodeId, node.key, type) }
            )
        }
    }
    }
}

/**
 * 下拉选择组件（支持 LoRA、采样器等枚举）：
 * 圆角卡片，展开时限制最大高度纵向滚动，高亮选中项并显示勾选图标。
 */
@Composable
fun AgentAuxDropdownWidget(
    node: EditableNode.TextInput,
    options: List<String>,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit
    ) {
    val scheme = MaterialTheme.colorScheme
    var expanded by remember(node.nodeId, node.key) { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "${node.title} (#${node.nodeId})",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant
    )
        Card(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 12.dp,
            colors = CardDefaults.defaultColors(color = scheme.surface),
            onClick = { expanded = !expanded }
    ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
    ) {
                Text(
                    text = node.text.ifBlank { "选择选项" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    )
                Icon(
                    PocketIcons.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = scheme.onSurfaceVariant
    )
            }
        }
        if (expanded) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .clip(RoundedCornerShape(12.dp)),
                cornerRadius = 12.dp,
                colors = CardDefaults.defaultColors(color = scheme.surface)
    ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(6.dp)
    ) {
                    options.forEach { opt ->
                        val isChosen = node.text == opt
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isChosen) scheme.primaryContainer.copy(alpha = 0.5f)
                                    else Color.Transparent
                                )
                                .clickable {
                                    onSelect(opt)
                                    expanded = false
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
    ) {
                            Text(
                                text = opt,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isChosen) scheme.primary else scheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                    )
                            if (isChosen) {
                                Icon(
                                    PocketIcons.Ok,
                                    contentDescription = "已选中",
                                    tint = scheme.primary,
                                    modifier = Modifier.size(16.dp)
    )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 文本输入组件：
 * 配合焦点保护（isFocused）杜绝光标乱跳与打字丢字；
 * 自动识别提示词/描述等长文本特征启用多行输入。
 */
@Composable
fun AgentAuxTextWidget(
    node: EditableNode.TextInput,
    modifier: Modifier = Modifier,
    onTextCommit: (String) -> Unit
    ) {
    val scheme = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    var textState by remember(node.nodeId, node.key) {
        mutableStateOf(TextFieldValue(node.text, TextRange(node.text.length)))
    }

    LaunchedEffect(node.text) {
        if (!isFocused && textState.text != node.text) {
            textState = TextFieldValue(node.text, TextRange(node.text.length))
        }
    }

    val isMultiline = remember(node.key, node.title) {
        val k = (node.key + " " + node.title).lowercase()
        k.contains("prompt") || k.contains("negative") || k.contains("desc") ||
            k.contains("文本") || k.contains("text") || k.contains("positive")
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "${node.title} (#${node.nodeId})",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant
    )
        TextField(
            value = textState,
            onValueChange = {
                textState = it
                onTextCommit(it.text)
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = !isMultiline,
            maxLines = if (isMultiline) 4 else 1,
            minLines = if (isMultiline) 2 else 1,
            interactionSource = interactionSource
    )
    }
}

/**
 * Seed 专用输入组件：
 * 纯数字输入，支持 -1 与随机生成按键，彻底避免 Slider 溢出。
 */
@Composable
fun AgentAuxSeedWidget(
    node: EditableNode.NumberInput,
    modifier: Modifier = Modifier,
    onValueCommit: (Any) -> Unit
    ) {
    val scheme = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val currentStr = node.value.toString()
    var textValue by remember(node.nodeId, node.key) {
        mutableStateOf(currentStr)
    }

    LaunchedEffect(node.value) {
        val now = node.value.toString()
        if (!isFocused && textValue != now) {
            textValue = now
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
    ) {
            Text(
                text = "${node.title} (#${node.nodeId})",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
    )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // 设为 -1 (随机)
                Button(
                    onClick = {
                        textValue = "-1"
                        onValueCommit(if (node.value is Long) -1L else -1)
                    },
                    modifier = Modifier.height(26.dp),
                    cornerRadius = 8.dp,
                    colors = ButtonDefaults.buttonColors(
                        color = scheme.secondaryContainer,
                        contentColor = scheme.onSecondaryContainer
    ),
                    insideMargin = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
    ) {
                    Text("-1 随机", fontSize = 11.sp)
                }

                // 骰子生成随机数
                Button(
                    onClick = {
                        val randSeed = Random.nextLong(1, 999999999999L)
                        textValue = randSeed.toString()
                        onValueCommit(if (node.value is Long) randSeed else randSeed.toInt())
                    },
                    modifier = Modifier.height(26.dp),
                    cornerRadius = 8.dp,
                    colors = ButtonDefaults.buttonColors(
                        color = scheme.primaryContainer,
                        contentColor = scheme.primary
    ),
                    insideMargin = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
    ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
                        Icon(
                            PocketIcons.Refresh,
                            contentDescription = "重新生成随机种子",
                            modifier = Modifier.size(12.dp),
                            tint = scheme.primary
    )
                        Text("换种子", fontSize = 11.sp)
                    }
                }
            }
        }

        TextField(
            value = textValue,
            onValueChange = { raw ->
                val clean = raw.trim()
                textValue = clean
                if (clean == "-1") {
                    onValueCommit(if (node.value is Long) -1L else -1)
                } else {
                    val parsedLong = clean.toLongOrNull()
                    if (parsedLong != null) {
                        onValueCommit(if (node.value is Long) parsedLong else parsedLong.toInt())
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            interactionSource = interactionSource
    )
    }
}

/**
 * 数值调节组件（Slider + 步长吸附）：
 * Int/Long 吸附为 1，Float/Double 吸附为 0.05，松手持久化防频繁触发。
 */
@Composable
fun AgentAuxNumberWidget(
    node: EditableNode.NumberInput,
    modifier: Modifier = Modifier,
    onValueCommit: (Any) -> Unit
    ) {
    val scheme = MaterialTheme.colorScheme
    val expectsInt = remember(node.value, node.min, node.max, node.key) {
        !com.comfyui.mobile.model.isStrengthKey(node.key) && (
            node.value is Int || node.value is Long ||
                (node.min != null && node.max != null &&
                    node.min % 1.0 == 0.0 && node.max % 1.0 == 0.0 &&
                    node.value.toDouble() % 1.0 == 0.0)
            )
    }

    val currentFloat = node.value.toFloat()
    val isStrength = remember(node.key, node.title) {
        com.comfyui.mobile.model.isStrengthKey(node.key) ||
            (node.title.contains("强度") && !node.key.contains("num_", ignoreCase = true))
    }
    val isDenoise = remember(node.key, node.title) {
        val k = (node.key + " " + node.title).lowercase()
        k.contains("denoise") || k.contains("重绘") || k.contains("降噪")
    }
    val strengthRange = if (isStrength) {
        com.comfyui.mobile.model.strengthSliderRange(
            if (node.min != null && node.max != null) node.min to node.max else null
        )
    } else null
    val minVal = strengthRange?.first?.toFloat()
        ?: node.min?.toFloat()
        ?: (if (currentFloat < 0f) currentFloat else if (expectsInt) 0f else 0f)
    val defaultMax = when {
        isStrength -> com.comfyui.mobile.model.STRENGTH_SLIDER_MAX.toFloat()
        isDenoise -> 1f
        expectsInt -> {
            val floor = maxOf(currentFloat, 1f)
            if (floor > 50f) floor * 1.5f else 100f
        }
        currentFloat > 2f -> 20f
        else -> maxOf(currentFloat * 2f, 2f)
    }
    val maxVal = if (strengthRange != null) {
        strengthRange.second.toFloat()
    } else {
        node.max?.toFloat() ?: defaultMax
    }
    val safeMaxVal = if (maxVal > minVal) maxVal else (minVal + 1f)

    var sliderDraft by remember(node.nodeId, node.key, currentFloat) {
        mutableFloatStateOf(currentFloat.coerceIn(minVal, safeMaxVal))
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
    ) {
            Text(
                text = "${node.title} (#${node.nodeId})",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
    )
            Text(
                text = if (expectsInt) {
                    sliderDraft.toInt().toString()
                } else {
                    String.format("%.2f", sliderDraft)
                },
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.primary
    )
        }

        Slider(
            value = sliderDraft,
            onValueChange = { newVal ->
                sliderDraft = if (isStrength) {
                    com.comfyui.mobile.model.snapStrength(newVal).toFloat()
                } else if (expectsInt) {
                    Math.round(newVal).toFloat()
                } else {
                    (Math.round(newVal * 20) / 20.0).toFloat()
                }
            },
            onValueChangeFinished = {
                val finalVal: Any = if (isStrength) {
                    com.comfyui.mobile.model.snapStrength(sliderDraft)
                } else if (node.value is Long) {
                    Math.round(sliderDraft).toLong()
                } else if (expectsInt) {
                    Math.round(sliderDraft)
                } else {
                    Math.round(sliderDraft * 20) / 20.0
                }
                onValueCommit(finalVal)
            },
            valueRange = minVal..safeMaxVal,
            modifier = Modifier.fillMaxWidth()
    )
    }
}

/**
 * 布尔开关组件：
 * 整行点击卡片切换，带有 Material Switch 开关。
 */
@Composable
fun AgentAuxBooleanWidget(
    node: EditableNode.BooleanInput,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit
    ) {
    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 12.dp,
        colors = CardDefaults.defaultColors(color = MaterialTheme.colorScheme.surface),
        onClick = { onCheckedChange(!node.value) }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
    ) {
            Text(
                text = "${node.title} (#${node.nodeId})",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                    )
            Switch(
                checked = node.value,
                onCheckedChange = onCheckedChange
    )
        }
    }
}
