package com.comfyui.mobile.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/** The pre-P2 page transition, shared by both bottom bars and Danbooru details. */
internal data class PocketPageMotion(val enterDivisor: Int, val exitDivisor: Int) {
    val durationMillis = 280
    val exitFadeMillis = 220
    fun enterOffset(width: Int) = width / enterDivisor
    fun exitOffset(width: Int) = width / exitDivisor
}

internal fun pocketPageMotion(pop: Boolean, rtl: Boolean = false): PocketPageMotion {
    val sign = if (rtl) -1 else 1
    return if (pop) PocketPageMotion(-8 * sign, 5 * sign)
    else PocketPageMotion(5 * sign, -8 * sign)
}

/**
 * Saved tab stacks can be restored by either a navigate or a pop operation.
 * Their visual direction follows the bar order, not NavController's operation.
 * Secondary pages retain the original push/pop behavior.
 */
internal fun pocketNavigationIsBackwards(from: String?, to: String?, pop: Boolean): Boolean {
    val fromIndex = pocketTabs.indexOfFirst { it.route == from }
    val toIndex = pocketTabs.indexOfFirst { it.route == to }
    return if (fromIndex >= 0 && toIndex >= 0 && fromIndex != toIndex) toIndex < fromIndex else pop
}

internal fun pocketPageEnter(pop: Boolean, rtl: Boolean = false): EnterTransition {
    val motion = pocketPageMotion(pop, rtl)
    return slideInHorizontally(tween(motion.durationMillis), motion::enterOffset) +
        fadeIn(tween(motion.durationMillis))
}

internal fun pocketPageExit(pop: Boolean, rtl: Boolean = false): ExitTransition {
    val motion = pocketPageMotion(pop, rtl)
    return slideOutHorizontally(tween(motion.durationMillis), motion::exitOffset) +
        fadeOut(tween(motion.exitFadeMillis))
}
