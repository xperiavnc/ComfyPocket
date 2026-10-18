package com.comfyui.mobile.ui.navigation

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.ViewModelStore
import com.comfyui.mobile.ComfyApplication
import com.comfyui.mobile.modelcenter.*
import com.comfyui.mobile.ui.screens.modelcenter.LocalModelsPage
import com.comfyui.mobile.ui.theme.ComfyControllerTheme
import com.google.gson.Gson
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-mdpi", application = ComfyApplication::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class ModelCenterFilterUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun realCategoryChipHidesPreviousCardsWhileLoadingAndThenShowsLoras() {
        val release = CountDownLatch(1)
        val requested = CountDownLatch(1)
        MockWebServer().use { server ->
            val gson = Gson()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = requireNotNull(request.requestUrl)
                    val data: Any = if (url.encodedPath.endsWith("/capabilities"))
                        ModelCapabilities(1, "ui-filter", listOf("model-index"))
                    else {
                        val lora = url.queryParameter("category") == "LORA"
                        if (lora) {
                            requested.countDown()
                            release.await(10, TimeUnit.SECONDS)
                        }
                        val category = if (lora) "LORA" else "CHECKPOINT"
                        ModelPage(serverId = "ui-filter", total = if (lora) 48 else 2,
                            hasMore = lora, scanComplete = true, items = (1..2).map {
                                LocalModel(id = "$category-$it", category = category,
                                    name = "$category-$it.safetensors", present = true,
                                    preview = ModelPreviewInfo("none"))
                            })
                    }
                    return MockResponse().setHeader("Content-Type", "application/json")
                        .setBody(gson.toJson(ModelEnvelope(data = data)))
                }
            }
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
            val vm = ModelCenterViewModel(RuntimeEnvironment.getApplication())
            val store = ViewModelStore().apply { put("fixture", vm) }
            controller.get().setContent {
                ComfyControllerTheme(darkTheme = true, manageSystemBars = false) {
                    val state by vm.state.collectAsState()
                    LocalModelsPage(vm, state)
                }
            }
            try {
                compose.runOnIdle {
                    vm.context("ui-filter-${server.port}", "测试电脑", server.url("/").toString(), true)
                }
                compose.waitUntil(10_000) {
                    compose.waitForIdle()
                    !vm.state.value.loading && !vm.state.value.fromCache
                }
                compose.onNodeWithTag("model-card-CHECKPOINT-1").assertExists()
                compose.onNodeWithText("LoRA").performClick()
                compose.onNodeWithTag("model-card-CHECKPOINT-1").assertDoesNotExist()
                compose.onNodeWithText("正在读取LoRA…").assertExists()
                compose.waitUntil(10_000) { requested.count == 0L }
                release.countDown()
                compose.waitUntil(10_000) {
                    compose.waitForIdle()
                    !vm.state.value.loading
                }
                compose.onNodeWithTag("model-card-LORA-1").assertExists()
                compose.onNodeWithTag("model-card-CHECKPOINT-1").assertDoesNotExist()
                assertEquals("LORA", vm.state.value.query.category)
                assertEquals(48, vm.state.value.page.total)
                println("MODEL_FILTER_UI LoRA chip -> pending without CHECKPOINT rows -> total=48 LORA cards")
            } finally {
                release.countDown()
                store.clear()
                controller.pause().stop().destroy()
            }
        }
    }
}
