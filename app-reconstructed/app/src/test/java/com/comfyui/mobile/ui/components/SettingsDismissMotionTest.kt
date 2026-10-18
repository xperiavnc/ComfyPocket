package com.comfyui.mobile.ui.components

import androidx.activity.ComponentActivity
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import com.comfyui.mobile.danbooru.ContentFilter
import com.comfyui.mobile.danbooru.DanbooruQuery
import com.comfyui.mobile.danbooru.DanbooruSettings
import com.comfyui.mobile.danbooru.DanbooruState
import com.comfyui.mobile.market.CivitaiSettings
import com.comfyui.mobile.ui.screens.danbooru.DanbooruSettingsDialog
import com.comfyui.mobile.ui.screens.market.MarketSettingsDialog
import com.comfyui.mobile.ui.screens.modelbrowser.ModelBrowserManageFilterDialog
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
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
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsDismissMotionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val open = mutableStateOf(true)
    private var dismissed = 0
    private var saved = 0
    private var rootBacks = 0
    private var saveFails = false
    private fun result(): Result<Unit> {
        saved++
        return if (saveFails) Result.failure(IllegalArgumentException("本地测试保存失败")) else Result.success(Unit)
    }

    private fun host(activity: ComponentActivity, market: Boolean) {
        activity.setContentView(ComposeView(activity).apply { setContent {
            MiuixTheme {
                Scaffold {
                    PredictiveBackHandler { progress -> progress.collect { }; rootBacks++ }
                    Box(Modifier.fillMaxSize()) {
                        if (open.value) {
                            val close = { dismissed++; open.value = false }
                            if (market) MarketSettingsDialog(CivitaiSettings(), close,
                                { result() }, { "local fixture" })
                            else DanbooruSettingsDialog(
                                DanbooruSettings(), DanbooruState(DanbooruQuery.resolve("", ContentFilter.GENERAL)),
                                close, { result() }, { "local fixture" },
                                { Result.success(Unit) }, { "local cleared" })
                        }
                    }
                }
            }
        } })
        compose.waitForIdle()
    }

    private fun cancel(market: Boolean) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            host(activity.get(), market)
            compose.mainClock.autoAdvance = false
            compose.onNodeWithText("取消").performTouchInput { click() }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(80)
            assertEquals("Unmounting the caller now cuts off the dialog's exit animation", 0, dismissed)
            assertTrue(open.value)
            compose.runOnIdle { activity.get().onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            assertEquals("An exiting modal must not send another Back to the page underneath", 0, rootBacks)
            compose.mainClock.advanceTimeBy(600)
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            assertEquals(1, dismissed)
            assertFalse(open.value)
            assertEquals(0, saved)
            compose.onNodeWithText(if (market) "集市连接设置" else "Danbooru 设置").assertDoesNotExist()
            compose.runOnIdle { activity.get().onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            assertEquals(1, rootBacks)
        } finally {
            compose.mainClock.autoAdvance = true
            activity.pause().stop().destroy()
        }
    }

    @Test fun danbooruCancelRetainsTheDialogUntilItsExitFinishes() = cancel(market = false)
    @Test fun marketCancelRetainsTheDialogUntilItsExitFinishes() = cancel(market = true)

    @Test fun saveCompletesOnceThenAnimatesAndFailedSaveKeepsTheForm() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            host(activity.get(), market = false)
            saveFails = true
            compose.onNodeWithText("保存").performTouchInput { click() }
            compose.waitForIdle()
            compose.onNodeWithText("本地测试保存失败").assertIsDisplayed()
            assertTrue(open.value); assertEquals(0, dismissed)
            saveFails = false
            compose.mainClock.autoAdvance = false
            compose.onNodeWithText("保存").performTouchInput { click() }
            compose.waitForIdle(); compose.mainClock.advanceTimeBy(80)
            assertEquals(2, saved); assertEquals(0, dismissed)
            compose.mainClock.advanceTimeBy(600)
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            assertEquals(2, saved); assertEquals(1, dismissed); assertFalse(open.value)
        } finally {
            compose.mainClock.autoAdvance = true
            activity.pause().stop().destroy()
        }
    }

    @Test fun cancelledGestureKeepsDraftAndCompletedBackAnimatesOnlyTheModal() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            host(activity.get(), market = false)
            compose.runOnIdle {
                activity.get().onBackPressedDispatcher.dispatchOnBackStarted(
                    BackEventCompat(0f, 100f, 0f, BackEventCompat.EDGE_LEFT))
                activity.get().onBackPressedDispatcher.dispatchOnBackProgressed(
                    BackEventCompat(80f, 100f, 0.5f, BackEventCompat.EDGE_LEFT))
                activity.get().onBackPressedDispatcher.dispatchOnBackCancelled()
            }
            compose.waitForIdle()
            assertTrue(open.value); assertEquals(0, dismissed)
            compose.onNodeWithText("Danbooru 设置").assertIsDisplayed()
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { activity.get().onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle(); compose.mainClock.advanceTimeBy(80)
            assertEquals(0, dismissed); assertEquals(0, rootBacks)
            compose.mainClock.advanceTimeBy(600)
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            assertEquals(1, dismissed); assertEquals(0, rootBacks)
        } finally {
            compose.mainClock.autoAdvance = true
            activity.pause().stop().destroy()
        }
    }

    @Test fun actualOverlayMovesThroughExitAndDismissesExactlyOnceWithoutATimer() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var coordinates: LayoutCoordinates? = null
        try {
            activity.get().setContentView(ComposeView(activity.get()).apply { setContent {
                MiuixTheme {
                    Scaffold {
                        if (open.value) {
                            val dismissal = rememberDialogDismiss { dismissed++; open.value = false }
                            OverlayDialog(
                                show = dismissal.show,
                                modifier = dismissal.modifier,
                                title = "退出动画本地测试",
                                onDismissRequest = dismissal::dismiss,
                                onDismissFinished = dismissal::onDismissFinished
                            ) {
                                // Observe a descendant of Miuix's own graphicsLayer, not a
                                // supplied outer modifier whose coordinate origin stays still.
                                TextButton("取消", onClick = dismissal::dismiss, modifier = Modifier
                                    .testTag("fixture-cancel").onGloballyPositioned { coordinates = it })
                            }
                        }
                    }
                }
            } })
            compose.waitForIdle()
            val rest = requireNotNull(coordinates).positionInRoot().y
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("fixture-cancel").performTouchInput { click() }
            compose.waitForIdle(); compose.mainClock.advanceTimeBy(80)
            val early = requireNotNull(coordinates).positionInRoot().y
            assertTrue("The actual card should move downward, not vanish: rest=$rest, early=$early", early > rest + 1f)
            compose.onNodeWithTag("fixture-cancel").assertDoesNotExist()
            assertEquals(0, dismissed)
            compose.mainClock.advanceTimeBy(80)
            val later = requireNotNull(coordinates).positionInRoot().y
            assertTrue("The exit must progress across frames", later > early + 1f)
            compose.mainClock.advanceTimeBy(600)
            compose.mainClock.autoAdvance = true; compose.waitForIdle()
            assertEquals(1, dismissed)
            assertFalse(open.value)
        } finally {
            compose.mainClock.autoAdvance = true
            activity.pause().stop().destroy()
        }
    }

    @Test fun modelFilterManagerKeepsItsCardUntilTheDoneAnimationFinishes() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val pinned = mutableStateListOf("Krea2")
        try {
            activity.get().setContentView(ComposeView(activity.get()).apply { setContent {
                MiuixTheme {
                    Scaffold {
                        ModelBrowserManageFilterDialog(open.value, "管理标签本地测试", "", pinned,
                            {}, { dismissed++; open.value = false })
                    }
                }
            } })
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            compose.onNodeWithText("完成").performTouchInput { click() }
            compose.waitForIdle(); compose.mainClock.advanceTimeBy(80)
            assertEquals(0, dismissed); assertTrue(open.value)
            compose.mainClock.advanceTimeBy(600)
            compose.mainClock.autoAdvance = true; compose.waitForIdle()
            assertEquals(1, dismissed); assertFalse(open.value)
            assertEquals(listOf("Krea2"), pinned.toList())
        } finally {
            compose.mainClock.autoAdvance = true
            activity.pause().stop().destroy()
        }
    }
}
