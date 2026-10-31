package com.comfyui.mobile.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/** Material 3 emphasized easing curves. Incoming content decelerates, outgoing accelerates. */
internal val PocketEmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
internal val PocketEmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
internal val PocketStandard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * Two transition families:
 *
 * - Shared axis (tab switches and in-page list/detail): a short horizontal shift with a
 *   fade-through. The outgoing page fades out quickly before the incoming page fades in,
 *   so two half-transparent pages never overlap ("ghosting").
 * - Push / pop (NavHost secondary pages): the opaque new page slides over the full width
 *   while the covered page recedes by a fraction of the width, like a native stack.
 */
internal data class PocketPageMotion(val enterDivisor: Int, val exitDivisor: Int) {
    val durationMillis = SHARED_AXIS_MILLIS
    val exitFadeMillis = FADE_OUT_MILLIS
    fun enterOffset(width: Int) = width / enterDivisor
    fun exitOffset(width: Int) = width / exitDivisor

    companion object {
        const val SHARED_AXIS_MILLIS = 300
        const val FADE_OUT_MILLIS = 90
        const val PUSH_MILLIS = 350
        const val POP_MILLIS = 300
        /** Shared-axis travel: 1/12 of the width, enough to show direction without "flying". */
        const val SHARED_AXIS_DIVISOR = 12
        /** The page being covered by a push recedes by 1/4 of the width. */
        const val PARALLAX_DIVISOR = 4
    }
}

/** Shared-axis offsets. Forward enters from the end side; pop mirrors it. */
internal fun pocketPageMotion(pop: Boolean, rtl: Boolean = false): PocketPageMotion {
    val sign = (if (rtl) -1 else 1) * (if (pop) -1 else 1)
    val d = PocketPageMotion.SHARED_AXIS_DIVISOR * sign
    return PocketPageMotion(d, -d)
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

/** True when both ends are top-level tabs: a lateral switch, not a hierarchy change. */
internal fun pocketIsTabSwitch(from: String?, to: String?): Boolean =
    pocketTabs.any { it.route == from } && pocketTabs.any { it.route == to }

/** Shared-axis enter: waits for the outgoing fade, then decelerates into place. */
internal fun pocketPageEnter(pop: Boolean, rtl: Boolean = false): EnterTransition {
    val motion = pocketPageMotion(pop, rtl)
    val fadeDelay = PocketPageMotion.FADE_OUT_MILLIS
    return slideInHorizontally(tween(motion.durationMillis, easing = PocketEmphasizedDecelerate), motion::enterOffset) +
        fadeIn(tween(motion.durationMillis - fadeDelay, delayMillis = fadeDelay, easing = PocketStandard))
}

/** Shared-axis exit: a quick fade so the incoming page is never drawn over a ghost. */
internal fun pocketPageExit(pop: Boolean, rtl: Boolean = false): ExitTransition {
    val motion = pocketPageMotion(pop, rtl)
    return slideOutHorizontally(tween(motion.durationMillis, easing = PocketEmphasizedAccelerate), motion::exitOffset) +
        fadeOut(tween(motion.exitFadeMillis, easing = PocketStandard))
}

/**
 * Push/pop for opaque NavHost destinations. NavHost keeps the popped page above the one
 * being revealed, so the same geometry reads correctly in both directions.
 */
internal fun pocketStackEnter(pop: Boolean, rtl: Boolean = false): EnterTransition {
    val sign = if (rtl) -1 else 1
    return if (pop) {
        slideInHorizontally(tween(PocketPageMotion.POP_MILLIS, easing = PocketEmphasizedDecelerate)) {
            -sign * it / PocketPageMotion.PARALLAX_DIVISOR
        } + fadeIn(tween(PocketPageMotion.POP_MILLIS, easing = PocketStandard), initialAlpha = 0.6f)
    } else {
        slideInHorizontally(tween(PocketPageMotion.PUSH_MILLIS, easing = PocketEmphasizedDecelerate)) { sign * it }
    }
}

internal fun pocketStackExit(pop: Boolean, rtl: Boolean = false): ExitTransition {
    val sign = if (rtl) -1 else 1
    return if (pop) {
        slideOutHorizontally(tween(PocketPageMotion.POP_MILLIS, easing = PocketEmphasizedDecelerate)) { sign * it }
    } else {
        // Stay opaque underneath; the push covers it. A light fade dims it as it recedes.
        slideOutHorizontally(tween(PocketPageMotion.PUSH_MILLIS, easing = PocketEmphasizedDecelerate)) {
            -sign * it / PocketPageMotion.PARALLAX_DIVISOR
        } + fadeOut(tween(PocketPageMotion.PUSH_MILLIS, easing = PocketStandard), targetAlpha = 0.6f)
    }
}

/** NavHost helper: tab ↔ tab is a shared-axis switch; anything else is push/pop. */
internal fun pocketNavEnter(from: String?, to: String?, pop: Boolean, rtl: Boolean): EnterTransition =
    if (pocketIsTabSwitch(from, to)) pocketPageEnter(pocketNavigationIsBackwards(from, to, pop), rtl)
    else pocketStackEnter(pop, rtl)

internal fun pocketNavExit(from: String?, to: String?, pop: Boolean, rtl: Boolean): ExitTransition =
    if (pocketIsTabSwitch(from, to)) pocketPageExit(pocketNavigationIsBackwards(from, to, pop), rtl)
    else pocketStackExit(pop, rtl)
