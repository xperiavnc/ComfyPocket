package com.comfyui.mobile.ui.screens.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.navigation.compose.rememberNavController
import com.comfyui.mobile.agent.AgentPromptSettings
import com.comfyui.mobile.model.LlmProfile
import com.comfyui.mobile.taglib.LocalTagLibraryState
import com.comfyui.mobile.ui.MainViewModel
import com.comfyui.mobile.ui.screens.AgentSettingsScreen
import com.comfyui.mobile.ui.theme.ComfyControllerTheme
import java.io.File
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
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AgentSettingsUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun capture(activity: ComponentActivity, name: String) {
        compose.waitForIdle()
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnIdle { view.draw(Canvas(bitmap)) }
        val file = File("build/reports/agent-settings/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue(file.length() > 10000)
    }

    @Test fun actualScreenExpandsEditsSavesAndKeepsPromptCancelSeparate() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("comfy_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        val vm = MainViewModel(app)
        val store = ViewModelStore().apply { put("settings", vm) }
        com.comfyui.mobile.ui.awaitAgentStorage(vm)
        vm.addOrUpdateLlmProfile(LlmProfile(id = "ui-test", name = "日常绘图", baseUrl = "https://example.test/v1",
            apiKey = "", model = "example-model"))
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply { setContent {
                CompositionLocalProvider(LocalSquircleEnabled provides false) {
                    ComfyControllerTheme(darkTheme = true, manageSystemBars = false) {
                        Scaffold {
                            AgentSettingsScreen(rememberNavController(), vm)
                        }
                    }
                }
            } })
            compose.waitUntil(5000) { !vm.localTagLibraryState.value.checking }
            compose.onNodeWithText("模型服务").assertExists()
            compose.onNodeWithText("API Base URL").assertDoesNotExist()
            compose.onNodeWithTag("agent-context-tokens").assertDoesNotExist()
            val profileBounds = compose.onNodeWithTag("agent-profile-ui-test").getUnclippedBoundsInRoot()
            assertTrue(profileBounds.bottom - profileBounds.top >= 44.dp)
            assertTrue("Profile chip must not grow to the old 64dp block", profileBounds.bottom - profileBounds.top <= 48.dp)
            capture(controller.get(), "overview-dark")
            compose.onNodeWithText("生成参数").performScrollTo().performClick()
            compose.onNodeWithTag("agent-context-tokens").performScrollTo().performTextReplacement("32768")
            compose.onNodeWithTag("agent-output-tokens").performScrollTo().performTextReplacement("2048")
            compose.onNodeWithText("保存配置").performScrollTo().performClick()
            assertEquals(32768, vm.currentLlmProfile.value!!.generationLimits.contextTokens)
            assertEquals(2048, vm.currentLlmProfile.value!!.generationLimits.outputTokens)
            compose.onNodeWithText("生成参数").performScrollTo().performClick()
            compose.onNodeWithText("导入词库").performScrollTo()
            compose.onNodeWithText("未安装").assertExists()
            compose.onNodeWithTag("agent-local-tags-enabled").assertDoesNotExist()
            capture(controller.get(), "optional-pack-dark")
            val before = vm.agentSystemPrompt.value
            compose.onNodeWithTag("agent-prompt-system").performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithText("编辑系统人设").assertExists()
            compose.onNodeWithText("取消").performClick()
            compose.waitForIdle()
            assertEquals(before, vm.agentSystemPrompt.value)
            compose.onNodeWithText("编辑系统人设").assertDoesNotExist()
        } finally {
            store.clear()
            vm.repository.disconnect()
            controller.pause().stop().destroy()
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun narrowScreenShowsImportWithoutIrrelevantSearchOrSaveControls() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var imports = 0
        var searches = 0
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply { setContent {
                CompositionLocalProvider(LocalSquircleEnabled provides false) {
                    ComfyControllerTheme(darkTheme = false, manageSystemBars = false) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            AgentLocalTagSettings(AgentPromptSettings(), LocalTagLibraryState(checking = false),
                                search = { _, _ -> searches++; emptyList() }, onImport = { imports++ },
                                onRemove = {}, onSave = {})
                        }
                    }
                }
            } })
            compose.onNodeWithText("导入词库").performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithText("绘图前查词").assertDoesNotExist()
            compose.onNodeWithText("中文 / 英文 / 别名").assertDoesNotExist()
            compose.onNodeWithText("保存词库设置").assertDoesNotExist()
            assertEquals(1, imports)
            assertEquals(0, searches)
            capture(controller.get(), "optional-pack-320dp-light")
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    @Config(qualifiers = "w280dp-h720dp-mdpi")
    fun narrowGenerationButtonsHaveSeparatedRowsAndCompactReadableLabels() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val value = mutableStateOf(AgentLimitsDraft("1000000"))
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply { setContent {
                CompositionLocalProvider(LocalSquircleEnabled provides false) {
                    ComfyControllerTheme(darkTheme = true, manageSystemBars = false) {
                        Column(Modifier.fillMaxSize()
                            .background(MiuixTheme.colorScheme.background)
                            .verticalScroll(rememberScrollState()).padding(16.dp)) {
                            AgentGenerationLimitsControls(value.value) { value.value = it }
                        }
                    }
                }
            } })
            val a = compose.onNodeWithText("16K").getUnclippedBoundsInRoot()
            val b = compose.onNodeWithText("默认").getUnclippedBoundsInRoot()
            assertTrue(a.bottom - a.top in 44.dp..48.dp)
            assertTrue(b.top >= a.bottom + 4.dp)
            compose.onNodeWithText("max_completion_tokens").performScrollTo().assertIsDisplayed()
            val parameter = compose.onNodeWithText("max_completion_tokens").getUnclippedBoundsInRoot()
            assertTrue(parameter.right - parameter.left < 260.dp)
            assertTrue(parameter.bottom - parameter.top <= 48.dp)
            compose.onNodeWithText("max_tokens").performClick().assertIsSelected()
            compose.onNodeWithText("32K").performClick().assertIsSelected()
            capture(controller.get(), "generation-buttons-280dp-dark")
        } finally { controller.pause().stop().destroy() }
    }
}
