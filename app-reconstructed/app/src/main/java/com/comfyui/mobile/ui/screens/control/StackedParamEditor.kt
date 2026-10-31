package com.comfyui.mobile.ui.screens.control

import androidx.compose.animation.core.animateFloatAsState
import com.comfyui.mobile.ui.designsystem.PocketTokens
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.comfyui.mobile.model.EditableNode
import com.comfyui.mobile.ui.screens.modelbrowser.CloudModelBrowseRow
import com.comfyui.mobile.ui.screens.modelbrowser.cloudModelResourceTypeFor
import com.comfyui.mobile.ui.components.MediaInputRow
import com.comfyui.mobile.ui.components.isThirdPartyLoaderInput
import com.comfyui.mobile.ui.components.mediaInputMime
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * Control-screen style stacked parameter card (single column):
 * title chip + rename, inline option dropdown, free text with double-tap fullscreen,
 * number + optional slider, boolean switch, image/media row.
 */
@Composable
fun StackedParamCard(
    node: EditableNode,
    modifier: Modifier = Modifier,
    isDragging: Boolean = false,
    onCard: Color,
    isCloudMode: Boolean,
    onRenameClick: (nodeId: String, key: String, title: String) -> Unit,
    onValueChange: (nodeId: String, key: String, value: Any) -> Unit,
    onOpenFullScreenText: (EditableNode.TextInput) -> Unit,
    onModelBrowseClick: (nodeId: String, key: String, resType: String) -> Unit,
    onUploadClick: (nodeId: String, key: String, mimeType: String) -> Unit,
    getViewUrl: (filename: String, subfolder: String, type: String) -> String,
    loaderUploadLabel: String = "上传文件",
    imageUploadLabel: String = "上传"
    ) {
    val scheme = MiuixTheme.colorScheme
    val paramKey = paramKeyOf(node)
    val cloudModelType = if (node is EditableNode.TextInput) {
        cloudModelResourceTypeFor(node.classType, paramKey)
    } else null

    Card(
        modifier = modifier,
        cornerRadius = PocketTokens.Radius.Large,
        insideMargin = PaddingValues(12.dp),
        colors = CardDefaults.defaultColors(
            color = if (isDragging) {
                scheme.primaryContainer.copy(alpha = 0.95f)
            } else {
                scheme.surfaceContainerHigh.copy(alpha = 0.92f)
            }
    ),
        holdDownState = false,
        onClick = null,
        pressFeedbackType = PressFeedbackType.None,
        showIndication = false
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
                Card(
                    modifier = Modifier.weight(1f, fill = false),
                    cornerRadius = PocketTokens.Radius.XLarge * 2,
                    insideMargin = PaddingValues(horizontal = 12.dp, vertical = 5.dp),
                    colors = CardDefaults.defaultColors(
                        color = scheme.primary.copy(alpha = 0.12f)
    ),
                    onClick = null,
                    pressFeedbackType = PressFeedbackType.None,
                    showIndication = false
    ) {
                    Text(
                        text = node.title,
                        style = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.Medium),
                        color = scheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false
    )
                }
                IconButton(onClick = { onRenameClick(node.nodeId, paramKey, node.title) }) {
                    Icon(
                        MiuixIcons.Edit,
                        contentDescription = "重命名",
                        tint = onCard.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            when (node) {
                is EditableNode.TextInput -> {
                    val options = node.options
                    if (!options.isNullOrEmpty()) {
                        var expanded by remember(node.nodeId, node.key) { mutableStateOf(false) }
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                cornerRadius = PocketTokens.Radius.Large,
                                colors = CardDefaults.defaultColors(
                                    color = scheme.secondaryContainer.copy(alpha = 0.65f)
    ),
                                pressFeedbackType = PressFeedbackType.None,
                                showIndication = false,
                                onClick = { expanded = !expanded }
    ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
    ) {
                                    Text(
                                        text = node.text.ifBlank { "选择选项" },
                                        style = MiuixTheme.textStyles.body1,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
    )
                                    Icon(
                                        MiuixIcons.ExpandMore,
                                        contentDescription = null,
                                        tint = scheme.onSecondaryContainer
    )
                                }
                            }
                            if (expanded) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(
                                            1.dp,
                                            scheme.outline.copy(alpha = 0.25f),
                                            PocketTokens.Shape.Large
    ),
                                    cornerRadius = PocketTokens.Radius.Large,
                                    colors = CardDefaults.defaultColors(
                                        color = scheme.surface.copy(alpha = 0.95f)
    ),
                                    onClick = null,
                                    pressFeedbackType = PressFeedbackType.None,
                                    showIndication = false
    ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 240.dp)
                                            .verticalScroll(rememberScrollState())
    ) {
                                        options.forEach { opt ->
                                            Text(
                                                text = opt,
                                                style = MiuixTheme.textStyles.body1,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        onValueChange(node.nodeId, node.key, opt)
                                                        expanded = false
                                                    }
                                                    .padding(horizontal = 16.dp, vertical = 12.dp)
    )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
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
                    }
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
                                .padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End
    ) {
                            Button(
                                onClick = {
                                    val mimeType = mediaInputMime(node.classType, node.key, node.text)
                                    onUploadClick(node.nodeId, node.key, mimeType)
                                },
                                colors = ButtonDefaults.buttonColorsPrimary()
    ) { Text(loaderUploadLabel) }
                        }
                    }
                }

                is EditableNode.NumberInput -> {
                    val min = node.min
                    val max = node.max
                    val isStrength = com.comfyui.mobile.model.isStrengthKey(node.key)
                    val expectsInt = !isStrength && (
                        node.value is Int || node.value is Long ||
                            (min != null && max != null && min % 1.0 == 0.0 && max % 1.0 == 0.0 &&
                                node.value.toDouble() % 1.0 == 0.0)
                        )
                    var textValue by remember(node.nodeId, node.key) {
                        mutableStateOf(formatNumberForDisplay(node.value))
                    }
                    LaunchedEffect(node.value) {
                        val now = formatNumberForDisplay(node.value)
                        val inProgress = textValue.isBlank() ||
                            textValue == "-" ||
                            textValue == "." ||
                            textValue == "-." ||
                            textValue.endsWith(".")
                        if (!inProgress && now != textValue) textValue = now
                    }
                    Column {
                        TextField(
                            value = textValue,
                            onValueChange = numChange@{ raw ->
                                textValue = raw
                                if (raw.isBlank() || raw == "-" || raw == "." || raw == "-." || raw.endsWith(".")) {
                                    return@numChange
                                }
                                if (expectsInt && raw.contains('.')) {
                                    return@numChange
                                }
                                if (expectsInt) {
                                    val parsed = raw.toLongOrNull() ?: return@numChange
                                    val minLong = min?.toLong()
                                    val maxLong = max?.toLong()
                                    val clamped = when {
                                        minLong != null && parsed < minLong -> minLong
                                        maxLong != null && parsed > maxLong -> maxLong
                                        else -> parsed
                                    }
                                    val any: Any = if (node.value is Long) {
                                        clamped
                                    } else {
                                        if (clamped in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                                            clamped.toInt()
                                        } else {
                                            return@numChange
                                        }
                                    }
                                    onValueChange(node.nodeId, node.key, any)
                                } else {
                                    val num = raw.toDoubleOrNull() ?: return@numChange
                                    val clamped = when {
                                        min != null && num < min -> min
                                        max != null && num > max -> max
                                        else -> num
                                    }
                                    val any: Any =
                                        if (clamped.isFinite() && clamped % 1.0 == 0.0) clamped.toLong() else clamped
                                    onValueChange(node.nodeId, node.key, any)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = if (expectsInt) KeyboardType.Number else KeyboardType.Decimal
    )
    )
                        if (min != null && max != null) {
                            var sliderVal by remember(node.value) { mutableFloatStateOf(node.value.toFloat()) }
                            Slider(
                                value = sliderVal,
                                onValueChange = {
                                    sliderVal = if (isStrength) com.comfyui.mobile.model.snapStrength(it).toFloat() else it
                                    val displayVal = if (expectsInt) (it + 0.5f).toInt() else sliderVal
                                    textValue = formatNumberForDisplay(displayVal)
                                },
                                onValueChangeFinished = {
                                    val finalVal: Any =
                                        if (expectsInt) (sliderVal + 0.5f).toInt()
                                        else if (isStrength) com.comfyui.mobile.model.snapStrength(sliderVal)
                                        else sliderVal
                                    onValueChange(node.nodeId, node.key, finalVal)
                                },
                                valueRange = min.toFloat()..max.toFloat(),
                                modifier = Modifier.padding(top = 4.dp)
    )
                        }
                    }
                }

                is EditableNode.BooleanInput -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
    ) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Switch(
                            checked = node.value,
                            onCheckedChange = { onValueChange(node.nodeId, node.key, it) }
    )
                    }
                }

                is EditableNode.ImageInput -> {
                    val previewUrl = remember(node.filename, node.subfolder, node.type) {
                        if (node.filename.isNotBlank()) getViewUrl(node.filename, node.subfolder, node.type) else ""
                    }
                    MediaInputRow(
                        node = node,
                        previewUrl = previewUrl,
                        showFileName = true,
                        uploadText = imageUploadLabel,
                        onUpload = {
                            val mimeType = mediaInputMime(node.classType, node.key, node.filename)
                            onUploadClick(node.nodeId, node.key, mimeType)
                        }
    )
                }
            }
        }
    }
}

