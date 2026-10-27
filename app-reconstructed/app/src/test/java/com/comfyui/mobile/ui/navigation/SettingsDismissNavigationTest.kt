package com.comfyui.mobile.ui.navigation

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsDismissNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun offline() {
        ReflectionHelpers.setStaticField(ViewModelProvider.AndroidViewModelFactory::class.java, "_instance", null)
        app.getSharedPreferences("comfy_prefs", Context.MODE_PRIVATE).edit().clear()
            .putInt("theme_mode", MainViewModel.THEME_DARK).commit()
        app.getSharedPreferences("civitai_market", Context.MODE_PRIVATE).edit().putString("mode", "BRIDGE").commit()
    }

    @Test fun agentPromptCancelAndResetCancelRetainThePageAndCustomTextWithBothBars() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            compose.waitForIdle()
            val vm = ViewModelProvider(controller.get())[MainViewModel::class.java]
            for (style in BottomBarStyle.entries) {
                compose.runOnIdle {
                    vm.setBottomBarStyle(style)
                    vm.setAgentSystemPrompt("本地回归人设：保留用户设置")
                    vm.setAgentDrawPrompt("本地回归规则：正反向提示词都保留")
                }
                compose.onNodeWithTag("pocket-tab-2").performTouchInput { click() }
                compose.onNodeWithContentDescription("Agent 设置").performTouchInput { click() }
                compose.waitForIdle()
                compose.onNodeWithTag("agent-rules-system-view").performScrollTo().performTouchInput { click() }
                compose.waitForIdle()
                compose.onNodeWithText("查看对话规则").assertIsDisplayed()
                compose.mainClock.autoAdvance = false
                compose.onNodeWithText("关闭").performTouchInput { click() }
                compose.waitForIdle(); compose.mainClock.advanceTimeBy(80)
                compose.runOnIdle { controller.get().onBackPressedDispatcher.onBackPressed() }
                compose.waitForIdle()
                compose.mainClock.advanceTimeBy(600)
                compose.mainClock.autoAdvance = true; compose.waitForIdle()
                compose.onNodeWithText("查看对话规则").assertDoesNotExist()
                // The rapid second Back was consumed by the exiting dialog, not the settings route.
                compose.onNodeWithTag("agent-rules-system-view").assertExists()
                assertEquals("本地回归人设：保留用户设置", vm.agentSystemPrompt.value)
                assertEquals("本地回归规则：正反向提示词都保留", vm.agentDrawPrompt.value)

                compose.onNodeWithTag("agent-rules-system-remove").performScrollTo().performTouchInput { click() }
                compose.waitForIdle()
                compose.onNodeWithText("移除对话规则").assertIsDisplayed()
                compose.onNodeWithText("取消").performTouchInput { click() }
                compose.waitForIdle()
                compose.onNodeWithText("移除对话规则").assertDoesNotExist()
                assertEquals("本地回归人设：保留用户设置", vm.agentSystemPrompt.value)
                compose.runOnIdle { controller.get().onBackPressedDispatcher.onBackPressed() }
                compose.waitForIdle()
                compose.onNodeWithTag("pocket-tab-2").assertIsSelected()
                assertFalse(controller.get().isFinishing)
            }
        } finally {
            compose.mainClock.autoAdvance = true
            controller.pause().stop().destroy()
        }
    }
}
