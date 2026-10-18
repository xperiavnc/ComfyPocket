package com.comfyui.mobile.ui.navigation

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.comfyui.mobile.Screen
import com.comfyui.mobile.ui.MainViewModel
import com.comfyui.mobile.ui.rememberAppHaptics
import com.comfyui.mobile.ui.screens.*
import com.comfyui.mobile.ui.screens.gallery.GalleryHomeScreen
import com.comfyui.mobile.ui.theme.ComfyControllerTheme
import top.yukonga.miuix.kmp.basic.Scaffold as LegacyScaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun PocketApp(viewModel: MainViewModel) {
    val mode by viewModel.themeMode.collectAsState()
    val palette by viewModel.themePaletteId.collectAsState()
    val bottomBarStyle by viewModel.bottomBarStyle.collectAsState()
    val dark = when (mode) {
        MainViewModel.THEME_FOLLOW_SYSTEM -> isSystemInDarkTheme()
        MainViewModel.THEME_LIGHT -> false
        else -> true
    }
    val nav = rememberNavController()
    val activeEntry by nav.currentBackStackEntryAsState()
    val overlays = remember { PocketOverlayHost() }
    val haptics = rememberAppHaptics()
    PocketAppTheme(dark, palette) {
      Box(Modifier.fillMaxSize()) {
        PocketNavigationShell(
            nav, viewModel.userMessage, haptics::tick,
            bottomBarStyle = bottomBarStyle, darkTheme = dark, paletteId = palette
        ) { entry ->
            // A deliberate migration boundary: old screens get only their original theme.
            // Material navigation and feedback are siblings, never children of MiuixTheme.
            ComfyControllerTheme(dark, palette, manageSystemBars = false) {
              // Own overlay state inside this destination; portal only its rendering
              // above the shell so neither bar can cover dialog actions or receive taps.
              LegacyScaffold(
                  containerColor = MiuixTheme.colorScheme.background,
                  popupHost = { overlays.Capture(entry.id) }
              ) { _ ->
                when (entry.destination.route) {
                    Screen.Dashboard.route -> DashboardScreen(nav, viewModel)
                    Screen.Control.route -> ControlScreen(viewModel)
                    Screen.Agent.route -> AgentScreen(nav, viewModel)
                    Screen.AiApps.route -> AiAppsScreen(nav, viewModel)
                    Screen.Gallery.route -> GalleryHomeScreen(nav, viewModel)
                    "ai_app/{id}" -> AiAppDetailScreen(nav, viewModel, entry.arguments?.getString("id").orEmpty())
                    "console" -> ConsoleScreen(nav, viewModel)
                    "settings" -> SettingsScreen(nav, viewModel)
                    "theme" -> ThemeSettingsScreen(nav, viewModel)
                    "servers" -> ServerManageScreen(nav, viewModel)
                    "about" -> AboutScreen(nav)
                    "agent_settings" -> AgentSettingsScreen(nav, viewModel)
                    "cloud_tasks" -> RhTaskRecordsScreen(nav, viewModel)
                    "model_hub" -> ModelHubScreen(nav, viewModel)
                }
              }
            }
        }
        overlays.Render(activeEntry?.id)
      }
    }
}
