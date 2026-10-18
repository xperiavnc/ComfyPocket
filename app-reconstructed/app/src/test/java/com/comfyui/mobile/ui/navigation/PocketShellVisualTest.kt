@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.comfyui.mobile.ui.navigation

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.comfyui.mobile.ui.theme.ThemePaletteIds
import com.comfyui.mobile.ui.theme.BottomBarStyle
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

/** Real production shell with explicitly labelled local content, not migrated business screens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PocketShellVisualTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun renderProductionShellThemesTransitionsAndSecondaryPageWithLocalContent() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val view = ComposeView(controller.get())
        val dark = mutableStateOf(false)
        val barStyle = mutableStateOf(BottomBarStyle.MATERIAL)
        try {
            controller.get().setContentView(view.apply { setContent {
                PocketAppTheme(dark.value, ThemePaletteIds.COMFY_BLUE) {
                    val nav = rememberNavController()
                    PocketNavigationShell(nav, bottomBarStyle = barStyle.value, darkTheme = dark.value,
                        paletteId = ThemePaletteIds.COMFY_BLUE) { entry ->
                        val tab = pocketTabs.firstOrNull { it.route == entry.destination.route }
                        Column(Modifier.fillMaxSize()) {
                            TopAppBar(title = { Text(tab?.label ?: "二级页面") })
                            LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                item { Text("P2 外壳验证 · 本地测试内容", style = MaterialTheme.typography.titleMedium) }
                                item { Text("仅验证正式导航、主题、页面栈与避让。业务页面仍保留原界面，后续按阶段迁移。") }
                                item { Button({ nav.navigate("settings") }) { Text("进入二级页面") } }
                                items(12) { index ->
                                    OutlinedCard(Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(20.dp)) {
                                            Text("内容区 ${index + 1}", style = MaterialTheme.typography.titleMedium)
                                            Text("主导航固定，列表不绘制在底栏下方。")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } })
            fun save(name: String) {
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                compose.runOnUiThread { view.draw(Canvas(bitmap)) }
                val file = File("build/reports/pocket-shell/$name.png").apply { parentFile?.mkdirs() }
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                assertTrue(file.length() > 10000)
            }
            compose.waitForIdle()
            save("shell-home-light")
            compose.onNodeWithTag("pocket-tab-4").performClick(); compose.waitForIdle()
            compose.runOnIdle { dark.value = true }; compose.waitForIdle()
            save("shell-gallery-dark")
            compose.runOnIdle { barStyle.value = BottomBarStyle.CLASSIC }; compose.waitForIdle()
            save("shell-classic-gallery-dark")
            compose.runOnIdle { dark.value = false }; compose.waitForIdle()
            save("shell-classic-gallery-light")
            compose.runOnIdle { barStyle.value = BottomBarStyle.MATERIAL; dark.value = true }; compose.waitForIdle()
            val bar = compose.onNodeWithTag("pocket-navigation").fetchSemanticsNode().boundsInRoot
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("pocket-tab-2").performClick(); compose.waitForIdle()
            compose.mainClock.advanceTimeBy(80)
            assertEquals(bar, compose.onNodeWithTag("pocket-navigation").fetchSemanticsNode().boundsInRoot)
            save("shell-tab-mid")
            compose.mainClock.advanceTimeBy(500)
            compose.mainClock.autoAdvance = true
            compose.onNodeWithText("进入二级页面").performClick(); compose.waitForIdle()
            compose.onNodeWithTag("pocket-navigation").assertDoesNotExist()
            save("shell-secondary-dark")
        } finally { controller.pause().stop().destroy() }
    }
}
