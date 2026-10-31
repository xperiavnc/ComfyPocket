package com.comfyui.mobile.ui.screens.control

import androidx.compose.foundation.gestures.detectTapGestures
import com.comfyui.mobile.ui.designsystem.PocketTokens
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comfyui.mobile.model.EditableNode
import com.comfyui.mobile.ui.screens.modelbrowser.CloudModelBrowseRow
import com.comfyui.mobile.ui.screens.modelbrowser.cloudModelResourceTypeFor
import com.comfyui.mobile.ui.components.MediaInputRow
import com.comfyui.mobile.ui.components.NodeIdChip
import com.comfyui.mobile.ui.components.isThirdPartyLoaderInput
import com.comfyui.mobile.ui.components.mediaInputMime
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/** Whether this node should span the full row in the AI App 2-column grid. */
fun isParamFullWidth(node: EditableNode): Boolean = when (node) {
    is EditableNode.TextInput ->
        node.options.isNullOrEmpty() || cloudModelResourceTypeFor(node.classType, node.key) != null
    is EditableNode.ImageInput -> true
    is EditableNode.NumberInput -> false
    is EditableNode.BooleanInput -> false
}

/** Layout unit for the AI App parameter grid (full / pair / lonely half). */
sealed class ParamRowItem {
    data class Full(val node: EditableNode) : ParamRowItem()
    data class Pair(val first: EditableNode, val second: EditableNode) : ParamRowItem()
    data class Half(val node: EditableNode) : ParamRowItem()
}

/** Pack visible nodes into full-width rows and side-by-side pairs. */
fun buildParamRowItems(visibleNodes: List<EditableNode>): List<ParamRowItem> {
    val result = mutableListOf<ParamRowItem>()
    var pendingHalf: EditableNode? = null
    for (node in visibleNodes) {
        if (isParamFullWidth(node)) {
            if (pendingHalf != null) {
                result.add(ParamRowItem.Half(pendingHalf))
                pendingHalf = null
            }
            result.add(ParamRowItem.Full(node))
        } else {
            if (pendingHalf == null) {
                pendingHalf = node
            } else {
                result.add(ParamRowItem.Pair(pendingHalf, node))
                pendingHalf = null
            }
        }
    }
    if (pendingHalf != null) {
        result.add(ParamRowItem.Half(pendingHalf))
    }
    return result
}

/**
 * Compact parameter card for side-by-side (2-column) grid display.
 * Used for Boolean switches, Number inputs, and Option dropdowns.
 */
@Composable
fun CompactParamCard(
    node: EditableNode,
    modifier: Modifier = Modifier,
    isDragging: Boolean = false,
    isHoverTarget: Boolean = false,
    onCard: Color,
    onRenameClick: (nodeId: String, key: String, title: String) -> Unit,
    onValueChange: (nodeId: String, key: String, value: Any) -> Unit,
    onDropdownClick: (EditableNode.TextInput) -> Unit
    ) {
    val scheme = MiuixTheme.colorScheme
    val paramKey = paramKeyOf(node)

    Card(
        modifier = modifier,
        cornerRadius = PocketTokens.Radius.Medium,
        insideMargin = PaddingValues(horizontal = PocketTokens.Space.S, vertical = PocketTokens.Space.S - 2.dp),
        colors = CardDefaults.defaultColors(
            color = when {
                isHoverTarget -> scheme.primaryContainer.copy(alpha = 0.45f)
                isDragging -> scheme.surfaceContainerHighest
                else -> scheme.surfaceContainerHigh
            },
            contentColor = onCard
    ),
        onClick = null,
        pressFeedbackType = PressFeedbackType.None,
        showIndication = false
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
    ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
                    Text(
                        text = node.title,
                        style = MiuixTheme.textStyles.footnote1.copy(fontWeight = FontWeight.Medium),
                        color = onCard.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
    )
                    if (node is EditableNode.NumberInput) {
                        NodeIdChip(nodeId = node.nodeId)
                    }
                }
                if (isHoverTarget) {
                    Text(
                        text = "移至此",
                        style = MiuixTheme.textStyles.footnote2.copy(fontWeight = FontWeight.Bold),
                        color = scheme.primary
    )
                } else {
                    IconButton(
                        onClick = { onRenameClick(node.nodeId, paramKey, node.title) },
                        modifier = Modifier.size(22.dp)
    ) {
                        Icon(
                            MiuixIcons.Edit,
                            contentDescription = "重命名",
                            tint = onCard.copy(alpha = 0.45f),
                            modifier = Modifier.size(13.dp)
    )
                    }
                }
            }

            when (node) {
                is EditableNode.BooleanInput -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
    ) {
                        Text(
                            text = if (node.value) "开启" else "关闭",
                            style = MiuixTheme.textStyles.body2,
                            color = if (node.value) scheme.primary else onCard.copy(alpha = 0.6f)
    )
                        Switch(
                            checked = node.value,
                            onCheckedChange = { onValueChange(node.nodeId, node.key, it) }
    )
                    }
                }

                is EditableNode.NumberInput -> {
                    var raw by rememberSaveable(node.nodeId, node.key) { mutableStateOf(node.value.toString()) }
                    LaunchedEffect(node.value) {
                        val next = node.value.toString()
                        if (raw != next) raw = next
                    }
                    TextField(
                        value = raw,
                        onValueChange = { next ->
                            raw = next
                            val trimmed = next.trim()
                            if (trimmed.isBlank() || trimmed == "-" || trimmed == "." || trimmed == "-." || trimmed.endsWith(".")) return@TextField
                            val num = trimmed.toDoubleOrNull() ?: return@TextField
                            val any: Any = if (node.value is Int) num.toInt() else if (node.value is Long) num.toLong() else num
                            onValueChange(node.nodeId, node.key, any)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (node.value is Int || node.value is Long) KeyboardType.Number else KeyboardType.Decimal,
                            imeAction = ImeAction.Done
    )
    )
                }

                is EditableNode.TextInput -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = PocketTokens.Radius.Medium,
                        colors = CardDefaults.defaultColors(
                            color = scheme.secondaryContainer.copy(alpha = 0.65f)
    ),
                        insideMargin = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                        onClick = { onDropdownClick(node) },
                        pressFeedbackType = PressFeedbackType.Sink,
                        showIndication = true
    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
    ) {
                            Text(
                                text = node.text.ifBlank { "选择" },
                                style = MiuixTheme.textStyles.body2,
                                color = onCard,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                    )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                MiuixIcons.ExpandMore,
                                contentDescription = null,
                                tint = onCard.copy(alpha = 0.7f),
                                modifier = Modifier.size(16.dp)
    )
                        }
                    }
                }

                else -> {}
            }
        }
    }
}

