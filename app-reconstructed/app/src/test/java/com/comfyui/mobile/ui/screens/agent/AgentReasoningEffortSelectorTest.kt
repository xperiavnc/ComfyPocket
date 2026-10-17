package com.comfyui.mobile.ui.screens.agent

import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.mutableStateOf
import com.comfyui.mobile.agent.AgentReasoningEffort
import org.junit.Assert.assertEquals
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
class AgentReasoningEffortSelectorTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun everyTierIsVisibleAndXhighAndMaxAreSelectable() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val selected = mutableStateOf(AgentReasoningEffort.DEFAULT)
        try {
            activity.setContentView(ComposeView(activity).apply {
                setContent { MiuixTheme { AgentReasoningEffortSelector(selected.value) { selected.value = it } } }
            })
            compose.waitForIdle()
            AgentReasoningEffort.entries.forEach { compose.onNodeWithText(it.label).assertExists() }
            compose.onNodeWithText("极高").performClick()
            compose.onNodeWithText("极高").assertIsSelected()
            assertEquals(AgentReasoningEffort.XHIGH, selected.value)
            compose.onNodeWithText("Max").performClick()
            compose.onNodeWithText("Max").assertIsSelected()
            assertEquals(AgentReasoningEffort.MAX, selected.value)
            compose.onNodeWithText("跟随模型").performClick()
            assertEquals(AgentReasoningEffort.DEFAULT, selected.value)
        } finally { controller.pause().stop().destroy() }
    }
}
