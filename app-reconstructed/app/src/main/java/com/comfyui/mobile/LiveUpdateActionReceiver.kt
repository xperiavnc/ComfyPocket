package com.comfyui.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.comfyui.mobile.data.AppModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Live Update actions. Stop = interrupt current + clear entire queue (all tasks).
 *
 * Order matters:
 * 1. Latch first so the busy-watcher cannot restart the FGS.
 * 2. Cancel local/cloud work while the FGS still keeps the process alive.
 * 3. Then tear down the FGS (and its notification id) before posting "终止".
 * Replacing the FGS notification with a non-ongoing one while the service is started
 * crashes ColorOS 流体云 / HyperOS 焦点通知 / Android 14+ FGS checks.
 */
class LiveUpdateActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_STOP) return
        val app = context.applicationContext
        val pendingResult = goAsync()
        try {
            GenerationLiveUpdate.beginUserStop()
            val repo = AppModule.provideRepository()
            runCatching { repo.setIsGenerating(false) }
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    withTimeoutOrNull(8_000L) {
                        if (!repo.cloudActive) {
                            runCatching { repo.interrupt() }
                            runCatching { repo.clearQueue() }
                            runCatching { repo.interrupt() }
                        }
                    }
                    runCatching { repo.webSocketManager.resetAfterStop() }
                    runCatching { repo.notifyExternalStop() }
                    // Let the ViewModel collector run cancelRunning on Main before we drop the FGS.
                    delay(300L)
                    KeepAliveService.stop(app)
                    delay(100L)
                    runCatching { GenerationLiveUpdate.notifyFailedThenDismiss(app) }
                } catch (e: Exception) {
                    Log.e("LiveUpdateStop", "stop all failed", e)
                    runCatching { AppModule.provideRepository().webSocketManager.resetAfterStop() }
                    KeepAliveService.stop(app)
                    runCatching { GenerationLiveUpdate.notifyFailedThenDismiss(app) }
                } finally {
                    try {
                        pendingResult.finish()
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e("LiveUpdateStop", "onReceive failed", t)
            runCatching { KeepAliveService.stop(app) }
            try {
                pendingResult.finish()
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        const val ACTION_STOP = "com.comfyui.mobile.action.LIVE_UPDATE_STOP"
    }
}
