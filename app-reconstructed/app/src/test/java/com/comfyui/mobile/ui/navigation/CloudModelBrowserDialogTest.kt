package com.comfyui.mobile.ui.navigation

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import com.comfyui.mobile.model.ServerProfile
import com.comfyui.mobile.modelcenter.LocalModel
import com.comfyui.mobile.modelcenter.ModelAnnotation
import com.comfyui.mobile.modelcenter.ModelProvider
import com.comfyui.mobile.modelcenter.modelPickerResource
import com.comfyui.mobile.network.RhResourceItem
import com.comfyui.mobile.network.RhResourcePageData
import com.comfyui.mobile.ui.CloudModelBrowserHost
import com.comfyui.mobile.ui.screens.control.CloudModelBrowserDialog
import com.comfyui.mobile.ui.theme.ComfyControllerTheme
import kotlinx.coroutines.flow.MutableStateFlow
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
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.basic.Text
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class CloudModelBrowserDialogTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ComponentActivity? = null
    private data class Request(val type: String, val keyword: String, val page: Int, val refresh: Boolean)
    private class Host(var count: Int) : CloudModelBrowserHost {
        override val servers = MutableStateFlow(listOf(
            ServerProfile(id = "one", host = "one.test"), ServerProfile(id = "two", host = "two.test")))
        override val currentServerId = MutableStateFlow("one")
        val requests = mutableListOf<Request>()
        var failPage = -1
        var shortPages = false
        var withMetadata = false
        var emptyWords = false
        var longNotes = false
        val selected = mutableListOf<RhResourceItem>()
        var detailCalls = 0
        override suspend fun localModelDetail(item: RhResourceItem): Result<RhResourceItem> {
            detailCalls++
            val local = item.localModel ?: return Result.success(item)
            return Result.success(modelPickerResource(item, local.copy(annotation = local.annotation.copy(
                notes = if (longNotes) "这是来自模型中心的详细使用备注。".repeat(80) else "最新使用备注"
            ))) { null })
        }
        override suspend fun searchCloudModels(resourceType: String, keyword: String, page: Int,
            pageSize: Int, baseModels: List<String>?) = error("local picker must not call cloud")
        override suspend fun searchLocalModels(resourceType: String, keyword: String, page: Int,
            pageSize: Int, baseModel: String?, refresh: Boolean): Result<RhResourcePageData> {
            requests += Request(resourceType, keyword, page, refresh)
            if (page == failPage) return Result.failure(IllegalStateException("分页加载失败"))
            val size = if (shortPages) 2 else pageSize
            val all = (0 until if (resourceType == "GGUF") 0 else count).map {
                val item = RhResourceItem(id = "$it", resourceName = "model-$it",
                    nodeModelName = "Anima\\model-$it.safetensors")
                if (!withMetadata) item else modelPickerResource(item, LocalModel(
                    id = "location-$it", serverId = "bridge", name = "model-$it.safetensors",
                    relativePath = "Anima/model-$it.safetensors", present = true,
                    annotation = ModelAnnotation(alias = "星空柔光画风-$it", tags = listOf("柔光"),
                        useCases = listOf("style"), trainedWords = if (emptyWords) emptyList() else listOf("manual_trigger"),
                        fieldLocks = listOf("trainedWords")),
                    provider = ModelProvider(modelDescription = "这是模型中心共享的完整简介", description = "版本使用说明",
                        trainedWords = listOf("provider_trigger"))
                )) { null }
            }.filter { keyword.isEmpty() || it.resourceName.orEmpty().contains(keyword) }
            val rows = all.drop((page - 1) * size).take(size)
            val pages = (all.size + size - 1) / size
            return Result.success(RhResourcePageData(rows, total = all.size, current = page,
                size = size, pages = pages, hasNext = page < pages))
        }
    }

    private fun screen(host: Host, parentDialog: Boolean = false, block: () -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        activity = controller.get()
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply { setContent {
                CompositionLocalProvider(LocalSquircleEnabled provides false) {
                    ComfyControllerTheme(darkTheme = true, manageSystemBars = false) {
                        Scaffold {
                            if (parentDialog) OverlayDialog(show = true, title = "画风与辅助参数",
                                onDismissRequest = {}) { Text("父级参数面板") }
                            CloudModelBrowserDialog(show = true, initialResourceType = "UNET",
                                viewModel = host, onDismiss = {}, onSelectModel = { host.selected += it })
                        }
                    }
                }
            } })
            compose.waitForIdle()
            block()
        } finally { activity = null; controller.pause().stop().destroy() }
    }

    @Test fun elevenServerModelsCanScrollPastFourVisibleCards() {
        val host = Host(11)
        screen(host) {
            compose.onNodeWithTag("model-picker-count").assertTextEquals("已加载 11 / 11 个")
            val before = compose.onNodeWithText("model-0").fetchSemanticsNode().boundsInRoot.top
            compose.onNodeWithTag("model-picker-grid").performTouchInput { swipeUp() }
            compose.waitForIdle()
            val first = compose.onAllNodesWithText("model-0").fetchSemanticsNodes()
            assertTrue("touch swipe must move the grid", first.isEmpty() || first.first().boundsInRoot.top < before)
            compose.onNodeWithTag("model-picker-grid").performScrollToIndex(10)
            compose.onNodeWithText("model-10").assertIsDisplayed()
            compose.onNodeWithTag("model-picker-grid").performScrollToIndex(11)
            compose.onNodeWithText("已加载全部模型 (11)").assertExists()
        }
    }

    @Test fun all51ModelsLoadAcrossThreePages() {
        val host = Host(51)
        screen(host) {
            compose.onNodeWithTag("model-picker-grid").performScrollToIndex(23)
            compose.waitForIdle()
            compose.onNodeWithTag("model-picker-count").assertTextEquals("已加载 48 / 51 个")
            compose.onNodeWithTag("model-picker-grid").performScrollToIndex(47)
            compose.waitForIdle()
            compose.onNodeWithTag("model-picker-count").assertTextEquals("已加载 51 / 51 个")
            compose.onNodeWithTag("model-picker-grid").performScrollToIndex(50)
            compose.onNodeWithText("model-50").assertIsDisplayed()
            assertEquals(listOf(1, 2, 3), host.requests.map { it.page })
        }
    }

    @Test fun shortPagesContinueLoadingWhileViewportHasRoom() {
        val host = Host(6).apply { shortPages = true }
        screen(host) {
            compose.onNodeWithTag("model-picker-count").assertTextEquals("已加载 6 / 6 个")
            assertEquals(listOf(1, 2, 3), host.requests.map { it.page })
        }
    }

    @Test fun refreshAndServerChangeReplaceSnapshot() {
        val host = Host(11)
        screen(host) {
            host.count = 12
            compose.onNodeWithTag("model-picker-refresh").performClick()
            compose.onNodeWithTag("model-picker-count").assertTextEquals("已加载 12 / 12 个")
            assertTrue(host.requests.last().refresh)
            host.count = 3
            compose.runOnIdle { host.currentServerId.value = "two" }
            compose.onNodeWithTag("model-picker-count").assertTextEquals("已加载 3 / 3 个")
        }
    }

    @Test fun draftKeywordDoesNotChangeNextPageAndRetryIsVisible() {
        val host = Host(51).apply { failPage = 2 }
        screen(host) {
            compose.onNodeWithTag("model-picker-search").performTextInput("model-50")
            compose.onNodeWithTag("model-picker-grid").performScrollToIndex(23)
            compose.waitForIdle()
            compose.onNodeWithTag("model-picker-grid").performScrollToIndex(24)
            compose.onNodeWithText("分页加载失败").assertExists()
            assertEquals("", host.requests.last().keyword)
            assertEquals(2, host.requests.last().page)
            host.failPage = -1
            compose.onNodeWithText("重试").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("model-picker-count").assertTextEquals("已加载 48 / 51 个")
            assertEquals(listOf(1, 2, 2), host.requests.map { it.page })
        }
    }

    @Test fun switchingTypeClearsOldModelsAndShowsHonestEmptyGguf() {
        val host = Host(11)
        screen(host) {
            compose.onNodeWithText("GGUF").performClick()
            compose.onNodeWithTag("model-picker-count").assertTextEquals("已加载 0 / 0 个")
            compose.onNodeWithText("model-0").assertDoesNotExist()
            compose.onNodeWithText("未找到相关模型，请尝试更换分类或关键词").assertExists()
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = [34], qualifiers = "w320dp-h720dp-mdpi")
    fun narrowPickerWithParentOverlayStillScrollsAndRenders() {
        screen(Host(11).apply { withMetadata = true }, parentDialog = true) {
            compose.onNodeWithTag("model-picker-count").assertTextEquals("已加载 11 / 11 个")
            compose.onNodeWithTag("model-picker-refresh").assertIsDisplayed()
            val before = compose.onNodeWithText("model-0").fetchSemanticsNode().boundsInRoot.top
            compose.onNodeWithTag("model-picker-grid").performTouchInput { swipeUp() }
            compose.waitForIdle()
            val first = compose.onAllNodesWithText("model-0").fetchSemanticsNodes()
            assertTrue(first.isEmpty() || first.first().boundsInRoot.top < before)
            compose.onNodeWithTag("model-picker-grid").performScrollToIndex(10)
            compose.onNodeWithText("model-10").assertIsDisplayed()
            compose.onNodeWithTag("model-picker-refresh").performClick()
            compose.onNodeWithText("model-0").assertIsDisplayed()
            compose.onNodeWithTag("model-picker-alias-0", useUnmergedTree = true).assertTextEquals("星空柔光画风-0")
            render("picker-320-dark.png")
        }
    }

    @Test fun cardShowsChineseAnnotationBelowFilenameAndSelectionKeepsNativeNodeOption() {
        val host = Host(2).apply { withMetadata = true }
        screen(host) {
            val name = compose.onNodeWithText("model-0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val alias = compose.onNodeWithTag("model-picker-alias-0", useUnmergedTree = true)
                .assertTextEquals("星空柔光画风-0").fetchSemanticsNode().boundsInRoot
            assertTrue("Chinese annotation belongs below the native filename", alias.top >= name.bottom)
            compose.onNodeWithText("model-0").performClick()
            compose.onNodeWithText("这是模型中心共享的完整简介", substring = true).assertExists()
            compose.onNodeWithTag("model-picker-detail-scroll").performScrollToNode(hasTestTag("model-picker-version"))
            compose.onNodeWithTag("model-picker-version").performClick()
            compose.onNodeWithText("版本使用说明", substring = true).assertExists()
            compose.onNodeWithText("manual_trigger").assertExists()
            compose.onNodeWithTag("model-picker-detail-scroll").performScrollToNode(hasTestTag("model-picker-annotation-toggle"))
            compose.onNodeWithTag("model-picker-annotation-toggle").performClick()
            compose.onNodeWithTag("model-picker-detail-scroll").performScrollToNode(hasText("备注：最新使用备注"))
            compose.onNodeWithText("中文名称：星空柔光画风-0").assertExists()
            compose.onNodeWithText("用途：画风").assertExists()
            compose.onNodeWithTag("model-picker-confirm").assertIsDisplayed().performClick()
            assertEquals(1, host.detailCalls)
            assertEquals("Anima\\model-0.safetensors", host.selected.single().effectiveModelName)
            assertEquals("model-0", host.selected.single().effectiveResourceName)
            assertEquals("manual_trigger", host.selected.single().triggerWords)
        }
    }

    @Test fun explicitlyEmptyTriggersStayEmptyInsteadOfUsingProviderGuess() {
        val host = Host(1).apply { withMetadata = true; emptyWords = true }
        screen(host) {
            compose.onNodeWithText("model-0").performClick()
            compose.onNodeWithTag("model-picker-empty-triggers").assertTextEquals("暂无记录")
            compose.onNodeWithText("provider_trigger").assertDoesNotExist()
            compose.onNodeWithTag("model-picker-confirm").performClick()
            assertTrue(host.selected.single().triggerWords.isNullOrBlank())
        }
    }

    @Test fun triggerWordsOnlyAppearAfterOpeningConfirmation() {
        val host = Host(2).apply { withMetadata = true }
        screen(host) {
            compose.onNodeWithText("model-0").assertIsDisplayed()
            compose.onNodeWithTag("model-picker-alias-0", useUnmergedTree = true)
                .assertTextEquals("星空柔光画风-0")
            compose.onNodeWithText("触发词: manual_trigger").assertDoesNotExist()
            compose.onNodeWithText("manual_trigger").assertDoesNotExist()
            compose.onNodeWithText("model-0").performClick()
            compose.onNodeWithText("manual_trigger").assertIsDisplayed()
            compose.onNodeWithTag("model-picker-confirm").assertIsDisplayed().performClick()
            assertEquals("Anima\\model-0.safetensors", host.selected.single().effectiveModelName)
            assertEquals("manual_trigger", host.selected.single().triggerWords)
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = [34], qualifiers = "w320dp-h720dp-mdpi")
    fun longModelCenterDetailsScrollWithoutHidingSelectionButtons() {
        val host = Host(1).apply { withMetadata = true; longNotes = true }
        screen(host, parentDialog = true) {
            compose.onNodeWithText("model-0").performClick()
            compose.onNodeWithTag("model-picker-confirm").assertIsDisplayed()
            render("picker-detail-top-320-dark.png")
            compose.onNodeWithTag("model-picker-detail-scroll").performScrollToNode(hasTestTag("model-picker-annotation-toggle"))
            compose.onNodeWithTag("model-picker-annotation-toggle").performClick()
            compose.onNodeWithTag("model-picker-detail-scroll").performTouchInput { swipeUp() }
            compose.waitForIdle()
            compose.onNodeWithTag("model-picker-confirm").assertIsDisplayed()
            render("picker-detail-320-dark.png")
            compose.onNodeWithTag("model-picker-cancel").performClick()
            compose.onNodeWithTag("model-picker-alias-0", useUnmergedTree = true).assertIsDisplayed()
            assertTrue(host.selected.isEmpty())
        }
    }

    private fun render(name: String) {
            compose.runOnIdle {
                val view = requireNotNull(activity).window.decorView
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                val file = File("build/model-picker-preview/$name")
                file.parentFile?.mkdirs()
                file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                println("MODEL_PICKER_RENDER ${file.absolutePath} ${bitmap.width}x${bitmap.height}")
            }
    }
}
