package com.comfyui.mobile.ui.screens.danbooru

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Velocity
import com.comfyui.mobile.ui.navigation.PocketStandard
import kotlin.math.roundToInt

/**
 * Search/sort controls that scroll away with the grid and come back on the first
 * downward drag (enter-always), so the images get the screen while browsing.
 *
 * The header gives up its height instead of drawing over the grid: the pull-to-refresh
 * indicator and the first row stay visible below it, and the grid itself only moves
 * once the header is fully collapsed or fully expanded.
 */
@Stable
internal class DanbooruHeaderState(initialOffset: Float = 0f) {
    /** How many pixels of the header are hidden, 0 (expanded) to [height]. */
    var offset by mutableFloatStateOf(initialOffset)
        private set
    /** Measured header height; plain field so measurement never invalidates itself. */
    internal var height = 0f
    /** Bumped by every scroll, so a running settle animation yields to the next gesture. */
    private var generation = 0
    val collapsed get() = height > 0f && offset >= height - 0.5f

    /** Returns how much of [delta] (scroll pixels, negative = finger moving up) was used. */
    fun consume(delta: Float): Float {
        generation++
        if (height <= 0f) return 0f
        val next = (offset - delta).coerceIn(0f, height)
        val used = offset - next
        offset = next
        return used
    }

    fun expand() { generation++; offset = 0f }

    internal fun clamp() { if (offset > height) offset = height }

    /** Never leave the header half hidden after a gesture. */
    suspend fun settle() {
        if (height <= 0f || offset <= 0f || offset >= height) return
        val target = if (offset > height / 2) height else 0f
        val mine = ++generation
        animate(offset, target, animationSpec = tween(200, easing = PocketStandard)) { value, _ ->
            if (generation == mine) offset = value.coerceIn(0f, height)
        }
    }

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
            // Hide first when scrolling into the list, reveal first when scrolling back.
            Offset(0f, consume(available.y))

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            settle()
            return Velocity.Zero
        }
    }

    companion object {
        val Saver = Saver<DanbooruHeaderState, Float>({ it.offset }, { DanbooruHeaderState(it) })
    }
}

@Composable
internal fun rememberDanbooruHeaderState(): DanbooruHeaderState =
    rememberSaveable(saver = DanbooruHeaderState.Saver) { DanbooruHeaderState() }

@Composable
internal fun DanbooruCollapsingHeader(state: DanbooruHeaderState, content: @Composable () -> Unit) {
    Box(
        Modifier.clipToBounds().testTag("danbooru-browse-header").layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            state.height = placeable.height.toFloat()
            state.clamp()
            val hidden = state.offset.roundToInt().coerceIn(0, placeable.height)
            layout(placeable.width, placeable.height - hidden) { placeable.place(0, -hidden) }
        }.graphicsLayer {
            // Fade slightly ahead of the slide so half-hidden text never looks cut.
            alpha = if (state.height <= 0f) 1f else (1f - state.offset / (state.height * 0.7f)).coerceIn(0f, 1f)
        }
    ) { content() }
}