/**
 * Vertical long-press drag list of [StackedParamCard]s.
 * Owns local sync + drag state; reports reorder via [onReorder] with compound keys.
 * Screen chrome (outer Card, title, manage button) stays in the caller.
 */
@Composable
fun StackedParamEditorList(
    sourceNodes: List<EditableNode>,
    onCard: Color,
    isCloudMode: Boolean,
    onReorder: (orderedCompoundKeys: List<String>) -> Unit,
    onRenameClick: (nodeId: String, key: String, title: String) -> Unit,
    onValueChange: (nodeId: String, key: String, value: Any) -> Unit,
    onModelBrowseClick: (nodeId: String, key: String, resType: String) -> Unit,
    onUploadClick: (nodeId: String, key: String, mimeType: String) -> Unit,
    getViewUrl: (filename: String, subfolder: String, type: String) -> String,
    emptyText: String = "暂无显示的参数，点击右上角设置",
    modifier: Modifier = Modifier
    ) {
    val scheme = MiuixTheme.colorScheme
    var draggingKey by remember { mutableStateOf<String?>(null) }
    val localEditableNodes = rememberSyncedEditableNodes(sourceNodes, draggingKey)
    var editingTextNode by remember { mutableStateOf<EditableNode.TextInput?>(null) }

    var draggingOffsetY by remember { mutableFloatStateOf(0f) }
    var draggingPointerY by remember { mutableFloatStateOf(0f) }
    var draggingInitialIndex by remember { mutableIntStateOf(-1) }
    var draggingHoverIndex by remember { mutableIntStateOf(-1) }
    var draggedItemHeight by remember { mutableFloatStateOf(0f) }
    val itemBounds = remember { mutableStateMapOf<String, Rect>() }

    if (editingTextNode != null) {
        FullScreenTextEditorDialog(
            title = editingTextNode!!.title,
            initialText = editingTextNode!!.text,
            onConfirm = { text ->
                val node = editingTextNode!!
                onValueChange(node.nodeId, node.key, text)
                editingTextNode = null
            },
            onDismiss = { editingTextNode = null }
    )
    }

    val visibleNodes = localEditableNodes.filter { it.isVisible }

    if (visibleNodes.isEmpty()) {
        Text(
            text = emptyText,
            style = MiuixTheme.textStyles.body2,
            color = onCard.copy(alpha = 0.6f),
            modifier = modifier.padding(vertical = 8.dp)
    )
    } else {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
            visibleNodes.forEachIndexed { index, node ->
                key(compoundKeyOf(node)) {
                    val compound = compoundKeyOf(node)
                    val isDragging = draggingKey == compound
                    val currentVisibleNodes by rememberUpdatedState(visibleNodes)
                    val density = LocalDensity.current
                    val spacingPx = with(density) { 12.dp.toPx() }

                    val targetShift = if (draggingKey == null || isDragging) {
                        0f
                    } else if (draggingInitialIndex != -1 && draggingHoverIndex != -1) {
                        when {
                            draggingInitialIndex < draggingHoverIndex &&
                                index > draggingInitialIndex && index <= draggingHoverIndex ->
                                -draggedItemHeight - spacingPx
                            draggingInitialIndex > draggingHoverIndex &&
                                index < draggingInitialIndex && index >= draggingHoverIndex ->
                                draggedItemHeight + spacingPx
                            else -> 0f
                        }
                    } else {
                        0f
                    }

                    val animatedShift by animateFloatAsState(
                        targetValue = targetShift,
                        animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f),
                        label = "paramShift"
    )
                    val realVisualOffset = if (isDragging) draggingOffsetY else animatedShift

                    StackedParamCard(
                        node = node,
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(compound) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { offset ->
                                        draggingKey = compound
                                        draggingOffsetY = 0f
                                        draggingInitialIndex =
                                            currentVisibleNodes.indexOfFirst { compoundKeyOf(it) == compound }
                                        draggingHoverIndex = draggingInitialIndex
                                        val bounds = itemBounds[compound]
                                        if (bounds != null) {
                                            draggingPointerY = bounds.top + offset.y
                                            draggedItemHeight = bounds.height
                                        }
                                    },
                                    onDragEnd = {
                                        if (
                                            draggingInitialIndex != -1 &&
                                            draggingHoverIndex != -1 &&
                                            draggingInitialIndex != draggingHoverIndex
                                        ) {
                                            val fromKey =
                                                compoundKeyOf(currentVisibleNodes[draggingInitialIndex])
                                            val toKey =
                                                compoundKeyOf(currentVisibleNodes[draggingHoverIndex])
                                            val realFromIndex =
                                                localEditableNodes.indexOfFirst { compoundKeyOf(it) == fromKey }
                                            val realToIndex =
                                                localEditableNodes.indexOfFirst { compoundKeyOf(it) == toKey }
                                            if (realFromIndex != -1 && realToIndex != -1) {
                                                val movedItem = localEditableNodes.removeAt(realFromIndex)
                                                localEditableNodes.add(realToIndex, movedItem)
                                                onReorder(localEditableNodes.map { compoundKeyOf(it) })
                                            }
                                        }
                                        draggingKey = null
                                        draggingOffsetY = 0f
                                        draggingPointerY = 0f
                                        draggingInitialIndex = -1
                                        draggingHoverIndex = -1
                                    },
                                    onDragCancel = {
                                        draggingKey = null
                                        draggingOffsetY = 0f
                                        draggingPointerY = 0f
                                        draggingInitialIndex = -1
                                        draggingHoverIndex = -1
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        draggingOffsetY += dragAmount.y
                                        draggingPointerY += dragAmount.y
                                        val draggedCenterY = draggingPointerY
                                        var closestIndex = draggingInitialIndex
                                        var minDiff = Float.MAX_VALUE
                                        currentVisibleNodes.forEachIndexed { i, n ->
                                            val rect = itemBounds[compoundKeyOf(n)] ?: return@forEachIndexed
                                            val slotCenter = (rect.top + rect.bottom) / 2f
                                            val diff = kotlin.math.abs(draggedCenterY - slotCenter)
                                            if (diff < minDiff) {
                                                minDiff = diff
                                                closestIndex = i
                                            }
                                        }
                                        if (closestIndex != -1 && closestIndex != draggingHoverIndex) {
                                            draggingHoverIndex = closestIndex
                                        }
                                    }
    )
                            }
                            .zIndex(if (isDragging) 10f else 0f)
                            .graphicsLayer {
                                translationY = realVisualOffset
                                shape = PocketTokens.Shape.Large
                                clip = true
                                if (isDragging) {
                                    shadowElevation = 6f
                                }
                            }
                            .clip(PocketTokens.Shape.Large)
                            .then(
                                if (isDragging) {
                                    Modifier.border(2.dp, scheme.primary, PocketTokens.Shape.Large)
                                } else {
                                    Modifier
                                }
    )
                            .onGloballyPositioned { coords ->
                                if (draggingKey == null) {
                                    val pos = coords.positionInRoot()
                                    val size = coords.size
                                    itemBounds[compound] = Rect(
                                        left = pos.x,
                                        top = pos.y,
                                        right = pos.x + size.width,
                                        bottom = pos.y + size.height
    )
                                }
                            },
                        isDragging = isDragging,
                        onCard = onCard,
                        isCloudMode = isCloudMode,
                        onRenameClick = onRenameClick,
                        onValueChange = onValueChange,
                        onOpenFullScreenText = { editingTextNode = it },
                        onModelBrowseClick = onModelBrowseClick,
                        onUploadClick = onUploadClick,
                        getViewUrl = getViewUrl
    )
                }
            }
        }
    }
}

/** Expose local list for callers that need to mutate order externally (e.g. tests). */
typealias LocalEditableNodes = SnapshotStateList<EditableNode>
