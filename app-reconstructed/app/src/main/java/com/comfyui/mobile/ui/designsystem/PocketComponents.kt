@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.comfyui.mobile.ui.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Application components backed only by Material 3. Business screens keep their
 * state/actions; this layer owns consistent sizes, colors and overlay windows.
 * No overlay registry or second root Scaffold participates in Back dispatch.
 */
@Composable
fun PocketCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    insideMargin: PaddingValues = PaddingValues(0.dp),
    colors: CardColors = PocketCardDefaults.defaultColors(),
    onClick: (() -> Unit)? = null,
    holdDownState: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val body: @Composable ColumnScope.() -> Unit = {
        Column(Modifier.padding(insideMargin), content = content)
    }
    val shape = RoundedCornerShape(cornerRadius.coerceAtMost(24.dp))
    val border = if (holdDownState) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
    if (onClick == null) Card(modifier, shape = shape, colors = colors, border = border, content = body)
    else Card(onClick = onClick, modifier = modifier, shape = shape, colors = colors, border = border, content = body)
}

object PocketCardDefaults {
    @Composable fun defaultColors(
        color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor: Color = MaterialTheme.colorScheme.onSurface
    ): CardColors = CardDefaults.cardColors(containerColor = color, contentColor = contentColor)
}

object PocketButtonDefaults {
    @Composable fun buttonColors(
        color: Color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer
    ) = ButtonDefaults.filledTonalButtonColors(containerColor = color, contentColor = contentColor)
    @Composable fun buttonColorsPrimary() = ButtonDefaults.buttonColors()
    @Composable fun textButtonColorsPrimary() = ButtonDefaults.textButtonColors()
    @Composable fun textButtonColors(
        color: Color = Color.Transparent, contentColor: Color = MaterialTheme.colorScheme.primary
    ) = ButtonDefaults.textButtonColors(containerColor = color, contentColor = contentColor)
}

@Composable
fun PocketButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: ButtonColors = PocketButtonDefaults.buttonColors(),
    cornerRadius: Dp = 16.dp, insideMargin: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit
) = Button(onClick, modifier.heightIn(min = 48.dp), enabled, shape = RoundedCornerShape(cornerRadius),
    colors = colors, contentPadding = insideMargin, content = content)

@Composable
fun PocketTextButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.textButtonColors(), minHeight: Dp = 48.dp, minWidth: Dp = 48.dp,
    cornerRadius: Dp = 16.dp, insideMargin: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    textStyle: TextStyle = MaterialTheme.typography.labelLarge
) = TextButton(onClick, modifier.heightIn(min = maxOf(48.dp, minHeight)).widthIn(min = maxOf(48.dp, minWidth)), enabled,
    shape = RoundedCornerShape(cornerRadius), colors = colors, contentPadding = insideMargin) {
    Text(text, style = textStyle)
}

@Composable
fun PocketIconButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    backgroundColor: Color = Color.Transparent, minWidth: Dp = 48.dp, minHeight: Dp = 48.dp,
    content: @Composable () -> Unit
) = IconButton(onClick, modifier.sizeIn(minWidth = maxOf(48.dp, minWidth), minHeight = maxOf(48.dp, minHeight)),
    enabled, colors = IconButtonDefaults.iconButtonColors(containerColor = backgroundColor), content = content)

@Composable
fun PocketTextField(
    value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, singleLine: Boolean = false, minLines: Int = 1, maxLines: Int = Int.MAX_VALUE,
    label: String = "", textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default,
    interactionSource: MutableInteractionSource? = null, leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null, cornerRadius: Dp = 12.dp,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors()
) = OutlinedTextField(value, onValueChange, modifier, enabled = enabled, textStyle = textStyle,
    label = if (label.isBlank()) null else { { Text(label) } }, singleLine = singleLine,
    minLines = minLines, maxLines = maxLines, visualTransformation = visualTransformation,
    keyboardOptions = keyboardOptions, keyboardActions = keyboardActions, interactionSource = interactionSource,
    leadingIcon = leadingIcon, trailingIcon = trailingIcon, shape = RoundedCornerShape(cornerRadius), colors = colors)

@Composable
fun PocketTextField(
    value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, singleLine: Boolean = false, minLines: Int = 1, maxLines: Int = Int.MAX_VALUE,
    label: String = "", textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default,
    interactionSource: MutableInteractionSource? = null, leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null, cornerRadius: Dp = 12.dp,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors()
) = OutlinedTextField(value, onValueChange, modifier, enabled = enabled, textStyle = textStyle,
    label = if (label.isBlank()) null else { { Text(label) } }, singleLine = singleLine,
    minLines = minLines, maxLines = maxLines, visualTransformation = visualTransformation,
    keyboardOptions = keyboardOptions, keyboardActions = keyboardActions, interactionSource = interactionSource,
    leadingIcon = leadingIcon, trailingIcon = trailingIcon, shape = RoundedCornerShape(cornerRadius), colors = colors)

