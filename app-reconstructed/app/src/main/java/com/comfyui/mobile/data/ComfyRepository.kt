package com.comfyui.mobile.data

import android.util.Log
import com.comfyui.mobile.model.HistoryItem
import com.comfyui.mobile.model.MediaOutput
import com.comfyui.mobile.model.PromptRequest
import com.comfyui.mobile.model.HistoryActionRequest
import com.comfyui.mobile.model.QueueActionRequest
import com.comfyui.mobile.model.QueueDto
import com.comfyui.mobile.model.QueueResponse
import com.comfyui.mobile.model.UploadResponse
import com.comfyui.mobile.network.ComfyApi
import com.comfyui.mobile.network.WebSocketManager
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class ComfyRepository private constructor() {

    private val gson = Gson()

    private val _baseUrl = MutableStateFlow("http://127.0.0.1:8188")
    val baseUrl: StateFlow<String> = _baseUrl

    /**
     * Lenient client (trust-all TLS) for the user's own LOCAL/LAN ComfyUI servers only
     * (self-signed certificates are common there). Never used for RunningHub hosts.
     */
    val okHttpClient: OkHttpClient by lazy {
        createOkHttpClient()
    }

    /** Normal certificate + hostname verification; used for every RunningHub-owned host. */
    val strictClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    fun clientForHost(host: String?): OkHttpClient =
        if (com.comfyui.mobile.network.RhHttpClient.isRunningHubHost(host)) strictClient else okHttpClient

    /** Picks strict vs lenient TLS per request host (RunningHub hosts are always strict). */
    val hostAwareCallFactory: okhttp3.Call.Factory = okhttp3.Call.Factory { request ->
        clientForHost(request.url.host).newCall(request)
    }

    private fun createOkHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .pingInterval(20, TimeUnit.SECONDS)

        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, trustAllCerts, SecureRandom())
            }
            builder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            builder.hostnameVerifier { _, _ -> true }
        } catch (e: Exception) {
            Log.w("ComfyRepository", "配置宽松 SSL TrustManager 异常", e)
        }

        return builder.build()
    }

    private val _externalStop = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Fired when Live Update (or other external UI) requests stop-all. */
    val externalStop = _externalStop.asSharedFlow()

    fun notifyExternalStop() {
        _externalStop.tryEmit(Unit)
    }

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating

    fun setIsGenerating(value: Boolean) {
        _isGenerating.value = value
    }

    /**
     * True while the active server is a RunningHub cloud profile. Cloud runs use the official task API only,
     * so the ComfyUI run endpoints (prompt / queue / history / interrupt / system_stats / upload / delete)
     * and the WebSocket are never used for it. Only two read-only endpoints stay reachable on the cloud base
     * URL: object_info (node-editing dropdowns) and view (results of older records). Local: always false.
     */
    @Volatile
    var cloudActive: Boolean = false
        private set

    fun setCloudActive(active: Boolean) {
        cloudActive = active
        webSocketManager.setDisabled(active)
    }

    val webSocketManager: WebSocketManager by lazy {
        WebSocketManager { host -> clientForHost(host) }
    }

    @Volatile
    private var api: ComfyApi? = null

    @Volatile
    private var objectInfoCache: Map<String, Any>? = null

    private fun ensureApi() {
        if (api != null) return
        val retrofit = Retrofit.Builder()
            .baseUrl(_baseUrl.value.trimEnd('/') + "/")
            .callFactory(hostAwareCallFactory)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
        api = retrofit.create(ComfyApi::class.java)
    }

    fun setBaseUrlOnly(baseUrl: String) {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (!com.comfyui.mobile.network.SecretRedactor.isValidBaseUrl(trimmed)) {
            Log.w("ComfyRepository", "setBaseUrlOnly: rejected invalid base url ${com.comfyui.mobile.network.SecretRedactor.redact(trimmed)}")
            return
        }
        if (_baseUrl.value != trimmed) {
            _baseUrl.value = trimmed
            api = null
            objectInfoCache = null
        }
    }

    fun updateConnection(ip: String, port: String) {
        updateConnection("http://$ip:$port")
    }

    fun updateConnection(baseUrl: String, force: Boolean = false) {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (!com.comfyui.mobile.network.SecretRedactor.isValidBaseUrl(trimmed)) {
            Log.w("ComfyRepository", "updateConnection: rejected invalid base url ${com.comfyui.mobile.network.SecretRedactor.redact(trimmed)}")
            return
        }
        val urlChanged = _baseUrl.value != trimmed
        _baseUrl.value = trimmed
        if (urlChanged) {
            api = null
            objectInfoCache = null
        }
        if (force || urlChanged) {
            webSocketManager.disconnect()
            webSocketManager.connect(trimmed)
        } else {
            // Address didn't change and not forced: do not destroy live connection
            webSocketManager.softEnsure(trimmed)
        }
    }

    suspend fun checkRunningHubStatus(
        hostUrl: String,
        apiKey: String
    ): Result<com.comfyui.mobile.network.RhAccountData> {
        return com.comfyui.mobile.network.RunningHubApiHelper.checkAccountStatus(
            client = strictClient,
            gson = gson,
            hostUrl = hostUrl,
            apiKey = apiKey
        )
    }

    suspend fun getRunningHubWorkflowJson(
        hostUrl: String,
        apiKey: String,
        workflowId: String
    ): Result<com.comfyui.mobile.network.RhWorkflowResult> {
        return com.comfyui.mobile.network.RunningHubApiHelper.getWorkflowJson(
            client = strictClient,
            gson = gson,
            hostUrl = hostUrl,
            apiKey = apiKey,
            workflowId = workflowId
        )
    }

    suspend fun getRunningHubWebAppDetail(
        hostUrl: String,
        apiKey: String,
        webappId: String
    ): Result<com.comfyui.mobile.network.RhWebAppDemoData> {
        return com.comfyui.mobile.network.RunningHubApiHelper.getWebAppDetail(
            client = strictClient,
            gson = gson,
            hostUrl = hostUrl,
            apiKey = apiKey,
            webappId = webappId
        )
    }

    @Volatile
    var lastQueuePromptError: String? = null
        private set

    /** Redacted raw body of the last failed /prompt call (for the in-app console), null on success. */
    @Volatile
    var lastQueuePromptRawBody: String? = null
        private set

    suspend fun queuePrompt(workflow: Map<String, Any>): String? {
        lastQueuePromptError = null
        lastQueuePromptRawBody = null
        if (cloudActive) {
            lastQueuePromptError = "云端服务器不支持此提交方式"
            return null
        }
        return try {
            ensureApi()
            val currentApi = api
            if (currentApi == null) {
                lastQueuePromptError = "API 未初始化（请检查服务器连接）"
                Log.e("Repository", lastQueuePromptError!!)
                return null
            }
            val request = PromptRequest(prompt = workflow, client_id = webSocketManager.clientId)
            // Raw body: local ComfyUI answers 400 with {error, node_errors}; the RunningHub proxy may answer
            // HTTP 200 with the same body (or an RH {code,msg} envelope / "{}") and no prompt_id.
            val response = currentApi.queuePromptRaw(request)
            val code = response.code()
            val raw = try {
                (if (response.isSuccessful) response.body() else response.errorBody())?.use { it.string() }
            } catch (_: Exception) { null }
            val parsed = com.comfyui.mobile.network.PromptErrorParser.parse(raw, code)
            val pid = parsed.promptId
            if (response.isSuccessful && !pid.isNullOrBlank()) {
                Log.d("Repository", "Queued prompt: $pid")
                pid
            } else {
                val safeRaw = com.comfyui.mobile.network.SecretRedactor.redact(raw?.take(4000).orEmpty())
                lastQueuePromptRawBody = safeRaw.ifBlank { "(空响应)" }
                lastQueuePromptError = parsed.message ?: "HTTP $code 未返回 prompt_id"
                Log.e("Repository", "Queue prompt failed: HTTP $code ${lastQueuePromptError} | body=$safeRaw")
                null
            }
        } catch (e: Exception) {
            lastQueuePromptError = com.comfyui.mobile.network.SecretRedactor.redact(e.message ?: e.toString())
            Log.e("Repository", "Queue prompt exception: $lastQueuePromptError")
            null
        }
    }

    suspend fun deleteMediaOnServer(mediaList: List<MediaOutput>, mock: Boolean = false): Boolean {
        if (cloudActive) return false
        var anySuccess = false
        
        // 1. Try to physically delete files via custom nodes
        try {
            ensureApi()
            val objectInfo = getObjectInfo()
            val deleteClassType = resolveDeleteClassType(objectInfo)
            
            if (deleteClassType != null) {
                val nodes = buildDeleteFilesPrompt(mediaList, mock, objectInfo, deleteClassType)
                if (nodes != null) {
                    val request = PromptRequest(prompt = nodes, client_id = webSocketManager.clientId)
                    val response = api?.queuePrompt(request)
                    if (response?.isSuccessful == true) {
                        anySuccess = true
                    } else {
                        val code = response?.code()
                        Log.e("Repository", "Delete file prompt failed: $code")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Repository", "Delete file prompt exception", e)
        }
        
        // 2. Try to delete history (this removes it from view even if file delete fails)
        // This is crucial for "not showing up after refresh"
        try {
            ensureApi()
            val promptIds = mediaList.mapNotNull { it.promptId }.distinct()
            val filtered = promptIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
            if (filtered.isNotEmpty()) {
                val res = api?.controlHistory(HistoryActionRequest(delete = filtered))
                if (res?.isSuccessful == true) {
                    anySuccess = true
                } else {
                    val code = res?.code()
                    val err = try { res?.errorBody()?.string() } catch (_: Exception) { null }
                    Log.e("Repository", "Delete history failed: $code ${err ?: ""}".trim())
                }
            }
        } catch (e: Exception) {
            Log.e("Repository", "Delete history exception", e)
        }
        
        // Return true if at least one operation succeeded (either file delete or history delete)
        // If both failed (or no promptId and no delete node), we might still want to return true
        // to allow local removal if that's the desired UX. But strictly speaking it failed.
        // However, user wants "don't show", so if we can't delete server side, we rely on local hiding?
        // For now, let's return true if anySuccess.
        return anySuccess
    }

    private fun resolveDeleteClassType(objectInfo: Map<String, Any>?): String? {
        val keys = objectInfo?.keys?.map { it.trim() }?.toSet().orEmpty()
        return when {
            "DeleteFiles" in keys -> "DeleteFiles"
            "DeleteFile" in keys -> "DeleteFile"
            "DeleteImages" in keys -> "DeleteImages"
            else -> null
        }
    }

    private fun buildDeleteFilesPrompt(
        mediaList: List<MediaOutput>,
        mock: Boolean,
        objectInfo: Map<String, Any>?,
        deleteClassType: String
    ): Map<String, Any>? {
        if (mediaList.isEmpty()) return null

        val paths = mediaList.flatMap { toSafeComfyCandidatePaths(it) }.distinct()
        if (paths.isEmpty()) return null

        val classEntry = objectInfo?.get(deleteClassType) as? Map<*, *>
        val input = classEntry?.get("input") as? Map<*, *>
        val required = input?.get("required") as? Map<*, *>
        val optional = input?.get("optional") as? Map<*, *>
        val inputKeys = buildSet {
            required?.keys?.forEach { add(it?.toString().orEmpty()) }
            optional?.keys?.forEach { add(it?.toString().orEmpty()) }
        }

        val pathKey = when {
            "paths" in inputKeys -> "paths"
            "files" in inputKeys -> "files"
            "path" in inputKeys -> "path"
            "file" in inputKeys -> "file"
            "filename" in inputKeys -> "filename"
            else -> "path"
        }

        val mockKey = when {
            "mock" in inputKeys -> "mock"
            else -> null
        }

        fun buildInputsForPath(path: Any): Map<String, Any> {
            val map = linkedMapOf<String, Any>()
            map[pathKey] = path
            if (mockKey != null) map[mockKey] = mock
            if (required != null) {
                for ((kAny, specAny) in required) {
                    val k = kAny?.toString() ?: continue
                    if (k == pathKey || k == mockKey) continue
                    if (map.containsKey(k)) continue
                    val spec = specAny as? List<*>
                    val first = spec?.getOrNull(0)
                    val fallback: Any = when (first) {
                        is String -> when (first.uppercase()) {
                            "BOOLEAN" -> false
                            "INT" -> 0
                            "FLOAT" -> 0.0
                            "STRING" -> ""
                            else -> ""
                        }
                        is List<*> -> first.firstOrNull()?.toString() ?: ""
                        else -> ""
                    }
                    map[k] = fallback
                }
            }
            return map
        }

        val prompt = linkedMapOf<String, Any>()

        if (pathKey == "paths" || pathKey == "files") {
            prompt["1"] = mapOf(
                "class_type" to deleteClassType,
                "inputs" to buildInputsForPath(paths)
            )
        } else {
            var nodeId = 1
            for (path in paths) {
                val id = nodeId.toString()
                prompt[id] = mapOf(
                    "class_type" to deleteClassType,
                    "inputs" to buildInputsForPath(path)
                )
                nodeId += 1
            }
        }

        return if (prompt.isEmpty()) null else prompt
    }

    private fun toSafeComfyCandidatePaths(media: MediaOutput): List<String> {
        fun sanitizeSegment(raw: String): String? {
            val normalized = raw.replace('\\', '/').trim().trimStart('/')
            if (normalized.isBlank()) return ""
            val bad = normalized.contains("..") || normalized.contains(':') || normalized.startsWith("/")
            return if (bad) null else normalized
        }

        val type = media.type.lowercase()
        val folder = when (type) {
            "output", "input", "temp" -> type
            else -> "output"
        }

        val safeSubfolder = sanitizeSegment(media.subfolder) ?: return emptyList()
        val safeFilename = sanitizeSegment(media.filename) ?: return emptyList()
        if (safeFilename.isBlank()) return emptyList()

        val withoutType = buildString {
            if (safeSubfolder.isNotBlank()) {
                append(safeSubfolder)
                append('/')
            }
            append(safeFilename)
        }.trimStart('/')

        val withType = buildString {
            append(folder)
            append('/')
            if (safeSubfolder.isNotBlank()) {
                append(safeSubfolder)
                append('/')
            }
            append(safeFilename)
        }

        return listOf(withoutType, withType)
            .map { it.replace("//", "/").trim().trimStart('/') }
            .filter { it.isNotBlank() }
            .distinct()
    }

    fun parseHistoryItem(id: String, info: com.comfyui.mobile.model.HistoryData): HistoryItem? {
        val rawWorkflow = info.prompt?.getOrNull(0)
        val workflowMap = rawWorkflow as? Map<String, Any> ?: emptyMap()
        val rawExtra = info.prompt?.getOrNull(1)
        val extraMap = rawExtra as? Map<*, *>

        fun asString(v: Any?): String? = when (v) {
            is String -> v
            else -> null
        }

        val nameFromExtra = run {
            val extraPng = extraMap?.get("extra_pnginfo") as? Map<*, *>
            val workflow = (extraPng?.get("workflow") as? Map<*, *>)
                ?: (extraMap?.get("workflow") as? Map<*, *>)
            asString(workflow?.get("name"))
                ?: asString(workflow?.get("title"))
                ?: asString(extraMap?.get("workflow_name"))
                ?: asString(extraMap?.get("name"))
                ?: asString(extraMap?.get("title"))
        }

        val nameFromWorkflowMeta = run {
            val meta = workflowMap["_meta"] as? Map<*, *>
            asString(meta?.get("title")) ?: asString(meta?.get("name"))
        }

        // ComfyUI history "status": {"status_str": "success"|"error", "completed": bool, "messages": [[type, data], ...]}
        val statusMap = info.status
        val statusStr = asString(statusMap?.get("status_str"))
        var statusError: String? = null
        var statusInterrupted = false
        (statusMap?.get("messages") as? List<*>)?.forEach { m ->
            val pair = m as? List<*> ?: return@forEach
            when (asString(pair.getOrNull(0))) {
                "execution_interrupted" -> statusInterrupted = true
                "execution_error" -> {
                    val d = pair.getOrNull(1) as? Map<*, *>
                    val msg = asString(d?.get("exception_message"))?.trim()?.ifBlank { null }
                        ?: asString(d?.get("exception_type"))
                    val node = d?.get("node_id")?.toString()?.ifBlank { null }
                    val nodeType = asString(d?.get("node_type"))
                    statusError = buildString {
                        if (node != null) append("节点 $node${if (nodeType != null) " ($nodeType)" else ""}: ")
                        append(msg ?: "未知错误")
                    }
                }
            }
        }

        if (workflowMap.isNotEmpty() || info.outputs != null) {
            return HistoryItem(
                promptId = id,
                workflow = workflowMap,
                outputs = info.outputs,
                name = nameFromExtra ?: nameFromWorkflowMeta,
                statusStr = statusStr,
                errorMessage = statusError,
                interrupted = statusInterrupted
            )
        }
        return null
    }

    suspend fun getHistoryItem(promptId: String): HistoryItem? {
        val trimmed = promptId.trim()
        if (trimmed.isBlank() || cloudActive) return null
        return try {
            ensureApi()
            val response = api?.getHistoryForPrompt(trimmed)
            if (response?.isSuccessful == true) {
                val body = response.body() ?: emptyMap()
                val info = body[trimmed] ?: body.values.firstOrNull() ?: return null
                parseHistoryItem(trimmed, info)
            } else {
                getHistory().firstOrNull { it.promptId == trimmed }
            }
        } catch (e: Exception) {
            Log.e("Repository", "Get history for prompt $trimmed failed", e)
            null
        }
    }

    /** Legacy helper: empty list on error. Prefer [getHistoryOrNull] where "error" must differ from "empty". */
    suspend fun getHistory(): List<HistoryItem> = getHistoryOrNull() ?: emptyList()

    /** History items, or null when it could not be fetched (network error / non-2xx / missing body). */
    suspend fun getHistoryOrNull(): List<HistoryItem>? {
        if (cloudActive) return null
        return try {
            ensureApi()
            val response = api?.getHistory()
            if (response?.isSuccessful == true) {
                val body = response.body() ?: return null
                body.entries.mapNotNull { (id, info) ->
                    parseHistoryItem(id, info)
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("Repository", "Get history failed: ${e.javaClass.simpleName}")
            null
        }
    }

    /** Legacy helper: empty list on error. Prefer [getQueueOrNull] where "error" must differ from "empty". */
    suspend fun getQueue(): List<QueueDto> = getQueueOrNull() ?: emptyList()

    /** Queue entries (running + pending), or null when the queue state is unknown. */
    suspend fun getQueueOrNull(): List<QueueDto>? {
        if (cloudActive) return null
        return try {
            ensureApi()
            val response = api?.getQueue()
            if (response?.isSuccessful == true) {
                val body: QueueResponse = response.body() ?: return null
                val running = body.queue_running ?: emptyList()
                val pending = body.queue_pending ?: emptyList()
                val all = running + pending
                all.mapNotNull { entry ->
                    if (entry.size >= 2) {
                        val first = entry[0]?.toString().orEmpty()
                        val second = entry[1]?.toString().orEmpty()
                        val actualPromptId = if (second.contains("-") || second.length > first.length) second else first
                        val actualNumber = if (actualPromptId == second) first else second
                        if (actualPromptId.isNotBlank()) {
                            QueueDto(promptId = actualPromptId, id = actualNumber)
                        } else null
                    } else {
                        null
                    }
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("Repository", "Get queue failed: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * Prompt ids currently running or pending on the server.
     * Returns null when the queue state is unknown (network error, non-2xx, missing body);
     * callers must treat null as "keep waiting", never as an empty queue.
     */
    suspend fun getActiveQueuePromptIds(): Set<String>? {
        if (cloudActive) return null
        return try {
            ensureApi()
            val response = api?.getQueue()
            if (response?.isSuccessful == true) {
                val body: QueueResponse = response.body() ?: return null
                if (body.queue_running == null && body.queue_pending == null) return null
                val running = body.queue_running ?: emptyList()
                val pending = body.queue_pending ?: emptyList()
                val all = running + pending
                all.mapNotNull { entry ->
                    if (entry.size >= 2) {
                        val first = entry[0]?.toString().orEmpty()
                        val second = entry[1]?.toString().orEmpty()
                        val actualPromptId = if (second.contains("-") || second.length > first.length) second else first
                        actualPromptId.ifBlank { null }
                    } else null
                }.toSet()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("Repository", "Get active queue prompt ids failed: ${e.javaClass.simpleName}")
            null
        }
    }

    suspend fun clearQueue() {
        if (cloudActive) return
        try {
            ensureApi()
            api?.controlQueue(QueueActionRequest(clear = true))
        } catch (e: Exception) {
            Log.e("Repository", "Clear Queue Failed", e)
        }
    }
    
    suspend fun interrupt() {
        if (cloudActive) return
        try {
            ensureApi()
            api?.interrupt()
        } catch (e: Exception) {
            Log.e("Repository", "Interrupt Failed", e)
        }
    }

    fun getImageUrl(filename: String, subfolder: String, type: String): String {
        if (filename.startsWith("http://", ignoreCase = true) || filename.startsWith("https://", ignoreCase = true)) {
            return filename
        }
        val base = _baseUrl.value.trimEnd('/')
        val built = (base + "/").toHttpUrlOrNull()
            ?.newBuilder()
            ?.addPathSegment("view")
            ?.addQueryParameter("filename", filename)
            ?.addQueryParameter("subfolder", subfolder)
            ?.addQueryParameter("type", type)
            ?.build()
            ?.toString()
        if (built != null) return built
        val enc = java.net.URLEncoder.encode(filename, Charsets.UTF_8.name())
        val encSub = java.net.URLEncoder.encode(subfolder, Charsets.UTF_8.name())
        val encType = java.net.URLEncoder.encode(type, Charsets.UTF_8.name())
        return "$base/view?filename=$enc&subfolder=$encSub&type=$encType"
    }

    /** Drop REST client + WebSocket without reconnecting. */
    fun disconnect() {
        api = null
        objectInfoCache = null
        webSocketManager.disconnect()
    }
    
    suspend fun getTextContent(filename: String, subfolder: String, type: String): String? {
        return try {
            ensureApi()
            val response = api?.getView(filename, subfolder, type)
            if (response?.isSuccessful == true) {
                response.body()?.string()
            } else {
                try { response?.errorBody()?.close() } catch (_: Exception) {}
                null
            }
        } catch (e: Exception) {
            Log.e("Repository", "Get Text Content Failed", e)
            null
        }
    }

    suspend fun downloadViewToFile(
        filename: String,
        subfolder: String,
        type: String,
        dest: java.io.File
    ): Boolean {
        return try {
            ensureApi()
            val response = api?.getView(filename, subfolder, type)
            if (response?.isSuccessful != true) {
                try { response?.errorBody()?.close() } catch (_: Exception) {}
                return false
            }
            val body: ResponseBody = response.body() ?: return false
            dest.parentFile?.mkdirs()
            body.byteStream().use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            Log.e("Repository", "Download view failed", e)
            false
        }
    }
    
    suspend fun uploadImage(file: java.io.File): UploadResponse? {
        if (cloudActive) return null
        return try {
            ensureApi()
            val requestBody = file.asRequestBody("image/*".toMediaType())
            val body = okhttp3.MultipartBody.Part.createFormData("image", file.name, requestBody)
            val response = api?.uploadImage(body)
            if (response?.isSuccessful == true) {
                response.body()
            } else {
                try { response?.errorBody()?.close() } catch (_: Exception) {}
                null
            }
        } catch (e: Exception) {
            Log.e("Repository", "Upload Failed", e)
            null
        }
    }

    suspend fun deleteHistoryIds(ids: List<String>): Boolean {
        if (ids.isEmpty()) return true
        if (cloudActive) return false
        return try {
            ensureApi()
            val response = api?.controlHistory(HistoryActionRequest(delete = ids))
            val ok = response?.isSuccessful == true
            if (!ok) {
                try { response?.errorBody()?.close() } catch (_: Exception) {}
            }
            ok
        } catch (e: Exception) {
            Log.e("Repository", "Delete history ids failed", e)
            false
        }
    }

    suspend fun getObjectInfo(force: Boolean = false): Map<String, Any>? {
        if (!force) {
            val cached = objectInfoCache
            if (cached != null) return cached
        }
        return try {
            ensureApi()
            val response = api?.getObjectInfo()
            if (response?.isSuccessful == true) {
                val body = response.body()
                if (body != null) objectInfoCache = body
                body
            } else {
                try { response?.errorBody()?.close() } catch (_: Exception) {}
                null
            }
        } catch (e: Exception) {
            Log.e("Repository", "Get object_info failed", e)
            null
        }
    }

    suspend fun getSystemStats(): com.comfyui.mobile.model.SystemStatsResponse? {
        if (cloudActive) return null
        return try {
            ensureApi()
            val response = api?.getSystemStats()
            if (response?.isSuccessful == true) {
                response.body()
            } else {
                try { response?.errorBody()?.close() } catch (_: Exception) {}
                null
            }
        } catch (e: Exception) {
            Log.e("Repository", "Get system_stats failed", e)
            null
        }
    }

    /** Optional Bridge 2.1 host telemetry; native system_stats remains the fallback. */
    suspend fun getMobileSystemMonitor(): com.comfyui.mobile.model.MobileSystemMonitor? {
        if (cloudActive) return null
        return try {
            ensureApi()
            val response = api?.getMobileSystemMonitor()
            if (response?.isSuccessful == true) {
                response.body()?.takeIf { it.code == 0 }?.data
            } else {
                response?.errorBody()?.close()
                null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getLocalResourceList(
        resourceType: String,
        keyword: String,
        page: Int,
        pageSize: Int,
        baseModel: String? = null
    ): Result<com.comfyui.mobile.network.RhResourcePageData> {
        return try {
            ensureApi()
            val response = api?.getMobileResourceList(resourceType, keyword, page, pageSize, baseModel)
            if (response?.isSuccessful == true) {
                val body = response.body()
                if (body?.data != null) {
                    Result.success(body.data)
                } else {
                    Result.failure(IllegalStateException(body?.msg ?: "未获取到模型数据"))
                }
            } else {
                val code = response?.code() ?: -1
                try { response?.errorBody()?.close() } catch (_: Exception) {}
                if (code == 404) {
                    Result.failure(IllegalStateException("未检测到本地模型库服务，请确认 ComfyUI 的 custom_nodes 目录下已放入 ComfyUI-Mobile-Bridge 插件"))
                } else {
                    Result.failure(IllegalStateException("获取本地模型失败 (HTTP $code)"))
                }
            }
        } catch (e: Exception) {
            Log.e("Repository", "Get mobile resource list failed", e)
            Result.failure(e)
        }
    }

    suspend fun getAssetsSummary(): Result<com.comfyui.mobile.model.AssetsSummaryData> {
        return try {
            ensureApi()
            val response = api?.getAssetsSummary()
            if (response?.isSuccessful == true) {
                val body = response.body()
                if (body?.data != null) {
                    Result.success(body.data)
                } else {
                    Result.failure(IllegalStateException(body?.msg ?: "未获取到资产摘要"))
                }
            } else {
                val code = response?.code() ?: -1
                try { response?.errorBody()?.close() } catch (_: Exception) {}
                Result.failure(IllegalStateException("获取资产摘要失败 (HTTP $code)"))
            }
        } catch (e: Exception) {
            Log.e("Repository", "Get assets summary failed", e)
            Result.failure(e)
        }
    }

    suspend fun searchCivitai(
        query: String,
        type: String? = null,
        baseModel: String? = null,
        page: Int = 1,
        cursor: String? = null,
        limit: Int = 20,
        sort: String? = null,
        username: String? = null,
        period: String? = null,
        nsfw: Boolean? = null,
        ids: String? = null
    ): Result<com.comfyui.mobile.model.CivitaiSearchPageData> {
        return try {
            ensureApi()
            val response = api?.searchCivitai(query, type, baseModel, page, cursor, limit, sort, username, period, nsfw, ids)
            if (response?.isSuccessful == true) {
                val body = response.body()
                if (body?.data != null) {
                    Result.success(body.data)
                } else {
                    Result.failure(IllegalStateException(body?.msg ?: "未获取到搜索结果"))
                }
            } else {
                val code = response?.code() ?: -1
                try { response?.errorBody()?.close() } catch (_: Exception) {}
                Result.failure(IllegalStateException("Civitai 搜索失败 (HTTP $code)"))
            }
        } catch (e: Exception) {
            Log.e("Repository", "Civitai search failed", e)
            Result.failure(e)
        }
    }

    suspend fun getModelFolders(type: String): Result<List<com.comfyui.mobile.model.ModelFolderItem>> {
        return try {
            ensureApi()
            val response = api?.getModelFolders(type)
            if (response?.isSuccessful == true) {
                Result.success(response.body()?.data?.folders ?: emptyList())
            } else {
                Result.failure(IllegalStateException("获取模型目录失败 (${response?.code()})"))
            }
        } catch (e: Exception) {
            Log.e("Repository", "getModelFolders failed", e)
            Result.failure(e)
        }
    }

    suspend fun downloadCivitaiModel(
        modelId: Long,
        modelVersionId: Long,
        modelType: String? = null,
        modelRoot: String? = null,
        subfolder: String? = null,
        fileId: Long? = null,
        downloadUrl: String? = null,
        fileType: String? = null,
        role: String? = null
    ): Result<String> {
        return try {
            ensureApi()
            val req = com.comfyui.mobile.model.CivitaiDownloadRequest(
                modelId = modelId,
                modelVersionId = modelVersionId,
                modelType = modelType,
                modelRoot = modelRoot,
                subfolder = subfolder,
                fileId = fileId,
                downloadUrl = downloadUrl,
                fileType = fileType,
                role = role
            )
            val response = api?.downloadCivitaiModel(req)
            if (response?.isSuccessful == true && response.body()?.code == 0) {
                Result.success(response.body()?.msg ?: "已提交下载任务")
            } else {
                val code = response?.code() ?: -1
                val errRaw = response?.errorBody()?.string() ?: ""
                val errMsg = runCatching { org.json.JSONObject(errRaw).optString("msg") }
                    .getOrNull()?.takeIf { it.isNotBlank() }
                if (code == 404 && errMsg == null) return Result.failure(IllegalStateException(
                    "请更新电脑上的 Mobile Bridge 2.0 并重启 ComfyUI，当前插件没有原生下载接口"))
                Result.failure(IllegalStateException(
                    response?.body()?.msg ?: errMsg ?: "提交下载失败 (HTTP $code)"))
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e("Repository", "Civitai download failed", e)
            Result.failure(e)
        }
    }

    suspend fun getCivitaiDownloadTasks(): Result<com.comfyui.mobile.model.CivitaiTasksData> {
        return try {
            ensureApi()
            val response = api?.getCivitaiDownloadTasks()
            if (response?.isSuccessful == true && response.body()?.code == 0) {
                Result.success(response.body()?.data ?: com.comfyui.mobile.model.CivitaiTasksData())
            } else {
                val code = response?.code() ?: -1
                Result.failure(IllegalStateException(if (code == 404)
                    "请更新电脑上的 Mobile Bridge 2.0 并重启 ComfyUI，以使用原生下载队列"
                    else response?.body()?.msg ?: "获取下载队列失败 (HTTP $code)"))
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e("Repository", "Civitai tasks failed", e)
            Result.failure(e)
        }
    }

    suspend fun civitaiTaskAction(
        action: String,
        downloadId: String
    ): Result<Unit> {
        return try {
            ensureApi()
            val req = com.comfyui.mobile.model.CivitaiTaskActionRequest(action, downloadId)
            val response = api?.civitaiTaskAction(req)
            if (response?.isSuccessful == true && response.body()?.code == 0) {
                Result.success(Unit)
            } else {
                val raw = response?.errorBody()?.string().orEmpty()
                val message = runCatching { org.json.JSONObject(raw).optString("msg") }.getOrNull()
                Result.failure(IllegalStateException(message?.takeIf { it.isNotBlank() }
                    ?: response?.body()?.msg ?: "任务操作失败 (HTTP ${response?.code()})"))
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e("Repository", "Civitai task action failed", e)
            Result.failure(e)
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: ComfyRepository? = null

        fun getInstance(): ComfyRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ComfyRepository().also { INSTANCE = it }
            }
        }
    }
}
