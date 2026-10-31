package com.comfyui.mobile.ui.designsystem

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import com.comfyui.mobile.ui.theme.ComfyControllerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class PocketComponentsTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun pillExposesSelectionAndKeepsTouchTarget() {
        val selected = mutableStateOf("全部")
        var disabledClicks = 0
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply { setContent {
                CompositionLocalProvider(LocalSquircleEnabled provides false) {
                    ComfyControllerTheme(darkTheme = false, manageSystemBars = false) {
                        Column {
                            listOf("全部", "收藏").forEach { label ->
                                PocketPill(label, selected = selected.value == label) { selected.value = label }
                            }
                            PocketPill("禁用", enabled = false) { disabledClicks++ }
                            PocketSettingsGroup("分组标题") { Text("行") }
                        }
                    }
                }
            } })
            compose.waitForIdle()
            compose.onNodeWithText("全部").assertIsSelected()
            compose.onNodeWithText("收藏").assertIsNotSelected().performClick()
            compose.onNodeWithText("收藏").assertIsSelected()
            compose.onNodeWithText("全部").assertIsNotSelected()
            compose.onNodeWithText("禁用").performClick()
            assertEquals(0, disabledClicks)
            compose.onNodeWithText("收藏").assertHeightIsAtLeast(PocketTokens.CompactControlHeight)
            compose.onNodeWithText("分组标题").assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Heading))
            compose.onNodeWithText("行").assertExists()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun compactControlFitsTwoRowAgentBar() {
        // Agent bottom area = capsule row + input row; both rows sit on the 36-40dp control scale.
        assertEquals(36.dp, PocketTokens.CompactControlHeight)
        assertEquals(40.dp, PocketTokens.ControlHeight)
    }
}
