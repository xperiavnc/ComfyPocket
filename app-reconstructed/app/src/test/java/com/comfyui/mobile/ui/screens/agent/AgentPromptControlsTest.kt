package com.comfyui.mobile.ui.screens.agent

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.comfyui.mobile.agent.AgentPromptFormat
import com.comfyui.mobile.danbooru.*
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
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class AgentPromptControlsTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun selectsPromptFormatAndSavesTheActualDraft() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var saved: DanbooruSettings? = null
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply {
                setContent { MiuixTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        AgentPromptNetworkSettings(DanbooruSettings()) { saved = it }
                    }
                } }
            })
            compose.waitForIdle()
            compose.onNodeWithText("Anima 混合").performClick()
            compose.onNodeWithText("兼容核对").performScrollTo().performClick()
            compose.onNodeWithText("保存提示词与联网设置").performScrollTo().performClick()
            assertEquals(AgentPromptFormat.ANIMA, saved!!.promptFormat)
            assertEquals(TagCallMode.COMPAT, saved!!.callMode)
            assertTrue(saved!!.enabled)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun showsFailureOriginAndExpandableResultsWithoutHidingUnknownTerms() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val report = DanbooruCheckReport(TagCheckOrigin.APP_COMPAT,
            listOf(DanbooruQueryResult("custom_trigger", DanbooruApiResult(LookupStatus.TIMEOUT))), 1)
        var refreshed = false
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply {
                setContent { MiuixTheme { DanbooruCheckPanel(report, null, onRefresh = { refreshed = true }) } }
            })
            compose.waitForIdle()
            compose.onNodeWithText("App 兼容核对 · 联网核对未完成，已保留原始提示词  详情").performClick()
            compose.onNodeWithText("custom_trigger · 查询超时").assertExists()
            compose.onNodeWithText("刷新核对（不重新出图）").performClick()
            assertTrue(refreshed)
        } finally { controller.pause().stop().destroy() }
    }
}
