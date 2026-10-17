package com.comfyui.mobile.ui.navigation

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.comfyui.mobile.ui.theme.ThemePaletteIds
import org.json.JSONArray
import org.json.JSONObject
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
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Measure the actual Material icon slot, not a guessed fraction of navigation bar height. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PocketNavigationIndicatorTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val records = JSONArray()
    private lateinit var view: ComposeView
    private val dark = mutableStateOf(false)

    private fun bounds(tag: String): Rect =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun assertAligned(index: Int) {
        val pill = bounds("pocket-navigation-indicator")
        val icon = bounds("pocket-tab-icon-$index")
        val label = bounds("pocket-tab-label-$index")
        val bar = bounds("pocket-navigation")
        records.put(JSONObject().put("tab", index).put("pill", pill.toString())
            .put("icon", icon.toString()).put("label", label.toString()).put("bar", bar.toString()))
        assertEquals("pill and icon horizontal centers: tab $index", icon.center.x, pill.center.x, 1f)
        assertEquals("pill and icon vertical centers: tab $index", icon.center.y, pill.center.y, 1f)
        assertTrue("icon must fit within pill vertically", pill.top < icon.top && pill.bottom > icon.bottom)
        assertTrue("pill must leave the label outside it", pill.bottom < label.top)
        assertTrue("pill stays inside bar", pill.left >= bar.left && pill.right <= bar.right &&
            pill.top >= bar.top && pill.bottom <= bar.bottom)
        compose.onNodeWithTag("pocket-tab-$index").assertIsSelected()
    }

    private fun saveBar(name: String) {
        val bar = bounds("pocket-navigation")
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        val crop = Bitmap.createBitmap(bitmap, floor(bar.left).toInt(), floor(bar.top).toInt(),
            ceil(bar.width).toInt(), ceil(bar.height).toInt())
        val dest = File("build/reports/pocket-indicator/$name.png").apply { parentFile?.mkdirs() }
        dest.outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        crop.recycle()
        bitmap.recycle()
    }

    private fun withShell(
        name: String,
        direction: LayoutDirection = LayoutDirection.Ltr,
        fontScale: Float = 1f,
        block: (ComponentActivity) -> Unit
    ) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            view = ComposeView(controller.get())
            controller.get().setContentView(view.apply { setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalLayoutDirection provides direction,
                    LocalDensity provides Density(density.density, fontScale)) {
                    PocketAppTheme(dark.value, ThemePaletteIds.COMFY_BLUE) {
                        PocketNavigationShell {
                            Box(Modifier.fillMaxSize()) { Text("底栏对齐验证 · 本地测试内容") }
                        }
                    }
                }
            } })
            compose.waitForIdle()
            block(controller.get())
        } finally {
            File("build/reports/pocket-indicator/$name.json").apply {
                parentFile?.mkdirs()
                writeText(records.toString(2))
            }
            compose.mainClock.autoAdvance = true
            controller.pause().stop().destroy()
        }
    }

    @Test fun allFiveItemsCenterOnTheirIconsAndNeverCoverLabelsInBothThemes() = withShell("all-tabs") {
        for (theme in listOf(false, true)) {
            compose.runOnIdle { dark.value = theme }
            for (index in pocketTabs.indices) {
                compose.onNodeWithTag("pocket-tab-$index").performClick()
                compose.waitForIdle()
                assertAligned(index)
                saveBar("${if (theme) "dark" else "light"}-$index")
            }
        }
    }

    @Test fun rtlUsesActualPhysicalIconCentersWithoutDoubleMirroring() =
        withShell("rtl", direction = LayoutDirection.Rtl) {
            for (index in pocketTabs.indices) {
                compose.onNodeWithTag("pocket-tab-$index").performClick()
                compose.waitForIdle()
                assertAligned(index)
            }
            assertTrue(bounds("pocket-tab-icon-0").center.x > bounds("pocket-tab-icon-4").center.x)
            saveBar("rtl-gallery")
        }

    @Test fun largerFontKeepsPillAboveLabelAndCenteredOnIcon() =
        withShell("font-150", fontScale = 1.5f) {
            for (index in pocketTabs.indices) {
                compose.onNodeWithTag("pocket-tab-$index").performClick()
                compose.waitForIdle()
                assertAligned(index)
            }
            saveBar("font-150-gallery")
        }

    @Test fun doubleFontScaleStillUsesIconGeometryRatherThanBarCenter() =
        withShell("font-200", fontScale = 2f) {
            compose.onNodeWithTag("pocket-tab-4").performClick()
            compose.waitForIdle()
            assertAligned(4)
            saveBar("font-200-gallery")
        }

    @Test
    @Config(qualifiers = "w360dp-h640dp-xhdpi")
    fun narrowWindowAndAsymmetricSystemInsetsKeepPillOnSelectedIcon() = withShell("insets") { activity ->
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(32, 0, 48, 48))
            .setVisible(WindowInsetsCompat.Type.navigationBars(), true).build()
        compose.runOnIdle { ViewCompat.dispatchApplyWindowInsets(activity.window.decorView, insets) }
        compose.waitForIdle()
        for (index in pocketTabs.indices) {
            compose.onNodeWithTag("pocket-tab-$index").performClick()
            compose.waitForIdle()
            assertAligned(index)
        }
        saveBar("narrow-insets-gallery")
    }

    @Test fun rapidReversalMovesHorizontallyWithoutVerticalJumpAndReselectDoesNotRestart() =
        withShell("reversal") {
            val initial = bounds("pocket-navigation-indicator")
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("pocket-tab-4").performClick()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(80)
            compose.waitForIdle()
            val moving = bounds("pocket-navigation-indicator")
            assertTrue(moving.center.x > initial.center.x)
            assertTrue(moving.center.x < bounds("pocket-tab-icon-4").center.x)
            assertEquals(initial.center.y, moving.center.y, 1f)
            compose.onNodeWithTag("pocket-tab-1").performClick()
            compose.waitForIdle()
            val retargeted = bounds("pocket-navigation-indicator")
            // performClick emits clocked touch events; it is not the same instant as the previous sample.
            assertTrue("retarget must not snap to the newly selected icon",
                abs(retargeted.center.x - bounds("pocket-tab-icon-1").center.x) > 1f)
            assertEquals("retarget remains on the icon row", initial.center.y, retargeted.center.y, 1f)
            compose.mainClock.advanceTimeBy(1200)
            compose.waitForIdle()
            assertAligned(1)
            val settled = bounds("pocket-navigation-indicator")
            compose.onNodeWithTag("pocket-tab-1").performClick()
            compose.mainClock.advanceTimeBy(80)
            compose.waitForIdle()
            assertEquals(settled, bounds("pocket-navigation-indicator"))
        }
}
