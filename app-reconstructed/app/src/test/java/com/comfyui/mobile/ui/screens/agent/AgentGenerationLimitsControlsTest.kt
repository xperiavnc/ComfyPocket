package com.comfyui.mobile.ui.screens.agent

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import com.comfyui.mobile.agent.AgentGenerationLimits
import com.comfyui.mobile.agent.OutputTokenParameter
import org.junit.Assert.*
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
@Config(sdk = [34], qualifiers = "w412dp-h915dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class AgentGenerationLimitsControlsTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun actualControlsEditLengthsSelectCompatibilityParameterAndResetContext() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val value = mutableStateOf(AgentLimitsDraft())
        try {
            activity.setContentView(ComposeView(activity).apply {
                setContent { MiuixTheme { AgentGenerationLimitsControls(value.value) { value.value = it } } }
            })
            compose.onNodeWithText("32K").performClick()
            compose.onNodeWithTag("agent-context-tokens").assertTextContains("32768")
            compose.onNodeWithTag("agent-output-tokens").performTextReplacement("2048")
            compose.onNodeWithText("max_tokens").performClick()
            assertEquals(AgentGenerationLimits(32768, 2048, OutputTokenParameter.LEGACY), value.value.parsed())
            compose.onNodeWithTag("agent-context-tokens").performTextReplacement("1024")
            compose.onNodeWithText("上下文预算必须大于输出预留（2048 tokens）").assertExists()
            assertTrue(runCatching { value.value.parsed() }.isFailure)
            compose.onNodeWithText("默认").performClick()
            assertNull(value.value.parsed().contextTokens)
            assertEquals(2048, value.value.parsed().outputTokens)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun blankDefaultsAndInvalidDraftsAreNotSilentlyCoerced() {
        assertEquals(AgentGenerationLimits(), AgentLimitsDraft().parsed())
        assertEquals(32768, AgentLimitsDraft("32768").parsed().contextTokens)
        for (draft in listOf(AgentLimitsDraft("abc"), AgentLimitsDraft(output = "12.5"),
                AgentLimitsDraft(output = "-1"), AgentLimitsDraft(output = "999999999999999999"))) {
            assertThrows(IllegalArgumentException::class.java) { draft.parsed() }
        }
    }
}
