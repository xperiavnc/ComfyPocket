package com.comfyui.mobile.ui.screens.agent

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.comfyui.mobile.model.AgentChatMessage
import com.comfyui.mobile.model.AgentClarification
import com.comfyui.mobile.model.AgentUserQuestion
import com.comfyui.mobile.model.AgentQuestionOption
import com.comfyui.mobile.model.AgentQuestionAnswer
import com.comfyui.mobile.ui.theme.ComfyControllerTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import top.yukonga.miuix.kmp.theme.MiuixTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AgentClarificationUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun failedContinuationReenablesTheSameFormAndPreservesThePreviousSelection() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val enabled = mutableStateOf(true)
        val sent = mutableListOf<List<AgentQuestionAnswer>>()
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply {
                setContent { MiuixTheme {
                    AgentQuestionForm("retry", listOf(AgentUserQuestion("outfit", "选哪套？", "multiple_choice",
                        listOf(AgentQuestionOption("a", "常服"), AgentQuestionOption("b", "礼服")))),
                        enabled.value, { sent += it; enabled.value = false; true }, {})
                } }
            })
            compose.onNodeWithTag("agent-question-outfit-option-b").performClick()
            compose.onNodeWithTag("agent-question-submit").performClick()
            compose.waitForIdle()
            assertEquals(1, sent.size)
            compose.runOnIdle { enabled.value = true }
            compose.onNodeWithTag("agent-question-submit").assertIsEnabled().performClick()
            assertEquals(2, sent.size)
            assertEquals(sent.first(), sent.last())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun wardrobeDescriptionsShowDifferencesWithoutAutoSelectingTheRecommendation() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val submitted = mutableListOf<List<AgentQuestionAnswer>>()
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply {
                setContent { MiuixTheme {
                    AgentQuestionForm("wardrobe", listOf(AgentUserQuestion("suzuran", "铃兰本轮用哪套？",
                        "multiple_choice", listOf(
                            AgentQuestionOption("casual", "常服（推荐）", "日常衬衫，延续熟悉的外观"),
                            AgentQuestionOption("formal", "礼服", "正式连衣裙，改变画面风格")))),
                        true, { submitted += it; true }, {})
                } }
            })
            compose.waitForIdle()
            compose.onNodeWithText("日常衬衫，延续熟悉的外观", useUnmergedTree = true).assertExists()
            render(controller.get(), "outfit-descriptions-360")
            compose.onNodeWithTag("agent-question-submit").assertIsNotEnabled()
            assertTrue(submitted.isEmpty())
            compose.onNodeWithTag("agent-question-suzuran-option-formal").performClick()
            compose.onNodeWithTag("agent-question-submit").assertIsEnabled().performClick()
            assertEquals(listOf("formal"), submitted.single().single().optionIds)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun questionFormBatchesSingleMultiAndCustomTextWithoutAutoSendingOnSelection() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val busy = mutableStateOf(false)
        val compact = mutableStateOf(false)
        val submitted = mutableListOf<List<AgentQuestionAnswer>>()
        val questions = listOf(
            AgentUserQuestion("framing", "想用哪种构图？", "multiple_choice",
                listOf(AgentQuestionOption("full", "全身"), AgentQuestionOption("half", "半身"))),
            AgentUserQuestion("details", "哪些配件需要保留？", "multiple_choice",
                listOf(AgentQuestionOption("hat", "帽子"), AgentQuestionOption("gloves", "手套")), true),
            AgentUserQuestion("background", "背景有什么要求？", "free_text"))
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply {
                setContent {
                    CompositionLocalProvider(LocalSquircleEnabled provides false) {
                        ComfyControllerTheme(darkTheme = true, manageSystemBars = false) {
                            Scaffold {
                                AgentChatPanel(
                                    messages = listOf(AgentChatMessage("q", "assistant", "先确认三项。",
                                        clarification = AgentClarification("请确认以下问题", emptyList(),
                                            purpose = "agent_question", questions = questions))),
                                    input = TextFieldValue(""), onInputChange = {}, busy = busy.value,
                                    cardColors = CardDefaults.defaultColors(MiuixTheme.colorScheme.surfaceContainerHigh),
                                    sidePad = 12.dp, listState = rememberLazyListState(),
                                    modifier = if (compact.value) Modifier.fillMaxWidth().height(330.dp) else Modifier.fillMaxSize(),
                                    onAnswerQuestions = { id, answers ->
                                        assertEquals("q", id)
                                        submitted += answers
                                        busy.value = true
                                        true
                                    },
                                    onSendMessage = { fail("Choice selection must not send a chat message") }
                                )
                            }
                        }
                    }
                }
            })
            compose.onNodeWithTag("agent-question-submit").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("agent-question-framing-option-full").performScrollTo().performClick()
            compose.onNodeWithTag("agent-question-details-option-hat").performScrollTo().performClick()
            compose.onNodeWithTag("agent-question-details-option-gloves").performScrollTo().performClick()
            assertTrue(submitted.isEmpty())
            compose.onNodeWithTag("agent-question-submit").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("agent-question-background-text").performScrollTo().performTextInput("雨夜街道")
            compose.onNodeWithTag("agent-question-submit").performScrollTo().assertIsEnabled()
            render(controller.get(), "ask-batch-360-dark")
            compose.runOnIdle { compact.value = true }
            compose.onNodeWithTag("agent-question-submit").performScrollTo().assertIsDisplayed()
            render(controller.get(), "ask-batch-360-dark-compact")
            compose.onNodeWithTag("agent-question-submit").performClick()
            assertEquals(1, submitted.size)
            assertEquals(listOf("full"), submitted.single()[0].optionIds)
            assertEquals(listOf("hat", "gloves"), submitted.single()[1].optionIds)
            assertEquals("雨夜街道", submitted.single()[2].text)
            compose.onNodeWithTag("agent-question-submit").assertDoesNotExist()
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h720dp-mdpi")
    fun customAnswerAndCancellationWorkOnNarrowScreen() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val sent = mutableListOf<String>()
        val label = "保留参考图中的完整外套与围巾（keep the original coat and scarf）"
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply {
                setContent {
                    CompositionLocalProvider(
                        LocalSquircleEnabled provides false,
                        LocalDensity provides Density(LocalDensity.current.density, 1.2f)
                    ) {
                        ComfyControllerTheme(darkTheme = false, manageSystemBars = false) {
                            Scaffold {
                                AgentChatPanel(
                                    messages = listOf(AgentChatMessage("q", "assistant", "确认一下服装细节。",
                                        clarification = AgentClarification("请确认以下问题", emptyList(),
                                            questions = listOf(AgentUserQuestion("outfit", "你希望怎么处理服装？", "multiple_choice",
                                                listOf(AgentQuestionOption("keep", label))))))),
                                    input = TextFieldValue(""), onInputChange = {}, busy = false,
                                    cardColors = CardDefaults.defaultColors(MiuixTheme.colorScheme.surfaceContainerHigh),
                                    sidePad = 8.dp, listState = rememberLazyListState(), modifier = Modifier.fillMaxSize(),
                                    onSendMessage = { sent += it }
                                )
                            }
                        }
                    }
                }
            })
            val option = compose.onNodeWithTag("agent-question-outfit-option-keep").performScrollTo()
            val bounds = option.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.height >= 44f)
            assertTrue(bounds.left >= 0f && bounds.right <= 320f)
            compose.onNodeWithTag("agent-question-outfit-text").performScrollTo().performTextInput("不用预设，按描述设计新外套")
            compose.onNodeWithTag("agent-question-submit").performScrollTo().assertIsEnabled()
            assertTrue(sent.isEmpty())
            render(controller.get(), "ask-custom-320-light-large-text")
            compose.onNodeWithTag("agent-question-cancel").performScrollTo().performClick()
            assertEquals(listOf("取消本次提问"), sent)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun latestChoiceSendsOneAnswerAndOldChoicesStayDisabled() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val busy = mutableStateOf(false)
        val sent = mutableListOf<String>()
        val question = AgentClarification("想使用哪一套服装？", listOf("冬装", "便服"))
        val messages = listOf(
            AgentChatMessage("old", "assistant", "上次的问题", clarification = question),
            AgentChatMessage("user", "user", "给星澜换一套衣服，保留雨夜背景。"),
            AgentChatMessage("new", "assistant", question.question, clarification = question)
        )
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply {
                setContent {
                    CompositionLocalProvider(LocalSquircleEnabled provides false) {
                        ComfyControllerTheme(darkTheme = true, manageSystemBars = false) {
                            Scaffold {
                                AgentChatPanel(
                                    messages = messages,
                                    input = TextFieldValue(""),
                                    onInputChange = {},
                                    busy = busy.value,
                                    cardColors = CardDefaults.defaultColors(MiuixTheme.colorScheme.surfaceContainerHigh),
                                    sidePad = 12.dp,
                                    listState = rememberLazyListState(),
                                    modifier = Modifier.fillMaxSize(),
                                    onSendMessage = { sent += it; busy.value = true }
                                )
                            }
                        }
                    }
                }
            })
            compose.waitForIdle()
            val choices = compose.onAllNodesWithTag("agent-clarification-option-1")
            choices[0].assertIsNotEnabled()
            choices[1].assertIsEnabled()
            render(controller.get(), "chat-360-dark")
            choices[1].performClick()
            assertEquals(listOf("我选择：便服"), sent)
            choices[1].assertIsNotEnabled()
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h720dp-mdpi")
    fun longBilingualChoicesWrapOnNarrowScreen() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val longOption = "星澜（轻金庆典版本）（star_lan_(light_gold_celebration)_(fixture_series)）"
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply {
                setContent {
                    CompositionLocalProvider(
                        LocalSquircleEnabled provides false,
                        LocalDensity provides Density(LocalDensity.current.density, 1.2f)
                    ) {
                        ComfyControllerTheme(darkTheme = false, manageSystemBars = false) {
                            Scaffold {
                                AgentChatPanel(
                                    messages = listOf(AgentChatMessage(
                                        "variant", "assistant", "词库有多种服装版本，请确认。",
                                        clarification = AgentClarification(
                                            "请选择本轮使用的版本。", listOf(longOption, "不添加词库角色标签"))
                                    )),
                                    input = TextFieldValue(""),
                                    onInputChange = {},
                                    busy = false,
                                    cardColors = CardDefaults.defaultColors(MiuixTheme.colorScheme.surfaceContainerHigh),
                                    sidePad = 8.dp,
                                    listState = rememberLazyListState(),
                                    modifier = Modifier.fillMaxSize(),
                                    onSendMessage = {}
                                )
                            }
                        }
                    }
                }
            })
            compose.waitForIdle()
            compose.onNodeWithText("请选择本轮使用的版本。").assertIsDisplayed()
            val option = compose.onNodeWithTag("agent-clarification-option-0").assertIsDisplayed()
            val bounds = option.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.height >= 44f)
            assertTrue(bounds.left >= 0f && bounds.right <= 320f)
            compose.onNodeWithText(longOption).assertExists()
            render(controller.get(), "chat-320-light-large-text")
        } finally { controller.pause().stop().destroy() }
    }

    private fun render(activity: ComponentActivity, name: String) = compose.runOnIdle {
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file = File("build/agent-clarification-preview/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue(file.length() > 8000)
        println("CLARIFICATION_UI_RENDER ${file.absolutePath}")
        bitmap.recycle()
    }
}
