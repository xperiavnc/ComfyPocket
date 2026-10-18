package com.comfyui.mobile.ui.navigation

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import com.comfyui.mobile.MainActivity
import com.comfyui.mobile.modelcenter.*
import com.comfyui.mobile.ui.MainViewModel
import com.comfyui.mobile.ui.theme.BottomBarStyle
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class ModelCenterNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun offline() {
        ReflectionHelpers.setStaticField(ViewModelProvider.AndroidViewModelFactory::class.java, "_instance", null)
        app.getSharedPreferences("comfy_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("civitai_market", Context.MODE_PRIVATE).edit().putString("mode", "BRIDGE").commit()
    }
    private fun back(activity: MainActivity) {
        compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun localDetailsAndBack(style: String) {
        app.getSharedPreferences("comfy_prefs", Context.MODE_PRIVATE).edit()
            .putString(BottomBarStyle.PREFERENCE_KEY, style).commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            compose.waitForIdle()
            val vm = ViewModelProvider(controller.get())[MainViewModel::class.java]
            val id = vm.currentServerId.value
            val server = vm.servers.value.firstOrNull { it.id == id || it.effectiveId == id }
            val identity = "$id|${server?.baseUrl.orEmpty()}"
            LocalModelCache(File(app.filesDir, "local-model-cache-v1"), identity).save(
                ModelCacheSnapshot("fixture-server", items = listOf(LocalModel(id = "fixture-id",
                    serverId = "fixture-server", category = "LORA", name = "fixture.safetensors",
                    annotation = ModelAnnotation(alias = "离线角色模型", useCases = listOf("character")),
                    present = true)), savedAt = 1000))
            compose.onNodeWithText("本地模型").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("离线角色模型").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onAllNodesWithText("在线集市").onFirst().assertExists()
            compose.onNodeWithTag("pocket-tab-0").assertDoesNotExist()
            compose.onNodeWithText("离线角色模型").performClick()
            compose.onNodeWithTag("local-model-detail").assertExists()
            compose.onNodeWithText("编辑标注").assertIsNotEnabled()
            back(controller.get())
            compose.onNodeWithTag("local-model-list").assertExists()
            back(controller.get())
            compose.onNodeWithText("集市 ↗").assertExists()
            assertFalse(controller.get().isFinishing)
            repeat(5) { compose.onNodeWithTag("pocket-tab-$it").assertExists() }
            compose.onNodeWithTag("pocket-tab-5").assertDoesNotExist()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun materialLocalModelDetailsReturnOneLayerAndNeverBecomeSixthTab() = localDetailsAndBack("material")
    @Test fun classicLocalModelDetailsReturnOneLayerAndNeverBecomeSixthTab() = localDetailsAndBack("classic")

    @Test fun assetEntrypointsRemainDistinctAndFilterCancelKeepsModelCenter() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            compose.waitForIdle()
            compose.onNodeWithText("插件", substring = false).performClick()
            compose.onAllNodesWithText("已装插件").onFirst().assertExists()
            compose.onNodeWithText("在线集市").assertDoesNotExist()
            back(controller.get())
            compose.onNodeWithText("集市 ↗").performClick()
            compose.onNodeWithText("Civitai 市场").assertDoesNotExist()
            compose.onNodeWithText("在线集市").assertExists()
            compose.onNodeWithText("本地模型").performClick()
            compose.onNodeWithText("筛选").performClick()
            compose.onNodeWithText("筛选本地模型").assertExists()
            compose.onNodeWithText("取消").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("local-model-list").assertExists()
            assertFalse(controller.get().isFinishing)
        } finally { controller.pause().stop().destroy() }
    }
}
