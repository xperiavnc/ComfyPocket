package com.comfyui.mobile.ui.navigation

import android.content.Context
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.navigation.compose.rememberNavController
import com.comfyui.mobile.R
import com.comfyui.mobile.Screen
import com.comfyui.mobile.model.MediaOutput
import com.comfyui.mobile.model.MediaType
import com.comfyui.mobile.ui.GalleryCategory
import com.comfyui.mobile.ui.GalleryUi
import com.comfyui.mobile.ui.designsystem.PocketMaterialTheme
import com.comfyui.mobile.ui.screens.gallery.GalleryHomeScreen
import com.comfyui.mobile.ui.theme.ComfyControllerTheme
import kotlinx.coroutines.flow.MutableStateFlow
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
import top.yukonga.miuix.kmp.basic.Scaffold
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class PocketGalleryNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val media = (0..59).map { MediaOutput("$it.png", "", "output", MediaType.IMAGE) }
    private val source = MutableStateFlow(GalleryUi.GALLERY_LOCAL)
    private val assignments = MutableStateFlow(media.take(40).associate { it.filename to "favourite" })
    private val values = mapOf(
        "getSessionMediaList" to MutableStateFlow(media),
        "getLocalMediaList" to MutableStateFlow(media),
        "getBaseUrl" to MutableStateFlow(""),
        "getGallerySource" to source,
        "getGalleryCategories" to MutableStateFlow(listOf(GalleryCategory("favourite", "收藏"))),
        "getGalleryCategoryAssignments" to assignments
    )
    private fun fixture(): GalleryUi {
        val app = RuntimeEnvironment.getApplication()
        return Proxy.newProxyInstance(GalleryUi::class.java.classLoader, arrayOf(GalleryUi::class.java)) { proxy, method, args ->
            when (method.name) {
                in values -> values.getValue(method.name)
                "mediaKey" -> (args!![0] as MediaOutput).filename
                "previewUrl" -> "android.resource://${app.packageName}/${R.drawable.material_sample_forest}"
                "refreshLocalMedia", "checkLatestHistory" -> Unit
                "setGallerySource" -> { source.value = args!![0] as String; Unit }
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "Gallery navigation fixture"
                else -> error("Unexpected gallery action: ${method.name}")
            }
        } as GalleryUi
    }
    private fun host(activity: ComponentActivity) {
        RuntimeEnvironment.getApplication().getSharedPreferences("comfy_prefs", Context.MODE_PRIVATE)
            .edit().clear().putString("gallery_home_source", "LOCAL").putString("gallery_source", "local").commit()
        val legacy = fixture()
        activity.setContentView(ComposeView(activity).apply {
            id = android.R.id.primary
            setContent {
                val nav = rememberNavController()
                PocketMaterialTheme(false) {
                    PocketNavigationShell(nav) { entry ->
                        if (entry.destination.route == Screen.Gallery.route) {
                            ComfyControllerTheme(false, manageSystemBars = false) {
                                Scaffold { _ -> GalleryHomeScreen(nav, legacy) }
                            }
                        } else Text("其他主入口")
                    }
                }
            }
        })
        compose.waitForIdle()
        compose.onNodeWithTag("pocket-tab-4").performClick()
        compose.waitForIdle()
    }
    private fun gesture(activity: ComponentActivity, cancel: Boolean) {
        compose.runOnIdle {
            activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 100f, 0f, BackEventCompat.EDGE_LEFT))
            activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(100f, 100f, 0.5f, BackEventCompat.EDGE_LEFT))
            if (cancel) activity.onBackPressedDispatcher.dispatchOnBackCancelled() else activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
    }
    @Test fun localFilterAndScrollSurviveTabSwitchAndSourceSwitch() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            host(controller.get())
            compose.onNodeWithText("收藏 40").performClick()
            compose.onNodeWithTag("legacy-gallery-grid").performScrollToIndex(16)
            compose.onNodeWithTag("legacy-media-16.png").assertIsDisplayed()
            compose.onNodeWithTag("pocket-tab-2").performClick()
            compose.onNodeWithTag("pocket-tab-4").performClick()
            compose.onNodeWithTag("legacy-media-16.png").assertIsDisplayed()
            compose.onNodeWithText("服务器", substring = false).performClick()
            compose.onNodeWithTag("legacy-gallery-grid").performScrollToIndex(8)
            compose.onNodeWithText("本地", substring = false).performClick()
            compose.onNodeWithTag("legacy-media-16.png").assertIsDisplayed()
            compose.onNodeWithText("服务器", substring = false).performClick()
            compose.onNodeWithTag("legacy-media-8.png").assertIsDisplayed()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun selectedMediaReturnAndCancelledGestureDoNotLeaveGallery() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            host(controller.get())
            compose.onNodeWithText("收藏 40").performClick()
            compose.onNodeWithTag("legacy-media-0.png").performTouchInput { longClick() }
            compose.onNodeWithContentDescription("退出多选").assertExists()
            gesture(controller.get(), true)
            compose.onNodeWithContentDescription("退出多选").assertExists()
            gesture(controller.get(), false)
            compose.onNodeWithContentDescription("退出多选").assertDoesNotExist()
            compose.onNodeWithTag("pocket-tab-4").assertIsSelected()
            assertFalse(controller.get().isFinishing)
            compose.onNodeWithTag("legacy-media-0.png").performTouchInput { longClick() }
            compose.onNodeWithContentDescription("退出多选").performClick()
            compose.onNodeWithContentDescription("退出多选").assertDoesNotExist()
            compose.onNodeWithTag("pocket-tab-4").assertIsSelected()
            gesture(controller.get(), false)
            compose.onNodeWithTag("pocket-tab-0").assertIsSelected()
        } finally { controller.pause().stop().destroy() }
    }
}
