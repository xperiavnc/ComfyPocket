package com.comfyui.mobile.ui.navigation

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import coil.ImageLoader
import com.comfyui.mobile.market.MarketCatalogState
import com.comfyui.mobile.market.MarketCatalogSection
import com.comfyui.mobile.model.CivitaiContentChannel
import com.comfyui.mobile.market.MarketPeriod
import com.comfyui.mobile.market.MarketSort
import com.comfyui.mobile.ui.screens.modelcenter.ModelMarketPage
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class MarketSearchControlsTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun bothSectionsHaveIndependentContinuationAndLinkLookupIsAnExplicitAction() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val loader = ImageLoader.Builder(activity).build()
        val sections = CivitaiContentChannel.entries.map {
            MarketCatalogSection(it, MarketCatalogState(hasNextPage = true, loadedPage = 1))
        }
        val requests = mutableListOf<CivitaiContentChannel>()
        var opens = 0
        try {
            activity.setContent {
                ComfyControllerTheme(darkTheme = true, manageSystemBars = false) {
                    ModelMarketPage(MarketCatalogState(keyword = "2779381", matureEnabled = true, sections = sections),
                        rememberLazyGridState(), emptyList(), loader, { null }, { it },
                        {}, {}, {}, {}, {}, {}, {}, {},
                        onOpenLink = { opens++ }, onMoreSection = { requests += it })
                }
            }
            compose.onNodeWithTag("market-open-link").performClick()
            assertEquals(1, opens)
            for (channel in CivitaiContentChannel.entries) {
                val tag = "market-footer-$channel"
                compose.onNodeWithTag("market-grid").performScrollToNode(hasTestTag(tag))
                compose.onNode(hasAnyAncestor(hasTestTag(tag)) and hasClickAction()).performClick()
            }
            assertEquals(CivitaiContentChannel.entries, requests)
            println("MARKET_DUAL_UI ordinary + mature independent load-more; explicit link action PASS")
        } finally {
            loader.shutdown()
            controller.pause().stop().destroy()
        }
    }

    @Test fun authorSearchOrderingAndEmptyPageContinuationAreReachableInRealPage() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val loader = ImageLoader.Builder(activity).build()
        val state = mutableStateOf(MarketCatalogState(hasNextPage = true, loadedPage = 1))
        var searches = 0
        var more = 0
        try {
            activity.setContent {
                ComfyControllerTheme(darkTheme = true, manageSystemBars = false) {
                    ModelMarketPage(state.value, rememberLazyGridState(), emptyList(), loader,
                        previewUrl = { null }, imageRequest = { it },
                        onKeyword = { state.value = state.value.copy(keyword = it) },
                        onType = { state.value = state.value.copy(type = it) },
                        onBaseModel = { state.value = state.value.copy(baseModel = it) },
                        onManageTags = {}, onSearch = { searches++ }, onRetry = {},
                        onMore = { more++ }, onOpen = {},
                        onAuthor = { state.value = state.value.copy(author = it) },
                        onSort = { state.value = state.value.copy(sort = it) },
                        onPeriod = { state.value = state.value.copy(period = it) })
                }
            }
            compose.onNodeWithTag("market-author").performTextInput("@fixture-author")
            compose.onNodeWithTag("market-author").performImeAction()
            assertEquals(1, searches)
            assertEquals("@fixture-author", state.value.author)
            compose.onNodeWithText("日期").performClick()
            assertEquals(MarketSort.NEWEST, state.value.sort)
            compose.onNodeWithText("热度 · 下载").performClick()
            assertEquals(MarketSort.DOWNLOADS, state.value.sort)
            compose.onNodeWithText("人气 · 赞").performScrollTo().performClick()
            assertEquals(MarketSort.LIKES, state.value.sort)
            compose.onNodeWithText("一周").performScrollTo().performClick()
            assertEquals(MarketPeriod.WEEK, state.value.period)
            compose.onNodeWithText("加载更多 · 已显示 0").performScrollTo().performClick()
            assertEquals(1, more)
            println("MARKET_SEARCH_UI author IME + date/downloads/likes + week + empty-page load-more PASS")
        } finally {
            loader.shutdown()
            controller.pause().stop().destroy()
        }
    }
}
