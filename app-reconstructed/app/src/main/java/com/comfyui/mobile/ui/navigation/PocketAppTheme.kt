package com.comfyui.mobile.ui.navigation

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.comfyui.mobile.ui.designsystem.PocketMaterialTheme
import com.comfyui.mobile.ui.theme.themePaletteById

/** Explicit preference adapter, not a Miuix theme surrounding Material controls. */
@Composable
internal fun PocketAppTheme(dark: Boolean, paletteId: String, content: @Composable () -> Unit) {
    val palette = remember(paletteId) { themePaletteById(paletteId) }
    val legacy = if (dark) palette.darkColors else palette.lightColors
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
    PocketMaterialTheme(dark) {
        val colors = MaterialTheme.colorScheme.copy(
            primary = legacy.primary, onPrimary = legacy.onPrimary,
            primaryContainer = legacy.primaryContainer, onPrimaryContainer = legacy.onPrimaryContainer,
            secondary = legacy.secondary, onSecondary = legacy.onSecondary,
            // Miuix's secondaryContainer is a white card, not a selected Material indicator.
            secondaryContainer = legacy.primaryContainer, onSecondaryContainer = legacy.onPrimaryContainer,
            tertiaryContainer = legacy.tertiaryContainer, onTertiaryContainer = legacy.onTertiaryContainer,
            background = legacy.background, onBackground = legacy.onBackground,
            surface = legacy.background, onSurface = legacy.onSurface,
            surfaceContainer = legacy.surfaceContainer,
            surfaceContainerHigh = legacy.surfaceContainerHigh,
            surfaceContainerHighest = legacy.surfaceContainerHighest,
            surfaceVariant = legacy.surfaceVariant, outline = legacy.outline, error = legacy.error
        )
        MaterialTheme(colorScheme = colors, content = content)
    }
}
