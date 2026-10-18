package com.comfyui.mobile.ui.navigation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import coil.Coil
import coil.EventListener
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.util.DebugLogger
import com.comfyui.mobile.ComfyApplication
import com.comfyui.mobile.market.MarketCatalogState
import com.comfyui.mobile.model.*
import com.comfyui.mobile.modelcenter.*
import com.comfyui.mobile.ui.screens.market.MarketDownloadQueuePanel
import com.comfyui.mobile.ui.screens.modelcenter.*
import com.comfyui.mobile.ui.theme.ComfyControllerTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer

/**
 * Compose/Skia layout render, synthetic covers and queues; no phone/server mutation.
 * Desktop Skia rejects Miuix's AGSL `layout(color)` qualifier. Only this render fixture
 * reports shader support as false to use the library's documented rounded-corner fallback.
 * All tests still run on API 34; screenshots are not phone/GPU frame-rate validation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-mdpi", application = ComfyApplication::class,
    shadows = [ModelCenterVisualTest.CpuShaderSupport::class],
    instrumentedPackages = ["top.yukonga.miuix.kmp.shader"])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ModelCenterVisualTest {
    @Implements(className = "top.yukonga.miuix.kmp.shader.RuntimeShader_androidKt", isInAndroidSdk = false)
    class CpuShaderSupport {
        companion object {
            @JvmStatic @Implementation fun isRuntimeShaderSupported(): Boolean = false
        }
    }
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()
    private val output = File(System.getProperty("user.dir"), "build/ui-previews/model-center")
    private var imageServer: MockWebServer? = null
    private var host: ComponentActivity? = null
    private val successfulImages = AtomicInteger()

    @Before fun imageDiagnostics() {
        ShadowLog.stream = System.out
        Coil.setImageLoader((app as ComfyApplication).newImageLoader().newBuilder()
            .logger(DebugLogger())
            .eventListener(object : EventListener {
                override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                    successfulImages.incrementAndGet()
                }
            }).build())
    }

    @After fun closeServer() {
        imageServer?.shutdown()
        Coil.imageLoader(app).shutdown()
        Coil.reset()
        ShadowLog.stream = null
    }

    private fun fixtureCover(): String {
        output.mkdirs()
        val file = File(output, "fixture-cover.png")
        val image = Bitmap.createBitmap(240, 280, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.drawColor(Color.rgb(48, 68, 96))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(141, 176, 206)
        canvas.drawCircle(170f, 85f, 46f, paint)
        paint.color = Color.rgb(72, 122, 143)
        canvas.drawOval(-100f, 140f, 250f, 420f, paint)
        paint.color = Color.rgb(40, 85, 112)
        canvas.drawOval(100f, 180f, 350f, 440f, paint)
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        val bytes = file.readBytes()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse()
                .setHeader("Content-Type", "image/png").setHeader("Cache-Control", "max-age=3600")
                .setBody(Buffer().write(bytes))
        }
        imageServer = server
        return server.url("/fixture-cover.png").toString()
    }

    private fun fixtureState(compact: Boolean = false) = ModelCenterState(
        restored = true, connected = true, fromCache = false, serverLabel = "工作电脑", compact = compact,
        page = ModelPage(total = 4, scanComplete = true, lastSync = 1792309200000,
            stats = ModelStats(locations = 77, bytes = 239520000000L, hashed = 77, uniqueContents = 73),
            items = listOf("柔光人像", "插画线条", "日常服装", "自然光影").mapIndexed { index, title ->
                LocalModel(id = "fixture-$index", name = "$title.safetensors", category = "LORA",
                    rootPath = "D:/fixture/loras", relativePath = "styles/$title.safetensors", size = 124000000,
                    present = true, annotation = ModelAnnotation(alias = title, baseModel = "Illustrious"),
                    preview = ModelPreviewInfo("local", "fixture-cover"))
            }))

    private fun screen(tab: Int, dark: Boolean = true, content: @Composable () -> Unit): AutoCloseable {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host = controller.get()
        controller.get().setContent {
            ComfyControllerTheme(darkTheme = dark, manageSystemBars = false) {
                Column(Modifier.fillMaxSize().background(top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.background)
                    .testTag("model-center-capture")) {
                    ModelCenterToolbar({}, {}, {})
                    ModelCenterTabs(tab, {})
                    content()
                }
            }
        }
        compose.waitForIdle()
        return AutoCloseable { host = null; controller.pause().stop().destroy() }
    }

    private fun capture(name: String) {
        output.mkdirs()
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            // Compose captureToImage waits for a real Window redraw callback unavailable in
            // Robolectric. Draw the actual measured View hierarchy into native Skia instead.
            val view = requireNotNull(host).window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        // A network CrossfadeDrawable starts its timer on the first draw. Advance both
        // the Compose and Android clocks, then capture its completed frame, not frame zero.
        ShadowSystemClock.advanceBy(Duration.ofMillis(400))
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle { requireNotNull(host).window.decorView.draw(Canvas(bitmap)) }
        File(output, "$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        println("MODEL_CENTER_RENDER ${File(output, "$name.png").absolutePath} ${bitmap.width}x${bitmap.height}")
    }

    private fun waitForCover() {
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasTestTag("model-cover-fixture-0") and
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "预览已加载"),
                useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun localGridAndCompactAutomaticallyLoadCoverAndHideVerbosePaths() {
        val cover = fixtureCover()
        val vm = ModelCenterViewModel(app)
        val compact = mutableStateOf(false)
        screen(0) {
            LocalModelList(vm, fixtureState(compact.value), coverUrl = { cover })
        }.use {
            waitForCover()
            compose.onNodeWithText("本地模型").assertIsSelected()
            compose.onNodeWithText("D:/fixture/loras", substring = true).assertDoesNotExist()
            val first = compose.onNodeWithTag("model-card-fixture-0").getUnclippedBoundsInRoot()
            val second = compose.onNodeWithTag("model-card-fixture-1").getUnclippedBoundsInRoot()
            assertTrue(first.top < 340.dp)
            assertTrue(second.left > first.left)
            capture("local-grid")
            compose.runOnIdle { compact.value = true }
            waitForCover()
            val compactCard = compose.onNodeWithTag("model-card-fixture-0").getUnclippedBoundsInRoot()
            assertTrue(compactCard.right - compactCard.left > 350.dp)
            capture("local-compact")
        }
    }

    @Test fun onlinePageSharesCompactChromeAndScrollableFilters() {
        val cover = fixtureCover()
        val models = listOf("柔光肖像", "自然色彩", "水彩线条", "电影质感").mapIndexed { index, name ->
            CivitaiModelItem(id = index.toLong(), name = name, creator = "示例作者", downloadCount = 32000,
                versions = listOf(CivitaiVersionItem(baseModel = "Illustrious")), previewUrl = cover)
        }
        var searches = 0
        screen(1) {
            ModelMarketPage(MarketCatalogState(items = models), rememberLazyGridState(),
                listOf("SDXL", "Illustrious", "Anima", "Pony"), Coil.imageLoader(app),
                { it.previewUrl }, { it }, {}, {}, {}, {}, { searches++ }, {}, {}, {})
        }.use {
            compose.onNodeWithText("在线集市").assertIsSelected()
            compose.onNodeWithText("搜索", substring = false).performClick()
            assertEquals(1, searches)
            compose.waitUntil(15_000) {
                // Drain PAUSED Robolectric's main queue so Coil can deliver network results.
                compose.waitForIdle()
                successfulImages.get() >= models.size
            }
            capture("online-market")
        }
    }

    @Test fun taskCardsHideZeroSpeedForCompletedAndKeepPauseAction() {
        val tasks = CivitaiTasksData(queue = listOf(
            CivitaiDownloadTaskItem(download_id = "active", model_name = "柔光人像 · SDXL",
                status = "downloading", progress = 62.4, speed_bytes = 3670016.0),
            CivitaiDownloadTaskItem(download_id = "done", model_name = "Photo Background · 写真背景与二次元合成",
                status = "completed", progress = 100.0, speed_bytes = 0.0, total_size = 126000000.0)))
        var operation = ""
        screen(2) {
            Row(Modifier.padding(start = 16.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModelChip("模型下载", true) {}
                ModelChip("资料补全", false) {}
            }
            MarketDownloadQueuePanel(true, { Result.success(tasks) },
                { action, id -> operation = "$action:$id"; Result.success(Unit) }, true)
        }.use {
            compose.waitUntil(10_000) { compose.onAllNodesWithText("已保存到电脑").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("0.00 MiB/s").assertDoesNotExist()
            capture("download-tasks")
            compose.onNodeWithText("暂停").performClick()
            compose.waitUntil { operation == "pause:active" }
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h640dp-mdpi")
    fun narrowLightLayoutKeepsSearchAndFilterVisible() {
        val cover = fixtureCover()
        val vm = ModelCenterViewModel(app)
        screen(0, dark = false) {
            LocalModelList(vm, fixtureState(), coverUrl = { cover })
        }.use {
            waitForCover()
            compose.onNodeWithTag("local-model-search").assertIsDisplayed()
            compose.onNodeWithText("筛选").assertIsDisplayed()
            val search = compose.onNodeWithTag("local-model-search").getUnclippedBoundsInRoot()
            assertTrue(search.right - search.left >= 180.dp)
            assertTrue(search.bottom - search.top < 60.dp)
            capture("local-narrow-light")
        }
    }
}
