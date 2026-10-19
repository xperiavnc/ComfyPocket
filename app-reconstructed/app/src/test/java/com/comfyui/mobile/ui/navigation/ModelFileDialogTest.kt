package com.comfyui.mobile.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import com.comfyui.mobile.modelcenter.*
import com.comfyui.mobile.ui.screens.modelcenter.ModelFileDialog
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
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h720dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class ModelFileDialogTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val item = LocalModel(id = "item", rootId = "root", category = "LORA",
        rootPath = "D:/models/loras", relativePath = "style/original.safetensors",
        name = "original.safetensors", present = true, size = 12, mtimeNs = 123)
    private var submitted: ModelFileWrite? = null

    private fun screen(block: () -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply { setContent {
                CompositionLocalProvider(LocalSquircleEnabled provides false) {
                    ComfyControllerTheme(darkTheme = true, manageSystemBars = false) {
                        Scaffold {
                            ModelFileDialog(item, listOf(ModelRoot("root", "LORA", item.rootPath)),
                                onDismiss = {}, directories = { root, path ->
                                    Result.success(ModelDirectories(rootId = root, path = path, directories = listOf("子目录")))
                                }, onApply = { request ->
                                    submitted = request
                                    Result.failure(IllegalStateException("fixture conflict; keep draft"))
                                })
                        }
                    }
                }
            } })
            compose.waitForIdle()
            block()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun renameKeepsExtensionAndFailureRetainsEditableDraft() = screen {
        compose.onNodeWithTag("model-file-rename").performClick()
        compose.onNodeWithTag("model-file-name").performTextReplacement("新文件名")
        compose.onNodeWithTag("model-file-confirm").performClick()
        compose.onNodeWithText("fixture conflict; keep draft").assertExists()
        compose.onNodeWithTag("model-file-name").assertTextContains("新文件名")
        assertEquals("新文件名.safetensors", submitted!!.name)
        assertEquals(item.relativePath, submitted!!.expectedPath)
        assertEquals(item.mtimeNs, submitted!!.expectedMtimeNs)
    }

    @Test fun movingSelectsServerFolderNotPhoneFilesystem() = screen {
        compose.onNodeWithTag("model-file-move").performClick()
        compose.onNodeWithTag("model-file-directory").performTextReplacement("作者/角色")
        compose.onNodeWithTag("model-file-confirm").performClick()
        assertEquals("move", submitted!!.action)
        assertEquals("root", submitted!!.rootId)
        assertEquals("作者/角色", submitted!!.directory)
    }

    @Test fun deletionNeedsSeparateConfirmationAndIncludesExactServerName() = screen {
        compose.onNodeWithTag("model-file-delete").performClick()
        assertNull(submitted)
        compose.onNodeWithText("确认删除").performClick()
        assertEquals("delete", submitted!!.action)
        assertEquals(item.name, submitted!!.confirmName)
    }
}
