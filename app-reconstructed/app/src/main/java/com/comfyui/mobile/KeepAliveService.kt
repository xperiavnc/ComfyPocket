package com.comfyui.mobile

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.comfyui.mobile.data.AppModule
import com.comfyui.mobile.data.SecretStore
import com.comfyui.mobile.model.ServerProfile
import com.comfyui.mobile.network.SecretRedactor
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class KeepAliveService : Service() {
    private val repository by lazy { AppModule.provideRepository() }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private var liveJob: Job? = null
    private var executionStartJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning.set(true)
        // startForegroundService requires startForeground within a few seconds. onStartCommand
        // may not have run yet if a stop raced in; posting here closes that window.
        enterForegroundSafe()
        if (stopRequested.get() || GenerationLiveUpdate.isUserStopRequested()) {
            return
        }
        // Resolve the real current server profile (saved list + current id). Never rebuild a
        // profile from the legacy "ip" pref: for RunningHub it only holds the site host and would
        // produce a URL without /proxy/{key}. If nothing valid can be resolved, do nothing.
        val targetUrl = resolveCurrentServerBaseUrl()
        if (targetUrl != null) {
            try {
                repository.setBaseUrlOnly(targetUrl)
                // Cloud profile: tasks are tracked by polling the official task API; never a WebSocket.
                if (!repository.cloudActive && !repository.webSocketManager.isSocketLive()) {
                    repository.webSocketManager.softEnsure(targetUrl)
                }
            } catch (e: Exception) {
                Log.w("KeepAliveService", "initial ensure failed: ${SecretRedactor.redact(e.message)}")
            }
        }
    }

    private fun resolveCurrentServerBaseUrl(): String? {
        return try {
            val prefs = getSharedPreferences("comfy_prefs", Context.MODE_PRIVATE)
            val raw = prefs.getString("servers_json", null)
            if (raw.isNullOrBlank()) return null
            val currentId = prefs.getString("current_server_id", "").orEmpty()
            if (currentId.isBlank()) return null
            val type = object : TypeToken<List<ServerProfile>>() {}.type
            val list: List<ServerProfile> = Gson().fromJson(raw, type) ?: emptyList()
            val server = list.firstOrNull { it.effectiveId == currentId } ?: return null
            val resolved = if (server.isRunningHub) {
                val stored = SecretStore.get(this).getRhKey(server.effectiveId)
                if (!stored.isNullOrBlank()) server.copy(rhApiKey = stored) else server
            } else server
            if (resolved.isRunningHub && resolved.effectiveRhApiKey.isBlank()) return null
            resolved.baseUrl.takeIf { SecretRedactor.isValidBaseUrl(it) }
        } catch (e: Exception) {
            Log.w("KeepAliveService", "resolve current server failed: ${e.javaClass.simpleName}")
            null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isServiceRunning.set(true)
        enterForegroundSafe()
        if (stopRequested.get() || GenerationLiveUpdate.isUserStopRequested() ||
            intent?.action == ACTION_STOP
        ) {
            leaveForegroundAndStop()
            return START_NOT_STICKY
        }

        if (executionStartJob == null) {
            executionStartJob = serviceScope.launch {
                repository.webSocketManager.executionStart.collect {
                    GenerationLiveUpdate.onExecutionStart()
                }
            }
        }
        if (monitorJob == null) {
            monitorJob = serviceScope.launch {
                while (isActive) {
                    val base = repository.baseUrl.value
                    if (!repository.cloudActive && SecretRedactor.isValidBaseUrl(base)) {
                        // Soft keep-alive in background while generating; do not tear down a live socket.
                        try {
                            repository.webSocketManager.softEnsure(base)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w("KeepAliveService", "softEnsure failed: ${SecretRedactor.redact(e.message)}")
                        }
                    }
                    delay(5_000)
                }
            }
        }
        if (liveJob == null) {
            liveJob = serviceScope.launch {
                combine(
                    repository.webSocketManager.progress,
                    repository.webSocketManager.queueSize,
                    repository.webSocketManager.executingNodeId,
                    repository.isGenerating
    ) { progress, queueSize, executingNodeId, isGenerating ->
                    val isExecuting = executingNodeId != null
                    val isBusy = GenerationLiveUpdate.isBusy(
                        progress = progress,
                        queueSize = queueSize,
                        isExecuting = isExecuting,
                        isGenerating = isGenerating
    )
                    LiveState(progress, queueSize, isExecuting, isBusy)
                }.collectLatest { state ->
                    if (state.isBusy) {
                        GenerationLiveUpdate.update(
                            applicationContext,
                            state.progress,
                            state.queueSize,
                            state.isExecuting,
                            isGenerating = true
    )
                    } else {
                        // Debounce before declaring idle / stopping service!
                        // Transient 0 state between nodes or batch prompts must not kill the service!
                        delay(2_000L)
                        val stillGenerating = repository.isGenerating.value
                        val stillQueue = repository.webSocketManager.queueSize.value
                        val stillExec = repository.webSocketManager.executingNodeId.value != null
                        val stillProg = repository.webSocketManager.progress.value
                        val stillBusy = GenerationLiveUpdate.isBusy(
                            progress = stillProg,
                            queueSize = stillQueue,
                            isExecuting = stillExec,
                            isGenerating = stillGenerating
    )
                        if (!stillBusy) {
                            GenerationLiveUpdate.update(
                                applicationContext,
                                stillProg,
                                stillQueue,
                                stillExec,
                                isGenerating = false
    )
                            stopForegroundCompat()
                            stopSelf()
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private data class LiveState(
        val progress: Float,
        val queueSize: Int,
        val isExecuting: Boolean,
        val isBusy: Boolean
    )

    private fun enterForegroundSafe() {
        try {
            enterForeground(GenerationLiveUpdate.buildProgressNotification(this, 0f, 1, true))
        } catch (t: Throwable) {
            Log.w("KeepAliveService", "progress notification failed: ${t.javaClass.simpleName}")
            val fallback = androidx.core.app.NotificationCompat.Builder(this, "comfy_live_update_v2")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("ComfyUI")
                .setContentText("任务进行中")
                .setOngoing(true)
                .setSilent(true)
                .build()
            enterForeground(fallback)
        }
    }

    private fun enterForeground(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                GenerationLiveUpdate.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    )
        } else {
            startForeground(GenerationLiveUpdate.NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
    }

    private fun leaveForegroundAndStop() {
        isServiceRunning.set(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isServiceRunning.set(false)
        monitorJob?.cancel()
        monitorJob = null
        liveJob?.cancel()
        liveJob = null
        executionStartJob?.cancel()
        executionStartJob = null
        if (stopRequested.get() || GenerationLiveUpdate.isUserStopRequested()) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            }
            GenerationLiveUpdate.cancelLive(applicationContext)
        } else {
            GenerationLiveUpdate.cancelLiveIfNotDismissing(applicationContext)
        }
        serviceScope.coroutineContext.cancel()
        super.onDestroy()
    }

    companion object {
        val isServiceRunning = AtomicBoolean(false)
        private val stopRequested = AtomicBoolean(false)
        private const val ACTION_STOP = "com.comfyui.mobile.action.KEEP_ALIVE_STOP"

        fun start(context: Context) {
            if (GenerationLiveUpdate.isUserStopRequested() || stopRequested.get()) return
            val appContext = context.applicationContext
            val intent = Intent(appContext, KeepAliveService::class.java)
            // Mark running before the start call so a concurrent watcher cannot issue a second
            // startForegroundService (DidNotStartInTime if one of them is then stopped).
            isServiceRunning.set(true)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            } catch (_: Throwable) {
                isServiceRunning.set(false)
            }
        }

        fun stop(context: Context) {
            stopRequested.set(true)
            isServiceRunning.set(false)
            val appContext = context.applicationContext
            try {
                appContext.stopService(Intent(appContext, KeepAliveService::class.java))
            } catch (_: Throwable) {
            }
        }

        fun clearStopRequest() {
            stopRequested.set(false)
        }
    }
}
