@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.comfyui.mobile.ui.navigation

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.comfyui.mobile.R
import com.comfyui.mobile.Screen
import com.comfyui.mobile.diagnostics.NavigationDiagnostics
import com.comfyui.mobile.ui.LocalFloatingNavClearance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

internal val pocketTabs = listOf(Screen.Dashboard, Screen.Control, Screen.Agent, Screen.AiApps, Screen.Gallery)
internal val pocketSecondaryRoutes = listOf(
    "ai_app/{id}", "console", "settings", "theme", "servers", "about",
    "agent_settings", "cloud_tasks", "model_hub"
)

/** A reselect is a no-op. All switches, including root Back, preserve the departing stack. */
internal fun NavHostController.selectPocketTab(route: String) {
    require(pocketTabs.any { it.route == route })
    if (currentDestination?.route == route) return
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

internal data class PocketRouteMotion(val enter: Int, val exit: Int, val duration: Int, val tab: Boolean)

internal fun pocketRouteMotion(from: String?, to: String?, pop: Boolean, rtl: Boolean): PocketRouteMotion {
    val a = pocketTabs.indexOfFirst { it.route == from }
    val b = pocketTabs.indexOfFirst { it.route == to }
    val sign = if (rtl) -1 else 1
    return when {
        a >= 0 && b >= 0 -> {
            val direction = sign * if (b < a) -1 else 1
            PocketRouteMotion(direction * 12, -direction * 12, 180, true)
        }
        pop -> PocketRouteMotion(-sign * 8, sign * 32, 180, false)
        else -> PocketRouteMotion(sign * 32, -sign * 8, 220, false)
    }
}

@Composable
private fun PocketNavigationItems(
    route: String?,
    onTabSelected: () -> Unit,
    focus: FocusManager,
    keyboard: SoftwareKeyboardController?,
    navController: NavHostController
) {
    val icons = listOf(
        Icons.Default.Home,
        Icons.Default.Build,
        Icons.Default.Person,
        Icons.AutoMirrored.Filled.List,
        ImageVector.vectorResource(R.drawable.material_symbol_gallery)
    )
    val selectedIndex = pocketTabs.indexOfFirst { it.route == route }.coerceAtLeast(0)
    val layoutDirection = LocalLayoutDirection.current
    val density = LocalDensity.current
    var barCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().onPlaced { barCoordinates = it }
    ) {
        // NavigationBarItem places icons above the bar's vertical center, with labels below.
        // Anchor to the actual placed icon slots so font scale, insets and RTL share one geometry.
        val iconBounds = remember(maxWidth, layoutDirection, density.density, density.fontScale) {
            mutableStateMapOf<Int, Rect>()
        }
        val itemWidth = maxWidth / pocketTabs.size
        val indicatorWidth = minOf(64.dp, (itemWidth - 16.dp).coerceAtLeast(0.dp))
        iconBounds[selectedIndex]?.let { icon ->
            key(maxWidth, layoutDirection, density.density, density.fontScale) {
                val widthPx = with(density) { indicatorWidth.toPx() }
                val heightPx = with(density) { 32.dp.toPx() }
                val indicatorX = animateFloatAsState(
                    targetValue = icon.center.x - widthPx / 2,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    ),
                    label = "pocket-navigation-indicator"
                )
                Box(
                    modifier = Modifier
                        // Coordinates already include RTL; never mirror this physical origin again.
                        .align(AbsoluteAlignment.TopLeft)
                        .absoluteOffset {
                            IntOffset(indicatorX.value.roundToInt(), (icon.center.y - heightPx / 2).roundToInt())
                        }
                        .width(indicatorWidth)
                        .height(32.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .testTag("pocket-navigation-indicator")
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            pocketTabs.forEachIndexed { index, tab ->
                NavigationBarItem(
                    selected = route == tab.route,
                    onClick = {
                        if (route != tab.route) {
                            focus.clearFocus()
                            keyboard?.hide()
                            onTabSelected()
                            navController.selectPocketTab(tab.route)
                        }
                    },
                    modifier = Modifier.weight(1f).testTag("pocket-tab-$index"),
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = Color.Transparent
                    ),
                    icon = { Icon(icons[index], null, Modifier.testTag("pocket-tab-icon-$index")
                        .onPlaced { placedIcon ->
                            barCoordinates?.takeIf { it.isAttached }?.let { bar ->
                                iconBounds[index] = bar.localBoundingBoxOf(placedIcon, clipBounds = false)
                            }
                        }) },
                    label = { Text(tab.label, Modifier.testTag("pocket-tab-label-$index"),
                        maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }
        }
    }
}

/**
 * Formal Material shell; content retains its own top app bar until its P3/P4/P5 migration.
 * Shell owns horizontal/bottom/IME insets. Legacy pages still own their status-bar inset.
 */
@Composable
internal fun PocketNavigationShell(
    navController: NavHostController = rememberNavController(),
    messages: Flow<String> = remember { emptyFlow() },
    onTabSelected: () -> Unit = {},
    content: @Composable (NavBackStackEntry) -> Unit
) {
    val entry by navController.currentBackStackEntryAsState()
    // Keep the graph builder stable. Capturing the changing entry in it rebuilds the graph,
    // which can discard saved UI state of covered destinations after Activity restoration.
    val currentEntry by rememberUpdatedState(entry)
    val destinationContent by rememberUpdatedState(content)
    val route = entry?.destination?.route
    val showBar = pocketTabs.any { it.route == route } && !WindowInsets.isImeVisible
    val snackbar = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(messages, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            messages.collectLatest { message ->
                if (message.isNotBlank()) {
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(message, withDismissAction = true)
                }
            }
        }
    }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(entry?.id) { focus.clearFocus(); keyboard?.hide() }
    DisposableEffect(navController) {
        val listener = androidx.navigation.NavController.OnDestinationChangedListener { _, destination, _ ->
            NavigationDiagnostics.record(NavigationDiagnostics.Event.DESTINATION_CHANGED, destination.route)
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }
    val events = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
    LaunchedEffect(events) {
        events?.transitionState?.map { it is NavigationEventTransitionState.InProgress }
            ?.distinctUntilChanged()?.collect { inProgress ->
                NavigationDiagnostics.record(if (inProgress) NavigationDiagnostics.Event.GESTURE_STARTED
                    else NavigationDiagnostics.Event.GESTURE_IDLE)
            }
    }
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // No floating overlay and no obsolete phantom clearance on secondary pages.
    CompositionLocalProvider(LocalFloatingNavClearance provides 0.dp) {
        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding().testTag("pocket-shell"),
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            snackbarHost = { SnackbarHost(snackbar, Modifier.testTag("pocket-feedback")) },
            bottomBar = {
                if (showBar) NavigationBar(
                    modifier = Modifier.testTag("pocket-navigation"),
                    windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                ) {
                    PocketNavigationItems(route, onTabSelected, focus, keyboard, navController)
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .clipToBounds().testTag("pocket-content")) {
                NavHost(
                    navController, startDestination = Screen.Dashboard.route,
                    enterTransition = {
                        val motion = pocketRouteMotion(initialState.destination.route, targetState.destination.route, false, rtl)
                        fadeIn(tween(if (motion.tab) 140 else 160, if (motion.tab) 40 else 60)) +
                            slideInHorizontally(tween(motion.duration, easing = FastOutSlowInEasing)) {
                                with(density) { motion.enter.dp.roundToPx() }
                            }
                    },
                    exitTransition = {
                        val motion = pocketRouteMotion(initialState.destination.route, targetState.destination.route, false, rtl)
                        fadeOut(tween(if (motion.tab) 60 else 80)) +
                            slideOutHorizontally(tween(motion.duration, easing = FastOutSlowInEasing)) {
                                with(density) { motion.exit.dp.roundToPx() }
                            }
                    },
                    popEnterTransition = {
                        val motion = pocketRouteMotion(initialState.destination.route, targetState.destination.route, true, rtl)
                        fadeIn(tween(140, 40)) + slideInHorizontally(tween(motion.duration, easing = FastOutSlowInEasing)) {
                            with(density) { motion.enter.dp.roundToPx() }
                        }
                    },
                    popExitTransition = {
                        val motion = pocketRouteMotion(initialState.destination.route, targetState.destination.route, true, rtl)
                        fadeOut(tween(60)) + slideOutHorizontally(tween(motion.duration, easing = FastOutSlowInEasing)) {
                            with(density) { motion.exit.dp.roundToPx() }
                        }
                    }
                ) {
                    (pocketTabs.map { it.route } + pocketSecondaryRoutes).forEach { destination ->
                        composable(destination) { backStack ->
                            val active = backStack.id == currentEntry?.id
                            PocketDestination(active) {
                                // Leaf handlers are composed later and take precedence over root Back.
                                PredictiveBackHandler(enabled = active && destination != Screen.Dashboard.route &&
                                    pocketTabs.any { it.route == destination }) { progress ->
                                    progress.collect { }
                                    if (navController.currentBackStackEntry?.id == backStack.id)
                                        navController.selectPocketTab(Screen.Dashboard.route)
                                }
                                destinationContent(backStack)
                            }
                        }
                    }
                }
            }
        }
    }
}
