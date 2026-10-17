package com.comfyui.mobile.ui.screens.danbooru

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.comfyui.mobile.danbooru.DanbooruSettings
import com.comfyui.mobile.danbooru.DanbooruState
import com.comfyui.mobile.ui.material.MaterialConnectionEditor
import com.comfyui.mobile.ui.material.MaterialConnectionScreen

/** Full settings page. The retained editor keeps drafts out of SavedState. */
@Composable
internal fun DanbooruSettingsDialog(
    initial: DanbooruSettings, state: DanbooruState, onDismiss: () -> Unit,
    onSave: suspend (DanbooruSettings) -> Result<Unit>,
    onTest: suspend (DanbooruSettings) -> String,
    onLogout: suspend () -> Result<Unit>, onClear: suspend () -> String
) {
    val editor: MaterialConnectionEditor = viewModel(key = "danbooru-connection-editor")
    editor.beginIfNeeded(initial)
    val context = LocalContext.current
    MaterialConnectionScreen(editor, online = true,
        onClose = { editor.finish(); onDismiss() }, onSave = onSave, onTest = onTest, onClear = onClear,
        onSaved = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() })
}
