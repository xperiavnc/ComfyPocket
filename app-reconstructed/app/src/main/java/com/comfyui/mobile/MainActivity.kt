package com.comfyui.mobile

import android.os.Bundle
import android.os.Build
import android.Manifest
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.activity.viewModels
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.comfyui.mobile.data.AppModule
import com.comfyui.mobile.diagnostics.NavigationDiagnostics
import androidx.navigation.NavController
import androidx.navigationevent.NavigationEventTransitionState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.comfyui.mobile.ui.MainViewModel
import com.comfyui.mobile.ui.rememberAppHaptics
import com.comfyui.mobile.ui.screens.AboutScreen
import com.comfyui.mobile.ui.screens.AgentScreen
import com.comfyui.mobile.ui.screens.AgentSettingsScreen
import com.comfyui.mobile.ui.screens.AiAppDetailScreen
import com.comfyui.mobile.ui.screens.AiAppsScreen
import com.comfyui.mobile.ui.screens.ConsoleScreen
import com.comfyui.mobile.ui.screens.ControlScreen
import com.comfyui.mobile.ui.screens.DashboardScreen
import com.comfyui.mobile.ui.screens.GalleryScreen
import com.comfyui.mobile.ui.screens.RhTaskRecordsScreen
import com.comfyui.mobile.ui.screens.SettingsScreen
import com.comfyui.mobile.ui.screens.ThemeSettingsScreen
import com.comfyui.mobile.ui.screens.ModelHubScreen
import com.comfyui.mobile.ui.theme.ComfyControllerTheme
import com.comfyui.mobile.ui.liquid.IosLiquidGlassNavigationBar
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Photos
import top.yukonga.miuix.kmp.icon.extended.Create
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import com.comfyui.mobile.ui.LocalFloatingNavClearance
import com.comfyui.mobile.ui.screens.ServerManageScreen

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()


    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        NavigationDiagnostics.start(this)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            NavigationDiagnostics.recordCrash(throwable)
            android.util.Log.e("ComfyCrash", "FATAL UNCAUGHT EXCEPTION", throwable)
            val sw = java.io.StringWriter()
            throwable.printStackTrace(java.io.PrintWriter(sw))
            val trace = sw.toString()
            try {
                val f = java.io.File(getExternalFilesDir(null), "comfy_crash.txt")
                f.writeText(trace)
                val f2 = java.io.File(filesDir, "comfy_crash.txt")
                f2.writeText(trace)
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        try {
            requestNotificationPermissionIfNeeded()

            lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_START) {
                        viewModel.ensureConnected(forceProbe = true)
                        viewModel.startForegroundHeartbeat()
                    } else if (event == Lifecycle.Event.ON_STOP) {
                        viewModel.stopForegroundHeartbeat()
                    }
                }
            )

            setContent {
                val navEventOwner = this as NavigationEventDispatcherOwner
                CompositionLocalProvider(
                    LocalNavigationEventDispatcherOwner provides navEventOwner
    ) {
                    val themeMode by viewModel.themeMode.collectAsState()
                    val systemDark = isSystemInDarkTheme()
                    val isDarkTheme = when (themeMode) {
                        MainViewModel.THEME_FOLLOW_SYSTEM -> systemDark
                        MainViewModel.THEME_LIGHT -> false
                        else -> true
                    }
                    val themePaletteId by viewModel.themePaletteId.collectAsState()
                    val uiScale by viewModel.uiScale.collectAsState()
                    ComfyControllerTheme(
                        darkTheme = isDarkTheme,
                        paletteId = themePaletteId
    ) {
                        val baseDensity = LocalDensity.current
                        CompositionLocalProvider(
                            LocalDensity provides Density(
                                density = baseDensity.density * uiScale,
                                fontScale = baseDensity.fontScale
    )
    ) {
                            MainScreen(viewModel)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            android.util.Log.e("ComfyCrash", "CRASH IN ONCREATE", t)
            val sw = java.io.StringWriter()
            t.printStackTrace(java.io.PrintWriter(sw))
            val trace = sw.toString()
            val sv = android.widget.ScrollView(this)
            val tv = android.widget.TextView(this).apply {
                text = "应用启动捕获到异常：\n\n$trace"
                setTextIsSelectable(true)
                setPadding(50, 100, 50, 50)
                textSize = 14f
                setTextColor(android.graphics.Color.RED)
            }
            sv.addView(tv)
            setContentView(sv)
        }
    }

    override fun onStop() {
        NavigationDiagnostics.record(
            NavigationDiagnostics.Event.ACTIVITY_STOPPED,
            finishing = isFinishing, changingConfiguration = isChangingConfigurations
        )
        super.onStop()
    }

    override fun onDestroy() {
        NavigationDiagnostics.record(
            NavigationDiagnostics.Event.ACTIVITY_DESTROYED,
            finishing = isFinishing, changingConfiguration = isChangingConfigurations
        )
        super.onDestroy()
    }
}

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val navController = rememberNavController()
    // Observe only: keep handler ownership, the navigation stack and animations unchanged.
    DisposableEffect(navController) {
        val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
            NavigationDiagnostics.record(
                NavigationDiagnostics.Event.DESTINATION_CHANGED, destination.route
            )
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }
    val navigationEvents = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
    LaunchedEffect(navigationEvents) {
        navigationEvents?.transitionState
            ?.map { it is NavigationEventTransitionState.InProgress }
            ?.distinctUntilChanged()
            ?.collect { inProgress ->
                NavigationDiagnostics.record(if (inProgress)
                    NavigationDiagnostics.Event.GESTURE_STARTED else NavigationDiagnostics.Event.GESTURE_IDLE)
            }
    }
    val items = listOf(
        Screen.Dashboard,
        Screen.Control,
        Screen.Agent,
        Screen.AiApps,
        Screen.Gallery
    )
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val showBottomBar = currentRoute in items.map { it.route }
    val scheme = MiuixTheme.colorScheme
    val themeMode by viewModel.themeMode.collectAsState()
    val isDarkTheme = when (themeMode) {
        MainViewModel.THEME_FOLLOW_SYSTEM -> isSystemInDarkTheme()
        MainViewModel.THEME_LIGHT -> false
        else -> true
    }
    val isLiquidGlassEnabled by viewModel.isLiquidGlassEnabled.collectAsState()


    val backdrop = rememberLayerBackdrop()
    val navigationItems = remember(items) {
        items.map { screen ->
            NavigationItem(
                label = screen.label,
                icon = screen.icon
    )
        }
    }
    val selectedIndex = items.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)
    val haptics = rememberAppHaptics()

    val navBarBottom = WindowInsets.navigationBars
        .only(WindowInsetsSides.Bottom)
        .asPaddingValues()
        .calculateBottomPadding()
    // Match IosLiquidGlassNavigationBar: 52dp pill + bottom pad + gap
    val liquidBarReserve = 52.dp + 10.dp + (
        if (navBarBottom > 0.dp) 4.dp + navBarBottom else 24.dp
    )
    // Floating: content draws full-bleed under the glass; lists add this as contentPadding.
    val secondaryRoutes = setOf(
        "agent_settings", "settings", "theme", "servers", "about", "console"
    )
    val keepClearance = showBottomBar ||
        currentRoute in secondaryRoutes ||
        (currentRoute?.startsWith("ai_app/") == true)
    val floatingNavClearance = if (keepClearance) liquidBarReserve else 0.dp

    // Floating glass overlays content; do NOT use Scaffold.bottomBar (full-width strip/mask).
    // IMPORTANT: glass bar must sit OUTSIDE the layerBackdrop Box, otherwise blur samples
    // itself and RenderThread stack-overflows (SIGSEGV).
    CompositionLocalProvider(LocalFloatingNavClearance provides floatingNavClearance) {
    Scaffold(
        containerColor = scheme.background
    ) { _ ->
        Box(
            modifier = Modifier.fillMaxSize()
    ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (isLiquidGlassEnabled) Modifier.layerBackdrop(backdrop) else Modifier)
    ) {
            NavHost(
                navController = navController,
                startDestination = Screen.Dashboard.route,
                modifier = Modifier.fillMaxSize(),
                enterTransition = {
                    slideInHorizontally(
                        animationSpec = tween(280),
                        initialOffsetX = { it / 5 }
    ) + fadeIn(animationSpec = tween(280))
                },
                exitTransition = {
                    slideOutHorizontally(
                        animationSpec = tween(280),
                        targetOffsetX = { -it / 8 }
    ) + fadeOut(animationSpec = tween(220))
                },
                popEnterTransition = {
                    slideInHorizontally(
                        animationSpec = tween(280),
                        initialOffsetX = { -it / 8 }
    ) + fadeIn(animationSpec = tween(280))
                },
                popExitTransition = {
                    slideOutHorizontally(
                        animationSpec = tween(280),
                        targetOffsetX = { it / 5 }
    ) + fadeOut(animationSpec = tween(220))
                }
    ) {
                composable(Screen.Dashboard.route) { DashboardScreen(navController, viewModel) }
                composable(Screen.Control.route) { ControlScreen(viewModel) }
                composable(Screen.Agent.route) { AgentScreen(navController, viewModel) }
                composable(Screen.AiApps.route) { AiAppsScreen(navController, viewModel) }
                composable(Screen.Gallery.route) { GalleryScreen(navController, viewModel) }
                composable("ai_app/{id}") { backStack ->
                    val id = backStack.arguments?.getString("id").orEmpty()
                    AiAppDetailScreen(navController, viewModel, id)
                }
                composable("console") { ConsoleScreen(navController, viewModel) }
                composable("settings") { SettingsScreen(navController, viewModel) }
                composable("theme") { ThemeSettingsScreen(navController, viewModel) }
                composable("servers") { ServerManageScreen(navController, viewModel) }
                composable("about") { AboutScreen(navController) }
                composable("agent_settings") { AgentSettingsScreen(navController, viewModel) }
                composable("cloud_tasks") { RhTaskRecordsScreen(navController, viewModel) }
                composable("model_hub") { ModelHubScreen(navController, viewModel) }
            }
            } // end content layerBackdrop Box

            if (showBottomBar) {
                IosLiquidGlassNavigationBar(
                    items = navigationItems,
                    selectedIndex = selectedIndex,
                    onItemClick = { index ->
                        if (index != selectedIndex) haptics.tick()
                        val screen = items[index]
                        navController.navigate(screen.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    backdrop = backdrop,
                    isBlurActive = isLiquidGlassEnabled,
                    isDark = isDarkTheme,
                    modifier = Modifier.align(Alignment.BottomCenter)
    )
            }
        }
    }
    } // CompositionLocalProvider LocalFloatingNavClearance
}

sealed class Screen(
    val route: String,
    val label: String,
    val icon: ImageVector
    ) {
    data object Dashboard : Screen(
        "控制面板",
        "主页",
        MiuixIcons.Home
    )
    data object Control : Screen(
        "控制器",
        "控制",
        MiuixIcons.Tune
    )
    data object AiApps : Screen(
        "AI应用",
        "应用",
        MiuixIcons.GridView
    )
    data object Agent : Screen(
        "agent",
        "Agent",
        MiuixIcons.Create
    )
    data object Gallery : Screen(
        "画廊",
        "画廊",
        MiuixIcons.Photos
    )
}
