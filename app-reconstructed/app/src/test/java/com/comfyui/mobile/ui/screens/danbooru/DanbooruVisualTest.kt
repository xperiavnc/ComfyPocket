package com.comfyui.mobile.ui.screens.danbooru

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.SavedStateHandle
import com.comfyui.mobile.danbooru.*
import com.comfyui.mobile.ui.screens.gallery.GalleryHomeSource
import com.comfyui.mobile.ui.screens.gallery.GallerySourcePicker
import com.comfyui.mobile.ui.theme.ComfyControllerTheme
import java.io.File
import java.time.Duration
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.shadows.ShadowDialog
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class DanbooruVisualTest {
    @get:Rule val compose = createEmptyComposeRule()

    private class Service(private val pictures: List<DanbooruPost>) : DanbooruService, DanbooruDataSource {
        override var settings = DanbooruSettings(proxyEnabled = true, proxyAddress = "http://127.0.0.1:2080")
        override val repository get() = this
        override suspend fun posts(query: DanbooruQuery, page: String?, refresh: Boolean) = if (page == null) pictures else emptyList()
        override suspend fun post(id: Long) = pictures.first { it.id == id }
        override suspend fun verify(settings: DanbooruSettings): DanbooruIdentity? = null
        override suspend fun test(settings: DanbooruSettings): DanbooruIdentity? = null
        override suspend fun save(settings: DanbooruSettings) { this.settings = settings }
        override suspend fun clearCache() = Unit
    }

    /** Locally drawn landscape fixtures: no account, API, CDN or downloaded image is involved. */
    private fun picture(directory: File, index: Int): DanbooruPost {
        val height = listOf(500, 420, 460, 540)[index % 4]
        val bitmap = Bitmap.createBitmap(360, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val palettes = listOf(
            intArrayOf(0xff9dbec3.toInt(), 0xffe9dfb5.toInt(), 0xff547c84.toInt(), 0xff284f64.toInt()),
            intArrayOf(0xffd9b59a.toInt(), 0xffffe2b7.toInt(), 0xff967d8a.toInt(), 0xff555571.toInt()),
            intArrayOf(0xffadb9d1.toInt(), 0xfff5dfc0.toInt(), 0xff6b86a3.toInt(), 0xff3b5c7a.toInt()),
            intArrayOf(0xffc1cbb8.toInt(), 0xfff4eac9.toInt(), 0xff8ba29a.toInt(), 0xff4c716b.toInt())
        )
        val colors = palettes[index % palettes.size]
        canvas.drawColor(colors[0])
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors[1] }
        canvas.drawCircle(270f, height * 0.25f, 42f, paint)
        for (layer in 0..1) {
            paint.color = colors[layer + 2]
            val path = Path().apply {
                moveTo(0f, height * (0.65f + layer * 0.12f))
                cubicTo(100f, height * (0.35f + layer * 0.2f), 220f, height * 0.9f, 360f, height * (0.55f + layer * 0.2f))
                lineTo(360f, height.toFloat()); lineTo(0f, height.toFloat()); close()
            }
            canvas.drawPath(path, paint)
        }
        val file = File(directory, "danbooru-landscape-$index.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return DanbooruPost(100L + index, mapOf(
            TagGroup.ARTIST to listOf("landscape_artist"), TagGroup.COPYRIGHT to listOf("original"),
            TagGroup.CHARACTER to listOf("original_character"),
            TagGroup.GENERAL to listOf("scenery", "blue_sky", "cloud", "mountain", "sunset", "river", "nature", "outdoors", "tree", "reflection"),
            TagGroup.META to listOf("highres")
        ), sampleUrl = file.toURI().toString(), previewUrl = file.toURI().toString(), width = 360, height = height, extension = "png", rating = "g")
    }

    private fun save(view: View, name: String) {
        // Coil cache insertion precedes its crossfade; capture settled content, not a dim first frame.
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        // CrossfadeDrawable uses Android uptime, not Compose's animation clock.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        val file = File("build/reports/danbooru-ui/$name.png").apply { parentFile?.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 10000)
    }

    @Test fun renderActualGalleryRankingTagsAndConnectionFormInTheAppTheme() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val pictures = (0..7).map { picture(activity.cacheDir, it) }
        val runtime = DanbooruRuntime.get(activity)
        val vm = DanbooruViewModel(SavedStateHandle(), Service(pictures))
        var dark by mutableStateOf(true)
        val view = ComposeView(activity)
        try {
            activity.setContentView(view.apply { setContent {
                // Native Robolectric Skia does not implement Miuix's AGSL color qualifier.
                // Use its documented rounded-corner fallback for screenshot layout/typography.
                CompositionLocalProvider(LocalSquircleEnabled provides false) {
                    ComfyControllerTheme(dark) {
                        DanbooruScreen(vm, runtime) { GallerySourcePicker(GalleryHomeSource.DANBOORU) {} }
                    }
                }
            } })
            compose.waitForIdle()
            compose.waitUntil(10000) {
                pictures.take(4).all { p -> runtime.imageLoader.memoryCache?.keys?.any {
                    it.key == p.mediaKey(runtime.settings.namespace(), "sample", p.sampleUrl!!)
                } == true }
            }
            compose.waitForIdle()
            compose.onNodeWithText("排行榜").performClick()
            compose.onNodeWithText("周榜").performClick()
            compose.waitForIdle()
            // Revisit after warming Coil's cache; do not capture an in-flight crossfade.
            compose.onNodeWithText("最新").performClick()
            compose.waitForIdle()
            save(view, "gallery-dark")
            compose.onNodeWithText("热度").performTouchInput { down(center); advanceEventTime(160) }
            save(view, "hot-pressed")
            compose.onNodeWithText("热度").performTouchInput { up() }
            compose.waitForIdle()
            save(view, "hot-selected")
            compose.onNodeWithText("排行榜").performClick()
            compose.waitForIdle()
            save(view, "ranking-dark")
            compose.runOnIdle { dark = false }
            compose.waitForIdle()
            save(view, "ranking-light")
            compose.runOnIdle { dark = true }
            compose.onNodeWithTag("danbooru-image-100").performClick()
            compose.waitForIdle()
            compose.onNodeWithContentDescription("帖子预览").performTouchInput { click() }
            compose.waitForIdle()
            val fullscreen = ShadowDialog.getLatestDialog().window!!.decorView
            save(fullscreen, "image-fullscreen")
            compose.onNodeWithTag("danbooru-zoom-surface").performTouchInput { doubleClick() }
            save(fullscreen, "image-fullscreen-zoomed")
            compose.onNodeWithContentDescription("退出全屏").performTouchInput { click() }
            compose.waitForIdle()
            compose.onNodeWithText("标签与提示词").performScrollTo()
            compose.waitForIdle()
            save(view, "tags-dark")
            compose.onNodeWithContentDescription("Danbooru 设置").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("保存").assertIsDisplayed()
            val overlay = WindowInspector.getGlobalWindowViews().lastOrNull { it.visibility == View.VISIBLE && it.width > 0 } ?: view
            save(overlay, "settings-dark")
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h800dp-xhdpi")
    fun renderWrappingBrowseControlsAt320dpAndLargeFontInBothThemes() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val pictures = (0..7).map { picture(activity.cacheDir, it) }
        val runtime = DanbooruRuntime.get(activity)
        val vm = DanbooruViewModel(SavedStateHandle(), Service(pictures))
        var dark by mutableStateOf(true)
        val view = ComposeView(activity)
        try {
            activity.setContentView(view.apply { setContent {
                CompositionLocalProvider(
                    LocalSquircleEnabled provides false,
                    LocalDensity provides Density(LocalDensity.current.density, 1.4f)
                ) {
                    ComfyControllerTheme(dark) {
                        DanbooruScreen(vm, runtime) { GallerySourcePicker(GalleryHomeSource.DANBOORU) {} }
                    }
                }
            } })
            compose.waitForIdle()
            compose.waitUntil(10000) {
                pictures.take(4).all { p -> runtime.imageLoader.memoryCache?.keys?.any {
                    it.key == p.mediaKey(runtime.settings.namespace(), "sample", p.sampleUrl!!)
                } == true }
            }
            compose.waitForIdle()
            for (text in listOf("最新", "热度", "排行榜", "评分", "收藏", "一般"))
                compose.onNodeWithText(text).assertIsDisplayed()
            save(view, "gallery-320-large-dark")
            compose.onNodeWithText("排行榜").performClick()
            compose.onNodeWithText("周榜").performClick()
            compose.waitForIdle()
            save(view, "ranking-320-large-dark")
            compose.runOnIdle { dark = false }
            compose.waitForIdle()
            save(view, "ranking-320-large-light")
        } finally { controller.pause().stop().destroy() }
    }
}
