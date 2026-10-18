package com.comfyui.mobile.ui.navigation

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import com.comfyui.mobile.MainActivity
import com.comfyui.mobile.ui.MainViewModel
import com.comfyui.mobile.ui.theme.BottomBarStyle
import com.comfyui.mobile.model.AgentChatMessage
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
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class PocketAppIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Before fun offlineDefaults() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("comfy_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("civitai_market", Context.MODE_PRIVATE).edit().putString("mode", "BRIDGE").commit()
    }

    @Test fun realFiveEntrypointsAndAgentDraftStillWorkAfterRecreationAndRootReturn() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            compose.waitForIdle()
            compose.onNodeWithText("集市 ↗").assertExists()
            compose.onNodeWithTag("pocket-tab-1").performClick()
            compose.onAllNodesWithText("控制器").onFirst().assertExists()
            compose.onNodeWithTag("pocket-tab-2").performClick()
            compose.onNode(hasSetTextAction()).performTextInput("P2 尚未发送")
            compose.onNodeWithTag("pocket-tab-3").performClick()
            compose.onAllNodesWithText("AI应用").onFirst().assertExists()
            compose.onNodeWithTag("pocket-tab-4").performClick()
            compose.onNodeWithText("服务器", substring = false).assertExists()
            compose.onNodeWithTag("pocket-tab-2").performClick()
            compose.onNode(hasSetTextAction()).assertTextContains("P2 尚未发送")
            controller.recreate()
            compose.waitForIdle()
            compose.onNode(hasSetTextAction()).assertTextContains("P2 尚未发送")
            compose.runOnIdle { controller.get().onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            compose.onNodeWithText("集市 ↗").assertExists()
            assertFalse(controller.get().isFinishing)
            compose.onNodeWithTag("pocket-tab-2").performClick()
            compose.onNode(hasSetTextAction()).assertTextContains("P2 尚未发送")
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun settingsThemeStackReturnsOneLayerAndOldGlassSwitchIsGone() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            compose.waitForIdle()
            compose.onNodeWithContentDescription("设置").performClick()
            compose.onNodeWithTag("pocket-navigation").assertDoesNotExist()
            compose.onNodeWithText("主题与外观").performScrollTo().performClick()
            compose.onNodeWithText("液态玻璃导航").assertDoesNotExist()
            compose.onNodeWithText("深色", substring = false).performClick()
            controller.recreate()
            compose.waitForIdle()
            compose.onNodeWithText("主题色板").assertExists()
            compose.runOnIdle { controller.get().onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            compose.onNodeWithText("Material 预览").assertDoesNotExist()
            compose.onNodeWithText("主题与外观").assertExists()
            compose.onNodeWithTag("pocket-navigation").assertDoesNotExist()
            compose.onNodeWithContentDescription("返回").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("pocket-navigation").assertIsDisplayed()
            assertFalse(controller.get().isFinishing)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun foregroundViewModelFeedbackUsesOriginalToastForBothBars() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            compose.waitForIdle()
            val vm = ViewModelProvider(controller.get())[MainViewModel::class.java]
            for (style in BottomBarStyle.entries) {
                compose.runOnIdle { vm.setBottomBarStyle(style) }
                compose.onNodeWithTag("pocket-tab-2").performClick()
                ShadowToast.reset()
                compose.runOnIdle { vm.showUserMessage("已重置并保存") }
                compose.waitForIdle()
                assertEquals(1, ShadowToast.shownToastCount())
                assertEquals("已重置并保存", ShadowToast.getTextOfLatestToast())
                compose.onNodeWithText("已重置并保存").assertDoesNotExist()
                compose.onNodeWithTag("pocket-feedback").assertDoesNotExist()
                compose.onNodeWithTag("pocket-tab-2").assertIsSelected()
                println("APP_FEEDBACK style=$style message=已重置并保存 toastCount=1 snackbar=absent")
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun feedbackIsNotReplayedByTabSwitchRecreationOrResume() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            compose.waitForIdle()
            val vm = ViewModelProvider(controller.get())[MainViewModel::class.java]
            ShadowToast.reset()
            compose.runOnIdle { vm.showToast("已保存配置") }
            compose.waitForIdle()
            assertEquals(1, ShadowToast.shownToastCount())
            compose.onNodeWithTag("pocket-tab-2").performClick()
            compose.onNodeWithTag("pocket-tab-4").performClick()
            controller.recreate()
            compose.waitForIdle()
            controller.pause().stop().start().resume().visible()
            compose.waitForIdle()
            assertEquals(1, ShadowToast.shownToastCount())
            compose.runOnIdle { vm.showToast("保存失败，草稿已保留") }
            compose.waitForIdle()
            assertEquals(2, ShadowToast.shownToastCount())
            assertEquals("保存失败，草稿已保留", ShadowToast.getTextOfLatestToast())
            compose.onNodeWithTag("pocket-feedback").assertDoesNotExist()
            println("APP_FEEDBACK lifecycle initial=1 afterTabRecreateResume=1 nextAction=2")
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun readingOlderAgentMessagesSurvivesTabSwitchAndActivityRecreation() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            compose.waitForIdle()
            val vm = ViewModelProvider(controller.get())[MainViewModel::class.java]
            compose.runOnIdle {
                vm._agentMessages.value = (0..39).map { AgentChatMessage("p2-$it", "user", "历史消息-$it") }
            }
            compose.onNodeWithTag("pocket-tab-2").performClick()
            compose.onNodeWithTag("agent-chat-list").performScrollToIndex(10)
            compose.onNodeWithText("历史消息-10").assertIsDisplayed()
            compose.onNodeWithTag("pocket-tab-4").performClick()
            compose.onNodeWithTag("pocket-tab-2").performClick()
            compose.onNodeWithText("历史消息-10").assertIsDisplayed()
            controller.recreate()
            compose.waitForIdle()
            compose.onNodeWithText("历史消息-10").assertIsDisplayed()
        } finally { controller.pause().stop().destroy() }
    }
}