@Composable
fun PocketScaffold(
    modifier: Modifier = Modifier, containerColor: Color = MaterialTheme.colorScheme.background,
    topBar: @Composable () -> Unit = {}, bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) = Scaffold(modifier, topBar = topBar, bottomBar = bottomBar, containerColor = containerColor,
    // The application shell owns horizontal/bottom/IME insets; each page owns its top app bar.
    contentWindowInsets = WindowInsets(0, 0, 0, 0), content = content)

@Composable
fun PocketTopAppBar(
    title: String, modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.background,
    navigationIcon: @Composable () -> Unit = {}, actions: @Composable RowScope.() -> Unit = {}
) = TopAppBar(title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    modifier = modifier, navigationIcon = navigationIcon, actions = actions,
    colors = TopAppBarDefaults.topAppBarColors(containerColor = color))

@Composable
fun PocketDialog(
    show: Boolean, title: String, onDismissRequest: () -> Unit, summary: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    if (!show) return
    Dialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().imePadding().safeDrawingPadding(), contentAlignment = Alignment.Center) {
            Surface(Modifier.padding(16.dp).widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight - 32.dp),
                shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp) {
                Column(Modifier.padding(24.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    summary?.let { Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Spacer(Modifier.height(16.dp))
                    content()
                }
            }
        }
    }
}

@Composable
fun PocketSmallTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary)
}

@Composable
fun PocketFloatingToolbar(
    color: Color = MaterialTheme.colorScheme.surfaceContainer, cornerRadius: Dp = 24.dp,
    shadowElevation: Dp = 3.dp, outSidePadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit
) = Surface(Modifier.padding(outSidePadding), shape = RoundedCornerShape(cornerRadius), color = color,
    tonalElevation = 3.dp, shadowElevation = shadowElevation, content = content)

@Composable
fun PocketListRow(
    title: String, summary: String? = null, modifier: Modifier = Modifier, holdDownState: Boolean = false,
    onClick: () -> Unit = {}, startAction: (@Composable () -> Unit)? = null, endActions: (@Composable () -> Unit)? = null
) {
    Surface(onClick, modifier.fillMaxWidth(), color = if (holdDownState) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent) {
        ListItem(headlineContent = { Text(title) }, supportingContent = summary?.let { { Text(it) } },
            leadingContent = startAction, trailingContent = endActions, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
    }
}

@Composable
fun PocketArrowPreference(
    title: String,
    summary: String? = null,
    startAction: (@Composable () -> Unit)? = null,
    onClick: () -> Unit = {}
) = PocketListRow(title, summary, onClick = onClick, startAction = startAction)

@Composable
fun PocketSwitchPreference(
    title: String,
    summary: String? = null,
    startAction: (@Composable () -> Unit)? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) = PocketListRow(title, summary, startAction = startAction, onClick = { onCheckedChange(!checked) },
    endActions = { Switch(checked = checked, onCheckedChange = onCheckedChange) })

@Composable
fun PocketSliderPreference(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit = {},
    title: String,
    summary: String? = null,
    valueText: String? = null,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    showKeyPoints: Boolean = false,
    keyPoints: List<Float> = emptyList(),
    hapticEffect: Any? = null,
    startAction: (@Composable () -> Unit)? = null
) {
    Column(Modifier.fillMaxWidth()) {
        PocketListRow(title, summary, startAction = startAction, onClick = {})
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Slider(value, onValueChange, valueRange = valueRange, steps = steps,
                onValueChangeFinished = onValueChangeFinished, modifier = Modifier.weight(1f))
            valueText?.let { Text(it, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary) }
        }
    }
}

data class PocketProgressColors(val foreground: Color, val background: Color)
object PocketProgressDefaults {
    @Composable fun progressIndicatorColors(
        foregroundColor: Color = MaterialTheme.colorScheme.primary,
        backgroundColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) = PocketProgressColors(foregroundColor, backgroundColor)
}
@Composable
fun PocketLinearProgressIndicator(
    progress: Float, modifier: Modifier = Modifier, colors: PocketProgressColors = PocketProgressDefaults.progressIndicatorColors()
) = LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = modifier,
    color = colors.foreground, trackColor = colors.background)

@Composable
fun PocketCircularProgressIndicator(
    modifier: Modifier = Modifier, strokeWidth: Dp = 3.dp, size: Dp = 36.dp,
    color: Color = MaterialTheme.colorScheme.primary
) = CircularProgressIndicator(modifier.size(size), color = color, strokeWidth = strokeWidth)
