package com.comfyui.mobile.ui.screens.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import com.comfyui.mobile.ui.components.rememberDialogDismiss
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/** Mount conditionally. Keep the closing snapshot until the overlay reports its real exit. */
@Composable
internal fun AgentActionDialog(
    title: String,
    onClosed: () -> Unit,
    content: @Composable ColumnScope.(close: () -> Unit, closeThen: (() -> Unit) -> Unit) -> Unit
) {
    var afterClose by remember { mutableStateOf<(() -> Unit)?>(null) }
    val dismissal = rememberDialogDismiss {
        onClosed()
        afterClose?.invoke()
    }
    val closeThen: (() -> Unit) -> Unit = { action ->
        if (dismissal.show) {
            afterClose = action
            dismissal.dismiss()
        }
    }
    OverlayDialog(show = dismissal.show, title = title,
        onDismissRequest = dismissal::dismiss, onDismissFinished = dismissal::onDismissFinished) {
        Column(modifier = dismissal.modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content(dismissal::dismiss, closeThen)
        }
    }
}
