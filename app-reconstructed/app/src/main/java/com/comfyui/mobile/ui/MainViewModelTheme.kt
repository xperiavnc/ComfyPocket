package com.comfyui.mobile.ui

import com.comfyui.mobile.ui.theme.ThemePaletteIds

object MainViewModelTheme {

    fun setThemeMode(vm: MainViewModel, mode: Int) {
        val next = mode.coerceIn(MainViewModel.THEME_FOLLOW_SYSTEM, MainViewModel.THEME_DARK)
        vm._themeMode.value = next
        vm._isDarkTheme.value = next == MainViewModel.THEME_DARK
        vm.prefs.edit()
            .putInt("theme_mode", next)
            .putBoolean("is_dark_theme", next == MainViewModel.THEME_DARK)
            .apply()
    }

    fun toggleTheme(vm: MainViewModel, isDark: Boolean) {
        setThemeMode(vm, if (isDark) MainViewModel.THEME_DARK else MainViewModel.THEME_LIGHT)
    }

    fun setThemePalette(vm: MainViewModel, paletteId: String) {
        val next = ThemePaletteIds.normalize(paletteId)
        vm._themePaletteId.value = next
        vm.prefs.edit()
            .putString("theme_palette_id", next)
            // Drop legacy Monet / seed prefs so they cannot resurrect.
            .remove("is_monet_enabled")
            .remove("custom_seed_color")
            .apply()
    }

    fun toggleLiquidGlass(vm: MainViewModel, enable: Boolean) {
        vm._isLiquidGlassEnabled.value = enable
        vm.prefs.edit().putBoolean("liquid_glass_enabled", enable).apply()
    }

    fun setLiveUpdateShowPercent(vm: MainViewModel, enable: Boolean) {
        vm._liveUpdateShowPercent.value = enable
        vm.prefs.edit().putBoolean("live_update_show_percent", enable).apply()
    }

    fun setUiScale(vm: MainViewModel, scale: Float) {
        val next = ((scale * 100f).toInt().coerceIn(85, 130)) / 100f
        vm._uiScale.value = next
        vm.prefs.edit().putFloat("ui_density_scale", next).apply()
    }
}
