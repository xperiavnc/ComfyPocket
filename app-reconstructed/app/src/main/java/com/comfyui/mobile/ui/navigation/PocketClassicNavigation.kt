package com.comfyui.mobile.ui.navigation

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.comfyui.mobile.ui.liquid.IosLiquidGlassNavigationBar
import com.comfyui.mobile.ui.theme.ComfyControllerTheme
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Create
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Photos
import top.yukonga.miuix.kmp.icon.extended.Tune

@Composable
internal fun PocketClassicNavigation(
    route: String?,
    dark: Boolean,
    palette: String,
    bottomGap: Dp,
    onItemClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val items = remember {
        val icons = listOf(MiuixIcons.Home, MiuixIcons.Tune, MiuixIcons.Create, MiuixIcons.GridView, MiuixIcons.Photos)
        pocketTabs.mapIndexed { index, tab -> NavigationItem(label = tab.label, icon = icons[index]) }
    }
    BoxWithConstraints(
        modifier.fillMaxWidth().testTag("pocket-navigation")
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
    ) {
        // Preserve the old 44dp side margins where possible; keep five 48dp touch
        // targets usable in compact windows and at the app's maximum UI scale.
        val side = minOf(44.dp, ((maxWidth - 248.dp) / 2).coerceAtLeast(8.dp))
        ComfyControllerTheme(dark, palette, manageSystemBars = false) {
            IosLiquidGlassNavigationBar(
                items = items,
                selectedIndex = pocketTabs.indexOfFirst { it.route == route }.coerceAtLeast(0),
                onItemClick = onItemClick,
                backdrop = null,
                isBlurActive = false,
                isDark = dark,
                bottomPaddingOverride = bottomGap,
                horizontalPadding = side,
                modifier = Modifier.testTag("pocket-classic-navigation")
            )
        }
    }
}
