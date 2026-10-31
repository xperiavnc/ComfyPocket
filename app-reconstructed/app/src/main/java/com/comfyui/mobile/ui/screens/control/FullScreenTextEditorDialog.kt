package com.comfyui.mobile.ui.screens.control

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
fun FullScreenTextEditorDialog(
    title: String,
    initialText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
    ) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var text by remember { mutableStateOf(initialText) }
    val scrollState = rememberScrollState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier.fillMaxSize(),
            cornerRadius = 0.dp,
            colors = CardDefaults.defaultColors(color = Color.Black),
            onClick = null,
            pressFeedbackType = PressFeedbackType.Sink,
            showIndication = true
    ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
    ) {
                    Text(
                        text = title,
                        color = Color.White,
                        style = MiuixTheme.textStyles.title3,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    IconButton(onClick = { onConfirm(text) }) {
                        Icon(MiuixIcons.Ok, contentDescription = "Save", tint = Color.White)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(MiuixIcons.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val viewportHeightPx = with(density) { maxHeight.toPx() }
                    val thumbMinHeightPx = with(density) { 36.dp.toPx() }

                    Box(modifier = Modifier.fillMaxSize()) {
                        TextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(end = 22.dp),
                            label = "",
                            useLabelAsPlaceholder = true,
                            textStyle = MiuixTheme.textStyles.body1.copy(color = Color.White),
                            colors = TextFieldDefaults.textFieldColors(
                                backgroundColor = Color.Transparent,
                                labelColor = Color.White.copy(alpha = 0.5f),
                                borderColor = Color.White.copy(alpha = 0.35f)
    )
    )

                        val maxScroll = scrollState.maxValue.toFloat().coerceAtLeast(1f)
                        val scroll = scrollState.value.toFloat().coerceIn(0f, maxScroll)

                        val thumbHeightPx = (viewportHeightPx * (viewportHeightPx / (viewportHeightPx + maxScroll)))
                            .coerceIn(thumbMinHeightPx, viewportHeightPx)
                        val thumbRange = (viewportHeightPx - thumbHeightPx).coerceAtLeast(0f)
                        val thumbY = if (maxScroll <= 0f) 0f else (scroll / maxScroll) * thumbRange

                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .fillMaxHeight()
                                .width(18.dp)
                                .padding(top = 4.dp, bottom = 4.dp)
    ) {
                            Box(
                                modifier = Modifier
                                    .offset { IntOffset(0, thumbY.toInt()) }
                                    .fillMaxWidth()
                                    .height(with(density) { thumbHeightPx.toDp() })
                                    .pointerInput(maxScroll, viewportHeightPx, thumbHeightPx) {
                                        detectDragGestures { change, dragAmount ->
                                            change.consume()
                                            if (maxScroll <= 0f) return@detectDragGestures
                                            val newThumb = (thumbY + dragAmount.y).coerceIn(0f, thumbRange)
                                            val target = ((newThumb / thumbRange) * maxScroll).toInt()
                                            scope.launch { scrollState.scrollTo(target) }
                                        }
                                    }
                                    .background(Color.White.copy(alpha = 0.45f), shape = RoundedCornerShape(8.dp))
    ) {}
                        }
                    }
                }
            }
        }
    }
}
