package com.comfyui.mobile

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.comfyui.mobile.diagnostics.NavigationDiagnostics
import com.comfyui.mobile.ui.MainViewModel
import com.comfyui.mobile.ui.navigation.PocketApp

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()


    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        NavigationDiagnostics.start(this)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            NavigationDiagnostics.recordCrash(throwable)
            android.util.Log.e("ComfyCrash", "FATAL UNCAUGHT EXCEPTION", throwable)
            val sw = java.io.StringWriter()
            throwable.printStackTrace(java.io.PrintWriter(sw))
            val trace = sw.toString()
            try {
                val f = java.io.File(getExternalFilesDir(null), "comfy_crash.txt")
                f.writeText(trace)
                val f2 = java.io.File(filesDir, "comfy_crash.txt")
                f2.writeText(trace)
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        try {
            requestNotificationPermissionIfNeeded()

            lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_START) {
                        viewModel.ensureConnected(forceProbe = true)
                        viewModel.startForegroundHeartbeat()
                    } else if (event == Lifecycle.Event.ON_STOP) {
                        viewModel.stopForegroundHeartbeat()
                    }
                }
            )

            setContent {
                val navEventOwner = this as NavigationEventDispatcherOwner
                CompositionLocalProvider(
                    LocalNavigationEventDispatcherOwner provides navEventOwner
    ) {
                    val uiScale by viewModel.uiScale.collectAsState()
                    val baseDensity = LocalDensity.current
                    CompositionLocalProvider(
                        LocalDensity provides Density(baseDensity.density * uiScale, baseDensity.fontScale)
                    ) {
                        MainScreen(viewModel)
                    }
                }
            }
        } catch (t: Throwable) {
            android.util.Log.e("ComfyCrash", "CRASH IN ONCREATE", t)
            val sw = java.io.StringWriter()
            t.printStackTrace(java.io.PrintWriter(sw))
            val trace = sw.toString()
            val sv = android.widget.ScrollView(this)
            val tv = android.widget.TextView(this).apply {
                text = "应用启动捕获到异常：\n\n$trace"
                setTextIsSelectable(true)
                setPadding(50, 100, 50, 50)
                textSize = 14f
                setTextColor(android.graphics.Color.RED)
            }
            sv.addView(tv)
            setContentView(sv)
        }
    }

    override fun onStop() {
        NavigationDiagnostics.record(
            NavigationDiagnostics.Event.ACTIVITY_STOPPED,
            finishing = isFinishing, changingConfiguration = isChangingConfigurations
        )
        super.onStop()
    }

    override fun onDestroy() {
        NavigationDiagnostics.record(
            NavigationDiagnostics.Event.ACTIVITY_DESTROYED,
            finishing = isFinishing, changingConfiguration = isChangingConfigurations
        )
        super.onDestroy()
    }
}

@Composable
fun MainScreen(viewModel: MainViewModel) = PocketApp(viewModel)

/** Keep persisted route names compatible with pre-P2 navigation saved state. */
sealed class Screen(val route: String, val label: String) {
    data object Dashboard : Screen("控制面板", "主页")
    data object Control : Screen("控制器", "控制")
    data object Agent : Screen("agent", "Agent")
    data object AiApps : Screen("AI应用", "应用")
    data object Gallery : Screen("画廊", "画廊")
}
