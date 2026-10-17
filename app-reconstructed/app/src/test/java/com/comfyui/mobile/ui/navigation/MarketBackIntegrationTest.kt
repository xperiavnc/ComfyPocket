package com.comfyui.mobile.ui.navigation

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.BackEventCompat
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.comfyui.mobile.MainActivity
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import com.comfyui.mobile.diagnostics.NavigationDiagnostics
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
// Robolectric's bundled Skia does not support Miuix's AGSL layout(color).
// Exercise Android/Compose navigation here, not hardware shader correctness.
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class MarketBackIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var activity: MainActivity

    @Before
    fun launch() {
        val app = RuntimeEnvironment.getApplication()
        // No external API or user settings are needed for the return path.
        app.getSharedPreferences("civitai_market", Context.MODE_PRIVATE)
            .edit().putString("mode", "BRIDGE").commit()
        controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        activity = controller.get()
        compose.waitForIdle()
    }

    @After
    fun close() {
        if (::controller.isInitialized) controller.pause().stop().destroy()
    }

    private fun openMarket() {
        compose.onNodeWithText("集市 ↗").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Civitai 市场").assertExists()
    }

    private fun assertDashboard() {
        compose.waitForIdle()
        compose.onNodeWithText("集市 ↗").assertExists()
        assertFalse("Returning from market must not finish the Activity", activity.isFinishing)
    }

    @Test
    fun completedGestureReturnsToDashboard() {
        openMarket()
        compose.runOnIdle {
            activity.onBackPressedDispatcher.dispatchOnBackStarted(
                BackEventCompat(0f, 200f, 0f, BackEventCompat.EDGE_LEFT)
            )
            activity.onBackPressedDispatcher.dispatchOnBackProgressed(
                BackEventCompat(150f, 200f, 0.6f, BackEventCompat.EDGE_LEFT)
            )
            activity.onBackPressedDispatcher.onBackPressed()
        }
        assertDashboard()
        val trace = NavigationDiagnostics.report(activity).substringAfterLast("SESSION_STARTED")
        assertEquals(1, trace.lineSequence().count { " MARKET_BACK_SYSTEM " in it })
        assertTrue(trace.contains("DESTINATION_CHANGED") && trace.contains("route=dashboard"))
        assertTrue(trace.contains("MARKET_PAGE_DISPOSED"))
        assertTrue(trace.contains("MARKET_REPOSITORY_CLOSED"))
    }

    @Test
    fun cancelledGestureStaysInMarket() {
        openMarket()
        compose.runOnIdle {
            activity.onBackPressedDispatcher.dispatchOnBackStarted(
                BackEventCompat(0f, 200f, 0f, BackEventCompat.EDGE_LEFT)
            )
            activity.onBackPressedDispatcher.dispatchOnBackProgressed(
                BackEventCompat(60f, 200f, 0.3f, BackEventCompat.EDGE_LEFT)
            )
            activity.onBackPressedDispatcher.dispatchOnBackCancelled()
        }
        compose.waitForIdle()
        compose.onNodeWithText("Civitai 市场").assertExists()
        assertFalse(activity.isFinishing)
        val trace = NavigationDiagnostics.report(activity).substringAfterLast("SESSION_STARTED")
        assertFalse("Cancelling a gesture must not run the return action", " MARKET_BACK_SYSTEM " in trace)
    }

    @Test
    fun toolbarReturnsToDashboardAndMarketCanReopen() {
        openMarket()
        compose.onNodeWithContentDescription("返回").performClick()
        assertDashboard()
        openMarket()
        compose.onNodeWithContentDescription("返回").performClick()
        assertDashboard()
    }

    @Test
    fun backDismissesSettingsBeforeLeavingMarket() {
        openMarket()
        compose.onNodeWithContentDescription("集市连接、代理与 Key 设置").performClick()
        compose.waitForIdle()
        compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("Civitai 市场").assertExists()
        assertFalse(activity.isFinishing)
        val trace = NavigationDiagnostics.report(activity).substringAfterLast("SESSION_STARTED")
        assertFalse("The market handler must yield to the dialog", " MARKET_BACK_SYSTEM " in trace)
        compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        assertDashboard()
    }

    @Test
    fun backStillReturnsAfterBackgroundAndResume() {
        openMarket()
        controller.pause().stop().restart().start().resume().visible()
        compose.waitForIdle()
        compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        assertDashboard()
    }

    @Test
    fun diagnosticReportCanBeSavedFromSettingsAfterMarketReturn() {
        openMarket()
        compose.onNodeWithContentDescription("返回").performClick()
        assertDashboard()
        compose.onNodeWithContentDescription("设置").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("导出返回/退出诊断").performScrollTo().performClick()
        val request = shadowOf(activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, request.intent.action)
        assertEquals("text/plain", request.intent.type)
        assertTrue(request.intent.getStringExtra(Intent.EXTRA_TITLE)!!.endsWith(".txt"))
        val target = File(activity.cacheDir, "return-export-test.txt")
        // The document provider is a test fixture; the Activity result, exporter and file write are real.
        val uri = Uri.parse("content://diagnostic-test/documents/report.txt")
        shadowOf(activity.contentResolver).registerOutputStreamSupplier(uri) { target.outputStream() }
        compose.runOnIdle {
            shadowOf(activity).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(uri))
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            target.exists() && target.readText().endsWith(
                "- An older system exit record is not evidence that the latest return crashed.\n"
            )
        }
        val report = target.readText()
        assertTrue("MARKET_BACK_TOOLBAR" in report)
        assertTrue("DESTINATION_CHANGED" in report)
        assertTrue("route=dashboard" in report)
        assertTrue("0.0.1-return-diagnostic" in report)
    }
}
