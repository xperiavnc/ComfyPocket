package com.comfyui.mobile.ui.material

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.comfyui.mobile.danbooru.DanbooruState

internal data class MaterialPage(val tab: Int, val detail: Boolean = false, val settings: Boolean = false) {
    val depth get() = (if (detail) 1 else 0) + (if (settings) 1 else 0)
    val name get() = when { settings -> "settings"; detail -> "detail"; else -> "tab-$tab" }
}

/** State is a rendering snapshot, not the animation key: loading updates must not navigate. */
internal data class MaterialPageFrame(val page: MaterialPage, val state: DanbooruState, val namespace: String)

internal enum class MaterialPageMotion { NONE, TAB, ENTER, RETURN }

internal fun materialPageMotion(from: MaterialPage, to: MaterialPage): MaterialPageMotion = when {
    from == to -> MaterialPageMotion.NONE
    to.depth > from.depth -> MaterialPageMotion.ENTER
    to.depth < from.depth -> MaterialPageMotion.RETURN
    else -> MaterialPageMotion.TAB
}

/**
 * Only the current page owns input/accessibility/back handling. The outgoing page keeps its
 * immutable content until the animation ends, even when the ViewModel clears the detail.
 * No size animation, blur, image resampling or per-frame network/state work.
 */
@Composable
internal fun MaterialPageTransition(
    frame: MaterialPageFrame, modifier: Modifier = Modifier,
    animateTabs: Boolean = true, hostTag: String = "material-motion-host", pageTagPrefix: String = "material-page",
    content: @Composable (MaterialPageFrame, Boolean) -> Unit
) {
    fun pageKey(page: MaterialPage) = if (!animateTabs && page.depth == 0) MaterialPage(-1) else page
    val density = LocalDensity.current
    val rtl = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    val short = with(density) { 12.dp.roundToPx() }
    val enter = with(density) { 32.dp.roundToPx() }
    val behind = with(density) { 8.dp.roundToPx() }
    val target = pageKey(frame.page)
    val latestTarget by rememberUpdatedState(target)
    // Only the route drives Transition. A request finishing mid-enter must neither restart
    // nor prematurely finish that animation. Snapshots are released with outgoing content.
    val snapshots = remember { mutableMapOf<MaterialPage, MaterialPageFrame>() }
    snapshots[target] = frame
    val transition = updateTransition(target, label = "Material page")
    transition.AnimatedContent(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).clipToBounds()
            .testTag(hostTag).semantics {
                stateDescription = if (transition.isRunning)
                    "页面切换中" else "页面稳定"
            },
        contentKey = { it },
        transitionSpec = {
            val motion = materialPageMotion(initialState, targetState)
            val direction = rtl * if (motion == MaterialPageMotion.TAB && targetState.tab < initialState.tab) -1 else 1
            val transform = when (motion) {
                MaterialPageMotion.TAB ->
                    (fadeIn(tween(140, delayMillis = 40, easing = LinearOutSlowInEasing)) + slideInHorizontally(tween(180, easing = FastOutSlowInEasing)) { direction * short })
                        .togetherWith(fadeOut(tween(60, easing = FastOutLinearInEasing)) +
                            slideOutHorizontally(tween(180, easing = FastOutSlowInEasing)) { -direction * short })
                MaterialPageMotion.ENTER ->
                    (fadeIn(tween(160, delayMillis = 60, easing = LinearOutSlowInEasing)) + slideInHorizontally(tween(220, easing = FastOutSlowInEasing)) { rtl * enter })
                        .togetherWith(fadeOut(tween(80, easing = FastOutLinearInEasing)) +
                            slideOutHorizontally(tween(220, easing = FastOutSlowInEasing)) { -rtl * behind })
                MaterialPageMotion.RETURN ->
                    (fadeIn(tween(140, delayMillis = 40, easing = LinearOutSlowInEasing)) + slideInHorizontally(tween(180, easing = FastOutSlowInEasing)) { -rtl * behind })
                        .togetherWith(fadeOut(tween(60, easing = FastOutLinearInEasing)) +
                            slideOutHorizontally(tween(180, easing = FastOutSlowInEasing)) { rtl * enter })
                        .apply { targetContentZIndex = -1f }
                MaterialPageMotion.NONE -> fadeIn(tween(0)).togetherWith(fadeOut(tween(0)))
            }
            transform.using(null)
        }
    ) { destination ->
        DisposableEffect(destination) {
            onDispose { if (destination != latestTarget) snapshots.remove(destination) }
        }
        val active = destination == target
        val shown = if (active) frame else checkNotNull(snapshots[destination])
        Box(Modifier.fillMaxSize().testTag("$pageTagPrefix-${shown.page.name}")) {
            Box(if (active) Modifier.fillMaxSize() else Modifier.fillMaxSize().clearAndSetSemantics { }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }) {
                content(shown, active)
            }
        }
    }
}
