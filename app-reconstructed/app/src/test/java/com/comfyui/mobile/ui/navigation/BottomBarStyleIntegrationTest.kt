package com.comfyui.mobile.ui.navigation

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModelProvider
import com.comfyui.mobile.MainActivity
import com.comfyui.mobile.ui.MainViewModel
import com.comfyui.mobile.ui.theme.BottomBarStyle
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
import org.robolectric.util.ReflectionHelpers
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class BottomBarStyleIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val prefs get() = RuntimeEnvironment.getApplication().getSharedPreferences("comfy_prefs", Context.MODE_PRIVATE)

    @Before fun offline() {
        // AndroidX keeps this factory static, while Robolectric replaces Application per test.
        // Reset only the test fixture so a cold launch reads this test's real preferences.
        ReflectionHelpers.setStaticField(ViewModelProvider.AndroidViewModelFactory::class.java, "_instance", null)
        prefs.edit().clear().putInt("theme_mode", 2).commit()
        RuntimeEnvironment.getApplication().getSharedPreferences("civitai_market", Context.MODE_PRIVATE)
            .edit().putString("mode", "BRIDGE").commit()
        ShadowToast.reset()
    }

    private fun back(activity: MainActivity) {
        compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }
    private fun appearance() {
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithText("Material 预览").assertDoesNotExist()
        compose.onNodeWithText("主题与外观").performScrollTo().performClick()
    }

    @Test fun actualAppearancePickerSwitchesBothWaysAndSurvivesActivityRecreation() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            compose.waitForIdle()
            compose.onNodeWithTag("pocket-navigation-indicator").assertExists()
            appearance()
            compose.onNodeWithTag("bottom-bar-style-classic").performScrollTo().performClick().assertIsSelected()
            assertEquals("classic", prefs.getString(BottomBarStyle.PREFERENCE_KEY, null))
            assertEquals("底栏风格已切换：经典悬浮", ShadowToast.getTextOfLatestToast())
            assertEquals(1, ShadowToast.shownToastCount())
            compose.onNodeWithTag("pocket-feedback").assertDoesNotExist()
            controller.recreate()
            compose.waitForIdle()
            assertEquals(1, ShadowToast.shownToastCount())
            compose.onNodeWithTag("bottom-bar-style-classic").performScrollTo().assertIsSelected()
            back(controller.get()); back(controller.get())
            compose.onNodeWithTag("pocket-classic-navigation").assertIsDisplayed()
            compose.onNodeWithTag("pocket-navigation-indicator").assertDoesNotExist()
            compose.onNodeWithTag("pocket-tab-2").performClick()
            compose.onNode(hasSetTextAction()).performTextInput("两种底栏共用的 Agent 草稿")
            compose.onNodeWithTag("pocket-tab-0").performClick()
            appearance()
            compose.onNodeWithTag("bottom-bar-style-material").performScrollTo().performClick().assertIsSelected()
            assertEquals("底栏风格已切换：${BottomBarStyle.MATERIAL.title}", ShadowToast.getTextOfLatestToast())
            assertEquals(2, ShadowToast.shownToastCount())
            back(controller.get()); back(controller.get())
            compose.onNodeWithTag("pocket-classic-navigation").assertDoesNotExist()
            compose.onNodeWithTag("pocket-navigation-indicator").assertExists()
            compose.onNodeWithTag("pocket-tab-2").performClick()
            compose.onNode(hasSetTextAction()).assertTextContains("两种底栏共用的 Agent 草稿")
            assertFalse(controller.get().isFinishing)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun classicCapsulePhysicalTapAndDragStillSelectTabsAndRootBackConsumesOnce() {
        prefs.edit().putString(BottomBarStyle.PREFERENCE_KEY, "classic").commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            compose.waitForIdle()
            val vm = ViewModelProvider(controller.get())[MainViewModel::class.java]
            assertSame(controller.get().application, vm.getApplication<android.app.Application>())
            assertEquals(BottomBarStyle.CLASSIC, vm.bottomBarStyle.value)
            compose.onNodeWithTag("pocket-tab-4").performTouchInput { click() }
            compose.waitForIdle()
            compose.onNodeWithTag("pocket-tab-4").assertIsSelected()
            compose.onNodeWithTag("pocket-classic-navigation").performTouchInput {
                swipe(Offset(width * 0.8f, height * 0.3f), Offset(width * 0.2f, height * 0.3f))
            }
            compose.waitForIdle()
            compose.onNodeWithTag("pocket-tab-0").assertIsSelected()
            compose.onNodeWithTag("pocket-tab-2").performClick()
            back(controller.get())
            compose.onNodeWithTag("pocket-tab-0").assertIsSelected()
            assertFalse(controller.get().isFinishing)
        } finally { controller.pause().stop().destroy() }
    }
}
