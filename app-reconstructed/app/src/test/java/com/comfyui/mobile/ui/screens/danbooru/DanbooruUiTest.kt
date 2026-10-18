package com.comfyui.mobile.ui.screens.danbooru

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.*
import androidx.lifecycle.SavedStateHandle
import com.comfyui.mobile.danbooru.*
import com.comfyui.mobile.ui.screens.gallery.*
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
import top.yukonga.miuix.kmp.basic.Scaffold

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class DanbooruUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val post = DanbooruPost(42, mapOf(TagGroup.ARTIST to listOf("artist_one"),
        TagGroup.CHARACTER to listOf("character_one"), TagGroup.GENERAL to listOf("blue_sky", "cloud")))
    private class Service(private val fixture: DanbooruPost, private val pictures: List<DanbooruPost>? = null) : DanbooruService, DanbooruDataSource {
        override var settings = DanbooruSettings()
        override val repository get() = this
        override suspend fun save(settings: DanbooruSettings) { this.settings = settings }
        override suspend fun clearCache() = Unit
        override suspend fun test(settings: DanbooruSettings): DanbooruIdentity? = null
        override suspend fun verify(settings: DanbooruSettings): DanbooruIdentity? = null
        var requests = 0
        override suspend fun posts(query: DanbooruQuery, page: String?, refresh: Boolean): List<DanbooruPost> {
            requests++
            return if (pictures != null) { if (page == null) pictures else emptyList() }
                else (1..40).map { fixture.copy(id = it.toLong()) }
        }
        override suspend fun post(id: Long) = pictures?.firstOrNull { it.id == id } ?: fixture.copy(id = id)
    }
    @Test fun sourcePickerExposesExactlyThreeTypedSources() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var selected = GalleryHomeSource.SERVER
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply { setContent { MiuixTheme {
                GallerySourcePicker(GalleryHomeSource.SERVER) { selected = it }
            } } })
            compose.waitForIdle()
            compose.onNodeWithText("服务器").assertExists()
            compose.onNodeWithText("本地").assertExists()
            compose.onNodeWithText("Danbooru").performClick()
            assertEquals(GalleryHomeSource.DANBOORU, selected)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun nativeTagPanelCopiesSingleAndPromptSubsetWithoutUiMetadata() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val copied = mutableListOf<CopiedTags>()
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply { setContent { MiuixTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DanbooruTagPanel(post, TagCopyOptions(), emptySet(), {}, {}, {}) { copied += it }
                }
            } } })
            compose.waitForIdle()
            compose.onNodeWithText("blue_sky").performScrollTo().performClick()
            assertEquals(CopiedTags("blue sky", 1), copied.last())
            compose.onNodeWithText("复制提示词").performScrollTo().performClick()
            assertEquals(CopiedTags("character one, blue sky, cloud", 3), copied.last())
            compose.onNodeWithText("原始 Tag").performScrollTo().performClick()
            compose.onNodeWithText("复制全部").performScrollTo().performClick()
            assertEquals(CopiedTags("artist_one character_one blue_sky cloud", 4), copied.last())
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun groupSelectionAndClearHaveIndependentActions() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var group: TagGroup? = null
        var cleared = false
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply { setContent { MiuixTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DanbooruTagPanel(post, TagCopyOptions(), setOf("cloud"), {}, { group = it }, { cleared = true }) {}
                }
            } } })
            compose.waitForIdle()
            compose.onNodeWithText("全选通用").performScrollTo().performClick()
            assertEquals(TagGroup.GENERAL, group)
            compose.onNodeWithText("清空选择").performScrollTo().performClick()
            assertTrue(cleared)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun oneSystemBackConsumesSelectionThenDetailAndKeepsActivityAlive() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val vm = DanbooruViewModel(SavedStateHandle(), Service(post))
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply { setContent { MiuixTheme {
                DanbooruScreen(vm, DanbooruRuntime.get(controller.get())) {}
            } } })
            compose.waitForIdle()
            compose.runOnIdle { vm.open(post); vm.select("cloud") }
            compose.waitForIdle()
            compose.runOnIdle { controller.get().onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            assertTrue(vm.hasDetail); assertTrue(vm.state.value.selected.isEmpty())
            compose.runOnIdle { controller.get().onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            assertFalse(vm.hasDetail)
            assertFalse(controller.get().isFinishing)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun cancellingPredictiveBackDoesNotCloseTheDetail() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val vm = DanbooruViewModel(SavedStateHandle(), Service(post))
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply { setContent { MiuixTheme {
                DanbooruScreen(vm, DanbooruRuntime.get(controller.get())) {}
            } } })
            compose.waitForIdle(); compose.runOnIdle { vm.open(post) }; compose.waitForIdle()
            compose.runOnIdle {
                controller.get().onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
                controller.get().onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(20f, 0f, 0.4f, BackEventCompat.EDGE_LEFT))
                controller.get().onBackPressedDispatcher.dispatchOnBackCancelled()
            }
            compose.waitForIdle()
            assertTrue(vm.hasDetail); assertFalse(controller.get().isFinishing)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun browseHasRealSortControlsButNoVisiblePostIdsOrDebugPagingText() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val vm = DanbooruViewModel(SavedStateHandle(), Service(post))
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply { setContent { MiuixTheme {
                DanbooruScreen(vm, DanbooruRuntime.get(controller.get())) {}
            } } })
            compose.waitForIdle()
            compose.onNodeWithText("热度").performClick()
            compose.waitForIdle()
            assertEquals(DanbooruSort.HOT, vm.state.value.query.sort)
            compose.onNodeWithText("排行榜").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("周榜").performClick()
            compose.waitForIdle()
            assertEquals(RankingPeriod.WEEK, vm.state.value.query.rankingPeriod)
            assertTrue(vm.state.value.query.tags.contains("order:score"))
            compose.onAllNodesWithText("#", substring = true).assertCountEquals(0)
            compose.onNodeWithText("ID 游标", substring = true).assertDoesNotExist()
            compose.onNodeWithText("数字页", substring = true).assertDoesNotExist()
            compose.onNodeWithText("下拉刷新").assertIsNotDisplayed()
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun aRealPullGestureRefreshesOnceAndKeepsTheGrid() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val service = Service(post)
        val vm = DanbooruViewModel(SavedStateHandle(), service)
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply { setContent { MiuixTheme {
                DanbooruScreen(vm, DanbooruRuntime.get(controller.get())) {}
            } } })
            compose.waitForIdle()
            val before = service.requests
            compose.onNodeWithTag("danbooru-grid").performTouchInput { swipeDown(durationMillis = 700) }
            compose.waitForIdle()
            assertEquals(before + 1, service.requests)
            assertEquals(40, vm.state.value.posts.size)
            assertFalse(vm.state.value.refreshing)
            compose.onNodeWithTag("danbooru-grid").assertExists()
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun settingsSeparatesConnectionFromPreferencesAndSaveStaysAvailable() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var saved: DanbooruSettings? = null
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply { setContent { MiuixTheme {
                Scaffold {
                    DanbooruSettingsDialog(DanbooruSettings(proxyEnabled = true, proxyAddress = "http://127.0.0.1:2080"),
                        DanbooruState(DanbooruQuery.resolve("", ContentFilter.GENERAL)), {},
                        { saved = it; Result.success(Unit) }, { "fixture ok" }, { Result.success(Unit) }, { "fixture cleared" })
                }
            } } })
            compose.waitForIdle()
            compose.onNodeWithText("用户名").assertExists()
            compose.onNodeWithText("保存").assertIsDisplayed()
            compose.onNodeWithText("偏好").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("默认内容评级").assertExists()
            compose.onNodeWithText("用户名").assertDoesNotExist()
            compose.onNodeWithText("保存").performClick()
            compose.waitForIdle()
            assertEquals("http://127.0.0.1:2080", saved?.proxyAddress)
            assertEquals(true, saved?.proxyEnabled)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun tagFormattingSwitchesAreCollapsedUntilRequested() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply { setContent { MiuixTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DanbooruTagPanel(post, TagCopyOptions(), emptySet(), {}, {}, {}) {}
                }
            } } })
            compose.waitForIdle()
            compose.onNodeWithText("下划线转空格").assertDoesNotExist()
            compose.onNodeWithText("选项").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("下划线转空格").assertExists()
            compose.onNodeWithText("括号转义").assertExists()
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun oneBackDismissesTheMiuixSettingsOverlayAndRetainsDetail() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val vm = DanbooruViewModel(SavedStateHandle(), Service(post))
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply { setContent { MiuixTheme {
                DanbooruScreen(vm, DanbooruRuntime.get(controller.get())) {}
            } } })
            compose.waitForIdle()
            compose.runOnIdle { vm.open(post); vm.showSettings() }
            compose.waitForIdle()
            compose.onNodeWithText("Danbooru 设置").assertExists()
            compose.runOnIdle { controller.get().onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            assertFalse(vm.state.value.settingsOpen)
            assertTrue(vm.hasDetail)
            assertFalse(controller.get().isFinishing)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun cancellingSettingsAnimatesBeforeUnmountAndRapidBackDoesNotLeaveTheDetail() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val service = Service(post)
        val vm = DanbooruViewModel(SavedStateHandle(), service)
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply { setContent { MiuixTheme {
                DanbooruScreen(vm, DanbooruRuntime.get(controller.get())) {}
            } } })
            compose.waitForIdle()
            compose.runOnIdle { vm.open(post); vm.showSettings() }
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            compose.onNodeWithText("取消").performTouchInput { click() }
            compose.waitForIdle(); compose.mainClock.advanceTimeBy(80)
            assertTrue("Keep the settings owner alive until exit completes", vm.state.value.settingsOpen)
            compose.runOnIdle { controller.get().onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle(); compose.mainClock.advanceTimeBy(600)
            compose.mainClock.autoAdvance = true; compose.waitForIdle()
            assertFalse(vm.state.value.settingsOpen)
            assertTrue(vm.hasDetail); assertFalse(controller.get().isFinishing)
            assertEquals(DanbooruSettings(), service.settings)
            compose.runOnIdle { controller.get().onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            assertFalse(vm.hasDetail); assertFalse(controller.get().isFinishing)
        } finally {
            compose.mainClock.autoAdvance = true
            controller.pause().stop().destroy()
        }
    }

    @Test fun ratingSelectionWaitsForExitBeforeReopeningAndDoesNotChangeTheChosenFilterTwice() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var filter by mutableStateOf(ContentFilter.GENERAL)
        var selections = 0
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply { setContent { MiuixTheme {
                Scaffold {
                    DanbooruRatingFilter(filter) { filter = it; selections++ }
                }
            } } })
            compose.waitForIdle()
            compose.onNodeWithTag("danbooru-rating-filter").performTouchInput { click() }
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            compose.onNodeWithText(ContentFilter.SENSITIVE.title).performTouchInput { click() }
            compose.waitForIdle(); compose.mainClock.advanceTimeBy(80)
            assertEquals(ContentFilter.SENSITIVE, filter); assertEquals(1, selections)
            compose.mainClock.advanceTimeBy(600)
            compose.mainClock.autoAdvance = true; compose.waitForIdle()
            compose.onNodeWithText("内容评级").assertDoesNotExist()
            compose.onNodeWithTag("danbooru-rating-filter").performTouchInput { click() }
            compose.waitForIdle()
            compose.onNodeWithText("内容评级").assertIsDisplayed()
            assertEquals(1, selections)
        } finally {
            compose.mainClock.autoAdvance = true
            controller.pause().stop().destroy()
        }
    }

    @Test fun tappingToViewTagsAndReturningAnimateWithoutResettingTheGridOrAnimatingSelection() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val vm = DanbooruViewModel(SavedStateHandle(), Service(post))
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply { setContent { MiuixTheme {
                DanbooruScreen(vm, DanbooruRuntime.get(controller.get())) {}
            } } })
            compose.waitForIdle()
            compose.onNodeWithTag("danbooru-grid").performScrollToIndex(14)
            compose.waitForIdle()
            val scroll = vm.scrollIndex to vm.scrollOffset
            val restingX = compose.onNodeWithTag("danbooru-browse-page").fetchSemanticsNode().positionInRoot.x
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("danbooru-image-15").performClick()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(80)
            val enteringX = compose.onNodeWithTag("danbooru-detail-page").fetchSemanticsNode().positionInRoot.x
            assertTrue("Detail must slide in, not appear immediately at rest", enteringX > restingX + 1)
            compose.mainClock.advanceTimeBy(400)
            compose.onNodeWithTag("danbooru-detail-page").assertExists()
            assertEquals(restingX, compose.onNodeWithTag("danbooru-detail-page").fetchSemanticsNode().positionInRoot.x, 1f)
            compose.runOnIdle { vm.select("cloud") }
            compose.mainClock.advanceTimeBy(80)
            assertEquals("Tag multi-selection must not restart a page transition", restingX,
                compose.onNodeWithTag("danbooru-detail-page").fetchSemanticsNode().positionInRoot.x, 1f)
            compose.runOnIdle { vm.clearSelection(); vm.back() }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(80)
            assertTrue("Browse page must slide back from the left",
                compose.onNodeWithTag("danbooru-browse-page").fetchSemanticsNode().positionInRoot.x < restingX - 1)
            compose.mainClock.advanceTimeBy(400)
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            compose.onNodeWithTag("danbooru-detail-page").assertDoesNotExist()
            assertEquals(scroll, vm.scrollIndex to vm.scrollOffset)
            assertFalse(controller.get().isFinishing)
        } finally {
            compose.mainClock.autoAdvance = true
            controller.pause().stop().destroy()
        }
    }
}
