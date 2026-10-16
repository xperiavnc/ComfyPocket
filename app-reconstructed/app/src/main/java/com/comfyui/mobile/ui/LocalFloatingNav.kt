package com.comfyui.mobile.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/** Bottom inset reserved so scrollable content can clear the floating liquid glass bar. */
val LocalFloatingNavClearance = staticCompositionLocalOf { 0.dp }
