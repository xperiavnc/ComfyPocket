package com.comfyui.mobile.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.ContextWrapper
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NavigationDiagnosticsTest {
    private lateinit var context: Context

    @Before
    fun start() {
        context = RuntimeEnvironment.getApplication()
        NavigationDiagnostics.start(context)
    }

    @Test
    fun routesAreAllowlistedInsteadOfExportingDynamicArguments() {
        val secret = "TOKEN-not-for-diagnostics"
        NavigationDiagnostics.record(
            NavigationDiagnostics.Event.DESTINATION_CHANGED,
            "ai_app/$secret?url=https://example.com"
        )
        val report = NavigationDiagnostics.report(context)
        assertFalse(secret in report)
        assertFalse("https://example.com" in report)
        assertTrue("route=<other>" in report)
    }

    @Test
    fun throwableMessagesAndSuppressedMessagesAreOmitted() {
        val secret = "CIVITAI_KEY-must-not-appear"
        val error = IllegalStateException(secret, IllegalArgumentException(secret))
        error.addSuppressed(RuntimeException(secret))
        NavigationDiagnostics.recordCrash(error)
        val report = NavigationDiagnostics.report(context)
        assertFalse(secret in report)
        assertTrue("java.lang.IllegalStateException" in report)
        assertTrue("java.lang.IllegalArgumentException" in report)
        assertTrue("NavigationDiagnosticsTest" in report)
    }

    @Test
    fun journalIsBoundedAndSurvivesANewActivitySession() {
        repeat(240) {
            NavigationDiagnostics.record(
                NavigationDiagnostics.Event.DESTINATION_CHANGED, "model_hub"
            )
        }
        NavigationDiagnostics.start(context)
        val lines = File(context.filesDir, "return_diagnostics.txt").readLines()
        assertEquals(200, lines.size)
        assertTrue(lines.last().contains("SESSION_STARTED"))
        assertTrue(lines.any { "route=model_hub" in it })
    }

    @Test
    fun systemNativeCrashIsDistinctFromUserExitWithoutExportingTraceData() {
        val secret = "PRIVATE-system-description-and-trace"
        val manager = context.getSystemService(ActivityManager::class.java)
        shadowOf(manager).addApplicationExitInfo(
            ApplicationExitInfoBuilder.newBuilder()
                .setTimestamp(1_792_166_400_000)
                .setReason(ApplicationExitInfo.REASON_CRASH_NATIVE)
                .setStatus(11)
                .setDescription(secret)
                .setTraceInputStream(ByteArrayInputStream(secret.toByteArray()))
                .build()
        )
        shadowOf(manager).addApplicationExitInfo(
            ApplicationExitInfoBuilder.newBuilder()
                .setTimestamp(1_792_166_401_000)
                .setReason(ApplicationExitInfo.REASON_USER_REQUESTED)
                .setStatus(0)
                .build()
        )
        val report = NavigationDiagnostics.report(context)
        assertTrue("reason=CRASH_NATIVE(5) status=11" in report)
        assertTrue("reason=USER_REQUESTED(10) status=0" in report)
        assertFalse(secret in report)
        assertTrue(report.indexOf("reason=USER_REQUESTED") < report.indexOf("reason=CRASH_NATIVE"))
    }

    @Test
    fun anIoFailureInDiagnosticsDoesNotEscapeIntoNavigation() {
        val notDirectory = File(context.cacheDir, "not-a-diagnostic-directory").apply {
            writeText("file")
        }
        val brokenStorage = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = notDirectory
        }
        NavigationDiagnostics.start(brokenStorage)
        NavigationDiagnostics.record(NavigationDiagnostics.Event.MARKET_BACK_SYSTEM, "model_hub")
        NavigationDiagnostics.recordCrash(IllegalStateException("message"))
        val report = NavigationDiagnostics.report(brokenStorage)
        assertTrue(report.contains("ComfyPocket return diagnostics"))
        NavigationDiagnostics.start(context)
    }
}
