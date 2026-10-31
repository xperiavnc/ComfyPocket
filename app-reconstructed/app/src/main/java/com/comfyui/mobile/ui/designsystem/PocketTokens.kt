package com.comfyui.mobile.ui.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * One scale for the whole app. Pages used a dozen different corner radii and
 * paddings, which is most of why neighbouring cards never looked related.
 *
 * Nesting rule: a container's radius is its child's radius plus the gap between
 * them, so a [Radius.Medium] chip inside a [Radius.Large] card sits 4dp in.
 */
object PocketTokens {
    object Radius {
        /** Tags, thumbnails inside rows, small inputs. */
        val Small = 8.dp
        /** Rows, inner tiles, text fields, buttons. */
        val Medium = 12.dp
        /** Cards and grouped sections. */
        val Large = 16.dp
        /** Sheets, dialogs and floating bars. */
        val XLarge = 22.dp
    }

    object Space {
        val XXS = 4.dp
        val XS = 8.dp
        val S = 12.dp
        val M = 16.dp
        val L = 20.dp
        val XL = 24.dp
    }

    /** Horizontal page inset shared by every tab and secondary page. */
    val PagePadding = Space.M
    /** Touch target for icon actions and compact controls. */
    val ControlHeight = 40.dp
    val CompactControlHeight = 36.dp

    object Shape {
        val Small = RoundedCornerShape(Radius.Small)
        val Medium = RoundedCornerShape(Radius.Medium)
        val Large = RoundedCornerShape(Radius.Large)
        val XLarge = RoundedCornerShape(Radius.XLarge)
        val Pill = RoundedCornerShape(50)
    }

    /** Status green, tuned to read on both light and dark surfaces. */
    val Success = Color(0xFF2EA043)
}

/**
 * Only two card surfaces: [base] for cards sitting on the page background and
 * [raised] for tiles that sit inside another card.
 */
object PocketCardColors {
    @Composable
    fun base(): CardColors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)

    @Composable
    fun raised(): CardColors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHigh)
}
