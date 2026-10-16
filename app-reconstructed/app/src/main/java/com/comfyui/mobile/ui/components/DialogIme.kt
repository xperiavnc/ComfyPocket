package com.comfyui.mobile.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * OverlayDialog form layout that stays usable with the IME.
 *
 * - Relies on OverlayDialog defaultWindowInsetsPadding (IME/nav) to lift the
 *   whole card above the keyboard. Do NOT clamp height by (screen - ime).
 * - [content] scrolls in the middle with a soft max height (screen fraction only).
 * - [footer] (Cancel/Save) stays fixed below the scroll, always visible in the card.
 */
@Composable
fun DialogFormColumn(
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    /** Soft max for the scrollable middle; based on screen size, not IME. */
    scrollMaxFraction: Float = 0.42f,
    scrollMaxMin: Dp = 180.dp,
    scrollMaxCap: Dp = 420.dp,
    footer: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    val scrollMax = (screenH * scrollMaxFraction).coerceIn(scrollMaxMin, scrollMaxCap)

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = horizontalAlignment,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = scrollMax)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = horizontalAlignment,
            content = content,
        )
        footer()
    }
}
