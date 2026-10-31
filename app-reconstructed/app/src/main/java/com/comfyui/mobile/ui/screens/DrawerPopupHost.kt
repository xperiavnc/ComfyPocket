@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.comfyui.mobile.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.utils.LocalRootDialogStates
import top.yukonga.miuix.kmp.utils.LocalRootPopupStates

/**
 * Miuix's Scaffold reuses an inherited root popup host when one exists. The Agent page's host lives
 * in the Activity window, beneath the drawer's Dialog window, so dialogs opened from the drawer were
 * drawn behind it. Clearing the root locals makes this Scaffold install its own host in the drawer
 * window. Uses Miuix internals (pinned at 0.9.3); re-check on upgrade.
 */
@Composable
internal fun DrawerPopupHost(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRootDialogStates provides null, LocalRootPopupStates provides null) {
        Scaffold(containerColor = Color.Transparent) { content() }
    }
}
