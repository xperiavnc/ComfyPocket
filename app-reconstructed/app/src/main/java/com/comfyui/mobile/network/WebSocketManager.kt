package com.comfyui.mobile.network

import android.util.Log
import com.comfyui.mobile.model.MediaOutput
import com.comfyui.mobile.model.MediaType
import com.comfyui.mobile.model.SystemMonitorData
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID

/**
 * [clientForHost] picks the OkHttp client per target host: strict TLS for RunningHub hosts,
 * the lenient LAN client for the user's own ComfyUI servers.
 */
class WebSocketManager(private val clientForHost: (String) -> OkHttpClient) {

    private var webSocket: WebSocket? = null
    private val gson = Gson()
    
    // StateFlows for UI to observe
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    private val _systemStats = MutableStateFlow<SystemMonitorData?>(null)
    val systemStats = _systemStats.asStateFlow()

    private val _progress = MutableStateFlow(0f) // 0.0 to 1.0
    val progress = _progress.asStateFlow()

    // Smoothness: ComfyUI can emit progress / crystools.monitor very frequently.
    // Throttle UI-facing StateFlow writes so Dashboard/Agent don't recompose every tick.
    private var lastProgressEmitAtMs = 0L
    private var lastStatsEmitAtMs = 0L

    private val _queueSize = MutableStateFlow(0)
    val queueSize = _queueSize.asStateFlow()

    private val _executingNodeId = MutableStateFlow<String?>(null)
    val executingNodeId: StateFlow<String?> = _executingNodeId.asStateFlow()
    
    private val _latestMedia = MutableStateFlow<MediaOutput?>(null)
    val latestMedia = _latestMedia.asStateFlow()

    // Event flow for every generated media (ensures we don't miss any for auto-save)
    private val _mediaEvents = kotlinx.coroutines.flow.MutableSharedFlow<MediaOutput>(extraBufferCapacity = 64)
    val mediaEvents = _mediaEvents.asSharedFlow()
    @Volatile var mediaSourceKey: String? = null
    @Volatile var mediaWorkspaceEpoch: Long = 0

    private val _executionStart = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    val executionStart = _executionStart.asSharedFlow()

    private val _executionStartPromptId = kotlinx.coroutines.flow.MutableSharedFlow<String?>(extraBufferCapacity = 64)
    val executionStartPromptId = _executionStartPromptId.asSharedFlow()

    private val _executionFinishedPromptId = kotlinx.coroutines.flow.MutableSharedFlow<String?>(extraBufferCapacity = 64)
    val executionFinishedPromptId = _executionFinishedPromptId.asSharedFlow()

    /** Failure details for a prompt (execution_error / execution_interrupted), recorded before the finished event. */
    data class ExecutionFailure(
        val promptId: String?,
        val interrupted: Boolean,
        val message: String?,
        val nodeId: String? = null,
        val nodeType: String? = null
    ) {
        val displayMessage: String
            get() = if (interrupted) {
                "任务已中断"
            } else {
                buildString {
                    if (!nodeId.isNullOrBlank()) {
                        append("节点 ")
                        append(nodeId)
                        if (!nodeType.isNullOrBlank()) append(" ($nodeType)")
                        append(": ")
                    }
                    append(message?.ifBlank { null } ?: "未知错误")
                }
            }
    }

    private val executionFailures = java.util.concurrent.ConcurrentHashMap<String, ExecutionFailure>()

    /** Returns and removes the recorded failure for [promptId], or null if it did not fail (as far as WS knows). */
    fun consumeExecutionFailure(promptId: String?): ExecutionFailure? {
        if (promptId.isNullOrBlank()) return null
        return executionFailures.remove(promptId)
    }

    private fun recordExecutionFailure(failure: ExecutionFailure) {
        val pid = failure.promptId ?: return
        if (executionFailures.size > 64) executionFailures.clear()
        executionFailures[pid] = failure
    }

    /** Non-null when the server permanently rejected the WebSocket (e.g. HTTP 401/403); auto-reconnect is stopped. */
    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

    @Volatile
    private var permanentFailureUrl: String? = null

    /**
     * True while a RunningHub cloud profile is active: cloud runs go through the official task API, so no
     * WebSocket is ever opened (connect / softEnsure / auto-reconnect are no-ops). Local profiles: false.
     */
    @Volatile
    private var disabled: Boolean = false

