package com.comfyui.mobile.ui.screens.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.comfyui.mobile.model.AgentChatMessage
import com.comfyui.mobile.model.MediaOutput
import com.comfyui.mobile.model.MediaType
import com.comfyui.mobile.ui.MainViewModel
import com.comfyui.mobile.ui.theme.ComfyControllerTheme
import coil.Coil
import coil.ImageLoader
import coil.intercept.Interceptor
import coil.decode.DataSource
import coil.request.ImageResult
import coil.request.SuccessResult
import java.io.File
import org.junit.Assert.*
import org.junit.After
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
@Config(sdk = [34], qualifiers = "w412dp-h915dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AgentWorkbenchUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val status = mutableStateOf(AgentCardStatus())
    private val input = mutableStateOf(TextFieldValue("保留当前草稿"))
    private var openedImage: Int? = null
    private var sessions = 0
    private var parameters = 0
    private var selectedModel = 0
    private var sent: String? = null
    private lateinit var listState: LazyListState
    private val mode = mutableStateOf(MainViewModel.AGENT_MODE_DRAW)
    private val results = mutableStateOf<List<MediaOutput>>(emptyList())
    private var loader: ImageLoader? = null
    private val loadedImages = java.util.concurrent.atomic.AtomicInteger()

    @After fun cleanupImageLoader() { loader?.shutdown(); Coil.reset() }

    private fun install(activity: ComponentActivity, dark: Boolean = true, compact: Boolean = false) {
        val image = File(activity.cacheDir, "workbench-preview.png")
        Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(0xffa8bfd3.toInt())
            canvas.drawCircle(260f, 55f, 28f, Paint().apply { color = 0xffffeac0.toInt() })
            canvas.drawOval(-70f, 110f, 350f, 380f, Paint().apply { color = 0xff567a83.toInt() })
            canvas.drawOval(130f, 150f, 430f, 380f, Paint().apply { color = 0xff294f60.toInt() })
            image.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
        // A synchronous local interceptor avoids decoder/main-loop scheduling differences in Robolectric.
        loader = ImageLoader.Builder(activity).components {
            add(object : Interceptor {
                override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                    val bitmap = requireNotNull(BitmapFactory.decodeFile(image.absolutePath))
                    loadedImages.incrementAndGet()
                    return SuccessResult(BitmapDrawable(activity.resources, bitmap), chain.request, DataSource.DISK)
                }
            })
        }.build().also { Coil.setImageLoader(it) }
        results.value = (0..2).map { MediaOutput("preview-$it.png", "", "output", MediaType.IMAGE,
            timestamp = it.toLong(), localUri = image.toURI().toString()) }
        activity.setContentView(ComposeView(activity).apply { setContent {
            Fixture(dark, compact)
        } })
    }

    @Composable private fun Fixture(dark: Boolean = true, compact: Boolean = false) {
        CompositionLocalProvider(LocalSquircleEnabled provides false) {
            ComfyControllerTheme(darkTheme = dark, manageSystemBars = false) {
                Scaffold {
                    AgentWorkspaceLayout(
                        sessionTitle = "雨天街景 · 保留角色与服装的长会话标题",
                        mediaList = results.value, previewUrl = { it.localUri.orEmpty() }, status = status.value,
                        wfLabel = "安卓 anima", cardColors = CardDefaults.defaultColors(MiuixTheme.colorScheme.surfaceContainerHighest),
                        sidePad = 16.dp, onOpenSessions = { sessions++ }, onImageClick = { openedImage = it },
                        modifier = Modifier.fillMaxSize().then(if (compact) Modifier.padding(bottom = 250.dp) else Modifier)
                    ) {
                        listState = rememberLazyListState()
                        AgentChatPanel(
                            messages = (0..23).map { AgentChatMessage("m$it", if (it % 2 == 0) "user" else "assistant",
                                if (it % 2 == 0) "换成雨天街景，保留之前的角色和构图。$it" else "保留角色与服装，只调整环境和光线。$it") },
                            input = input.value, onInputChange = { input.value = it }, busy = false,
                            cardColors = CardDefaults.defaultColors(MiuixTheme.colorScheme.background),
                            sidePad = 16.dp, listState = listState, modifier = Modifier.weight(1f), framed = false,
                            toolbar = {
                                AgentCapsuleBar("我的模型配置", mode.value, 0.dp,
                                    onModelSwitchClick = { selectedModel++ },
                                    onInteractionModeChange = { mode.value = it },
                                    onWorkbenchActionsClick = { parameters++ })
                            }, onSendMessage = { sent = it }
                        )
                    }
                }
            }
        }
    }

    private fun capture(activity: ComponentActivity, name: String) {
        compose.waitForIdle()
        compose.waitUntil(5000) { loadedImages.get() > 0 }
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnIdle { view.draw(Canvas(bitmap)) }
        File("build/reports/agent-workbench/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    @Test fun collapseReleasesChatSpaceAndPreservesDraftAndReadingPosition() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            install(controller.get())
            compose.onNodeWithTag("agent-workbench-preview").assertIsDisplayed()
            compose.onNodeWithTag("agent-chat-list").performScrollToIndex(8)
            val oldHeight = compose.onNodeWithTag("agent-chat-list").fetchSemanticsNode().boundsInRoot.height
            val anchor = compose.runOnIdle { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            capture(controller.get(), "expanded-dark")
            compose.onNodeWithTag("agent-workbench-toggle").performClick()
            compose.onNodeWithTag("agent-workbench-preview").assertDoesNotExist()
            val newHeight = compose.onNodeWithTag("agent-chat-list").fetchSemanticsNode().boundsInRoot.height
            assertTrue("Collapsing must give the conversation meaningful space", newHeight - oldHeight >= 80)
            compose.runOnIdle { assertEquals(anchor, listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset) }
            compose.onNodeWithTag("agent-chat-input").assertTextEquals("保留当前草稿")
            capture(controller.get(), "collapsed-dark")
            compose.onNodeWithContentDescription("绘图参数与操作").performClick()
            assertEquals(1, parameters)
            compose.onNodeWithText("我的模型配置").performClick()
            assertEquals(1, selectedModel)
            compose.onNodeWithContentDescription("发送").performClick()
            assertEquals("保留当前草稿", sent)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun collapsedStatusTracksBusyErrorAndNewResultsWithoutForcingExpansion() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            install(controller.get())
            compose.onNodeWithTag("agent-workbench-toggle").performClick()
            compose.runOnIdle { status.value = AgentCardStatus(AgentCardPhase.GeneratingLocal,
                title = "生成中", subtitle = "采样 · 42%", progress = .42f, showProgress = true, showOverlay = true) }
            compose.onNodeWithText("生成中 · 采样 · 42%").assertIsDisplayed()
            compose.onNodeWithTag("agent-workbench-progress").assertIsDisplayed()
            compose.onNodeWithTag("agent-workbench-thumbnail").performClick()
            assertNull(openedImage)
            capture(controller.get(), "collapsed-progress-dark")
            compose.runOnIdle {
                status.value = AgentCardStatus(errorBadge = "提交失败")
                results.value = listOf(results.value.first().copy(timestamp = 100)) + results.value
            }
            compose.onNodeWithTag("agent-workbench-preview").assertDoesNotExist()
            compose.onNodeWithText("提交失败").assertIsDisplayed()
            compose.onNodeWithText("最新生成 · 4 项").assertIsDisplayed()
            compose.onNodeWithTag("agent-workbench-thumbnail").performClick()
            assertEquals(0, openedImage)
            compose.onNodeWithTag("agent-open-sessions").performClick()
            assertEquals(1, sessions)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun pagerSelectionSurvivesCollapseAndOnlyNewResultsResetIt() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            install(controller.get())
            compose.onNodeWithTag("agent-workbench-preview").performTouchInput { swipeLeft() }
            compose.onNodeWithText("(2/3)").assertExists()
            compose.onNodeWithTag("agent-workbench-toggle").performClick()
            compose.onNodeWithTag("agent-workbench-toggle").performClick()
            compose.onNodeWithText("(2/3)").assertExists()
            compose.runOnIdle { results.value = listOf(results.value.first().copy(timestamp = 100)) + results.value }
            compose.onNodeWithText("(1/4)").assertExists()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun collapsePreferenceSurvivesSavedStateRecreation() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var savedState = SaveableStateRegistry(null) { true }
        val mounted = mutableStateOf(true)
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply { setContent {
                if (mounted.value) {
                    CompositionLocalProvider(LocalSaveableStateRegistry provides savedState) { Fixture() }
                }
            } })
            compose.onNodeWithTag("agent-workbench-toggle").performClick()
            val saved = compose.runOnIdle { savedState.performSave().also { mounted.value = false } }
            compose.waitForIdle()
            compose.runOnIdle { savedState = SaveableStateRegistry(saved) { true }; mounted.value = true }
            compose.onNodeWithTag("agent-workbench-preview").assertDoesNotExist()
            compose.onNodeWithTag("agent-workbench-toggle").performClick()
            compose.onNodeWithTag("agent-workbench-preview").assertIsDisplayed()
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun narrowLightLayoutKeepsInputAndControlsReachableInReducedHeight() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            install(controller.get(), dark = false, compact = true)
            compose.onNodeWithTag("agent-chat-input").assertIsDisplayed()
            compose.onNodeWithContentDescription("发送").assertIsDisplayed()
            compose.onNodeWithContentDescription("绘图参数与操作").assertIsDisplayed()
            compose.onNodeWithTag("agent-workbench-toggle").performClick()
            compose.onNodeWithTag("agent-chat-input").performTextReplacement("窄屏输入")
            compose.onNodeWithContentDescription("发送").performClick()
            assertEquals("窄屏输入", sent)
            capture(controller.get(), "compact-320-light")
        } finally { controller.pause().stop().destroy() }
    }
}
