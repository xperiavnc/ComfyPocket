package com.comfyui.mobile.ui.material

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.SavedStateHandle
import coil.ImageLoader
import com.comfyui.mobile.danbooru.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ActivityController

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class MaterialSampleUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private class Service(context: android.content.Context) : DanbooruService, DanbooruDataSource {
        val fixture = MaterialFixtures(context)
        override var settings = DanbooruSettings()
        override val repository get() = this
        var requests = 0
        var empty = false
        var fail = false
        var failSave = false
        override suspend fun posts(query: DanbooruQuery, page: String?, refresh: Boolean): List<DanbooruPost> {
            requests++
            if (fail) throw DanbooruException("测试连接失败")
            return if (empty || page != null) emptyList() else fixture.pictures.take(12)
        }
        override suspend fun post(id: Long) = fixture.pictures.first { it.id == id }
        override suspend fun verify(settings: DanbooruSettings): DanbooruIdentity? = null
        override suspend fun test(settings: DanbooruSettings): DanbooruIdentity? = null
        override suspend fun save(settings: DanbooruSettings) {
            if (failSave) throw DanbooruException("保存失败，输入已保留")
            this.settings = settings
        }
        override suspend fun clearCache() = Unit
    }
    private inner class Harness(configure: (Service) -> Unit = {}) : AutoCloseable {
        val controller: ActivityController<ComponentActivity> = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val service = Service(activity).also(configure)
        val vm = DanbooruViewModel(SavedStateHandle(), service)
        val editor = MaterialConnectionEditor()
        val loader = ImageLoader.Builder(activity).build()
        var exits = 0
        var copies = CopiedTags("", 0)
        init {
            activity.setContentView(ComposeView(activity).apply { setContent {
                MaterialSampleScreen(vm, editor, loader, false, {}, {}, { exits++ }, { copies = it }, {}, { _, _ -> "fixture" })
            } })
            compose.waitForIdle()
        }
        fun back() { compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle() }
        override fun close() { controller.pause().stop().destroy(); loader.shutdown() }
    }
    @Test fun realSortAndPeriodActionsKeepSearchAndDoNotExposeTileIds() {
        Harness().use { h ->
            compose.onNodeWithTag("material-search").performTextInput("blue_sky")
            compose.onNodeWithText("热度").performClick()
            compose.waitForIdle()
            assertTrue(h.vm.state.value.query.tags.contains("blue_sky"))
            assertTrue(h.vm.state.value.query.tags.contains("order:rank"))
            compose.onNodeWithText("排行榜").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("周榜").performClick()
            compose.waitForIdle()
            assertEquals(RankingPeriod.WEEK, h.vm.state.value.query.rankingPeriod)
            compose.onAllNodesWithText("#", substring = true).assertCountEquals(0)
        }
    }
    @Test fun emptyAndErrorResultsStillHaveAWorkingPullRefreshOwner() {
        Harness { it.empty = true }.use { h ->
            compose.onNodeWithText("没有找到图片").assertExists()
            val before = h.service.requests
            compose.onNodeWithTag("material-grid").performTouchInput { swipeDown(durationMillis = 600) }
            compose.waitForIdle()
            assertEquals(before + 1, h.service.requests)
            h.service.empty = false; h.service.fail = true
            compose.runOnIdle { h.vm.refresh() }
            compose.waitForIdle()
            compose.onNodeWithText("暂时没有加载成功").assertExists()
            h.service.fail = false
            compose.onNodeWithText("重试").performClick()
            compose.waitForIdle()
            assertTrue(h.vm.state.value.posts.isNotEmpty())
        }
    }
    @Test fun navigationDemoReturnsToTheSameGalleryQueryAndScroll() {
        Harness().use { h ->
            compose.onNodeWithTag("material-grid").performScrollToIndex(6)
            compose.waitForIdle()
            val index = h.vm.scrollIndex
            val requests = h.service.requests
            assertTrue(index > 0)
            compose.onNodeWithText("主页").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("主页 · 导航演示").assertExists()
            compose.onNodeWithText("画廊").performClick()
            compose.waitForIdle()
            assertEquals(index, h.vm.scrollIndex)
            assertEquals(requests, h.service.requests)
        }
    }
    @Test fun dirtySettingsBackShowsAChoiceAndSaveReportsSuccess() {
        Harness().use { h ->
            compose.onNodeWithContentDescription("Danbooru 设置").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("用户名").performTextInput("user")
            h.back()
            compose.onNodeWithText("保留这次修改？").assertExists()
            assertTrue(h.vm.state.value.settingsOpen)
            compose.onNodeWithText("继续编辑").performClick()
            compose.onNodeWithText("API Key").performTextInput("fixture-key")
            compose.onNodeWithTag("material-save").performClick()
            compose.waitForIdle()
            assertFalse(h.vm.state.value.settingsOpen)
            assertEquals("user", h.service.settings.username)
            compose.onNodeWithText("样板设置已保存，真实配置未变").assertExists()
            assertEquals(0, h.exits)
        }
    }
    @Test fun failedSaveKeepsDraftAndDoesNotShowSuccessOrCloseTheForm() {
        Harness { it.failSave = true }.use { h ->
            compose.onNodeWithContentDescription("Danbooru 设置").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("用户名").performTextInput("user")
            compose.onNodeWithText("API Key").performTextInput("fixture-key")
            compose.onNodeWithTag("material-save").performClick()
            compose.waitForIdle()
            assertTrue(h.vm.state.value.settingsOpen)
            assertEquals("fixture-key", h.editor.draft.apiKey)
            assertFalse(h.service.settings.loggedIn)
            compose.onNodeWithText("保存失败，输入已保留").assertExists()
            compose.onNodeWithText("样板设置已保存，真实配置未变").assertDoesNotExist()
        }
    }
    @Test fun singleAndCancelledPredictiveBackKeepTheCorrectLayer() {
        Harness().use { h ->
            compose.onNodeWithTag("material-image-100").performClick()
            compose.waitForIdle()
            assertTrue(h.vm.hasDetail)
            val dispatcher = h.activity.onBackPressedDispatcher
            compose.runOnIdle {
                dispatcher.dispatchOnBackStarted(androidx.activity.BackEventCompat(0f, 0f, 0f, 0))
                dispatcher.dispatchOnBackProgressed(androidx.activity.BackEventCompat(0f, 0f, .4f, 0))
                dispatcher.dispatchOnBackCancelled()
            }
            compose.waitForIdle()
            assertTrue(h.vm.hasDetail)
            h.back()
            assertFalse(h.vm.hasDetail)
            assertEquals(0, h.exits)
            assertTrue(h.vm.state.value.posts.isNotEmpty())
        }
    }
    @Test fun tagsSupportCopyCrossGroupSelectionAndCollapsedOptions() {
        Harness().use { h ->
            compose.onNodeWithTag("material-image-100").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("标签与提示词").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("下划线转空格").assertDoesNotExist()
            compose.onNodeWithText("scenery").performTouchInput { longClick() }
            compose.waitForIdle()
            assertTrue(h.vm.state.value.selected.contains("scenery"))
            compose.onNodeWithText("全选画师").performClick()
            compose.waitForIdle()
            assertTrue(h.vm.state.value.selected.contains("landscape_studio"))
            compose.onNodeWithText("复制选中 2").performClick()
            compose.waitForIdle()
            assertEquals(2, h.copies.count)
            assertEquals("landscape studio, scenery", h.copies.text)
        }
    }
}
