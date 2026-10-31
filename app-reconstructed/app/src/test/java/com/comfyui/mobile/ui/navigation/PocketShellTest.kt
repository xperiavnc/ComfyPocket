@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.comfyui.mobile.ui.navigation

import android.os.Bundle
import android.os.Parcel
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.comfyui.mobile.Screen
import com.comfyui.mobile.ui.designsystem.PocketMaterialTheme
import com.comfyui.mobile.ui.feedback.AppToast
import com.comfyui.mobile.ui.theme.BottomBarStyle
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class PocketShellTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var nav: NavHostController
    private var leafBacks = 0
    private var selections = 0
    private val barStyle = mutableStateOf(BottomBarStyle.MATERIAL)

    @Composable private fun Fixture() {
        val controller = rememberNavController()
        SideEffect { nav = controller }
        PocketMaterialTheme(false) {
            PocketNavigationShell(controller, { selections++ }, bottomBarStyle = barStyle.value) { entry ->
                val route = entry.destination.route.orEmpty()
                var input by rememberSaveable { mutableStateOf("") }
                var panel by rememberSaveable { mutableStateOf(false) }
                val list = rememberLazyListState()
                PredictiveBackHandler(enabled = panel) { progress -> progress.collect {}; leafBacks++; panel = false }
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    Text(route, Modifier.testTag("fixture-title"))
                    OutlinedTextField(input, { input = it }, Modifier.testTag("fixture-input"))
                    if (panel) Text("面板已打开", Modifier.testTag("fixture-panel"))
                    Button({ panel = true }, Modifier.testTag("fixture-open-panel")) { Text("面板") }
                    Button({ controller.navigate("ai_app/example") }, Modifier.testTag("fixture-detail")) { Text("详情") }
                    Button({ controller.navigate("settings") }, Modifier.testTag("fixture-settings")) { Text("设置") }
                    Button({ controller.popBackStack() }, Modifier.testTag("fixture-back")) { Text("返回") }
                    LazyColumn(Modifier.weight(1f).testTag("fixture-list"), state = list) {
                        items(80) { Text("项目 $it", Modifier.fillMaxWidth().height(64.dp).testTag("fixture-row-$it")) }
                    }
                }
            }
        }
    }

    private fun host(activity: ComponentActivity) {
        activity.setContentView(ComposeView(activity).apply { id = android.R.id.primary; setContent { Fixture() } })
        compose.waitForIdle()
    }
    private fun tab(index: Int) { compose.onNodeWithTag("pocket-tab-$index").performClick(); compose.waitForIdle() }
    private fun back(activity: ComponentActivity) { compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle() }
    private fun gesture(activity: ComponentActivity, cancel: Boolean) {
        compose.runOnIdle {
            activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 100f, 0f, BackEventCompat.EDGE_LEFT))
            activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(120f, 100f, 0.6f, BackEventCompat.EDGE_LEFT))
            if (cancel) activity.onBackPressedDispatcher.dispatchOnBackCancelled()
            else activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
    }

    @Test fun fiveEntriesRetainInputAndScrollAndReselectDoesNotNavigate() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            host(activity.get())
            pocketTabs.indices.forEach { index ->
                tab(index)
                compose.onNodeWithTag("fixture-input").performTextInput("草稿-$index")
                compose.onNodeWithTag("fixture-list").performScrollToIndex(25 + index)
            }
            pocketTabs.indices.forEach { index ->
                tab(index)
                compose.onNodeWithTag("fixture-input").assertTextContains("草稿-$index")
                compose.onNodeWithTag("fixture-row-${25 + index}").assertIsDisplayed()
                val id = nav.currentBackStackEntry!!.id
                val count = selections
                tab(index)
                assertEquals(id, nav.currentBackStackEntry!!.id)
                assertEquals(count, selections)
            }
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun rootBackSavesDraftAndCancellationDoesNotChangeEntry() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            host(activity.get()); tab(2)
            compose.onNodeWithTag("fixture-input").performTextInput("Agent 未发送草稿")
            gesture(activity.get(), true)
            assertEquals(Screen.Agent.route, nav.currentDestination?.route)
            gesture(activity.get(), false)
            assertEquals(Screen.Dashboard.route, nav.currentDestination?.route)
            assertFalse(activity.get().isFinishing)
            tab(2)
            compose.onNodeWithTag("fixture-input").assertTextContains("Agent 未发送草稿")
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun changingBarStyleDoesNotNavigateOrResetDraftAndListPosition() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            host(activity.get()); tab(2)
            compose.onNodeWithTag("fixture-input").performTextInput("共享页面状态")
            compose.onNodeWithTag("fixture-list").performScrollToIndex(29)
            val entryId = nav.currentBackStackEntry!!.id
            val callbacks = selections
            for (style in listOf(BottomBarStyle.CLASSIC, BottomBarStyle.MATERIAL)) {
                compose.runOnIdle { barStyle.value = style }
                compose.waitForIdle()
                assertEquals(entryId, nav.currentBackStackEntry!!.id)
                assertEquals(callbacks, selections)
                compose.onNodeWithTag("fixture-input").assertTextContains("共享页面状态")
                compose.onNodeWithTag("fixture-row-29").assertIsDisplayed()
                compose.onNodeWithTag("pocket-tab-2").assertIsSelected()
            }
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun leafPanelThenDetailThenRootConsumeExactlyOneLayer() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            host(activity.get()); tab(3)
            compose.onNodeWithTag("fixture-detail").performClick(); compose.waitForIdle()
            compose.onNodeWithTag("pocket-navigation").assertDoesNotExist()
            compose.onNodeWithTag("fixture-open-panel").performClick()
            gesture(activity.get(), true)
            compose.onNodeWithTag("fixture-panel").assertExists()
            gesture(activity.get(), false)
            assertEquals(1, leafBacks)
            assertEquals("ai_app/{id}", nav.currentDestination?.route)
            back(activity.get())
            assertEquals(Screen.AiApps.route, nav.currentDestination?.route)
            back(activity.get())
            assertEquals(Screen.Dashboard.route, nav.currentDestination?.route)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun tabStacksAndNestedEntriesSurviveSaveAndFreshActivityRestore() {
        var activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            host(activity.get()); tab(2)
            compose.onNodeWithTag("fixture-input").performTextInput("恢复后的 Agent")
            tab(3)
            compose.onNodeWithTag("fixture-detail").performClick(); compose.waitForIdle()
            compose.onNodeWithTag("fixture-input").performTextInput("详情草稿")
            compose.onNodeWithTag("fixture-input").assertTextContains("详情草稿")
            compose.onNodeWithTag("fixture-settings").performClick(); compose.waitForIdle()
            val state = Bundle()
            activity.saveInstanceState(state)
            // Model the process boundary, not aliased in-memory maps later cleared by old VMs.
            val parcel = Parcel.obtain()
            val restored = try {
                parcel.writeBundle(state)
                parcel.setDataPosition(0)
                parcel.readBundle(javaClass.classLoader)!!
            } finally {
                parcel.recycle()
            }
            activity.pause().stop().destroy()
            activity = Robolectric.buildActivity(ComponentActivity::class.java).create(restored).start().restoreInstanceState(restored).resume().visible()
            host(activity.get())
            assertEquals("settings", nav.currentDestination?.route)
            back(activity.get())
            assertEquals("example", nav.currentBackStackEntry?.arguments?.getString("id"))
            compose.onNodeWithTag("fixture-input").assertTextContains("详情草稿")
            back(activity.get()); tab(2)
            compose.onNodeWithTag("fixture-input").assertTextContains("恢复后的 Agent")
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun rapidSwitchesConvergeAndFixedBarDoesNotAnimateWithPages() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            host(activity.get())
            val bar = compose.onNodeWithTag("pocket-navigation").fetchSemanticsNode().boundsInRoot
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("pocket-tab-4").performClick()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(80)
            assertEquals(bar, compose.onNodeWithTag("pocket-navigation").fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithTag("pocket-tab-1").performClick()
            compose.onNodeWithTag("pocket-tab-2").performClick()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(500)
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            assertEquals(Screen.Agent.route, nav.currentDestination?.route)
            compose.onAllNodesWithTag("fixture-input").assertCountEquals(1)
            assertEquals(bar, compose.onNodeWithTag("pocket-navigation").fetchSemanticsNode().boundsInRoot)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun selectedIndicatorMovesThroughIntermediatePositionAndSettlesOnTarget() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            host(activity.get())
            val start = compose.onNodeWithTag("pocket-navigation-indicator").fetchSemanticsNode().boundsInRoot
            compose.mainClock.autoAdvance = false
            tab(4)
            compose.mainClock.advanceTimeBy(80)
            val middle = compose.onNodeWithTag("pocket-navigation-indicator").fetchSemanticsNode().boundsInRoot
            assertTrue("indicator should move before the transition completes", middle.left > start.left)
            compose.mainClock.advanceTimeBy(1000)
            val settled = compose.onNodeWithTag("pocket-navigation-indicator").fetchSemanticsNode().boundsInRoot
            assertTrue("indicator should settle farther toward the selected tab", settled.left > middle.left)
            compose.mainClock.autoAdvance = true
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun globalFeedbackSurvivesNavigationAndLatestMessageReplacesPrevious() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            host(activity.get())
            ShadowToast.reset()
            compose.runOnIdle { AppToast.show(activity.get(), "已保存配置") }
            compose.waitForIdle()
            assertEquals("已保存配置", ShadowToast.getTextOfLatestToast())
            assertEquals(1, ShadowToast.shownToastCount())
            tab(2)
            assertEquals(1, ShadowToast.shownToastCount())
            compose.runOnIdle { AppToast.show(activity.get(), "保存失败，草稿已保留") }
            compose.waitForIdle()
            assertEquals("保存失败，草稿已保留", ShadowToast.getTextOfLatestToast())
            assertEquals(2, ShadowToast.shownToastCount())
            compose.onNodeWithTag("pocket-feedback").assertDoesNotExist()
            compose.onNodeWithText("已保存配置").assertDoesNotExist()
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun destinationLifecycleDisablesOutgoingOwnerUntilActiveAgain() {
        val parent = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        parent.registry.currentState = Lifecycle.State.RESUMED
        val child = PocketDestinationLifecycle(parent.lifecycle)
        assertEquals(Lifecycle.State.CREATED, child.lifecycle.currentState)
        child.active = true
        assertEquals(Lifecycle.State.RESUMED, child.lifecycle.currentState)
        child.active = false
        assertEquals(Lifecycle.State.CREATED, child.lifecycle.currentState)
        parent.registry.currentState = Lifecycle.State.CREATED
        child.active = true
        assertEquals(Lifecycle.State.CREATED, child.lifecycle.currentState)
        parent.registry.currentState = Lifecycle.State.RESUMED
        assertEquals(Lifecycle.State.RESUMED, child.lifecycle.currentState)
        child.close()
        assertEquals(Lifecycle.State.DESTROYED, child.lifecycle.currentState)
    }

    @Test fun outgoingPanelDoesNotStealBackFromNewDestinationDuringAnimation() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            host(activity.get()); tab(2)
            compose.onNodeWithTag("fixture-open-panel").performClick()
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("fixture-settings").performClick()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(80)
            compose.runOnIdle { activity.get().onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(500)
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            assertEquals(Screen.Agent.route, nav.currentDestination?.route)
            assertEquals(0, leafBacks)
            compose.onNodeWithTag("fixture-panel").assertExists()
            back(activity.get())
            assertEquals(1, leafBacks)
            assertEquals(Screen.Agent.route, nav.currentDestination?.route)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun dashboardRootDelegatesExitToSystemWithoutAddingANavigationLayer() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            host(activity.get())
            back(activity.get())
            assertTrue(activity.get().isFinishing)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun sharedAxisMotionIsShortFadeThroughInBothDirections() {
        val forward = pocketPageMotion(pop = false)
        val back = pocketPageMotion(pop = true)
        assertEquals(300, forward.durationMillis)
        // Outgoing fades out before the incoming fades in: no translucent overlap.
        assertTrue(forward.exitFadeMillis < forward.durationMillis / 2)
        assertEquals(83, forward.enterOffset(1000))
        assertEquals(-83, forward.exitOffset(1000))
        assertEquals(-83, back.enterOffset(1000))
        assertEquals(83, back.exitOffset(1000))
        assertEquals(-83, pocketPageMotion(false, rtl = true).enterOffset(1000))
        assertEquals(83, pocketPageMotion(true, rtl = true).enterOffset(1000))
    }

    @Test fun onlyTabToTabIsALateralSwitch() {
        assertTrue(pocketIsTabSwitch(Screen.Dashboard.route, Screen.Gallery.route))
        assertFalse(pocketIsTabSwitch(Screen.Dashboard.route, "settings"))
        assertFalse(pocketIsTabSwitch("settings", Screen.Dashboard.route))
        assertFalse(pocketIsTabSwitch("settings", "theme"))
    }
}
