package com.comfyui.mobile.ui.navigation

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import com.comfyui.mobile.MainActivity
import com.comfyui.mobile.ui.MainViewModel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
// Existing Miuix AGSL is unsupported by Robolectric's bundled native Skia.
// Exercise real legacy layouts here; native shell-only screenshots have a separate test.
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class PocketShellInsetsTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Before fun offlineDefaults() {
        RuntimeEnvironment.getApplication().getSharedPreferences("comfy_prefs", Context.MODE_PRIVATE)
            .edit().clear().putInt("theme_mode", MainViewModel.THEME_LIGHT).commit()
    }

    private fun applyInsets(activity: MainActivity, ime: Int = 0, side: Int = 0) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(side, 0, 0, 48))
            .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 48, 0, 0))
            .setVisible(WindowInsetsCompat.Type.statusBars(), true)
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
            .setVisible(WindowInsetsCompat.Type.ime(), ime > 0)
            .build()
        compose.runOnIdle { ViewCompat.dispatchApplyWindowInsets(activity.window.decorView, insets) }
        compose.waitForIdle()
    }

    @Test fun actualLegacyPagesStayAboveMaterialBarAndNavigateInBothThemes() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            compose.waitForIdle()
            applyInsets(controller.get())
            val bar = compose.onNodeWithTag("pocket-navigation").fetchSemanticsNode().boundsInRoot
            val content = compose.onNodeWithTag("pocket-content").fetchSemanticsNode().boundsInRoot
            assertTrue(content.bottom <= bar.top + 1)
            compose.onNodeWithTag("pocket-tab-2").performClick(); compose.waitForIdle()
            compose.onNodeWithContentDescription("发送").assertIsDisplayed()
            compose.onNodeWithTag("pocket-tab-4").performClick(); compose.waitForIdle()
            compose.runOnIdle {
                ViewModelProvider(controller.get())[MainViewModel::class.java].setThemeMode(MainViewModel.THEME_DARK)
            }
            compose.waitForIdle()
            compose.onNodeWithTag("pocket-tab-0").performClick()
            compose.onNodeWithContentDescription("设置").performClick(); compose.waitForIdle()
            compose.onNodeWithTag("pocket-navigation").assertDoesNotExist()
            compose.onNodeWithText("主题与外观").assertExists()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun imeInsetsLiftAgentInputOnceAndHideBarThenRestoreIt() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            compose.waitForIdle()
            compose.onNodeWithTag("pocket-tab-2").performClick(); compose.waitForIdle()
            applyInsets(controller.get(), side = 32)
            val normal = compose.onNodeWithTag("pocket-content").fetchSemanticsNode().boundsInRoot
            val fullHeight = compose.onNodeWithTag("pocket-shell").fetchSemanticsNode().boundsInRoot.bottom
            assertTrue("Horizontal system inset must not cover content", normal.left >= 32)
            applyInsets(controller.get(), ime = 600, side = 32)
            compose.onNodeWithTag("pocket-navigation").assertDoesNotExist()
            compose.onNode(hasSetTextAction()).performTextInput("键盘避让，尚未发送")
            val content = compose.onNodeWithTag("pocket-content").fetchSemanticsNode().boundsInRoot
            val send = compose.onNodeWithContentDescription("发送").fetchSemanticsNode().boundsInRoot
            assertEquals("Shell applies exactly the IME inset", 600f, fullHeight - content.bottom, 1f)
            assertTrue("Input remains near the visible bottom, not padded twice", content.bottom - send.bottom < 100f)
            assertTrue(send.bottom <= content.bottom)
            applyInsets(controller.get(), side = 32)
            compose.onNodeWithTag("pocket-navigation").assertIsDisplayed()
            compose.onNode(hasSetTextAction()).assertTextContains("键盘避让，尚未发送")
            assertFalse(controller.get().isFinishing)
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    @Config(qualifiers = "w360dp-h540dp-xhdpi")
    fun shortWindowAndLargeFontKeepAgentActionsAboveNavigation() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val resources = controller.get().resources
        resources.updateConfiguration(Configuration(resources.configuration).apply { fontScale = 1.5f }, resources.displayMetrics)
        controller.setup().visible()
        try {
            compose.waitForIdle()
            compose.onNodeWithTag("pocket-tab-2").performClick(); compose.waitForIdle()
            val send = compose.onNodeWithContentDescription("发送").fetchSemanticsNode().boundsInRoot
            val bar = compose.onNodeWithTag("pocket-navigation").fetchSemanticsNode().boundsInRoot
            assertTrue(send.bottom <= bar.top)
            compose.onNodeWithContentDescription("发送").assertIsDisplayed()
        } finally { controller.pause().stop().destroy() }
    }
}
