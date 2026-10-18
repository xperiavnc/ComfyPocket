package com.comfyui.mobile.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.comfyui.mobile.Screen
import com.comfyui.mobile.ui.designsystem.PocketMaterialTheme
import com.comfyui.mobile.ui.theme.BottomBarStyle
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class PocketMotionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var nav: NavHostController
    private val style = mutableStateOf(BottomBarStyle.MATERIAL)
    private val direction = mutableStateOf(LayoutDirection.Ltr)

    private fun host(activity: ComponentActivity) {
        activity.setContentView(ComposeView(activity).apply {
            setContent {
                CompositionLocalProvider(LocalLayoutDirection provides direction.value) {
                    PocketMaterialTheme(false) {
                        val controller = rememberNavController()
                        SideEffect { nav = controller }
                        PocketNavigationShell(controller, bottomBarStyle = style.value) { entry ->
                            val route = entry.destination.route.orEmpty()
                            Box(Modifier.fillMaxSize().testTag("motion-page-$route")) { Text(route) }
                        }
                    }
                }
            }
        })
        compose.waitForIdle()
    }

    private fun select(index: Int) {
        compose.onNodeWithTag("pocket-tab-$index").performTouchInput { click() }
        compose.waitForIdle()
    }

    /** Sample the actual NavHost placement, not only an enum/helper's return value. */
    private fun assertSwitch(from: Int, to: Int, rtl: Boolean = false) {
        select(from)
        val resting = compose.onNodeWithTag("motion-page-${pocketTabs[from].route}")
            .fetchSemanticsNode().positionInRoot.x
        compose.mainClock.autoAdvance = false
        select(to)
        compose.mainClock.advanceTimeBy(80)
        val incoming = compose.onNodeWithTag("motion-page-${pocketTabs[to].route}")
            .fetchSemanticsNode().positionInRoot.x
        val expectedSign = (if (to > from) 1 else -1) * (if (rtl) -1 else 1)
        assertTrue("${style.value} $from -> $to, rtl=$rtl: incoming=$incoming, rest=$resting",
            (incoming - resting) * expectedSign > 1f)
        compose.mainClock.advanceTimeBy(500)
        assertEquals(resting, compose.onNodeWithTag("motion-page-${pocketTabs[to].route}")
            .fetchSemanticsNode().positionInRoot.x, 1f)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    @Test fun appsToAgentEntersFromLeftWithEitherBottomBar() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            host(activity.get())
            for (bar in BottomBarStyle.entries) {
                compose.runOnIdle { style.value = bar }
                assertSwitch(3, 2)
            }
        } finally {
            compose.mainClock.autoAdvance = true
            activity.pause().stop().destroy()
        }
    }

    @Test fun adjacentAndLongTabSwitchesFollowTabOrderInBothDirections() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            host(activity.get())
            for (bar in BottomBarStyle.entries) {
                compose.runOnIdle { style.value = bar }
                for ((from, to) in listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4,
                    4 to 3, 3 to 2, 2 to 1, 1 to 0, 0 to 4, 4 to 0)) {
                    assertSwitch(from, to)
                }
            }
        } finally {
            compose.mainClock.autoAdvance = true
            activity.pause().stop().destroy()
        }
    }

    @Test fun rtlMirrorsTabDirectionForBothBars() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            direction.value = LayoutDirection.Rtl
            host(activity.get())
            for (bar in BottomBarStyle.entries) {
                compose.runOnIdle { style.value = bar }
                assertSwitch(3, 2, rtl = true)
                assertSwitch(0, 4, rtl = true)
            }
        } finally {
            compose.mainClock.autoAdvance = true
            activity.pause().stop().destroy()
        }
    }

    @Test fun rootBackAndSecondaryPushPopKeepTheirOwnDirection() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            host(activity.get())
            select(3)
            val rest = compose.onNodeWithTag("motion-page-${Screen.AiApps.route}")
                .fetchSemanticsNode().positionInRoot.x
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { nav.navigate("settings") }
            compose.waitForIdle(); compose.mainClock.advanceTimeBy(80)
            assertTrue(compose.onNodeWithTag("motion-page-settings").fetchSemanticsNode().positionInRoot.x > rest + 1)
            compose.mainClock.advanceTimeBy(500)
            compose.runOnIdle { nav.popBackStack() }
            compose.waitForIdle(); compose.mainClock.advanceTimeBy(80)
            assertTrue(compose.onNodeWithTag("motion-page-${Screen.AiApps.route}").fetchSemanticsNode().positionInRoot.x < rest - 1)
            compose.mainClock.advanceTimeBy(500)
            compose.runOnIdle { activity.get().onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle(); compose.mainClock.advanceTimeBy(80)
            assertTrue("Root Back is a tab switch to the left, not a forward push",
                compose.onNodeWithTag("motion-page-${Screen.Dashboard.route}").fetchSemanticsNode().positionInRoot.x < rest - 1)
            compose.mainClock.advanceTimeBy(500)
            assertEquals(Screen.Dashboard.route, nav.currentDestination?.route)
            assertFalse(activity.get().isFinishing)
        } finally {
            compose.mainClock.autoAdvance = true
            activity.pause().stop().destroy()
        }
    }
}
