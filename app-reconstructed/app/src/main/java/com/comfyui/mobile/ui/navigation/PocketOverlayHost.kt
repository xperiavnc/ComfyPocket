package com.comfyui.mobile.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils.Companion.MiuixPopupHost

/**
 * Keep each destination's Miuix overlay state/owners, but draw its host above the
 * whole app shell. A host inside NavHost is clipped under the sibling bottom bar.
 *
 * Capturing locals through Scaffold's public popupHost slot preserves its theme,
 * dialog stack, lifecycle, saved-state and Back owner without accessing Miuix internals.
 */
internal class PocketOverlayHost {
    private val contexts = mutableStateMapOf<String, CompositionLocalContext>()

    @Composable
    fun Capture(entryId: String) {
        val context = currentCompositionLocalContext
        SideEffect { contexts[entryId] = context }
        DisposableEffect(entryId) {
            onDispose { contexts.remove(entryId) }
        }
    }

    @Composable
    fun Render(activeEntryId: String?) {
        val context = contexts[activeEntryId] ?: return
        // Never leave an outgoing entry's dialog/BackHandler alive over the new route.
        key(activeEntryId) {
            CompositionLocalProvider(context) {
                MiuixPopupHost()
            }
        }
    }
}
