package com.comfyui.mobile.ui.material

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.LayoutDirection
import com.comfyui.mobile.danbooru.*
import com.comfyui.mobile.ui.designsystem.PocketMaterialTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class MaterialMotionTest {
    private val scale = object : MotionDurationScale { override var scaleFactor = 1f }
    @get:Rule val compose = createEmptyComposeRule(effectContext = scale)
    private val gallery = MaterialPage(4)
    private val detail = gallery.copy(detail = true)
    private val state = DanbooruState(DanbooruQuery.resolve("", ContentFilter.GENERAL))

    private inner class Harness : AutoCloseable {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var frame by mutableStateOf(MaterialPageFrame(gallery, state, "fixture"))
        var direction by mutableStateOf(LayoutDirection.Ltr)
        val rendered = mutableMapOf<MaterialPage, MaterialPageFrame>()
        init {
            controller.get().setContentView(ComposeView(controller.get()).apply { setContent {
                PocketMaterialTheme(false) {
                    CompositionLocalProvider(LocalLayoutDirection provides this@Harness.direction) {
                        MaterialPageTransition(this@Harness.frame) { shown, _ ->
                            SideEffect { rendered[shown.page] = shown }
                            Box(Modifier.fillMaxSize()) {
                                Text(shown.state.detail?.let { "post-${it.id}" } ?: shown.state.message ?: "gallery")
                            }
                        }
                    }
                }
            } })
            compose.waitForIdle()
            compose.onNodeWithText("gallery").assertExists()
        }
        fun target(page: MaterialPage, state: DanbooruState = this@MaterialMotionTest.state) {
            compose.runOnIdle { this.frame = MaterialPageFrame(page, state, "fixture") }
            compose.waitForIdle()
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        override fun close() { controller.pause().stop().destroy() }
    }

    @Test fun motionUsesHierarchyBeforeTabIndexAndSameRouteDoesNotNavigate() {
        assertEquals(MaterialPageMotion.NONE, materialPageMotion(gallery, gallery))
        assertEquals(MaterialPageMotion.TAB, materialPageMotion(gallery, MaterialPage(0)))
        assertEquals(MaterialPageMotion.ENTER, materialPageMotion(gallery, detail))
        assertEquals(MaterialPageMotion.RETURN, materialPageMotion(detail, gallery))
        assertEquals(MaterialPageMotion.ENTER, materialPageMotion(detail, detail.copy(settings = true)))
        assertEquals(MaterialPageMotion.RETURN, materialPageMotion(detail.copy(settings = true), detail))
    }

    @Test fun loadingAndContentUpdatesUseTheSamePageWithoutAnAnimation() {
        Harness().use { h ->
            compose.mainClock.autoAdvance = false
            h.target(gallery, state.copy(message = "updated", loading = true))
            assertEquals(h.frame, h.rendered.getValue(gallery))
            compose.onNodeWithText("updated").assertExists()
            compose.onNodeWithTag("material-motion-host").assert(hasStateDescription("页面稳定"))
            compose.onAllNodesWithTag("material-page-tab-4").assertCountEquals(1)
            h.target(detail, state.copy(detail = DanbooruPost(100, emptyMap()), detailLoading = true))
            compose.mainClock.advanceTimeBy(48)
            compose.onNodeWithTag("material-motion-host").assert(hasStateDescription("页面切换中"))
            h.target(detail, state.copy(detail = DanbooruPost(100, emptyMap()), detailLoading = false))
            compose.onNodeWithTag("material-motion-host").assert(hasStateDescription("页面切换中"))
            compose.mainClock.advanceTimeBy(400)
            compose.onNodeWithTag("material-motion-host").assert(hasStateDescription("页面稳定"))
        }
    }

    @Test fun returningRetainsOutgoingDetailSnapshotButHidesItsActionsAndThenDisposesIt() {
        Harness().use { h ->
            compose.mainClock.autoAdvance = false
            h.target(detail, state.copy(detail = DanbooruPost(100, emptyMap())))
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithText("post-100").assertExists()
            h.target(gallery)
            compose.mainClock.advanceTimeBy(48)
            compose.onNodeWithTag("material-motion-host").assert(hasStateDescription("页面切换中"))
            compose.onNodeWithTag("material-page-detail").assertExists()
            assertEquals(100L, h.rendered.getValue(detail).state.detail!!.id)
            compose.onNodeWithText("post-100").assertDoesNotExist()
            compose.mainClock.advanceTimeBy(400)
            compose.onNodeWithTag("material-page-detail").assertDoesNotExist()
            compose.onNodeWithTag("material-motion-host").assert(hasStateDescription("页面稳定"))
        }
    }

    @Test fun interruptedTabTransitionsSettleOnTheLatestTargetWithoutAQueue() {
        Harness().use { h ->
            compose.mainClock.autoAdvance = false
            for (tab in listOf(0, 2, 1, 3, 4)) {
                h.target(MaterialPage(tab))
                compose.mainClock.advanceTimeBy(32)
            }
            compose.mainClock.advanceTimeBy(400)
            compose.onNodeWithTag("material-page-tab-4").assertExists()
            for (tab in 0..3) compose.onNodeWithTag("material-page-tab-$tab").assertDoesNotExist()
            compose.onNodeWithTag("material-motion-host").assert(hasStateDescription("页面稳定"))
        }
    }

    @Test fun enterDirectionFollowsLayoutDirection() {
        Harness().use { h ->
            compose.mainClock.autoAdvance = false
            for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
                h.target(gallery)
                compose.mainClock.advanceTimeBy(400)
                compose.runOnIdle { h.direction = direction }
                h.target(detail, state.copy(detail = DanbooruPost(100, emptyMap())))
                compose.mainClock.advanceTimeBy(32)
                val x = compose.onNodeWithTag("material-page-detail").fetchSemanticsNode().positionInRoot.x
                if (direction == LayoutDirection.Ltr) assertTrue("LTR enters from right: $x", x > 0f)
                else assertTrue("RTL enters from left: $x", x < 0f)
                compose.mainClock.advanceTimeBy(400)
            }
        }
    }

    @Test fun zeroSystemDurationScaleFinishesWithoutWaitingForTheNominalDuration() {
        scale.scaleFactor = 0f
        Harness().use { h ->
            compose.mainClock.autoAdvance = false
            h.target(detail, state.copy(detail = DanbooruPost(100, emptyMap())))
            repeat(3) { compose.mainClock.advanceTimeByFrame() }
            compose.onNodeWithTag("material-motion-host").assert(hasStateDescription("页面稳定"))
            compose.onNodeWithTag("material-page-tab-4").assertDoesNotExist()
            compose.onNodeWithText("post-100").assertExists()
        }
    }
}
