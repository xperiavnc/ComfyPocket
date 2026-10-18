package com.comfyui.mobile.ui.feedback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

/** Original system Toast presentation, shared by ViewModels, screens and dialogs. */
object AppToast {
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var current: Toast? = null

    fun show(context: Context, message: String) {
        val text = message.trim()
        if (text.isEmpty()) return
        // Capture the Application, never an Activity or a Compose destination.
        val app = context.applicationContext
        // Always enqueue, even on main: a newer main-thread result must not be
        // overwritten by an older worker-thread message already waiting here.
        mainHandler.post {
            try {
                current?.cancel()
                current = null
                Toast.makeText(app, text, Toast.LENGTH_SHORT).also {
                    current = it
                    it.show()
                }
            } catch (error: RuntimeException) {
                current = null
                Log.w("AppToast", "System feedback failed: ${error.javaClass.simpleName}")
            }
        }
    }
}
