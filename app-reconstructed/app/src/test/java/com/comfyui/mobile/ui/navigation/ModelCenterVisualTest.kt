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
import com.comfyui.mobile.data.AppModule
import com.comfyui.mobile.market.CivitaiRepository
import com.comfyui.mobile.market.CivitaiSettings
import com.comfyui.mobile.market.MarketCatalogState
import com.comfyui.mobile.market.MarketCatalogSection
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
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.OkHttpClient
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

    @Test fun dualChannelMarketShowsDistinctScrollableSectionsWithoutChangingTheNavigationShell() {
        val cover = fixtureCover()
        val ordinary = CivitaiModelItem(id = 1, name = "普通内容示例", creator = "示例作者",
            sfwOnly = true, minor = true, previewUrl = cover,
            versions = listOf(CivitaiVersionItem(baseModel = "Anima")))
        val extra = CivitaiModelItem(id = 2, name = "浏览范围示例", creator = "示例作者", previewUrl = cover,
            versions = listOf(CivitaiVersionItem(baseModel = "Illustrious")))
        val state = MarketCatalogState(matureEnabled = true, items = listOf(ordinary, extra),
            sections = listOf(
                MarketCatalogSection(CivitaiContentChannel.STANDARD, MarketCatalogState(items = listOf(ordinary))),
                MarketCatalogSection(CivitaiContentChannel.MATURE, MarketCatalogState(items = listOf(extra)))))
        screen(1) {
            ModelMarketPage(state, rememberLazyGridState(), listOf("Anima", "Illustrious"),
                Coil.imageLoader(app), { it.previewUrl }, { it }, {}, {}, {}, {}, {}, {}, {}, {})
        }.use {
            compose.onNodeWithTag("market-grid").performScrollToNode(hasTestTag("market-section-STANDARD"))
            compose.waitUntil(15_000) {
                compose.waitForIdle()
                successfulImages.get() >= 1
            }
            capture("online-market-dual-ordinary")
            compose.onNodeWithTag("market-grid").performScrollToNode(hasTestTag("market-section-MATURE"))
            compose.onNodeWithText("成人浏览范围", substring = false).assertIsDisplayed()
            capture("online-market-dual-mature")
        }
    }

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
        capabilities = ModelCapabilities(1, "fixture", listOf("model-index", "model-file-management"),
            roots = listOf(ModelRoot("root", "LORA", "D:/fixture/loras", "ready"))),
        page = ModelPage(total = 4, scanComplete = true, lastSync = 1792309200000,
            stats = ModelStats(locations = 77, bytes = 239520000000L, hashed = 77, uniqueContents = 73),
            items = listOf("柔光人像", "插画线条", "日常服装", "自然光影").mapIndexed { index, title ->
                LocalModel(id = "fixture-$index", serverId = "fixture", rootId = "root", name = "$title.safetensors", category = "LORA",
                    rootPath = "D:/fixture/loras", relativePath = "styles/$title.safetensors", size = 124000000,
                    present = true, mtimeNs = 1792309200000000000, annotation = ModelAnnotation(alias = title, baseModel = "Illustrious"),
                    preview = ModelPreviewInfo("local", "fixture-cover"))
            }))

    private fun screen(tab: Int, dark: Boolean = true, detail: Boolean = false, content: @Composable () -> Unit): AutoCloseable {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host = controller.get()
        controller.get().setContent {
            ComfyControllerTheme(darkTheme = dark, manageSystemBars = false) {
                Column(Modifier.fillMaxSize().background(top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.background)
                    .testTag("model-center-capture")) {
                    ModelCenterToolbar({}, {}, {}, title = if (detail) "模型详情" else "模型中心")
                    if (!detail) ModelCenterTabs(tab, {})
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

    private fun detailFixture() = fixtureState().page.items.first().copy(
        sha256 = "a".repeat(64), provider = ModelProvider(
            name = "柔光人像", author = "示例作者", version = "v2.1", baseModel = "Illustrious",
            modelDescription = "<p>为柔和的自然光和细腻色彩训练的人像模型。</p><p>适合日常场景，建议先从较低权重开始调整。</p>",
            modelDescriptionFetched = true, description = "<p>这一版优化了光影层次与背景表现。</p>",
            trainedWords = listOf("soft_light", "natural_colors"), fetchedBy = "phone"))

    @Test fun redesignedDetailPlacesActualIntroductionBeforeVersionAndCollapsedFileInformation() {
        val cover = fixtureCover()
        val vm = ModelCenterViewModel(app)
        val item = detailFixture()
        screen(0, detail = true) {
            LocalModelDetails(vm, fixtureState().copy(detail = item), item, coverUrl = cover)
        }.use {
            waitForCover()
            compose.onNodeWithText("模型简介").assertIsDisplayed()
            compose.onNodeWithText("为柔和的自然光", substring = true).assertIsDisplayed()
            compose.onNodeWithText("D:/fixture/loras", substring = true).assertDoesNotExist()
            compose.onNodeWithText("SHA-256：", substring = true).assertDoesNotExist()
            val preview = compose.onNodeWithTag("model-cover-fixture-0", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertTrue(preview.bottom - preview.top <= 164.dp)
            capture("local-model-detail-direct-dark")
            compose.onNodeWithText("文件信息").performScrollTo()
            compose.onNodeWithText("展开", substring = false).performClick()
            compose.onNodeWithText("D:/fixture/loras", substring = true).assertExists()
            compose.onNodeWithText("SHA-256：", substring = true).assertExists()
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h640dp-mdpi")
    fun narrowLightDetailKeepsIntroductionAndEditActionReadable() {
        val cover = fixtureCover()
        val vm = ModelCenterViewModel(app)
        val item = detailFixture()
        screen(0, dark = false, detail = true) {
            LocalModelDetails(vm, fixtureState().copy(detail = item), item, coverUrl = cover)
        }.use {
            waitForCover()
            compose.onNodeWithText("编辑标注").assertIsDisplayed()
            compose.onNodeWithText("文件管理").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithText("模型简介").assertIsDisplayed()
            capture("local-model-detail-direct-narrow-light")
        }
    }

    @Test fun computerOfflineCoverStillUsesPhoneHttpProxyInsteadOfGlobalCoilOrBridge() {
        fixtureCover() // Synthetic bytes only; the regular image server must receive no requests.
        MockWebServer().use { proxy ->
            val body = File(output, "fixture-cover.png").readBytes()
            proxy.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(body)))
            val repo = CivitaiRepository(app, CivitaiSettings(proxyEnabled = true,
                proxyAddress = proxy.url("/").toString(), apiKey = java.util.UUID.randomUUID().toString()),
                AppModule.provideRepository())
            val client = ReflectionHelpers.getField<OkHttpClient>(repo, "client")
            ReflectionHelpers.setField(repo, "client", client.newBuilder().addInterceptor { chain ->
                val url = chain.request().url
                assertEquals("image.civitai.com", url.host)
                chain.proceed(chain.request().newBuilder().url(url.newBuilder().scheme("http").port(80).build()).build())
            }.build())
            val cover = "https://image.civitai.com/phone-fixture/preview.png"
            val model = fixtureState().page.items.first().copy(provider = ModelProvider(
                images = listOf(ModelPreview(cover))), preview = ModelPreviewInfo("provider"))
            val state = fixtureState().copy(fromCache = true, connected = false,
                page = ModelPage(items = listOf(model), total = 1))
            val vm = ModelCenterViewModel(app)
            try {
                screen(0) {
                    CompositionLocalProvider(LocalPhoneModelImages provides
                        PhoneModelImages(repo.imageLoader, repo.sourceScope, repo::imageRequest)) {
                        LocalModelList(vm, state, coverUrl = { cover })
                    }
                }.use {
                    waitForCover()
                    assertEquals(1, proxy.requestCount)
                    assertEquals(0, imageServer!!.requestCount)
                    val request = proxy.takeRequest()
                    assertTrue(request.requestLine.contains("http://image.civitai.com/phone-fixture/preview.png"))
                    assertNull(request.getHeader("Authorization"))
                    capture("local-phone-proxy-cover")
                    println("MODEL_COVER_PROXY computerOffline=true phoneProxyRequests=1 computerRequests=0 cover=loaded")
                }
            } finally { repo.close() }
        }
    }

    @Test fun failedCoverRetriesImmediatelyWhenPhoneProxySettingsChange() {
        fixtureCover()
        MockWebServer().use { broken -> MockWebServer().use { working ->
            broken.enqueue(MockResponse().setResponseCode(503))
            working.enqueue(MockResponse().setHeader("Content-Type", "image/png")
                .setBody(Buffer().write(File(output, "fixture-cover.png").readBytes())))
            val config = CivitaiSettings(proxyEnabled = true, proxyAddress = broken.url("/").toString(),
                apiKey = java.util.UUID.randomUUID().toString())
            val repo = CivitaiRepository(app, config, AppModule.provideRepository())
            fun fixtureHttp() {
                val client = ReflectionHelpers.getField<OkHttpClient>(repo, "client")
                ReflectionHelpers.setField(repo, "client", client.newBuilder().addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().url(
                        chain.request().url.newBuilder().scheme("http").port(80).build()).build())
                }.build())
            }
            fixtureHttp()
            val accountScope = repo.sourceScope
            val transport = mutableStateOf(PhoneModelImages(repo.imageLoader, repo.transportScope, repo::imageRequest))
            val cover = "https://image.civitai.com/proxy-retry-fixture/preview.png"
            val model = fixtureState().page.items.first().copy(provider = ModelProvider(images = listOf(ModelPreview(cover))))
            try {
                screen(0) {
                    CompositionLocalProvider(LocalPhoneModelImages provides transport.value) {
                        LocalModelCover(model, cover, offline = true, modifier = Modifier.fillMaxWidth().height(180.dp))
                    }
                }.use {
                    compose.waitUntil(10_000) {
                        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "预览加载失败"),
                            useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                    }
                    assertEquals(1, broken.requestCount)
                    compose.runOnIdle {
                        repo.updateSettings(config.copy(proxyAddress = working.url("/").toString()))
                        fixtureHttp()
                        assertEquals(accountScope, repo.sourceScope)
                        transport.value = PhoneModelImages(repo.imageLoader, repo.transportScope, repo::imageRequest)
                    }
                    waitForCover()
                    assertEquals(1, working.requestCount)
                }
            } finally { repo.close() }
        } }
    }

    @Test fun unreadableLocalSidecarFallsBackToPhonePreviewWithoutBlockingDescription() {
        val local = fixtureCover()
        imageServer!!.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(404)
        }
        MockWebServer().use { proxy ->
            proxy.enqueue(MockResponse().setHeader("Content-Type", "image/png")
                .setBody(Buffer().write(File(output, "fixture-cover.png").readBytes())))
            val repo = CivitaiRepository(app, CivitaiSettings(proxyEnabled = true,
                proxyAddress = proxy.url("/").toString(), apiKey = java.util.UUID.randomUUID().toString()),
                AppModule.provideRepository())
            val client = ReflectionHelpers.getField<OkHttpClient>(repo, "client")
            ReflectionHelpers.setField(repo, "client", client.newBuilder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(
                    chain.request().url.newBuilder().scheme("http").port(80).build()).build())
            }.build())
            val remote = "https://image.civitai.com/fallback-fixture/preview.png"
            val model = detailFixture().copy(provider = detailFixture().provider!!.copy(images = listOf(ModelPreview(remote))))
            val vm = ModelCenterViewModel(app)
            try {
                screen(0, detail = true) {
                    CompositionLocalProvider(LocalPhoneModelImages provides
                        PhoneModelImages(repo.imageLoader, repo.transportScope, repo::imageRequest)) {
                        LocalModelDetails(vm, fixtureState().copy(detail = model), model, coverUrl = local)
                    }
                }.use {
                    waitForCover()
                    compose.onNodeWithText("为柔和的自然光", substring = true).assertIsDisplayed()
                    assertEquals(1, imageServer!!.requestCount)
                    assertEquals(1, proxy.requestCount)
                }
            } finally { repo.close() }
        }
    }
}
