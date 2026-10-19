package com.comfyui.mobile.ui.screens.agent

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import com.comfyui.mobile.agent.AgentPromptSettings
import com.comfyui.mobile.agent.AgentPromptBinding
import com.comfyui.mobile.agent.AgentPromptOutput
import com.comfyui.mobile.agent.PromptBindingMode
import com.comfyui.mobile.model.EditableNode
import com.comfyui.mobile.model.AgentChatMessage
import com.comfyui.mobile.taglib.LocalTagEntry
import com.comfyui.mobile.taglib.LocalTagReport
import com.comfyui.mobile.taglib.LocalTagLibraryState
import com.comfyui.mobile.taglib.TagLibraryManifest
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
    private val installed = LocalTagLibraryState(
        installed = TagLibraryManifest(2, "fixture", mapOf("tags" to 141007, "characters" to 101952), 240851, 2108),
        checking = false)
    @Test fun removedFormatOptionsStayAbsentAndLibrarySwitchSavesImmediately() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var saved: AgentPromptSettings? = null
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply {
                setContent { MiuixTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        AgentLocalTagSettings(AgentPromptSettings(), installed, search = { _, _ -> emptyList() },
                            onImport = {}, onRemove = {}) { saved = it }
                    }
                } }
            })
            compose.waitForIdle()
            compose.onNodeWithText("Anima 混合").assertDoesNotExist()
            compose.onNodeWithText("通用 Tags").assertDoesNotExist()
            compose.onNodeWithText("自然语言 / 反推").assertDoesNotExist()
            compose.onNodeWithText("保存词库设置").assertDoesNotExist()
            compose.onNodeWithTag("agent-local-tags-enabled").performScrollTo().performClick()
            assertTrue(saved!!.useLocalLibrary)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun localReportNoLongerAddsAStatusPanelToChat() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val report = LocalTagReport(listOf(LocalTagEntry("blue hair", "蓝发", "hair", "颜色", "tag", "", "", "蓝发", true)))
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply {
                setContent { MiuixTheme {
                    AgentBubble(AgentChatMessage("fixture", "assistant", "好的，正在整理画面。", localTagReport = report))
                } }
            })
            compose.waitForIdle()
            compose.onNodeWithText("好的，正在整理画面。").assertExists()
            compose.onNodeWithText("本地词库命中", substring = true).assertDoesNotExist()
            compose.onNodeWithText("blue hair · 蓝发").assertDoesNotExist()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun positiveAndNegativePromptDetailsExpandSeparately() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply {
                setContent { MiuixTheme {
                    AgentPromptDetails("cat, rain", "blurry, extra limbs", "已写入工作流反向输入（1 项）")
                } }
            })
            compose.waitForIdle()
            compose.onNodeWithText("正向 / 反向提示词").performClick()
            compose.onNodeWithText("正向提示词").assertExists()
            compose.onNodeWithText("cat, rain").assertExists()
            compose.onNodeWithText("反向提示词").assertExists()
            compose.onNodeWithText("blurry, extra limbs").assertExists()
            compose.onNodeWithText("已写入工作流反向输入（1 项）").assertExists()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun outputDialogShowsBothPromptsAndCopiesSeparatelyOrTogether() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val copied = mutableListOf<Pair<String, String>>()
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply {
                setContent { MiuixTheme {
                    AgentOutputPromptPanel(AgentPromptOutput("cat, rain", "blurry", "已写入反向")) { label, text ->
                        copied += label to text
                    }
                } }
            })
            compose.waitForIdle()
            compose.onNodeWithText("cat, rain").assertExists()
            compose.onNodeWithText("blurry").assertExists()
            compose.onNodeWithText("复制正向").performClick()
            compose.onNodeWithText("复制反向").performClick()
            compose.onNodeWithText("复制两组").performClick()
            assertEquals("正向提示词" to "cat, rain", copied[0])
            assertEquals("反向提示词" to "blurry", copied[1])
            assertEquals("正向提示词：\ncat, rain\n\n反向提示词：\nblurry", copied[2].second)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun bindingTabsPassDirectionAndExactFieldWithoutClosing() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val selected = mutableListOf<Pair<Boolean, AgentPromptBinding>>()
        val nodes = listOf(
            EditableNode.TextInput("1", "主体", "Inline", text = "cat", key = "draw"),
            EditableNode.TextInput("1", "排除", "Inline", text = "blurry", key = "avoid")
        )
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply {
                setContent { MiuixTheme {
                    AgentPromptBindingPanel(AgentPromptBinding(), AgentPromptBinding(), nodes) { negative, binding ->
                        selected += negative to binding
                    }
                } }
            })
            compose.waitForIdle()
            compose.onNodeWithText("#1:draw · 主体").performClick()
            compose.onNodeWithText("反向节点").performClick()
            compose.onNodeWithText("#1:avoid · 排除").performClick()
            compose.onNodeWithText("保留工作流反向原值").performClick()
            assertEquals(false to AgentPromptBinding(PromptBindingMode.EXPLICIT, "1", "draw"), selected[0])
            assertEquals(true to AgentPromptBinding(PromptBindingMode.EXPLICIT, "1", "avoid"), selected[1])
            assertEquals(PromptBindingMode.KEEP, selected[2].second.mode)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun manualLookupUsesTheSelectedVocabularyKindWithoutCallingTheAgent() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val queries = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply {
                setContent { MiuixTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        AgentLocalTagSettings(AgentPromptSettings(), installed, search = { query, kind ->
                            queries += query to kind
                            listOf(LocalTagEntry("hatsune_miku", "初音未来", "character_names", "vocaloid",
                                "character", "hatsune_miku", "blue hair", query, true))
                        }, onImport = {}, onRemove = {}, onSave = {})
                    }
                } }
            })
            compose.waitForIdle()
            compose.onNodeWithText("离线查词").performScrollTo().performClick()
            compose.onNodeWithTag("agent-tag-kind-character").performScrollTo().performClick()
            compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("初音未来")
            compose.waitUntil(timeoutMillis = 5000) { queries.any { it == "初音未来" to "character" } }
            compose.waitForIdle()
            compose.onNodeWithText("hatsune_miku · 初音未来").assertExists()
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    fun dualOutputAndBindingPanelsHaveSeparateAndroidLayout() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val view = ComposeView(controller.get())
        try {
            controller.get().setContentView(view)
            view.setContent { MiuixTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AgentOutputPromptPanel(AgentPromptOutput("cat, blue hair, rainy street",
                        "blurry, extra limbs", "已写入工作流反向输入（1 项）")) { _, _ -> }
                    AgentPromptBindingPanel(
                        AgentPromptBinding(PromptBindingMode.EXPLICIT, "1", "text"),
                        AgentPromptBinding(PromptBindingMode.EXPLICIT, "2", "text"),
                        listOf(EditableNode.TextInput("1", "主体", "CLIPTextEncode", text = "cat", key = "text"),
                            EditableNode.TextInput("2", "排除", "CLIPTextEncode", text = "blurry", key = "text"))
                    ) { _, _ -> }
                }
            } }
            compose.waitForIdle()
            compose.onNodeWithText("正向提示词").assertExists()
            compose.onNodeWithText("反向提示词").assertExists()
            compose.onNodeWithText("反向节点").assertExists()
            val positive = compose.onNodeWithText("正向提示词").fetchSemanticsNode().boundsInRoot
            val negative = compose.onNodeWithText("反向提示词").fetchSemanticsNode().boundsInRoot
            val binding = compose.onNodeWithText("反向节点").fetchSemanticsNode().boundsInRoot
            assertTrue(positive.width > 0f)
            assertTrue(positive.bottom < negative.top)
            assertTrue(negative.bottom < binding.top)
        } finally { controller.pause().stop().destroy() }
    }
}
