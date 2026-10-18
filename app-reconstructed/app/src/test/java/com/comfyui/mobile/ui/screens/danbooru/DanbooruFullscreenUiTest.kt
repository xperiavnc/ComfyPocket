package com.comfyui.mobile.ui.screens.danbooru

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.compose.ui.unit.Density
import androidx.media3.ui.PlayerView
import com.comfyui.mobile.danbooru.*
import java.io.ByteArrayOutputStream
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class DanbooruFullscreenUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private class Service(private val post: DanbooruPost) : DanbooruService, DanbooruDataSource {
        override val settings = DanbooruSettings()
        override val repository get() = this
        override suspend fun save(settings: DanbooruSettings) = Unit
        override suspend fun test(settings: DanbooruSettings): DanbooruIdentity? = null
        override suspend fun verify(settings: DanbooruSettings): DanbooruIdentity? = null
        override suspend fun clearCache() = Unit
        override suspend fun posts(query: DanbooruQuery, page: String?, refresh: Boolean) =
            if (page == null) listOf(post) else emptyList()
        override suspend fun post(id: Long) = post
    }

    private fun withDetail(post: DanbooruPost, fontScale: Float = 1f, block: (ComponentActivity, DanbooruViewModel) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val vm = DanbooruViewModel(SavedStateHandle(), Service(post))
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply { setContent {
                MiuixTheme {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                        DanbooruScreen(vm, DanbooruRuntime.get(controller.get())) {}
                    }
                }
            } })
            compose.waitForIdle()
            compose.runOnIdle { vm.open(post) }
            compose.waitForIdle()
            block(controller.get(), vm)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun imagePreviewTapOpensFullscreenAndOneBackKeepsTheDetail() {
        MockWebServer().use { server ->
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            val bytes = ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
            bitmap.recycle()
            repeat(4) { server.enqueue(MockResponse().addHeader("Content-Type", "image/png").setBody(Buffer().write(bytes))) }
            withDetail(DanbooruPost(501, emptyMap(), extension = "png",
                fileUrl = server.url("/picture.png").toString(), width = 800, height = 600)) { activity, vm ->
                compose.onNodeWithContentDescription("帖子预览").performTouchInput { click() }
                compose.waitForIdle()
                compose.onNodeWithTag("danbooru-image-fullscreen").assertIsDisplayed()
                val surface = compose.onNodeWithTag("danbooru-zoom-surface")
                surface.performTouchInput { doubleClick() }
                surface.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "250%"))
                surface.performTouchInput { doubleClick() }
                surface.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "100%"))
                surface.performTouchInput {
                    pinch(center + Offset(-30f, 0f), center + Offset(30f, 0f),
                        center + Offset(-100f, 0f), center + Offset(100f, 0f))
                }
                assertNotEquals("Physical pinch must change scale", "100%",
                    surface.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
                compose.onNodeWithText("复位").performTouchInput { click() }
                surface.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "100%"))
                compose.runOnIdle {
                    (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
                }
                compose.waitForIdle()
                compose.onNodeWithTag("danbooru-image-fullscreen").assertDoesNotExist()
                assertTrue("One Back closes the viewer, not the detail", vm.hasDetail)
                assertFalse(activity.isFinishing)
            }
        }
    }

    @Test fun videoDetailExposesADownloadAction() {
        withDetail(DanbooruPost(502, emptyMap(), extension = "mp4",
            fileUrl = "https://fixture.invalid/original.mp4")) { _, _ ->
            compose.onNodeWithText("下载视频").performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun videoPosterExposesFullscreenPlaybackWithoutAutoplay() {
        withDetail(DanbooruPost(503, emptyMap(), extension = "webm",
            fileUrl = "https://fixture.invalid/original.webm")) { _, _ ->
            compose.onNodeWithTag("danbooru-video-player").assertDoesNotExist()
            compose.onNodeWithText("全屏播放").assertIsDisplayed()
        }
    }

    @Test
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun nativeFullscreenControlAndBackMoveTheSamePausedPlayerWithoutResettingItsPosition() {
        MockWebServer().use { server ->
            repeat(4) { server.enqueue(MockResponse().setResponseCode(404)) }
            val post = DanbooruPost(504, emptyMap(), extension = "mp4", fileUrl = server.url("/missing.mp4").toString())
            withDetail(post) { activity, vm ->
                compose.onNodeWithText("播放视频").performTouchInput { click() }
                compose.waitForIdle()
                val inline = activity.window.decorView.findViewWithTag<PlayerView>("danbooru-inline-player")!!
                val player = inline.player!!
                // Observe actual network start; decoder/error callbacks are not a prerequisite
                // for checking a render-target transfer in Robolectric's paused main looper.
                compose.waitUntil(5000) { server.requestCount > 0 }
                compose.runOnIdle { player.pause(); player.seekTo(4321) }
                compose.waitForIdle()
                assertEquals(4321L, player.currentPosition)
                val requests = server.requestCount
                compose.runOnIdle { assertTrue(inline.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_fullscreen).performClick()) }
                compose.waitForIdle()
                compose.onNodeWithTag("danbooru-video-fullscreen").assertIsDisplayed()
                val dialog = ShadowDialog.getLatestDialog() as ComponentDialog
                val full = dialog.window!!.decorView.findViewWithTag<PlayerView>("danbooru-fullscreen-player")!!
                assertSame(player, full.player)
                assertNull(inline.player)
                assertEquals(4321L, player.currentPosition)
                assertFalse(player.playWhenReady)
                compose.runOnIdle { dialog.onBackPressedDispatcher.onBackPressed() }
                compose.waitForIdle()
                compose.onNodeWithTag("danbooru-video-fullscreen").assertDoesNotExist()
                assertSame(player, inline.player)
                assertNull(full.player)
                assertFalse(player.playWhenReady)
                assertEquals(4321L, player.currentPosition)
                assertEquals("Fullscreen must not prepare or request the video again", requests, server.requestCount)
                assertTrue(vm.hasDetail)
                compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
                compose.waitForIdle()
                assertFalse(vm.hasDetail)
                assertFalse(activity.isFinishing)
            }
        }
    }

    private fun assertRatingOnFirstSortLine(fontScale: Float) {
        withDetail(DanbooruPost(505, emptyMap()), fontScale) { _, vm ->
            compose.runOnIdle { vm.back() }
            compose.waitForIdle()
            val row = compose.onNodeWithTag("danbooru-browse-filters").fetchSemanticsNode().boundsInRoot
            val rating = compose.onNodeWithTag("danbooru-rating-filter").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val first = compose.onNodeWithText("最新").fetchSemanticsNode().boundsInRoot
            assertEquals("Rating must stay on the first sort line", first.center.y, rating.center.y, 2f)
            assertTrue(rating.right <= row.right && rating.left >= row.left)
            for (text in listOf("最新", "热度", "排行榜", "评分", "收藏")) compose.onNodeWithText(text).assertIsDisplayed()
            compose.onAllNodesWithTag("danbooru-rating-filter").assertCountEquals(1)
            compose.onNodeWithTag("danbooru-rating-filter").performTouchInput { click() }
            compose.onNodeWithText("敏感").performTouchInput { click() }
            compose.waitForIdle()
            assertEquals(ContentFilter.SENSITIVE, vm.state.value.query.filter)
            compose.onNodeWithText("热度").performTouchInput { click() }
            compose.waitForIdle()
            assertEquals(DanbooruSort.HOT, vm.state.value.query.sort)
            assertEquals(ContentFilter.SENSITIVE, vm.state.value.query.filter)
        }
    }

    @Test fun ratingSharesTheFirstSortLineAndDoesNotChangeTheSourcePicker() = assertRatingOnFirstSortLine(1f)

    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h800dp-mdpi")
    fun ratingStaysOnTheFirstSortLineAt320dpAndLargeFontWithoutHidingSortChoices() =
        assertRatingOnFirstSortLine(1.4f)
}