/**
 * Full-width parameter card for large content: free-form multiline text prompts and image inputs.
 */
@Composable
fun FullWidthParamCard(
    node: EditableNode,
    modifier: Modifier = Modifier,
    isDragging: Boolean = false,
    isHoverTarget: Boolean = false,
    onCard: Color,
    isCloudMode: Boolean,
    onRenameClick: (nodeId: String, key: String, title: String) -> Unit,
    onValueChange: (nodeId: String, key: String, value: Any) -> Unit,
    onOpenFullScreenText: (EditableNode.TextInput) -> Unit,
    onModelBrowseClick: (nodeId: String, key: String, resType: String) -> Unit,
    onUploadClick: (nodeId: String, key: String, mimeType: String) -> Unit,
    getViewUrl: (filename: String, subfolder: String, type: String) -> String
    ) {
    val scheme = MiuixTheme.colorScheme
    val paramKey = paramKeyOf(node)
    val cloudModelType = if (node is EditableNode.TextInput) {
        cloudModelResourceTypeFor(node.classType, paramKey)
    } else null

    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = PocketTokens.Radius.Medium,
        insideMargin = PaddingValues(PocketTokens.Space.S),
        colors = CardDefaults.defaultColors(
            color = when {
                isHoverTarget -> scheme.primaryContainer.copy(alpha = 0.45f)
                isDragging -> scheme.surfaceContainerHighest
                else -> scheme.surfaceContainerHigh
            },
            contentColor = onCard
    ),
        onClick = null,
        pressFeedbackType = PressFeedbackType.None,
        showIndication = false
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
                Text(
                    text = node.title,
                    style = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.Medium),
                    color = onCard,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
    )
                if (node is EditableNode.TextInput || node is EditableNode.NumberInput) {
                    NodeIdChip(nodeId = node.nodeId)
                }
                Spacer(modifier = Modifier.weight(1f))
                if (isHoverTarget) {
                    Text(
                        text = "松手移动至此",
                        style = MiuixTheme.textStyles.footnote2.copy(fontWeight = FontWeight.Bold),
                        color = scheme.primary
    )
                } else {
                    IconButton(
                        onClick = { onRenameClick(node.nodeId, paramKey, node.title) },
                        modifier = Modifier.size(24.dp)
    ) {
                        Icon(
                            MiuixIcons.Edit,
                            contentDescription = "重命名",
                            tint = onCard.copy(alpha = 0.45f),
                            modifier = Modifier.size(13.dp)
    )
                    }
                }
            }

            when (node) {
                is EditableNode.TextInput -> {
                    val interactionSource = remember { MutableInteractionSource() }
                    val isFocused by interactionSource.collectIsFocusedAsState()
                    var textState by rememberSaveable(
                        node.nodeId,
                        node.key,
                        stateSaver = TextFieldValue.Saver
    ) {
                        mutableStateOf(TextFieldValue(node.text, TextRange(node.text.length)))
                    }
                    LaunchedEffect(node.text) {
                        if (!isFocused && textState.text != node.text) {
                            textState = TextFieldValue(node.text, TextRange(node.text.length))
                        }
                    }
                    TextField(
                        value = textState,
                        onValueChange = {
                            textState = it
                            onValueChange(node.nodeId, node.key, it.text)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(node.nodeId, node.key) {
                                detectTapGestures(onDoubleTap = { onOpenFullScreenText(node) })
                            },
                        interactionSource = interactionSource
    )

                    if (cloudModelType != null) {
                        CloudModelBrowseRow(
                            isCloud = isCloudMode,
                            onClick = {
                                onModelBrowseClick(node.nodeId, node.key, cloudModelType)
                            }
                        )
                    }

                    if (isThirdPartyLoaderInput(node.classType, node.key)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End
    ) {
                            Button(
                                onClick = {
                                    val mimeType = mediaInputMime(node.classType, node.key, node.text)
                                    onUploadClick(node.nodeId, node.key, mimeType)
                                },
                                colors = ButtonDefaults.buttonColorsPrimary()
    ) {
                                Icon(MiuixIcons.Image, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("上传")
                            }
                        }
                    }
                }

                is EditableNode.ImageInput -> {
                    val previewUrl = remember(node.filename, node.subfolder, node.type) {
                        if (node.filename.isNotBlank()) getViewUrl(node.filename, node.subfolder, node.type) else ""
                    }
                    MediaInputRow(
                        node = node,
                        previewUrl = previewUrl,
                        showFileName = false,
                        uploadText = "上传",
                        onUpload = {
                            val mime = mediaInputMime(node.classType, node.key, node.filename)
                            onUploadClick(node.nodeId, node.key, mime)
                        }
    )
                }

                else -> {}
            }
        }
    }
}