    /** [value] = true closes the socket silently (no error shown) and refuses every (re)connect. */
    fun setDisabled(value: Boolean) {
        disabled = value
        if (!value) return
        isManualDisconnect = true
        reconnectAttempts = 0
        reconnectJob?.cancel()
        val oldSocket = webSocket
        webSocket = null
        try {
            oldSocket?.close(1000, "Cloud profile")
        } catch (_: Exception) {
        }
        _connectionState.value = ConnectionState.DISCONNECTED
        _systemStats.value = null
        _connectionError.value = null
    }

    private val _logEvents = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 128)
    val logEvents = _logEvents.asSharedFlow()

    private val _lastMessageType = MutableStateFlow<String>("")
    val lastMessageType = _lastMessageType.asStateFlow()

    val clientId: String = UUID.randomUUID().toString()
    private var currentUrl: String = ""

    @Volatile
    private var lastInboundElapsedMs: Long = 0L

    fun getLastInboundAgeMs(): Long {
        val t = lastInboundElapsedMs
        return if (t > 0L) android.os.SystemClock.elapsedRealtime() - t else Long.MAX_VALUE
    }

    @Volatile
    private var lastMonitorReceivedAtMs = 0L

    fun updateSystemStats(stats: SystemMonitorData) {
        val current = _systemStats.value
        if (current != null && current.isCrystools && !stats.isCrystools) {
            val age = android.os.SystemClock.elapsedRealtime() - lastMonitorReceivedAtMs
            if (age < 3500L) {
                _systemStats.value = current.copy(
                    deviceName = current.deviceName ?: stats.deviceName,
                    cpuUtil = if (current.cpuAvailable) current.cpuUtil else stats.cpuUtil,
                    cpuAvailable = current.cpuAvailable || stats.cpuAvailable,
                    ramUsed = if (current.ramAvailable) current.ramUsed else stats.ramUsed,
                    ramTotal = if (current.ramAvailable) current.ramTotal else stats.ramTotal,
                    ramAvailable = current.ramAvailable || stats.ramAvailable
                )
                return
            }
        }
        _systemStats.value = stats
    }


    /** Publish progress to UI at most ~10 Hz unless crossing start/end. */
    private fun publishProgress(p: Float) {
        val clamped = p.coerceIn(0f, 1f)
        val now = android.os.SystemClock.elapsedRealtime()
        val prev = _progress.value
        val boundary = clamped <= 0.001f || clamped >= 0.999f
        val delta = kotlin.math.abs(clamped - prev)
        if (!boundary && delta < 0.005f && (now - lastProgressEmitAtMs) < 100L) return
        if (!boundary && delta < 0.001f) return
        lastProgressEmitAtMs = now
        _progress.value = clamped
    }

    /** Publish monitor stats; skip near-identical samples within 250ms. */
    private fun publishSystemStats(stats: SystemMonitorData) {
        lastMonitorReceivedAtMs = android.os.SystemClock.elapsedRealtime()
        val prev = _systemStats.value
        if (prev == stats) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (prev != null && (now - lastStatsEmitAtMs) < 250L) {
            val similar =
                kotlin.math.abs(prev.gpuUtil - stats.gpuUtil) < 1.5f &&
                kotlin.math.abs(prev.cpuUtil - stats.cpuUtil) < 1.5f &&
                kotlin.math.abs(prev.vramUsed - stats.vramUsed) < 32f &&
                kotlin.math.abs(prev.ramUsed - stats.ramUsed) < 32f &&
                prev.deviceName == stats.deviceName &&
                prev.cpuAvailable == stats.cpuAvailable && prev.ramAvailable == stats.ramAvailable
            if (similar) return
        }
        lastStatsEmitAtMs = now
        updateSystemStats(stats)
    }

    // Helper to emit logs
    private fun emitLog(msg: String) {
        // We can't suspend here easily if called from non-suspend context, 
        // but tryEmit is non-blocking and good for logging.
        _logEvents.tryEmit(msg)
    }

    private var isManualDisconnect = false
    private var reconnectAttempts = 0
    private val baseReconnectDelayMs = 1500L
    private val maxReconnectDelayMs = 20000L
    private var reconnectJob: kotlinx.coroutines.Job? = null
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    fun isSocketLive(): Boolean {
        return webSocket != null && _connectionState.value == ConnectionState.CONNECTED
    }

    fun resetReconnectBackoff() {
        reconnectAttempts = 0
        reconnectJob?.cancel()
    }

    fun reconnect(resetBackoff: Boolean = true) {
        if (currentUrl.isBlank()) return
        if (resetBackoff) {
            reconnectAttempts = 0
        }
        reconnectJob?.cancel()
        connect(currentUrl, force = true)
    }

    /** Local UI reset after user stops all jobs (does not touch the socket). */
    fun resetAfterStop() {
        publishProgress(0f)
        _queueSize.value = 0
        _executingNodeId.value = null
    }

    fun softEnsure(baseUrl: String, forceIfSuspicious: Boolean = false) {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isBlank()) return
        if (!SecretRedactor.isValidBaseUrl(trimmed)) {
            Log.w("WebSocket", "softEnsure: rejected invalid base url ${SecretRedactor.redact(trimmed)}")
            return
        }
        // Server permanently rejected this URL (auth / balance). Only an explicit reconnect clears it.
        if (disabled || permanentFailureUrl == trimmed) return

        if (forceIfSuspicious && isSocketLive() && currentUrl == trimmed) {
            val ws = webSocket
            val probeOk = if (ws != null) {
                try {
                    ws.send("""{"type":"ping"}""")
                } catch (_: Exception) {
                    false
                }
            } else false
            if (!probeOk) {
                emitLog("WebSocket 探活失败，立即重建连接...")
                reconnect(resetBackoff = true)
                return
            }
        }

        if (isSocketLive() && currentUrl == trimmed) return
        if (_connectionState.value == ConnectionState.CONNECTING && currentUrl == trimmed) return

        connect(trimmed, force = true)
    }

    fun connect(baseUrl: String, force: Boolean = false) {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isBlank()) return
        // Validate before touching the current socket: an invalid URL must never tear down a good connection.
        if (!SecretRedactor.isValidBaseUrl(trimmed)) {
            Log.w("WebSocket", "connect: rejected invalid base url ${SecretRedactor.redact(trimmed)}")
            return
        }
        if (disabled) {
            Log.d("WebSocket", "connect: cloud profile active, no WebSocket")
            return
        }

        if (!force) {
            if (webSocket != null &&
                _connectionState.value == ConnectionState.CONNECTED &&
                currentUrl == trimmed
            ) {
                return
            }
            if (_connectionState.value == ConnectionState.CONNECTING && currentUrl == trimmed) {
                return
            }
        }
        
        isManualDisconnect = false
        reconnectJob?.cancel()
        
        val oldSocket = webSocket
        webSocket = null
        try {
            oldSocket?.cancel()
        } catch (_: Exception) {
        }

        _systemStats.value = null
        currentUrl = trimmed
        _connectionState.value = ConnectionState.CONNECTING

        val httpUrl = trimmed.toHttpUrlOrNull()

        val fullUrl = if (httpUrl != null) {
            val wsScheme = if (httpUrl.scheme.equals("https", ignoreCase = true)) "wss" else "ws"
            val portPart = if ((httpUrl.scheme.equals("https", ignoreCase = true) && httpUrl.port == 443) ||
                (httpUrl.scheme.equals("http", ignoreCase = true) && httpUrl.port == 80)) {
                ""
            } else {
                ":${httpUrl.port}"
            }
            val basePath = httpUrl.encodedPath.trimEnd('/')
            "$wsScheme://${httpUrl.host}$portPart$basePath/ws?clientId=$clientId"
        } else {
            val wsUrl = when {
                trimmed.startsWith("https://", ignoreCase = true) -> "wss://" + trimmed.substring(8)
                trimmed.startsWith("http://", ignoreCase = true) -> "ws://" + trimmed.substring(7)
                else -> "ws://$trimmed"
            }
            "${wsUrl.trimEnd('/')}/ws?clientId=$clientId"
        }

        Log.d("WebSocket", "Connecting to ${SecretRedactor.redact(fullUrl)}")

        val request = try {
            Request.Builder().url(fullUrl).build()
        } catch (e: IllegalArgumentException) {
            Log.w("WebSocket", "Invalid WebSocket url ${SecretRedactor.redact(fullUrl)}")
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        if (permanentFailureUrl != trimmed) {
            permanentFailureUrl = null
            _connectionError.value = null
        }
        
        val capturedSourceKey = mediaSourceKey
        val capturedWorkspaceEpoch = mediaWorkspaceEpoch
        val capturedBaseUrl = trimmed
        webSocket = clientForHost(request.url.host).newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocket !== this@WebSocketManager.webSocket) {
                    try { webSocket.close(1000, "Obsolete socket") } catch (_: Exception) {}
                    return
                }
                lastInboundElapsedMs = android.os.SystemClock.elapsedRealtime()
                _connectionState.value = ConnectionState.CONNECTED
                reconnectAttempts = 0
                reconnectJob?.cancel()
                permanentFailureUrl = null
                _connectionError.value = null
                Log.d("WebSocket", "Connected")
                emitLog("WebSocket 已连接")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== this@WebSocketManager.webSocket) {
                    return
                }
                lastInboundElapsedMs = android.os.SystemClock.elapsedRealtime()
                handleMessage(text, capturedSourceKey, capturedBaseUrl, capturedWorkspaceEpoch)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== this@WebSocketManager.webSocket) {
                    return
                }
                try { webSocket.close(1000, null) } catch (_: Exception) {}
                Log.d("WebSocket", "Closing: $code / $reason")
                if (!isManualDisconnect) {
                    _connectionState.value = ConnectionState.DISCONNECTED
                    emitLog("WebSocket 连接关闭: $reason")
                    scheduleReconnect()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== this@WebSocketManager.webSocket) {
                    return
                }
                Log.d("WebSocket", "Closed: $code / $reason")
                if (!isManualDisconnect) {
                    _connectionState.value = ConnectionState.DISCONNECTED
                    emitLog("WebSocket 已断开")
                    scheduleReconnect()
                } else {
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== this@WebSocketManager.webSocket) {
                    return
                }
                val httpCode = response?.code
                Log.e("WebSocket", "Failure: ${SecretRedactor.redact(t.message)} (http=$httpCode)")
                if (!isManualDisconnect) {
                    _connectionState.value = ConnectionState.DISCONNECTED
                    if (httpCode == 401 || httpCode == 402 || httpCode == 403) {
                        // Permanent rejection (bad key / no balance / forbidden): stop auto-reconnect loop.
                        permanentFailureUrl = currentUrl
                        reconnectJob?.cancel()
                        val msg = if (currentUrl.contains("runninghub", ignoreCase = true)) {
                            "RunningHub 连接被拒绝，请检查 API Key 或余额 (HTTP $httpCode)"
                        } else {
                            "服务器拒绝了 WebSocket 连接 (HTTP $httpCode)，已停止自动重连"
                        }
                        _connectionError.value = msg
                        emitLog("WebSocket 连接被拒绝 (HTTP $httpCode)，已停止自动重连")
                    } else {
                        emitLog("WebSocket 连接失败: ${SecretRedactor.redact(t.message)}")
                        scheduleReconnect()
                    }
                }
            }
        })
    }

    fun disconnect() {
        isManualDisconnect = true
        reconnectAttempts = 0
        reconnectJob?.cancel()
        permanentFailureUrl = null
        _connectionError.value = null
        val oldSocket = webSocket
        webSocket = null
        try {
            oldSocket?.close(1000, "User disconnected")
        } catch (_: Exception) {
        }
        _connectionState.value = ConnectionState.DISCONNECTED
        _systemStats.value = null
    }

    private fun scheduleReconnect() {
        if (isManualDisconnect) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delayMs = (baseReconnectDelayMs * (1L shl reconnectAttempts.coerceAtMost(4)))
                .coerceAtMost(maxReconnectDelayMs)
            reconnectAttempts++
            kotlinx.coroutines.delay(delayMs)
            if (!isManualDisconnect && currentUrl.isNotBlank() && permanentFailureUrl != currentUrl && !disabled) {
                val delaySec = delayMs / 1000.0
                emitLog("正在尝试重连 (第${reconnectAttempts}次，间隔 ${delaySec}s)...")
                connect(currentUrl)
            }
        }
    }

    private fun parseSystemMonitorData(raw: JSONObject): SystemMonitorData? {
        val data = raw.optJSONObject("data") ?: raw

        fun toFloatValue(v: Any?): Float? {
            return when (v) {
                is Number -> v.toFloat()
                is String -> {
                    val cleaned = v.trim()
                        .replace("%", "")
                        .replace(",", "")
                    cleaned.toFloatOrNull()
                }
                else -> null
            }
        }

        fun normalizePercent(v: Float?): Float? {
            if (v == null) return null
            if (!v.isFinite()) return null
            val scaled = when {
                v in 0f..1.0f -> v * 100f
                v > 100f && v <= 10000f -> v / 100f
                else -> v
            }
            return scaled.coerceIn(0f, 100f)
        }

        fun findUtilInContext(root: Any?, primaryKeys: Set<String>): Float? {
            fun keyLooksLikeUtil(k: String): Boolean {
                val lower = k.lowercase()
                return lower.contains("util") ||
                    lower.contains("usage") ||
                    lower.contains("load") ||
                    lower.contains("percent") ||
                    lower.contains("pct")
            }

            fun rec(node: Any?, inUtilSection: Boolean): Float? {
                return when (node) {
                    is JSONObject -> {
                        val it = node.keys()
                        while (it.hasNext()) {
                            val k = it.next()
                            val v = node.opt(k)
                            val lower = k.lowercase()
                            val nextInUtil = inUtilSection || keyLooksLikeUtil(k)

                            val parsed = toFloatValue(v)
                            if (parsed != null) {
                                if (keyLooksLikeUtil(k)) return parsed
                                if (nextInUtil && primaryKeys.any { pk -> lower == pk || lower.contains(pk) }) return parsed
                            }

                            val nested = rec(v, nextInUtil)
                            if (nested != null) return nested
                        }
                        null
                    }
                    is org.json.JSONArray -> {
                        for (i in 0 until node.length()) {
                            val nested = rec(node.opt(i), inUtilSection)
                            if (nested != null) return nested
                        }
                        null
                    }
                    else -> null
                }
            }

            return rec(root, inUtilSection = false)
        }

        fun pickObj(vararg keys: String): JSONObject? {
            for (k in keys) {
                val o = data.optJSONObject(k)
                if (o != null) return o
            }
            return null
        }

        fun optFloat(vararg keys: String): Float? {
            for (key in keys) {
                if (data.has(key) && !data.isNull(key)) {
                    return toFloatValue(data.opt(key))
                }
            }
            return null
        }

        fun optFloatIn(obj: JSONObject?, vararg keys: String): Float? {
            if (obj == null) return null
            for (k in keys) {
                if (obj.has(k) && !obj.isNull(k)) {
                    return toFloatValue(obj.opt(k))
                }
            }
            return null
        }

        fun findFirstNumberByKey(root: Any?, match: (String) -> Boolean): Float? {
            return when (root) {
                is JSONObject -> {
                    val it = root.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        val v = root.opt(k)
                        if (match(k)) {
                            val parsed = toFloatValue(v)
                            if (parsed != null) return parsed
                        }
                        val nested = findFirstNumberByKey(v, match)
                        if (nested != null) return nested
                    }
                    null
                }
                is org.json.JSONArray -> {
                    for (i in 0 until root.length()) {
                        val nested = findFirstNumberByKey(root.opt(i), match)
                        if (nested != null) return nested
                    }
                    null
                }
                else -> null
            }
        }

        val cpuObj = pickObj("cpu", "cpu_info", "cpuInfo")
        val ramObj = pickObj("ram", "memory", "mem")
        val gpuObj = pickObj("gpu")
        val deviceStats = pickObj("device_stats", "device_info")

        var gpuUtil = optFloat("gpu_util", "gpuUtil", "gpu_usage", "gpuUsage", "gpu_utilization", "gpuUtilization", "gpu_percent", "gpuPercent")
        var vramUsed = optFloat("vram_used", "vramUsed", "mem_used", "memUsed", "memory_used", "memoryUsed", "vramUsedBytes", "vram_used_bytes")
        var vramTotal = optFloat("vram_total", "vramTotal", "mem_total", "memTotal", "memory_total", "memoryTotal", "vramTotalBytes", "vram_total_bytes")
        var gpuTemp = optFloat("gpu_temp", "gpuTemp", "temperature", "temp", "gpu_temperature")

        // Try extracting from device_stats
        if (deviceStats != null) {
            gpuUtil = gpuUtil ?: optFloatIn(deviceStats, "gpu_util", "gpu_usage")
            vramUsed = vramUsed ?: optFloatIn(deviceStats, "vram_used", "vram_used_bytes")
            vramTotal = vramTotal ?: optFloatIn(deviceStats, "vram_total", "vram_total_bytes")
            gpuTemp = gpuTemp ?: optFloatIn(deviceStats, "gpu_temp", "gpu_temperature")
        }

        val gpus = data.optJSONArray("gpus")
        if (gpus != null && gpus.length() > 0) {
            val gpu0 = gpus.optJSONObject(0)
            if (gpu0 != null) {
                gpuUtil = gpuUtil ?: optFloatIn(gpu0, "gpu_util", "util", "usage", "gpu_usage", "gpuUsage")
                vramUsed = vramUsed ?: optFloatIn(gpu0, "vram_used", "mem_used", "used", "vram_used_bytes")
                vramTotal = vramTotal ?: optFloatIn(gpu0, "vram_total", "mem_total", "total", "vram_total_bytes")
                gpuTemp = gpuTemp ?: optFloatIn(gpu0, "gpu_temp", "temp", "temperature", "gpu_temperature")
            }
        }

        var cpuUtil = optFloat("cpu_util", "cpuUtil", "cpu_usage", "cpuUsage", "cpu_utilization", "cpuUtilization", "cpu_percent", "cpuPercent", "cpu_load", "cpuLoad")
        cpuUtil = cpuUtil ?: optFloatIn(cpuObj, "cpu_util", "util", "usage", "percent", "cpu_usage", "cpu_utilization", "cpu_percent")
        cpuUtil = cpuUtil ?: optFloatIn(deviceStats, "cpu_util", "cpu_usage", "cpu_utilization", "cpu_percent")

        var ramUsed = optFloat("ram_used", "ramUsed", "memory_used", "memoryUsed", "mem_used", "memUsed")
        var ramTotal = optFloat("ram_total", "ramTotal", "memory_total", "memoryTotal", "mem_total", "memTotal")
        ramUsed = ramUsed ?: optFloatIn(ramObj, "ram_used", "used", "mem_used", "memory_used", "used_bytes")
        ramTotal = ramTotal ?: optFloatIn(ramObj, "ram_total", "total", "mem_total", "memory_total", "total_bytes")
        
        // device_stats fallback for RAM
        ramUsed = ramUsed ?: optFloatIn(deviceStats, "ram_used", "ram_used_bytes")
        ramTotal = ramTotal ?: optFloatIn(deviceStats, "ram_total", "ram_total_bytes")

        gpuUtil = gpuUtil ?: optFloatIn(gpuObj, "gpu_util", "util", "usage", "gpu_usage")
        vramUsed = vramUsed ?: optFloatIn(gpuObj, "vram_used", "mem_used", "used", "used_bytes")
        vramTotal = vramTotal ?: optFloatIn(gpuObj, "vram_total", "mem_total", "total", "total_bytes")
        gpuTemp = gpuTemp ?: optFloatIn(gpuObj, "gpu_temp", "temp", "temperature", "gpu_temperature")

        if (gpuUtil == null || gpuUtil == 0f) {
            val gpuCandidate =
                gpuObj ?: deviceStats?.optJSONObject("gpu") ?: gpus?.optJSONObject(0)
            gpuUtil = gpuUtil ?: findUtilInContext(
                gpuCandidate,
                primaryKeys = setOf("gpu", "graphics", "sm", "core")
            )
        }

        if (cpuUtil == null || cpuUtil == 0f) {
            val cpuCandidate = cpuObj ?: deviceStats?.optJSONObject("cpu")
            cpuUtil = cpuUtil ?: findUtilInContext(
                cpuCandidate,
                primaryKeys = setOf("cpu", "total", "usage")
            )
        }

        if (gpuUtil == null || gpuUtil == 0f) {
            gpuUtil = findFirstNumberByKey(data) { k ->
                k.contains("gpu", ignoreCase = true) &&
                    (k.contains("util", ignoreCase = true) ||
                        k.contains("usage", ignoreCase = true) ||
                        k.contains("load", ignoreCase = true) ||
                        k.contains("percent", ignoreCase = true))
            }
        }
        if (cpuUtil == null || cpuUtil == 0f) {
            cpuUtil = findFirstNumberByKey(data) { k ->
                k.contains("cpu", ignoreCase = true) &&
                    (k.contains("util", ignoreCase = true) ||
                        k.contains("usage", ignoreCase = true) ||
                        k.contains("load", ignoreCase = true) ||
                        k.contains("percent", ignoreCase = true))
            }
        }

        cpuUtil = normalizePercent(cpuUtil)
        gpuUtil = normalizePercent(gpuUtil)

        val existingDeviceName = _systemStats.value?.deviceName
        val parsed = SystemMonitorData(
            gpuUtil = gpuUtil ?: 0f,
            vramUsed = vramUsed ?: 0f,
            vramTotal = vramTotal ?: 0f,
            cpuUtil = cpuUtil ?: 0f,
            cpuAvailable = cpuUtil != null,
            ramUsed = ramUsed ?: 0f,
            ramTotal = ramTotal ?: 0f,
            ramAvailable = ramTotal != null && ramTotal > 0f && ramUsed != null,
            gpuTemp = gpuTemp ?: 0f,
            deviceName = existingDeviceName,
            isCrystools = true
    )

        val meaningful = parsed.gpuUtil != 0f ||
            parsed.cpuAvailable ||
            parsed.ramAvailable ||
            parsed.gpuTemp != 0f ||
            parsed.vramTotal != 0f ||
            parsed.ramTotal != 0f ||
            parsed.vramUsed != 0f ||
            parsed.ramUsed != 0f

        if (!meaningful) {
            // Debug-only: do not push empty/zero monitor payloads into the UI console log.
            val keys = mutableListOf<String>()
            val keysIter = data.keys()
            while (keysIter.hasNext()) {
                keys.add(keysIter.next())
            }
            Log.d("WebSocket", "Crystools data empty/zeros. Keys: $keys")
            if (deviceStats != null) {
                 val dsKeys = mutableListOf<String>()
                 val dsIter = deviceStats.keys()
                 while (dsIter.hasNext()) {
                     dsKeys.add(dsIter.next())
                 }
                 Log.d("WebSocket", "device_stats keys: $dsKeys")
            }
        }

        return if (meaningful) parsed else null
    }

    fun injectTestMedia(media: MediaOutput) {
        _latestMedia.value = media
        _mediaEvents.tryEmit(media)
        emitLog("Test Media Injected: ${media.filename}")
    }

    private fun handleMessage(text: String, sourceKey: String? = null, sourceBaseUrl: String? = null, workspaceEpoch: Long? = null) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type").ifBlank { json.optString("event") }
            // Do not push progress / crystools.monitor into lastMessageType (Dashboard/Agent collect it).
            if (type != "crystools.monitor" && type != "progress") {
                _lastMessageType.value = type
            }

            // Log message types for debugging, suppressing high-frequency routine spam
            if (type != "crystools.monitor" && type != "progress") {
                emitLog("WS Msg: $type")
            }

            val data = json.optJSONObject("data")
            fun promptIdFrom(obj: JSONObject?): String? {
                if (obj == null) return null
                if (obj.isNull("prompt_id") && obj.isNull("promptId")) return null
                val v = obj.optString("prompt_id").ifBlank { obj.optString("promptId") }
                if (v.isBlank() || v.equals("null", ignoreCase = true)) return null
                return v
            }
            val promptId = promptIdFrom(data) ?: promptIdFrom(json)

            val emitted = HashSet<String>()
            fun emitMedia(media: MediaOutput) {
                val key = "${media.subfolder}|${media.filename}|${media.type}"
                if (emitted.add(key)) {
                    val sourceUrl = sourceBaseUrl?.toHttpUrlOrNull()?.newBuilder()
                        ?.addPathSegment("view")?.addQueryParameter("filename", media.filename)
                        ?.addQueryParameter("subfolder", media.subfolder)?.addQueryParameter("type", media.type)
                        ?.build()?.toString()
                    val stamped = media.copy(sourceKey = sourceKey, sourceUrl = sourceUrl, workspaceEpoch = workspaceEpoch)
                    _latestMedia.value = stamped
                    _mediaEvents.tryEmit(stamped)
                }
            }

            fun mediaTypeFromFilename(filename: String): MediaType {
                val lower = filename.lowercase()
                return when {
                    lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mov") -> MediaType.VIDEO
                    lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".ogg") || lower.endsWith(".flac") -> MediaType.AUDIO
                    lower.endsWith(".txt") || lower.endsWith(".log") || lower.endsWith(".json") || lower.endsWith(".csv") -> MediaType.TEXT
                    else -> MediaType.IMAGE
                }
            }

            fun traverseAndEmit(value: Any?, defaultType: String?) {
                when (value) {
                    is JSONObject -> {
                        val filename = value.optString("filename")
                        if (filename.isNotBlank()) {
                            val subfolder = value.optString("subfolder")
                            val folderType = value.optString("type").ifBlank { defaultType ?: "output" }
                            emitMedia(
                                MediaOutput(
                                    filename = filename,
                                    subfolder = subfolder,
                                    type = folderType,
                                    mediaType = mediaTypeFromFilename(filename),
                                    promptId = promptId
                                )
                            )
                        }
                        val keys = value.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            traverseAndEmit(value.opt(k), k)
                        }
                    }
                    is org.json.JSONArray -> {
                        for (i in 0 until value.length()) {
                            traverseAndEmit(value.opt(i), defaultType)
                        }
                    }
                }
            }

            // Debug log for executed type
            if (type == "executed") {
                 emitLog("DEBUG: Received executed message: $text")
            }

            when (type) {
                "status" -> {
                    val queue = data?.optJSONObject("status")
                        ?.optJSONObject("exec_info")
                        ?.optInt("queue_remaining") ?: 0
                    _queueSize.value = queue
                    // Idle connect also has queue==0; do not emit completion here.
                    // Completion: executing(node=null) / execution_success / error / cached.
                }
                "progress" -> {
                    val value = data?.optDouble("value") ?: 0.0
                    val max = data?.optDouble("max") ?: 1.0
                    if (max > 0) {
                        publishProgress((value / max).toFloat())
                    }
                }
                "execution_start" -> {
                    publishProgress(0f)
                    _executingNodeId.value = null
                    _executionStart.tryEmit(Unit)
                    _executionStartPromptId.tryEmit(promptId)
                }
                "executing" -> {
                    // Node executing... can highlight in graph later
                    // If node is null or empty, execution finished for this prompt
                    val isNodeNull = data == null || data.isNull("node")
                    val nodeStr = data?.optString("node")
                    val isFinished = isNodeNull || nodeStr.isNullOrBlank() || nodeStr.equals("null", ignoreCase = true)

                    if (isFinished) {
                        publishProgress(0f)
                        _executingNodeId.value = null
                        _executionFinishedPromptId.tryEmit(promptId)
                    } else {
                        _executingNodeId.value = nodeStr
                    }
                }
                "executed" -> {
                    val outputAny = data?.opt("output")
                    if (outputAny != null) {
                        traverseAndEmit(outputAny, null)
                    }
                }
                "execution_success" -> {
                    // This event also indicates a prompt has finished executing successfully
                    val outputAny = data?.opt("output")
                    if (outputAny != null) {
                        traverseAndEmit(outputAny, null)
                    }
                    publishProgress(0f)
                    _executingNodeId.value = null
                    _executionFinishedPromptId.tryEmit(promptId)
                }
                "execution_error" -> {
                    val exceptionMsg = data?.optString("exception_message")?.ifBlank { null }
                        ?: data?.optString("exception_type")?.ifBlank { null }
                        ?: "未知错误"
                    val nodeId = data?.opt("node_id")?.toString()?.ifBlank { null }?.takeIf { it != "null" }
                    val nodeType = data?.optString("node_type")?.ifBlank { null }
                    recordExecutionFailure(ExecutionFailure(promptId, interrupted = false, message = exceptionMsg, nodeId = nodeId, nodeType = nodeType))
                    emitLog("执行出错: ${if (nodeId != null) "节点 $nodeId: " else ""}$exceptionMsg")
                    publishProgress(0f)
                    _executingNodeId.value = null
                    _executionFinishedPromptId.tryEmit(promptId)
                }
                "execution_interrupted" -> {
                    recordExecutionFailure(ExecutionFailure(promptId, interrupted = true, message = null))
                    emitLog("任务已被中断")
                    publishProgress(0f)
                    _executingNodeId.value = null
                    _executionFinishedPromptId.tryEmit(promptId)
                }
                "execution_cached" -> {
                    // In ComfyUI, execution_cached notifies cached nodes at START of execution, NOT finish!
                    val nodes = data?.optJSONArray("nodes")
                    val count = nodes?.length() ?: 0
                    emitLog("命中缓存节点: $count 个")
                }
                "crystools.monitor" -> {
                    // Parse system stats for Dashboard UI only; do not append to console log
                    // These messages arrive about once per second and would flood the console.
                    if (data != null) {
                        val parsed = parseSystemMonitorData(data)
                        if (parsed != null) {
                            publishSystemStats(parsed)
                        }
                    }
                }
                else -> {
                    val maybe = data ?: json
                    val parsed = parseSystemMonitorData(maybe)
                    if (parsed != null) {
                        publishSystemStats(parsed)
                    }
                }
            }

        } catch (e: Exception) {
            Log.e("WebSocket", "Error parsing message", e)
        }
    }
}

enum class ConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, ERROR
}
