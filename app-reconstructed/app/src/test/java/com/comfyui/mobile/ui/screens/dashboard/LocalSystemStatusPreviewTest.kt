package com.comfyui.mobile.ui.screens.dashboard

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.comfyui.mobile.model.SystemMonitorData
import org.junit.Rule
import org.junit.Assert.assertEquals
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

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun cpuMemoryAndVramBarsUseTheSameRenderedColor() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        lateinit var view: ComposeView
        var expected = Color.Unspecified
        try {
            view = ComposeView(activity).apply {
                setContent {
                    MiuixTheme {
                        expected = MiuixTheme.colorScheme.primary
                        Column(Modifier.background(MiuixTheme.colorScheme.surface)) {
                            LocalSystemStatusPreview(SystemMonitorData(
                                isCrystools = false, cpuUtil = 50f, cpuAvailable = true,
                                ramUsed = 50f, ramTotal = 100f, ramAvailable = true,
                                vramUsed = 50f, vramTotal = 100f), MiuixTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
            activity.setContentView(view)
            compose.waitForIdle()
            val screenshot = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            // Draw the actual Compose hierarchy into a native Skia canvas. PixelCopy's redraw wait
            // needs a device compositor, which Robolectric doesn't drive.
            compose.runOnUiThread { view.draw(android.graphics.Canvas(screenshot)) }
            fun color(label: String): Int {
                val bounds = compose.onNodeWithTag("system-progress:$label").fetchSemanticsNode().boundsInRoot
                return screenshot.getPixel((bounds.left + bounds.width / 4).toInt(), bounds.center.y.toInt())
            }
            val cpu = color("CPU 利用率")
            org.junit.Assert.assertTrue(
                compose.onNodeWithTag("system-progress:CPU 利用率").fetchSemanticsNode().boundsInRoot.bottom <
                    compose.onNodeWithTag("system-progress:内存占用").fetchSemanticsNode().boundsInRoot.top)
            assertEquals(expected.toArgb(), cpu)
            assertEquals(color("内存占用"), cpu)
            assertEquals(color("显存占用"), cpu)
            val file = java.io.File("build/reports/cpu-progress-color.png").apply { parentFile?.mkdirs() }
            file.outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { controller.pause().stop().destroy() }
    }

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
