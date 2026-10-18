package com.comfyui.mobile.ui.navigation

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.rememberNavController
import com.comfyui.mobile.ComfyApplication
import com.comfyui.mobile.data.AppModule
import com.comfyui.mobile.market.MarketCatalogState
import com.comfyui.mobile.market.MarketViewModel
import com.comfyui.mobile.model.CivitaiDownloadTaskItem
import com.comfyui.mobile.model.CivitaiModelItem
import com.comfyui.mobile.model.CivitaiTasksData
import com.comfyui.mobile.modelcenter.*
import com.comfyui.mobile.ui.MainViewModel
import com.comfyui.mobile.ui.screens.ModelHubScreen
import com.comfyui.mobile.ui.theme.ComfyControllerTheme
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/**
 * Exercise the real ModelHubScreen, including its Scaffold and parent nested-scroll
 * connection. performScrollTo/ScrollBy semantics would bypass the gesture regression.
 * View-model fixture injection is test-only; no real server or generation is used.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-mdpi", application = ComfyApplication::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class ModelCenterScrollTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun offlineFixture() {
        ReflectionHelpers.setStaticField(ViewModelProvider.AndroidViewModelFactory::class.java, "_instance", null)
        app.getSharedPreferences("comfy_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("civitai_market", Context.MODE_PRIVATE).edit().putString("mode", "BRIDGE").commit()
    }

    private data class Fixture(val models: ModelCenterViewModel, val market: MarketViewModel)

    private fun screen(block: (Fixture) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val provider = ViewModelProvider(activity)
        val main = provider[MainViewModel::class.java]
        activity.setContent {
            ComfyControllerTheme(darkTheme = true, manageSystemBars = false) {
                ModelHubScreen(rememberNavController(), main, initialPage = 0)
            }
        }
        try {
            val models = provider[ModelCenterViewModel::class.java]
            val market = provider[MarketViewModel::class.java]
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                models.state.value.restored
            }
            block(Fixture(models, market))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun localFixture(vm: ModelCenterViewModel, compact: Boolean = false) {
        val flow = ReflectionHelpers.getField<MutableStateFlow<ModelCenterState>>(vm, "mutable")
        compose.runOnIdle {
            flow.value = vm.state.value.copy(restored = true, loading = false, connected = false,
                fromCache = true, compact = compact,
                page = ModelPage(total = 40, items = (0 until 40).map {
                    LocalModel(id = "scroll-$it", name = "滚动模型 $it.safetensors", category = "LORA",
                        present = true, preview = ModelPreviewInfo("none"))
                }),
                tasks = ModelTasks(enrichment = (0 until 40).map {
                    ModelEnrichmentTask(id = "enrichment-$it", locationId = "scroll-$it",
                        status = "completed", message = "资料已保存 $it")
                }))
        }
    }

    private val vertical = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
    private fun scrollValue(): Float =
        compose.onNode(vertical).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun swipeBothWays(label: String) {
        val start = scrollValue()
        compose.onNode(vertical).performTouchInput {
            swipeUp(startY = height * .85f, endY = height * .2f, durationMillis = 600)
        }
        compose.waitForIdle()
        val afterUp = scrollValue()
        println("MODEL_CENTER_TOUCH $label before=$start afterUp=$afterUp")
        assertTrue("$label: an upward touch swipe must reveal later rows", afterUp > start)
        compose.onNode(vertical).performTouchInput {
            swipeDown(startY = height * .2f, endY = height * .85f, durationMillis = 600)
        }
        compose.waitForIdle()
        val afterDown = scrollValue()
        println("MODEL_CENTER_TOUCH $label afterDown=$afterDown")
        assertTrue("$label: a downward touch swipe must return toward earlier rows", afterDown < afterUp)
    }

    @Test fun localGridScrollsByTouchInsideRealModelHub() = screen { fixture ->
        localFixture(fixture.models)
        swipeBothWays("local-grid")
        compose.onNodeWithText("本地模型").assertIsSelected()
    }

    @Test fun localCompactListScrollsByTouchInsideRealModelHub() = screen { fixture ->
        localFixture(fixture.models, compact = true)
        swipeBothWays("local-compact")
    }

    @Test fun onlineMarketScrollsByTouchInsideRealModelHub() = screen { fixture ->
        val flow = ReflectionHelpers.getField<MutableStateFlow<MarketCatalogState>>(fixture.market.catalog, "mutableState")
        compose.runOnIdle {
            ReflectionHelpers.setField(fixture.market.catalog, "loaded", true)
            flow.value = MarketCatalogState(items = (0 until 40).map {
                CivitaiModelItem(id = it.toLong(), name = "在线模型 $it", creator = "测试作者")
            }, loadedPage = 1)
        }
        compose.onNodeWithText("在线集市").performClick()
        swipeBothWays("online-market")
        compose.onNodeWithText("在线集市").assertIsSelected()
        compose.onNodeWithText("本地模型").performClick()
        compose.onNodeWithText("本地模型").assertIsSelected()
    }

    @Test fun downloadQueueScrollsByTouchInsideRealModelHub() {
        MockWebServer().use { server ->
            val payload = Gson().toJson(mapOf("code" to 0, "data" to CivitaiTasksData(
                queue = (0 until 40).map {
                    CivitaiDownloadTaskItem(download_id = "download-$it", model_name = "下载模型 $it",
                        status = "completed", progress = 100.0, total_size = 120000000.0)
                })))
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) =
                    MockResponse().setHeader("Content-Type", "application/json").setBody(payload)
            }
            screen { fixture ->
                localFixture(fixture.models)
                compose.runOnIdle {
                    AppModule.provideRepository().setCloudActive(false)
                    AppModule.provideRepository().setBaseUrlOnly(server.url("/").toString())
                    val flow = ReflectionHelpers.getField<MutableStateFlow<ModelCenterState>>(fixture.models, "mutable")
                    flow.value = fixture.models.state.value.copy(connected = true)
                }
                compose.onNodeWithText("下载任务").performClick()
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText("下载模型 0").fetchSemanticsNodes().isNotEmpty()
                }
                swipeBothWays("download-queue")
                compose.onNodeWithText("下载任务").assertIsSelected()
                assertTrue(server.requestCount > 0)
            }
        }
    }

    @Test fun enrichmentTasksScrollByTouchInsideRealModelHub() = screen { fixture ->
        localFixture(fixture.models)
        compose.onNodeWithText("下载任务").performClick()
        compose.onNodeWithText("资料补全", substring = true).performClick()
        swipeBothWays("enrichment-tasks")
    }

    @Test fun localDetailsScrollByTouchInsideRealModelHub() = screen { fixture ->
        localFixture(fixture.models)
        val flow = ReflectionHelpers.getField<MutableStateFlow<ModelCenterState>>(fixture.models, "mutable")
        compose.runOnIdle {
            val item = fixture.models.state.value.page.items.first().copy(
                annotation = ModelAnnotation(notes = (0 until 60).joinToString("\n") { "详情内容 $it" }))
            flow.value = fixture.models.state.value.copy(detail = item)
        }
        compose.onNodeWithTag("local-model-detail").assertExists()
        swipeBothWays("local-details")
        compose.onNodeWithText("返回列表").performClick()
        compose.onNodeWithTag("local-model-list").assertExists()
    }
}
