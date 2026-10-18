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
