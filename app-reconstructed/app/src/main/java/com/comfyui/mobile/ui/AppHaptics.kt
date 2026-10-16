package com.comfyui.mobile.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Light app haptics via [View.performHapticFeedback].
 * Does not pass IGNORE flags, so Android / OEM "touch feedback" settings are respected.
 */
class AppHaptics internal constructor(private val view: View) {

    /** Soft tick: tab switch, secondary tap. */
    fun tick() {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /** Confirm: primary run/save, dialog OK, palette select. */
    fun confirm() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.KEYBOARD_TAP
        }
        view.performHapticFeedback(type)
    }

    /** Toggle: switches / checkboxes. */
    fun toggle() {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }
}

@Composable
fun rememberAppHaptics(): AppHaptics {
    val view = LocalView.current
    return remember(view) { AppHaptics(view) }
}
