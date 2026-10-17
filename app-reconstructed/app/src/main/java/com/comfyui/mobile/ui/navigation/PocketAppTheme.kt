package com.comfyui.mobile.ui.navigation

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.comfyui.mobile.ui.designsystem.PocketMaterialTheme
import com.comfyui.mobile.ui.theme.themePaletteById

/** Applies the persisted fixed palette to the single application Material theme. */
@Composable
internal fun PocketAppTheme(dark: Boolean, paletteId: String, content: @Composable () -> Unit) {
    val palette = remember(paletteId) { themePaletteById(paletteId) }
    val colors = if (dark) palette.darkColors else palette.lightColors
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val window = (view.context as Activity).window
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    PocketMaterialTheme(dark, colorScheme = colors, content = content)
}
