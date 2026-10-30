package com.comfyui.mobile.ui.navigation

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.BackEventCompat
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.comfyui.mobile.MainActivity
import com.comfyui.mobile.agent.AgentPromptOutput
import com.comfyui.mobile.model.EditableNode
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

/** Real screens and physical taps: a semantics-only performClick bypasses occlusion. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class FloatingOverlayNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun offline() {
        ReflectionHelpers.setStaticField(ViewModelProvider.AndroidViewModelFactory::class.java, "_instance", null)
        app.getSharedPreferences("comfy_prefs", Context.MODE_PRIVATE).edit().clear()
            .putInt("theme_mode", MainViewModel.THEME_DARK)
            .putString(BottomBarStyle.PREFERENCE_KEY, "classic").commit()
        app.getSharedPreferences("civitai_market", Context.MODE_PRIVATE).edit().putString("mode", "BRIDGE").commit()
    }

    private fun openWorkbenchAction(label: String) {
        compose.onNodeWithContentDescription("绘图参数与操作").performTouchInput { click() }
        compose.onNodeWithText(label, substring = false).performTouchInput { click() }
        compose.waitForIdle()
    }

    @Test fun styleDialogFooterReceivesPhysicalTapInsteadOfTheFloatingTabs() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            compose.waitForIdle()
            compose.onNodeWithTag("pocket-tab-2").performClick()
            openWorkbenchAction("画风 / 画师与辅助参数")
            compose.onNodeWithText("画风与辅助参数").assertIsDisplayed()
            compose.onNodeWithText("关闭").performScrollTo().performTouchInput { click() }
            compose.waitForIdle()
            compose.onNodeWithText("画风与辅助参数").assertDoesNotExist()
            compose.onNodeWithTag("pocket-tab-2").assertIsSelected()
            assertFalse(controller.get().isFinishing)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun longPositiveNegativePromptDialogCopiesAndClosesWithoutSwitchingTabs() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            compose.waitForIdle()
            compose.onNodeWithTag("pocket-tab-2").performClick()
            val output = AgentPromptOutput(positive = "blue sky, cloud, ".repeat(50),
                negative = "blurry, low quality, ".repeat(50))
            compose.runOnIdle { ViewModelProvider(controller.get())[MainViewModel::class.java]._agentPromptOutput.value = output }
            openWorkbenchAction("正向 / 反向提示词")
            compose.onNodeWithText("出图提示词").assertIsDisplayed()
            compose.onNodeWithText("复制两组").performTouchInput { click() }
            compose.waitForIdle()
            val clipboard = controller.get().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals("正向提示词：\n${output.positive}\n\n反向提示词：\n${output.negative}",
                clipboard.primaryClip!!.getItemAt(0).text.toString())
            compose.onNodeWithTag("pocket-tab-2").assertIsSelected()
            compose.onNodeWithText("关闭").performTouchInput { click() }
            compose.waitForIdle()
            compose.onNodeWithText("出图提示词").assertDoesNotExist()
            compose.onNodeWithTag("pocket-tab-2").assertIsSelected()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun controlNodeManagerFooterIsClickableAndKeepsItsUnderlyingWorkflow() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            compose.waitForIdle()
            compose.onNodeWithTag("pocket-tab-1").performClick()
            val vm = ViewModelProvider(controller.get())[MainViewModel::class.java]
            val nodes = (1..24).map { EditableNode.NumberInput("$it", "本地测试参数 $it",
                "KSampler", 1.0, "cfg") }
            compose.runOnIdle { vm._editableNodes.value = nodes }
            compose.onNodeWithContentDescription("管理参数").performClick()
            compose.onNodeWithText("管理节点参数").assertIsDisplayed()
            compose.onNodeWithText("关闭").performTouchInput { click() }
            compose.waitForIdle()
            compose.onNodeWithText("管理节点参数").assertDoesNotExist()
            compose.onNodeWithTag("pocket-tab-1").assertIsSelected()
            assertEquals(nodes, vm.editableNodes.value)
            assertFalse(controller.get().isFinishing)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun nestedDialogsOwnBackAndPhysicalBackdropTapsForBothNavigationStyles() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            compose.waitForIdle()
            val vm = ViewModelProvider(controller.get())[MainViewModel::class.java]
            for (style in BottomBarStyle.entries) {
                compose.runOnIdle { vm.setBottomBarStyle(style) }
                compose.onNodeWithTag("pocket-tab-2").performClick()
                openWorkbenchAction("画风 / 画师与辅助参数")
                compose.onNodeWithContentDescription("切换画师节点").performClick()
                compose.onNodeWithText("选择画师节点").assertIsDisplayed()
                compose.runOnIdle {
                    controller.get().onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
                    controller.get().onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(60f, 0f, 0.4f, BackEventCompat.EDGE_LEFT))
                    controller.get().onBackPressedDispatcher.dispatchOnBackCancelled()
                }
                compose.waitForIdle()
                compose.onNodeWithText("选择画师节点").assertIsDisplayed()
                // This coordinate used to be owned by Gallery even while a dialog was open.
                compose.onNodeWithTag("pocket-tab-4").performTouchInput { click() }
                compose.waitForIdle()
                compose.onNodeWithTag("pocket-tab-2").assertIsSelected()
                compose.onNodeWithText("选择画师节点").assertDoesNotExist()
                compose.onNodeWithText("画风与辅助参数").assertIsDisplayed()
                compose.runOnIdle { controller.get().onBackPressedDispatcher.onBackPressed() }
                compose.waitForIdle()
                compose.onNodeWithText("画风与辅助参数").assertDoesNotExist()
                compose.onNodeWithTag("pocket-tab-2").assertIsSelected()
                compose.runOnIdle { controller.get().onBackPressedDispatcher.onBackPressed() }
                compose.waitForIdle()
                compose.onNodeWithTag("pocket-tab-0").assertIsSelected()
                assertFalse(controller.get().isFinishing)
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun portaledDialogUsesTheFullWindowAndAvoidsTheKeyboardOnlyOnce() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            compose.waitForIdle()
            compose.onNodeWithTag("pocket-tab-2").performClick()
            openWorkbenchAction("画风 / 画师与辅助参数")
            val windowBottom = compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom
            fun insets(ime: Int) {
                val value = WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 48))
                    .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
                    .setVisible(WindowInsetsCompat.Type.ime(), ime > 0)
                    .build()
                compose.runOnIdle { ViewCompat.dispatchApplyWindowInsets(controller.get().window.decorView, value) }
                compose.waitForIdle()
            }
            insets(600)
            compose.onNodeWithText("关闭").performScrollTo().assertIsDisplayed()
            val footer = compose.onNodeWithText("关闭").fetchSemanticsNode().boundsInRoot
            assertTrue("The lifted host handles IME independently of the padded shell", footer.bottom <= windowBottom - 600f + 1f)
            assertTrue("IME must not also be inherited as another 600px shell offset", windowBottom - 600f - footer.bottom < 200f)
            insets(0)
            compose.onNodeWithText("关闭").performScrollTo().performTouchInput { click() }
            compose.waitForIdle()
            compose.onNodeWithText("画风与辅助参数").assertDoesNotExist()
            compose.onNodeWithTag("pocket-tab-2").assertIsSelected()
        } finally { controller.pause().stop().destroy() }
    }
}
