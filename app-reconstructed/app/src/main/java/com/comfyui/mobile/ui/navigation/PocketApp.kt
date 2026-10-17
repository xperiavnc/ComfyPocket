package com.comfyui.mobile.ui.navigation

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.rememberNavController
import com.comfyui.mobile.Screen
import com.comfyui.mobile.ui.MainViewModel
import com.comfyui.mobile.ui.rememberAppHaptics
import com.comfyui.mobile.ui.screens.*
import com.comfyui.mobile.ui.screens.gallery.GalleryHomeScreen

@Composable
internal fun PocketApp(viewModel: MainViewModel) {
    val mode by viewModel.themeMode.collectAsState()
    val palette by viewModel.themePaletteId.collectAsState()
    val dark = when (mode) {
        MainViewModel.THEME_FOLLOW_SYSTEM -> isSystemInDarkTheme()
        MainViewModel.THEME_LIGHT -> false
        else -> true
    }
    val nav = rememberNavController()
    val haptics = rememberAppHaptics()
    PocketAppTheme(dark, palette) {
        PocketNavigationShell(nav, viewModel.userMessage, haptics::tick) { entry ->
            val page: @Composable () -> Unit = {
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
            page()
        }
    }
}
