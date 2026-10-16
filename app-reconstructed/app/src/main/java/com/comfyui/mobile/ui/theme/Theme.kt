package com.comfyui.mobile.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * App theme: fixed hand-written light/dark palettes only.
 * No Monet / wallpaper dynamic color / seed→Monet.
 */
@Composable
fun ComfyControllerTheme(
    darkTheme: Boolean,
    paletteId: String = ThemePaletteIds.DEFAULT,
    content: @Composable () -> Unit
    ) {
    val palette = remember(paletteId) { themePaletteById(paletteId) }
    val colorSchemeMode = if (darkTheme) ColorSchemeMode.Dark else ColorSchemeMode.Light

    val controller = remember(colorSchemeMode, paletteId, darkTheme) {
        ThemeController(
            colorSchemeMode = colorSchemeMode,
            keyColor = null,
            lightColors = palette.lightColors,
            darkColors = palette.darkColors,
            isDark = darkTheme
    )
    }

    MiuixTheme(controller = controller) {
        val view = LocalView.current
        if (!view.isInEditMode) {
            SideEffect {
                val window = (view.context as Activity).window
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isStatusBarContrastEnforced = false
                    window.isNavigationBarContrastEnforced = false
                }
                WindowCompat.setDecorFitsSystemWindows(window, false)
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !darkTheme
                insetsController.isAppearanceLightNavigationBars = !darkTheme
            }
        }
        content()
    }
}
