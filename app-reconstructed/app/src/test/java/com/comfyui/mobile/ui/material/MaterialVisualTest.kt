package com.comfyui.mobile.ui.material

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import coil.ImageLoader
import com.comfyui.mobile.danbooru.*
import com.comfyui.mobile.ui.designsystem.PocketMaterialTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MaterialVisualTest {
    @get:Rule val compose = createEmptyComposeRule()
    private class Service(context: android.content.Context) : DanbooruService, DanbooruDataSource {
        val fixtures = MaterialFixtures(context)
        override var settings = DanbooruSettings()
        override val repository get() = this
        override suspend fun posts(query: DanbooruQuery, page: String?, refresh: Boolean) = if (page == null) fixtures.pictures.take(12) else emptyList()
        override suspend fun post(id: Long) = fixtures.pictures.first { it.id == id }
        override suspend fun verify(settings: DanbooruSettings): DanbooruIdentity? = null
        override suspend fun test(settings: DanbooruSettings): DanbooruIdentity? = null
        override suspend fun save(settings: DanbooruSettings) { this.settings = settings }
        override suspend fun clearCache() = Unit
    }
    private fun save(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        val file = File("build/reports/material-ui/$name.png").apply { parentFile?.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 10000)
    }
    @Test fun captureActualMaterialGalleryRankingTagsAndSettingsInBothThemes() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val service = Service(activity)
        val vm = DanbooruViewModel(SavedStateHandle(), service)
        val editor = MaterialConnectionEditor()
        val loader = ImageLoader.Builder(activity).crossfade(false).build()
        val view = ComposeView(activity)
        try {
            activity.setContentView(view.apply { setContent {
                MaterialSampleScreen(vm, editor, loader, false, {}, {}, {}, {}, {}, { _, _ -> "" })
            } })
            // Vector fixtures may be successful without creating bitmap memory-cache entries.
            compose.waitUntil(15000) { compose.onAllNodes(hasStateDescription("图片已加载")).fetchSemanticsNodes().size >= 2 }
            compose.waitForIdle()
            save(view, "gallery-light")
            compose.onNodeWithContentDescription("样板选项").performClick()
            compose.onNodeWithText("主题与外观").performClick()
            compose.onNodeWithText("深色").performClick()
            compose.onNodeWithContentDescription("完成外观设置").performClick()
            compose.waitForIdle()
            save(view, "gallery-dark")
            compose.onNodeWithText("排行榜").performClick()
            compose.onNodeWithText("周榜").performClick()
            compose.waitForIdle()
            save(view, "ranking-dark")
            compose.onNodeWithTag("material-image-100").performClick()
            compose.waitForIdle()
            save(view, "detail-dark")
            compose.onNodeWithText("标签与提示词").performClick()
            compose.waitForIdle()
            val overlay = WindowInspector.getGlobalWindowViews().last { it.visibility == View.VISIBLE && it.width > 0 }
            save(overlay, "tags-dark")
            compose.onNodeWithText("完成标签浏览").performClick()
            compose.onNodeWithContentDescription("Danbooru 设置").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("material-save").assertIsDisplayed()
            save(view, "settings-dark")
        } finally { controller.pause().stop().destroy(); loader.shutdown() }
    }
    @Test
    @Config(qualifiers = "w360dp-h420dp-xhdpi")
    fun shortFormWithLargeFontKeepsSaveVisibleAndFieldsScrollable() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val editor = MaterialConnectionEditor().apply { begin(DanbooruSettings(proxyEnabled = true, proxyAddress = "http://127.0.0.1:2080")) }
        val view = ComposeView(activity)
        try {
            activity.setContentView(view.apply { setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    PocketMaterialTheme(true) { MaterialConnectionScreen(editor, false, {}, { Result.success(Unit) }, { "" }, { "" }, {}) }
                }
            } })
            compose.waitForIdle()
            compose.onNodeWithTag("material-save").assertIsDisplayed()
            compose.onNodeWithText("代理地址").performScrollTo()
            compose.onNodeWithTag("material-save").assertIsDisplayed()
            save(view, "settings-short-font150")
        } finally { controller.pause().stop().destroy() }
    }
    @Test
    @Config(qualifiers = "w360dp-h640dp-xhdpi")
    fun longChineseAndEnglishTagsWrapAtFont130WithoutChangingCopiedValues() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val longEnglish = "a_very_long_landscape_tag_with_unbroken_words_and_parentheses_(original_work)"
        val longChinese = "用于检查长中文换行与阅读顺序的界面样例标签"
        val post = DanbooruPost(1, mapOf(TagGroup.GENERAL to listOf(longEnglish, longChinese)))
        var copied = CopiedTags("", 0)
        val view = ComposeView(activity)
        try {
            activity.setContentView(view.apply { setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                    PocketMaterialTheme(false) {
                        androidx.compose.material3.Surface {
                            MaterialTagPanel(post, TagCopyOptions(), emptySet(), {}, {}, {}) { copied = it }
                        }
                    }
                }
            } })
            compose.waitForIdle()
            compose.onNodeWithText(longEnglish).assertIsDisplayed()
            compose.onNodeWithText(longChinese).assertIsDisplayed()
            compose.onNodeWithText(longEnglish).performClick()
            assertEquals(longEnglish.replace('_', ' '), copied.text)
            save(view, "tags-long-font130")
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun captureNativeMidTransitionFramesAndKeepTheTabBarStationary() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val service = Service(activity)
        val vm = DanbooruViewModel(SavedStateHandle(), service)
        val loader = ImageLoader.Builder(activity).crossfade(false).build()
        val view = ComposeView(activity)
        try {
            activity.setContentView(view.apply { setContent {
                MaterialSampleScreen(vm, remember { MaterialConnectionEditor() }, loader, false, {}, {}, {}, {}, {}, { _, _ -> "" })
            } })
            compose.waitUntil(15000) { compose.onAllNodes(hasStateDescription("图片已加载")).fetchSemanticsNodes().size >= 2 }
            compose.waitForIdle()
            val tabBounds = compose.onNodeWithTag("material-tab-4").fetchSemanticsNode().boundsInRoot
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("material-tab-0").performClick()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(80)
            compose.onNodeWithTag("material-motion-host").assert(hasStateDescription("页面切换中"))
            assertEquals(tabBounds, compose.onNodeWithTag("material-tab-4").fetchSemanticsNode().boundsInRoot)
            save(view, "motion-tab-mid")
            compose.mainClock.advanceTimeBy(400)
            compose.onNodeWithTag("material-tab-4").performClick()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(400)
            compose.onNodeWithTag("material-image-100").performClick()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(120)
            compose.onNodeWithTag("material-shell-motion-host").assert(hasStateDescription("页面切换中"))
            save(view, "motion-enter-mid")
            compose.mainClock.advanceTimeBy(400)
            compose.onNodeWithContentDescription("返回图片列表").performClick()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(120)
            compose.onNodeWithTag("material-shell-motion-host").assert(hasStateDescription("页面切换中"))
            save(view, "motion-return-mid")
            compose.mainClock.advanceTimeBy(400)
            assertFalse(vm.hasDetail)
            compose.onNodeWithTag("material-grid").assertIsDisplayed()
        } finally { controller.pause().stop().destroy(); loader.shutdown() }
    }
}
