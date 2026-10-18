package com.comfyui.mobile.ui.components

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics

/**
 * For conditionally composed forms: first drive OverlayDialog.show to false,
 * then let its actual onDismissFinished unmount the caller and discard drafts.
 * No timer: system animation scale, interrupted entry and window size stay Miuix-owned.
 */
@Stable
internal class DialogDismissState(private val onDismiss: () -> Unit) {
    var show by mutableStateOf(true)
        private set
    private var finished = false

    fun dismiss() { show = false }

    fun onDismissFinished() {
        if (!show && !finished) {
            finished = true
            onDismiss()
        }
    }

    /** An exiting form is a render-only snapshot, not another chance to save/edit. */
    val modifier: Modifier
        get() = if (show) Modifier else Modifier
            .clearAndSetSemantics { }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
}

@Composable
internal fun rememberDialogDismiss(onDismiss: () -> Unit): DialogDismissState {
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val state = remember { DialogDismissState { currentOnDismiss() } }
    // Miuix disables its own Back when show=false. Keep this one modal until its
    // exit finishes so a rapid second gesture cannot pop the page underneath.
    PredictiveBackHandler(enabled = !state.show) { progress -> progress.collect { } }
    return state
}
