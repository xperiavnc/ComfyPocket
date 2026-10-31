@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.comfyui.mobile.ui.navigation

import androidx.activity.compose.PredictiveBackHandler
import com.comfyui.mobile.ui.designsystem.PocketTokens
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import com.comfyui.mobile.ui.theme.BottomBarStyle
import com.comfyui.mobile.ui.theme.ThemePaletteIds
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

/** Bottom bar show/hide when entering or leaving a secondary page. */
private const val PocketBarMillis = 250

internal val pocketTabs = listOf(Screen.Dashboard, Screen.Control, Screen.Agent, Screen.AiApps, Screen.Gallery)
internal val pocketSecondaryRoutes = listOf(
    "ai_app/{id}", "console", "settings", "theme", "servers", "about",
    "agent_settings", "agent_sessions", "cloud_tasks", "model_hub", "local_models", "plugins"
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
                        .clip(PocketTokens.Shape.Pill)
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
 * Both bar styles share the original page transitions and the same navigation stacks.
 * Shell owns horizontal/bottom/IME insets. Pages still own their status-bar inset.
 */
@Composable
internal fun PocketNavigationShell(
    navController: NavHostController = rememberNavController(),
    onTabSelected: () -> Unit = {},
    bottomBarStyle: BottomBarStyle = BottomBarStyle.MATERIAL,
    darkTheme: Boolean = false,
    paletteId: String = ThemePaletteIds.DEFAULT,
    content: @Composable (NavBackStackEntry) -> Unit
) {
    val entry by navController.currentBackStackEntryAsState()
    // Keep the graph builder stable. Capturing the changing entry in it rebuilds the graph,
    // which can discard saved UI state of covered destinations after Activity restoration.
    val currentEntry by rememberUpdatedState(entry)
    val destinationContent by rememberUpdatedState(content)
    val route = entry?.destination?.route
    val onTab = pocketTabs.any { it.route == route }
    // The keyboard hides the bar instantly (the IME already animates the layout);
    // route changes animate it so content does not jump when a secondary page opens.
    val imeVisible = WindowInsets.isImeVisible
    val showBar = onTab && !imeVisible
    // While the bar animates out for a secondary page, keep showing the tab it left from;
    // otherwise its indicator would snap to the first item during the exit.
    var lastTabRoute by rememberSaveable { mutableStateOf(Screen.Dashboard.route) }
    if (onTab && route != null && route != lastTabRoute) lastTabRoute = route
    val barRoute = if (onTab) route else lastTabRoute
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
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val floating = showBar && bottomBarStyle == BottomBarStyle.CLASSIC
    val bottomInset = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    val bottomGap = if (bottomInset > 0.dp) 4.dp else 24.dp
    // The content already consumes the system bottom inset. Reserve only the
    // capsule and its gap, and only while it is actually visible.
    val floatingClearance = if (floating) 52.dp + 10.dp + bottomGap else 0.dp
    CompositionLocalProvider(LocalFloatingNavClearance provides floatingClearance) {
        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding().testTag("pocket-shell"),
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            bottomBar = {
                if (!imeVisible && bottomBarStyle != BottomBarStyle.CLASSIC) AnimatedVisibility(
                    visible = onTab,
                    enter = expandVertically(tween(PocketBarMillis, easing = PocketEmphasizedDecelerate),
                        expandFrom = Alignment.Top) + fadeIn(tween(PocketBarMillis)),
                    exit = shrinkVertically(tween(PocketBarMillis, easing = PocketEmphasizedAccelerate),
                        shrinkTowards = Alignment.Top) + fadeOut(tween(PocketBarMillis / 2))
                ) {
                    NavigationBar(
                        modifier = Modifier.testTag("pocket-navigation"),
                        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                    ) {
                        PocketNavigationItems(barRoute, onTabSelected, focus, keyboard, navController)
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize()) {
              Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .clipToBounds().testTag("pocket-content")) {
                NavHost(
                    navController, startDestination = Screen.Dashboard.route,
                    enterTransition = { pocketNavEnter(
                        initialState.destination.route, targetState.destination.route, pop = false, rtl) },
                    exitTransition = { pocketNavExit(
                        initialState.destination.route, targetState.destination.route, pop = false, rtl) },
                    popEnterTransition = { pocketNavEnter(
                        initialState.destination.route, targetState.destination.route, pop = true, rtl) },
                    popExitTransition = { pocketNavExit(
                        initialState.destination.route, targetState.destination.route, pop = true, rtl) }
                ) {
                    (pocketTabs.map { it.route } + pocketSecondaryRoutes).forEach { destination ->
                        composable(destination) { backStack ->
                            val active = backStack.id == currentEntry?.id
                            // Opaque pages: a push slides over the covered page instead of
                            // blending two translucent layers during the transition.
                            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
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
              if (!imeVisible && bottomBarStyle == BottomBarStyle.CLASSIC) AnimatedVisibility(
                  visible = onTab,
                  modifier = Modifier.align(Alignment.BottomCenter),
                  enter = slideInVertically(tween(PocketBarMillis, easing = PocketEmphasizedDecelerate)) { it / 2 } +
                      fadeIn(tween(PocketBarMillis)),
                  exit = slideOutVertically(tween(PocketBarMillis, easing = PocketEmphasizedAccelerate)) { it / 2 } +
                      fadeOut(tween(PocketBarMillis / 2))
              ) {
                  PocketClassicNavigation(
                      barRoute, darkTheme, paletteId, bottomGap,
                      onItemClick = { index ->
                          val tab = pocketTabs[index]
                          if (navController.currentDestination?.route != tab.route) {
                              focus.clearFocus()
                              keyboard?.hide()
                              onTabSelected()
                              navController.selectPocketTab(tab.route)
                          }
                      }
                  )
              }
            }
        }
    }
}
