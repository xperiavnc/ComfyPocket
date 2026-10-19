package com.comfyui.mobile.ui.navigation

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.comfyui.mobile.model.EditableNode
import com.comfyui.mobile.modelcenter.LocalModel
import com.comfyui.mobile.modelcenter.ModelAnnotation
import com.comfyui.mobile.modelcenter.ModelProvider
import com.comfyui.mobile.modelcenter.modelPickerResource
import com.comfyui.mobile.network.RhResourceItem
import com.comfyui.mobile.network.RhResourceVersion
import com.comfyui.mobile.ui.screens.modelbrowser.ModelSelectionConfirmation
import com.comfyui.mobile.ui.theme.ComfyControllerTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class ModelSelectionConfirmationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ComponentActivity? = null
    private val triggers = "night_style, soft lighting, calm expression"
    private val node = EditableNode.TextInput("7", "正向提示词", "CLIPTextEncode", "existing_prompt", key = "text")
    private fun fixture(longIntro: Boolean = false, longWords: Boolean = false): RhResourceItem {
        val introduction = if (longIntro) ("这是用来检查确认页排版的模型简介，说明适合的画面、采样设置与使用方法。" +
            "简介不应挤占整个确认页，可按需展开阅读。").repeat(16) else
            "适合夜景与柔光插画。建议先沿用工作流参数，再按实际效果调整模型强度。"
        return modelPickerResource(
            RhResourceItem(id = "fixture", resourceName = "portrait-softlight-v1.0",
                nodeModelName = "Anima\\画风\\portrait-softlight-v1.0.safetensors"),
            LocalModel(id = "location", serverId = "bridge", category = "LORA",
                name = "portrait-softlight-v1.0.safetensors", relativePath = "Anima/画风/portrait-softlight-v1.0.safetensors",
                present = true, size = 412L*1024*1024, format = "SAFETENSORS",
                annotation = ModelAnnotation(alias = "柔光肖像 · 星夜画风", tags = listOf("夜景", "柔光"),
                    useCases = listOf("style"), preferredStrength = .7, notes = "先用 0.7 强度测试，按实际效果调整。",
                    trainedWords = listOf(if (longWords) triggers.repeat(30) else triggers), fieldLocks = listOf("trainedWords")),
                provider = ModelProvider(name = "Softlight Portrait", version = "v1.0", author = "fixture-author",
                    baseModel = "Anima", modelDescription = introduction, description = "此版本优化了夜景光照与轮廓层次。"))
        ) { null }
    }
    private class Calls {
        var selected = 0
        var dismissed = 0
        var retried = 0
        var written: Pair<EditableNode.TextInput, String>? = null
        var extra: Pair<RhResourceItem, String>? = null
    }

    private fun screen(
        item: RhResourceItem = fixture(), local: Boolean = true, dark: Boolean = true, fontScale: Float = 1f,
        nodes: Boolean = false, extra: Boolean = false, loading: Boolean = false, error: String? = null,
        block: (Calls) -> Unit
    ) {
        val calls = Calls()
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        activity = controller.get()
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply { setContent {
                CompositionLocalProvider(LocalSquircleEnabled provides false,
                    LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    ComfyControllerTheme(darkTheme = dark, manageSystemBars = false) {
                        Scaffold {
                            ModelSelectionConfirmation(item, local, "LORA", "http://computer.test:8188",
                                phoneImages = null, phoneImageRequest = { it }, detailLoading = loading, detailError = error,
                                onRetryDetail = { calls.retried++ }, onDismiss = { calls.dismissed++ },
                                onConfirm = { calls.selected++ }, textNodes = if (nodes) listOf(node) else emptyList(),
                                onWriteTriggerNode = { selected, words -> calls.written = selected to words },
                                extraTriggerAction = if (extra) { model, words, dismiss ->
                                    TextButton("加入角色库", onClick = { calls.extra = model to words; dismiss() },
                                        modifier = Modifier.testTag("test-extra-action"))
                                } else null)
                        }
                    }
                }
            } })
            compose.waitForIdle()
            block(calls)
        } finally { activity = null; controller.pause().stop().destroy() }
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag("model-picker-detail-scroll").performScrollToNode(hasTestTag(tag))
    }

    @Test fun confirmationPrioritizesNameAliasAndTriggersAndFoldsSecondaryInformation() {
        screen { calls ->
            compose.onNodeWithText("确认选择模型").assertExists()
            compose.onNodeWithTag("model-picker-confirm-name").assertTextEquals("portrait-softlight-v1.0")
            compose.onNodeWithTag("model-picker-confirm-alias").assertTextEquals("柔光肖像 · 星夜画风")
            compose.onNodeWithText(triggers).assertIsDisplayed()
            compose.onNodeWithTag("model-picker-native-option").assertDoesNotExist()
            compose.onNodeWithTag("model-picker-annotation").assertDoesNotExist()
            scrollTo("model-picker-version")
            compose.onNodeWithTag("model-picker-version").performClick()
            compose.onNodeWithText("此版本优化了夜景光照与轮廓层次。").assertExists()
            scrollTo("model-picker-annotation-toggle")
            compose.onNodeWithTag("model-picker-annotation-toggle").performClick()
            compose.onNodeWithText("推荐强度：0.7").assertExists()
            scrollTo("model-picker-file-toggle")
            compose.onNodeWithTag("model-picker-file-toggle").performClick()
            compose.onNodeWithTag("model-picker-native-option")
                .assertTextEquals("Anima\\画风\\portrait-softlight-v1.0.safetensors")
            compose.onNodeWithTag("model-picker-confirm").assertIsDisplayed().performClick()
            assertEquals(1, calls.selected)
            assertNull(calls.written)
        }
    }

    @Test fun copyingTriggersKeepsExactTextAndDoesNotSelectOrWriteTheWorkflow() {
        screen { calls ->
            compose.onNodeWithTag("model-picker-copy-triggers").performClick()
            val manager = activity!!.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(triggers, manager.primaryClip!!.getItemAt(0).text.toString())
            assertEquals(0, calls.selected)
            assertNull(calls.written)
        }
    }

    @Test fun explicitNodeActionReceivesExactTriggerWordsAndDoesNotAutoConfirm() {
        screen(nodes = true) { calls ->
            compose.onNodeWithTag("model-picker-trigger-nodes").performClick()
            scrollTo("model-picker-write-7")
            compose.onNodeWithTag("model-picker-write-7").performClick()
            assertEquals(node to triggers, calls.written)
            assertEquals(0, calls.selected)
            assertEquals("existing_prompt", node.text)
        }
    }

    @Test fun agentExtraActionReceivesExactOriginalItemAndTriggers() {
        val item = fixture()
        screen(item, extra = true) { calls ->
            compose.onNodeWithTag("test-extra-action").performClick()
            assertEquals(item to triggers, calls.extra)
            assertEquals(1, calls.dismissed)
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun longIntroductionCanExpandAndCollapseWithoutHidingFooter() {
        screen(fixture(longIntro = true)) { calls ->
            compose.onNodeWithTag("model-picker-detail-scroll").performScrollToNode(hasText("展开简介"))
            compose.onNodeWithText("展开简介").performClick()
            compose.onNodeWithTag("model-picker-confirm").assertIsDisplayed()
            compose.onNodeWithTag("model-picker-detail-scroll").performScrollToNode(hasText("收起简介"))
            compose.onNodeWithText("收起简介").performClick()
            compose.onNodeWithTag("model-picker-confirm").assertIsDisplayed()
            compose.onNodeWithTag("model-picker-cancel").performClick()
            assertEquals(1, calls.dismissed)
            assertEquals(0, calls.selected)
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun longTriggersExpandInsideTheSameScrollOwnerAndCopyFullValue() {
        val item = fixture(longWords = true)
        screen(item) {
            compose.onNodeWithText("展开触发词").performClick()
            compose.onNodeWithTag("model-picker-confirm").assertIsDisplayed()
            compose.onNodeWithTag("model-picker-detail-scroll").performScrollToNode(hasText("收起触发词"))
            compose.onNodeWithText("收起触发词").performClick()
            scrollTo("model-picker-copy-triggers")
            compose.onNodeWithTag("model-picker-copy-triggers").performClick()
            val manager = activity!!.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(item.triggerWords, manager.primaryClip!!.getItemAt(0).text.toString())
        }
    }

    @Test fun oldBridgeMissingMetadataStillAllowsSelectionAndOffersExplicitRetry() {
        screen(item = RhResourceItem(id = "old", resourceName = "native-model",
            nodeModelName = "folder/native-model.safetensors"), error = "资料加载失败，请重试") { calls ->
            compose.onNodeWithTag("model-picker-confirm-name").assertTextEquals("native-model")
            compose.onNodeWithText("资料加载失败，请重试").assertExists()
            compose.onNodeWithText("重试资料").performClick()
            assertEquals(1, calls.retried)
            compose.onNodeWithTag("model-picker-confirm").performClick()
            assertEquals(1, calls.selected)
        }
    }

    @Test fun runningHubKeepsTriggerActionsWithoutLocalOnlyAnnotationOrLoadingMessages() {
        val item = RhResourceItem(id = "cloud", resourceName = "Cloud model", desc = "云端模型说明",
            versions = listOf(RhResourceVersion(triggerWords = triggers, versionResourceName = "Cloud model.safetensors")))
        screen(item, local = false, nodes = true, loading = true) {
            compose.onNodeWithText(triggers).assertExists()
            compose.onNodeWithTag("model-picker-detail-loading").assertDoesNotExist()
            compose.onNodeWithTag("model-picker-annotation-toggle").assertDoesNotExist()
            compose.onNodeWithTag("model-picker-empty-triggers").assertDoesNotExist()
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
    fun darkConfirmationRendersCompactHeaderAndSectionHierarchy() {
        screen(fixture(longIntro = true)) {
            compose.onNodeWithText(triggers).assertIsDisplayed()
            compose.onNodeWithTag("model-picker-confirm").assertIsDisplayed()
            render("confirmation-360-dark.png")
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = [34], qualifiers = "w320dp-h720dp-mdpi")
    fun narrowLightLargeTextCanScrollAndKeepsBothButtonsVisible() {
        screen(fixture(longIntro = true), dark = false, fontScale = 1.3f) {
            compose.onNodeWithTag("model-picker-confirm").assertIsDisplayed()
            compose.onNodeWithTag("model-picker-cancel").assertIsDisplayed()
            compose.onNodeWithTag("model-picker-detail-scroll").performTouchInput { swipeUp() }
            compose.waitForIdle()
            compose.onNodeWithTag("model-picker-confirm").assertIsDisplayed()
            compose.onNodeWithTag("model-picker-cancel").assertIsDisplayed()
            val result = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText("确定选择", useUnmergedTree = true)
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(result) }
            assertEquals(1, result.single().lineCount)
            render("confirmation-320-light-large-text.png")
        }
    }

    private fun render(name: String) = compose.runOnIdle {
        val view = requireNotNull(activity).window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file = File("build/model-confirm-preview/$name")
        file.parentFile?.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        println("MODEL_CONFIRM_RENDER ${file.absolutePath} ${bitmap.width}x${bitmap.height}")
    }
}
