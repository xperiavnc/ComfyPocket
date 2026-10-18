package com.comfyui.mobile.ui.screens.danbooru

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import coil.ImageLoader
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.ErrorResult
import coil.request.ImageResult
import coil.request.SuccessResult
import com.comfyui.mobile.danbooru.*
import com.comfyui.mobile.ui.LocalFloatingNavClearance
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import top.yukonga.miuix.kmp.theme.MiuixTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class DanbooruMediaUiTest {
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

    /** Real Coil request/error callbacks, deterministic local decoded results; no CDN/user data. */
    private fun loader(activity: ComponentActivity, requests: MutableList<String>) =
        ImageLoader.Builder(activity).components {
            add(object : Interceptor {
                override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                    val url = chain.request.data.toString()
                    requests += url
                    return if (url.endsWith("cover.png")) SuccessResult(
                        BitmapDrawable(activity.resources, Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)),
                        chain.request, DataSource.MEMORY
                    ) else ErrorResult(null, chain.request, IllegalStateException("fixture decode failure"))
                }
            })
        }.build()

    @Test fun videoTileLoadsTheImagePosterNotTheMp4() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val requests = CopyOnWriteArrayList<String>()
        val loader = loader(controller.get(), requests)
        try {
            val post = DanbooruPost(42, emptyMap(), extension = "mp4",
                sampleUrl = "https://fixture.invalid/video.mp4", previewUrl = "https://fixture.invalid/cover.png")
            controller.get().setContentView(ComposeView(controller.get()).apply { setContent {
                MiuixTheme { DanbooruImageTile(post, loader, "fixture") {} }
            } })
            compose.waitForIdle()
            compose.waitUntil(5000) { requests.isNotEmpty() }
            compose.waitForIdle()
            assertEquals(listOf("https://fixture.invalid/cover.png"), requests.toList())
        } finally { loader.shutdown(); controller.pause().stop().destroy() }
    }

    @Test fun failedSampleAutomaticallyTriesTheThumbnailInsteadOfGivingUp() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val requests = CopyOnWriteArrayList<String>()
        val loader = loader(controller.get(), requests)
        try {
            val post = DanbooruPost(43, emptyMap(), extension = "jpg",
                sampleUrl = "https://fixture.invalid/broken.jpg", previewUrl = "https://fixture.invalid/cover.png")
            controller.get().setContentView(ComposeView(controller.get()).apply { setContent {
                MiuixTheme { DanbooruImageTile(post, loader, "fixture") {} }
            } })
            compose.waitForIdle()
            compose.waitUntil(5000) { requests.size >= 2 }
            compose.waitForIdle()
            assertEquals(listOf("https://fixture.invalid/broken.jpg", "https://fixture.invalid/cover.png"), requests.toList())
            compose.onNodeWithTag("danbooru-preview-unavailable").assertDoesNotExist()
        } finally { loader.shutdown(); controller.pause().stop().destroy() }
    }

    @Test fun allSortChoicesFitAtNarrowWidthAndLargeFontWithoutHorizontalClipping() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply { setContent {
                MiuixTheme {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.4f)) {
                        Column(Modifier.fillMaxWidth()) {
                            DanbooruBrowseControls("", {}, DanbooruQuery.resolve("", ContentFilter.SENSITIVE),
                                {}, {}, {}, {}, {})
                        }
                    }
                }
            } })
            compose.waitForIdle()
            // All sort choices should be exposed without a sideways gesture.
            compose.onAllNodes(hasAnyDescendant(hasText("收藏")) and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)).assertCountEquals(0)
            for (text in listOf("最新", "热度", "排行榜", "评分", "收藏")) {
                val node = compose.onNodeWithText(text).assertIsDisplayed()
                val bounds = node.getUnclippedBoundsInRoot()
                assertTrue("$text must be fully inside the 320dp viewport: $bounds",
                    bounds.left.value >= 0 && bounds.right.value <= 320f)
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun exhaustedPreviewCandidatesCanBeRetriedWithAPhysicalTap() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val requests = CopyOnWriteArrayList<String>()
        val loader = loader(controller.get(), requests)
        try {
            val post = DanbooruPost(44, emptyMap(), extension = "jpg",
                sampleUrl = "https://fixture.invalid/broken.jpg", previewUrl = "https://fixture.invalid/missing.jpg")
            controller.get().setContentView(ComposeView(controller.get()).apply { setContent {
                MiuixTheme { DanbooruMediaPreview(post, loader, "fixture", Modifier.fillMaxWidth().height(240.dp)) }
            } })
            compose.waitForIdle()
            compose.waitUntil(5000) { requests.size == 2 }
            compose.waitForIdle()
            compose.onNodeWithText("重试预览").performTouchInput { click() }
            compose.waitUntil(5000) { requests.size == 4 }
            assertEquals(requests.take(2), requests.drop(2))
        } finally { loader.shutdown(); controller.pause().stop().destroy() }
    }

    @Test fun missingVideoPosterStillOffersPlaybackAndTagsWithoutFetchingVideoInTheGrid() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val post = DanbooruPost(45, mapOf(TagGroup.GENERAL to listOf("cloud")),
            extension = "mp4", fileUrl = "https://fixture.invalid/video.mp4")
        val vm = DanbooruViewModel(SavedStateHandle(), Service(post))
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply { setContent {
                MiuixTheme { DanbooruScreen(vm, DanbooruRuntime.get(controller.get())) {} }
            } })
            compose.waitForIdle()
            compose.onNodeWithTag("danbooru-video-player").assertDoesNotExist()
            compose.onNodeWithTag("danbooru-image-45").performTouchInput { click() }
            compose.waitForIdle()
            compose.onNodeWithText("视频详情").assertIsDisplayed()
            compose.onNodeWithText("播放视频").assertIsDisplayed()
            compose.onNodeWithTag("danbooru-video-player").assertDoesNotExist()
            compose.onNodeWithText("cloud").assertExists()
            compose.runOnIdle { controller.get().onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            assertFalse(vm.hasDetail)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun physicalPlayStartsTheRealMedia3PlayerAndBackRemovesIt() {
        MockWebServer().use { server ->
            // Deliberately missing media: exercise real player creation/error UI, not hardware decoding.
            repeat(4) { server.enqueue(MockResponse().setResponseCode(404)) }
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
            val post = DanbooruPost(47, mapOf(TagGroup.GENERAL to listOf("cloud")),
                extension = "mp4", fileUrl = server.url("/missing.mp4").toString())
            val vm = DanbooruViewModel(SavedStateHandle(), Service(post))
            try {
                controller.get().setContentView(ComposeView(controller.get()).apply { setContent {
                    MiuixTheme { DanbooruScreen(vm, DanbooruRuntime.get(controller.get())) {} }
                } })
                compose.waitForIdle()
                compose.runOnIdle { vm.open(post) }
                compose.waitForIdle()
                assertEquals("Opening the detail must not fetch video", 0, server.requestCount)
                compose.onNodeWithText("播放视频").performTouchInput { click() }
                compose.waitForIdle()
                compose.onNodeWithTag("danbooru-video-player").assertExists()
                compose.waitUntil(5000) { server.requestCount > 0 }
                compose.waitForIdle()
                compose.runOnIdle { controller.get().onBackPressedDispatcher.onBackPressed() }
                compose.waitForIdle()
                compose.onNodeWithTag("danbooru-video-player").assertDoesNotExist()
                assertFalse(vm.hasDetail)
                assertFalse(controller.get().isFinishing)
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun detailScrollsBehindTheFloatingBarButItsLastTagCanClearItAndReceiveATap() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val post = DanbooruPost(46, mapOf(TagGroup.GENERAL to (1..35).map { "fixture_tag_$it" },
            TagGroup.META to listOf("tail_tag")))
        val vm = DanbooruViewModel(SavedStateHandle(), Service(post))
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply { setContent {
                MiuixTheme {
                    CompositionLocalProvider(LocalFloatingNavClearance provides 96.dp) {
                        DanbooruScreen(vm, DanbooruRuntime.get(controller.get())) {}
                    }
                }
            } })
            compose.waitForIdle()
            compose.runOnIdle { vm.open(post) }
            compose.waitForIdle()
            val viewport = compose.onNodeWithTag("danbooru-detail-scroll").fetchSemanticsNode().boundsInRoot
            val page = compose.onNodeWithTag("danbooru-detail-page").fetchSemanticsNode().boundsInRoot
            assertEquals("The viewport must extend behind the floating bar, not stop 96dp early",
                page.bottom, viewport.bottom, 1f)
            repeat(3) {
                compose.onNodeWithTag("danbooru-detail-scroll").performSemanticsAction(SemanticsActions.ScrollBy) {
                    it(0f, 10000f)
                }
                compose.waitForIdle()
            }
            val tail = compose.onNodeWithText("tail_tag").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("End padding must lift the final tag above the 96dp floating-bar region",
                tail.bottom <= viewport.bottom - 96f)
            compose.onNodeWithText("tail_tag").performTouchInput { click() }
            val clipboard = controller.get().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            assertEquals("tail tag", clipboard.primaryClip!!.getItemAt(0).text.toString())
            assertTrue(vm.hasDetail)
        } finally { controller.pause().stop().destroy() }
    }
}
