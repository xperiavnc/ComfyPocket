package com.comfyui.mobile.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import com.comfyui.mobile.danbooru.ContentFilter
import com.comfyui.mobile.danbooru.DanbooruQuery
import com.comfyui.mobile.danbooru.DanbooruSettings
import com.comfyui.mobile.danbooru.DanbooruState
import com.comfyui.mobile.ui.screens.danbooru.DanbooruSettingsDialog
import com.comfyui.mobile.ui.theme.ComfyControllerTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import java.io.File

/** The real settings form; local empty data, no network/account or real settings write. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsMotionVisualTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun frame(fallback: View, name: String): Bitmap {
        compose.waitForIdle()
        val root = WindowInspector.getGlobalWindowViews()
            .lastOrNull { it.visibility == View.VISIBLE && it.width > 0 } ?: fallback
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { root.draw(Canvas(bitmap)) }
        val file = File("build/reports/navigation-motion/$name.png").apply { parentFile?.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 10000)
        return bitmap
    }

    @Test fun renderDanbooruCancelAtOpenIntermediateAndClosedFrames() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var open by mutableStateOf(true)
        val activity = controller.get()
        val view = ComposeView(activity)
        try {
            activity.setContentView(view.apply { setContent {
                // Native Skia's AGSL lacks Miuix's color qualifier; use the library fallback.
                CompositionLocalProvider(LocalSquircleEnabled provides false) {
                    ComfyControllerTheme(true) {
                        Scaffold {
                            Text("本地界面测试 · 没有联网或写入设置")
                            if (open) DanbooruSettingsDialog(
                                DanbooruSettings(),
                                DanbooruState(DanbooruQuery.resolve("", ContentFilter.GENERAL)),
                                { open = false }, { Result.success(Unit) }, { "fixture" },
                                { Result.success(Unit) }, { "fixture" })
                        }
                    }
                }
            } })
            compose.waitForIdle()
            val initial = frame(view, "settings-open")
            compose.mainClock.autoAdvance = false
            compose.onNodeWithText("取消").performTouchInput { click() }
            compose.waitForIdle(); compose.mainClock.advanceTimeBy(80)
            val early = frame(view, "settings-exit-80ms")
            assertTrue(open)
            compose.mainClock.advanceTimeBy(80)
            val later = frame(view, "settings-exit-160ms")
            assertTrue(open)
            compose.mainClock.advanceTimeBy(600)
            compose.mainClock.autoAdvance = true; compose.waitForIdle()
            val closed = frame(view, "settings-closed")
            assertFalse(open)
            assertFalse(initial.sameAs(early))
            assertFalse(early.sameAs(later))
            assertFalse(later.sameAs(closed))
        } finally {
            compose.mainClock.autoAdvance = true
            controller.pause().stop().destroy()
        }
    }
}
