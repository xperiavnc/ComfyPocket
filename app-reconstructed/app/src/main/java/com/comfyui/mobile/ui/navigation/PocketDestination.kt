package com.comfyui.mobile.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * NavHost retains outgoing entries for animation. They may render, but must not keep a
 * leaf BackHandler or an active Danbooru owner while a different entry is current.
 * SaveableStateRegistry / ViewModelStoreOwner remain NavHost's original entry.
 */
internal class PocketDestinationLifecycle(private val parent: Lifecycle) : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    var active = false
        set(value) { field = value; sync() }
    private val observer = LifecycleEventObserver { _, _ -> sync() }
    init { parent.addObserver(observer); sync() }
    private fun sync() {
        val state = parent.currentState
        registry.currentState = if (active || state < Lifecycle.State.STARTED) state else Lifecycle.State.CREATED
    }
    fun close() {
        parent.removeObserver(observer)
        registry.currentState = Lifecycle.State.DESTROYED
    }
}

@Composable
internal fun PocketDestination(active: Boolean, content: @Composable () -> Unit) {
    val parent = LocalLifecycleOwner.current.lifecycle
    val owner = remember(parent) { PocketDestinationLifecycle(parent) }
    // Update before children install handlers, not one animation frame after them.
    owner.active = active
    DisposableEffect(owner) { onDispose { owner.close() } }
    CompositionLocalProvider(LocalLifecycleOwner provides owner) {
        Box(if (active) Modifier.fillMaxSize() else Modifier.fillMaxSize()
            .clearAndSetSemantics { }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }) { content() }
    }
}
