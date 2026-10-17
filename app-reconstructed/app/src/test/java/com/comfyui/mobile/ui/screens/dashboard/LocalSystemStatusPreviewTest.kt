package com.comfyui.mobile.ui.screens.dashboard

import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.comfyui.mobile.model.SystemMonitorData
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import top.yukonga.miuix.kmp.theme.MiuixTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class LocalSystemStatusPreviewTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun cpuAndMemoryAreVisibleWithoutCrystools() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        try {
            activity.setContentView(ComposeView(activity).apply {
                setContent {
                    MiuixTheme {
                        LocalSystemStatusPreview(SystemMonitorData(
                            cpuUtil = 26.5f, cpuAvailable = true, isCrystools = false,
                            ramUsed = 12f * 1024 * 1024 * 1024, ramTotal = 32f * 1024 * 1024 * 1024,
                            ramAvailable = true), MiuixTheme.colorScheme.onSurface)
                    }
                }
            })
            compose.waitForIdle()
            compose.onNodeWithText("CPU 利用率").assertExists()
            compose.onNodeWithText("26.5%").assertExists()
            compose.onNodeWithText("内存占用").assertExists()
            compose.onNodeWithText("37.5% (12.0 / 32.0 GiB)").assertExists()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun unavailableCpuIsNotShownAsZeroPercent() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        try {
            activity.setContentView(ComposeView(activity).apply {
                setContent {
                    MiuixTheme {
                        LocalSystemStatusPreview(SystemMonitorData(
                            isCrystools = false, ramAvailable = true, ramUsed = 1f, ramTotal = 4f,
                            vramTotal = 0f
                        ), MiuixTheme.colorScheme.onSurface)
                    }
                }
            })
            compose.waitForIdle()
            compose.onNodeWithText("CPU 利用率").assertExists()
            compose.onNodeWithText("0.0%").assertDoesNotExist()
            compose.onNodeWithText("CPU 数据需 Mobile Bridge 2.1+ 或 Crystools 实时监控").assertExists()
        } finally { controller.pause().stop().destroy() }
    }
}
