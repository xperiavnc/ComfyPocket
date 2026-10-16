package com.comfyui.mobile.network

import android.util.Log
import com.comfyui.mobile.model.RunningHubSite
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Isolated HTTP client for the RunningHub official API.
 *
 * - Own OkHttpClient (own dispatcher + connection pool) with the platform's normal certificate and
 *   hostname verification (no trust-all).
 * - Adds `Authorization: Bearer <key>` only for requests to this profile's site host.
 * - Logs method / redacted URL / status / duration only (never bodies or headers).
 * - The API key never goes into a URL, except for `/api/webapp/apiCallDemo`, whose documented form
 *   requires it as a query parameter; logged URLs and exception texts are always redacted.
 */
class RhHttpClient(val site: RunningHubSite, apiKey: String) {

    private val apiKey: String = apiKey.trim()
    val hasKey: Boolean get() = apiKey.isNotBlank()
    private val siteHost: String = site.hostUrl.toHttpUrlOrNull()?.host.orEmpty()
    private val gson = Gson()

    private val dispatcher = Dispatcher()
    private val pool = ConnectionPool(4, 2, TimeUnit.MINUTES)

    private val authInterceptor = Interceptor { chain ->
        val req = chain.request()
        if (apiKey.isNotBlank() && req.url.host.equals(siteHost, ignoreCase = true) && req.header("Authorization") == null) {
            chain.proceed(req.newBuilder().header("Authorization", "Bearer $apiKey").build())
        } else {
            chain.proceed(req)
        }
    }

    private val loggingInterceptor = Interceptor { chain ->
        val req = chain.request()
        val started = System.nanoTime()
        val safeUrl = SecretRedactor.redact(req.url.toString())
        try {
            val resp = chain.proceed(req)
            val ms = (System.nanoTime() - started) / 1_000_000
            Log.d(TAG, "${req.method} $safeUrl -> HTTP ${resp.code} (${ms}ms)")
            resp
        } catch (e: Exception) {
            Log.w(TAG, "${req.method} $safeUrl failed: ${SecretRedactor.redact(e.message)}")
            throw e
        }
    }

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .connectionPool(pool)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(authInterceptor)
        .addInterceptor(loggingInterceptor)
        .build()

    /** For output fileUrl / cover downloads (CDN hosts): no auth header, longer read timeout. */
    val downloadClient: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .connectionPool(pool)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(loggingInterceptor)
        .build()

    val api: RunningHubTaskApi = Retrofit.Builder()
        .baseUrl(site.hostUrl.trimEnd('/') + "/")
        .client(okHttp)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
        .create(RunningHubTaskApi::class.java)

    @Volatile
    var disposed: Boolean = false
        private set

    /** Cancels every in-flight call of this client and drops its pooled connections. */
    fun dispose() {
        disposed = true
        runCatching { dispatcher.cancelAll() }
        runCatching { pool.evictAll() }
    }

    private fun requireKey() {
        if (apiKey.isBlank()) throw RhApiException(802, "未配置 API Key")
        if (disposed) throw IllegalStateException("RunningHub 连接已切换/释放")
    }

    private suspend fun envelope(block: suspend RunningHubTaskApi.() -> Response<RhEnvelope>): RhEnvelope {
        requireKey()
        val resp = api.block()
        if (!resp.isSuccessful) {
            val raw = runCatching { resp.errorBody()?.string() }.getOrNull().orEmpty()
            val env = runCatching { gson.fromJson(raw, RhEnvelope::class.java) }.getOrNull()
            throw RhApiException(env?.code, SecretRedactor.redact(env?.text ?: raw.take(200)), resp.code())
        }
        return resp.body() ?: throw RhApiException(null, "空响应", resp.code())
    }

    private fun okOrThrow(env: RhEnvelope): RhEnvelope {
        if (!env.isOk) throw RhApiException(env.code, env.text)
        return env
    }

    // ---------------- endpoints ----------------

    suspend fun runAiApp(webappId: String, nodes: List<RhNodeInfo>, instanceType: String?): Result<RhSubmitResult> = runCatchingIo {
        val env = okOrThrow(envelope { runAiApp(RhAiAppRunRequest(apiKey, webappId.trim(), nodes, instanceType)) })
        parseSubmit(env.data)
    }

    suspend fun createTask(
        workflowId: String?,
        nodes: List<RhNodeInfo>?,
        instanceType: String?,
        workflowJson: String? = null
    ): Result<RhSubmitResult> = runCatchingIo {
        val req = RhCreateTaskRequest(
            apiKey = apiKey,
            workflowId = workflowId?.trim()?.ifBlank { null },
            workflow = workflowJson?.ifBlank { null },
            nodeInfoList = nodes?.ifEmpty { null },
            addMetadata = null,
            instanceType = instanceType
    )
        val env = okOrThrow(envelope { createTask(req) })
        parseSubmit(env.data)
    }

    suspend fun taskStatus(taskId: String): Result<String> = runCatchingIo {
        val env = okOrThrow(envelope { taskStatus(RhTaskIdRequest(apiKey, taskId)) })
        str(env.data) ?: "UNKNOWN"
    }

    suspend fun taskOutputs(taskId: String): Result<RhOutputsResult> = runCatchingIo {
        val env = envelope { taskOutputs(RhTaskIdRequest(apiKey, taskId)) }
        when (env.code) {
            RhError.OK, RhError.OK_V2 -> {
                val arr = env.data?.takeIf { it.isJsonArray }?.asJsonArray
                val files = arr?.mapNotNull { el ->
                    val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    val url = str(o, "fileUrl") ?: return@mapNotNull null
                    RhOutputFile(
                        fileUrl = url,
                        fileType = str(o, "fileType"),
                        nodeId = str(o, "nodeId"),
                        taskCostTime = str(o, "taskCostTime"),
                        consumeCoins = str(o, "consumeCoins"),
                        consumeMoney = str(o, "consumeMoney"),
                        thirdPartyConsumeMoney = str(o, "thirdPartyConsumeMoney")
    )
                }.orEmpty()
                RhOutputsResult.Success(files)
            }
            RhError.TASK_RUNNING -> RhOutputsResult.Running(queued = false)
            RhError.TASK_QUEUED -> RhOutputsResult.Running(queued = true)
            RhError.TASK_FAILED -> {
                val reasonEl = env.data?.takeIf { it.isJsonObject }?.asJsonObject?.get("failedReason")
                val reason = when {
                    reasonEl == null || reasonEl.isJsonNull -> null
                    reasonEl.isJsonObject -> reasonEl.asJsonObject.let { r ->
                        RhFailedReason(str(r, "exception_type"), str(r, "exception_message"), str(r, "node_name"), str(r, "node_id"))
                    }
                    else -> RhFailedReason(null, str(reasonEl), null, null)
                }
                RhOutputsResult.Failed(reason, reason?.display ?: RhError.message(env.code, env.text))
            }
            else -> throw RhApiException(env.code, env.text)
        }
    }

    suspend fun cancelTask(taskId: String): Result<Unit> = runCatchingIo {
        okOrThrow(envelope { cancelTask(RhTaskIdRequest(apiKey, taskId)) })
        Unit
    }

    /** Uploads an input file via the documented legacy endpoint; returns data.fileName for use as fieldValue. */
    suspend fun uploadInput(file: File, mimeType: String?, displayName: String? = null): Result<String> = runCatchingIo {
        if (file.length() > 30L * 1024 * 1024) throw RhApiException(809, null)
        val part = MultipartBody.Part.createFormData(
            "file",
            displayName?.takeIf { it.isNotBlank() } ?: file.name,
            file.asRequestBody((mimeType ?: "application/octet-stream").toMediaTypeOrNull())
        )
        val text = "text/plain".toMediaTypeOrNull()
        val env = okOrThrow(envelope { uploadInput(apiKey.toRequestBody(text), "input".toRequestBody(text), part) })
        val data = env.data?.takeIf { it.isJsonObject }?.asJsonObject
        str(data, "fileName") ?: throw RhApiException(808, "上传成功但未返回 fileName")
    }

    /**
     * Official model / LoRA library page (POST openapi/v2/resource/list). The API key is sent only in the
     * Authorization header (never in the URL or body).
     */
    suspend fun resourceList(
        resourceType: String,
        keyword: String,
        page: Int,
        pageSize: Int,
        baseModels: List<String>? = null
    ): Result<RhResourcePageData> = runCatchingIo {
        val req = RhResourceListRequest(
            resourceType = resourceType,
            resourceName = keyword.trim(),
            current = page.coerceAtLeast(1),
            size = pageSize.coerceIn(1, 50),
            baseModels = baseModels?.ifEmpty { null },
            apiKey = null
    )
        val env = okOrThrow(envelope { resourceList(req) })
        parseResourcePage(env.data)
    }

    suspend fun accountStatus(): Result<RhAccountData> = runCatchingIo {
        val env = okOrThrow(envelope { accountStatus(RhAccountStatusRequest(apiKey)) })
        val data = env.data?.takeIf { it.isJsonObject } ?: return@runCatchingIo RhAccountData()
        val type = object : com.google.gson.reflect.TypeToken<Map<String, Any?>>() {}.type
        val rawMap: Map<String, Any?> = runCatching { gson.fromJson<Map<String, Any?>>(data, type) }.getOrNull() ?: emptyMap()
        val typed = runCatching { gson.fromJson(data, RhAccountData::class.java) }.getOrNull()
        (typed ?: RhAccountData()).copy(rawMap = rawMap)
    }

    suspend fun queueStatus(): Result<RhQueueStatus> = runCatchingIo {
        val env = okOrThrow(envelope { this.queueStatus() })
        val o = env.data?.takeIf { it.isJsonObject }?.asJsonObject
        RhQueueStatus(
            apiKeyType = str(o, "apiKeyType"),
            concurrentLimit = str(o, "concurrentLimit")?.toDoubleOrNull()?.toInt(),
            runningCount = str(o, "runningCount")?.toDoubleOrNull()?.toInt(),
            queuedCount = str(o, "queuedCount")?.toDoubleOrNull()?.toInt(),
            totalCurrentTasks = str(o, "totalCurrentTasks")?.toDoubleOrNull()?.toInt()
    )
    }

    suspend fun webAppDemo(webappId: String): Result<RhWebAppDemoData> = runCatchingIo {
        val env = okOrThrow(envelope { webAppApiCallDemo(apiKey, webappId.trim()) })
        val data = env.data?.takeIf { it.isJsonObject } ?: throw RhApiException(RhError.WEBAPP_NOT_FOUND, env.text)
        gson.fromJson(data, RhWebAppDemoData::class.java) ?: throw RhApiException(RhError.WEBAPP_NOT_FOUND, env.text)
    }

    suspend fun workflowJson(workflowId: String): Result<RhWorkflowResult> = runCatchingIo {
        val env = okOrThrow(envelope { workflowJsonApiFormat(RhWorkflowJsonRequest(apiKey, workflowId.trim())) })
        val o = env.data?.takeIf { it.isJsonObject }?.asJsonObject ?: throw RhApiException(380, env.text)
        val prompt = str(o, "prompt")?.takeIf { it.isNotBlank() } ?: throw Exception("接口返回成功但 prompt 拓扑为空")
        val title = (str(o, "workflowTitle") ?: str(o, "workflowName") ?: str(o, "title") ?: str(o, "name"))?.trim()
        RhWorkflowResult(prompt = prompt, title = title)
    }

    /** Public page title; getJsonApiFormat only returns the prompt graph, not the name shown on the website. */
    suspend fun workflowPublicName(workflowId: String): String? = runCatching {
        val env = envelope { portalWorkflowDetail(RhPortalWorkflowDetailRequest(workflowId.trim())) }
        if (!env.isOk) return@runCatching null
        val data = env.data ?: return@runCatching null
        extractWorkflowName(data)
    }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }

    private fun extractWorkflowName(el: JsonElement): String? {
        if (el.isJsonObject) {
            val o = el.asJsonObject
            sequenceOf("workflowName", "nameCn", "wfName", "name", "title", "workflowTitle", "creationName")
                .mapNotNull { str(o, it)?.trim()?.takeIf { n -> n.isNotBlank() } }
                .firstOrNull()?.let { return it }
            sequenceOf("workflow", "creation", "info", "detail")
                .mapNotNull { o.get(it) }
                .firstNotNullOfOrNull { extractWorkflowName(it) }
                ?.let { return it }
        }
        return null
    }

    /** Downloads [url] (no auth header) into [dest]. Returns true when a non-empty file was written. */
    suspend fun download(url: String, dest: File): Boolean = withContext(Dispatchers.IO) {
        if (disposed) return@withContext false
        try {
            val req = Request.Builder().url(url).get().build()
            downloadClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                val body = resp.body ?: return@withContext false
                dest.parentFile?.mkdirs()
                dest.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
            dest.exists() && dest.length() > 0L
        } catch (e: Exception) {
            Log.w(TAG, "download failed: ${SecretRedactor.redact(e.message)}")
            runCatching { dest.delete() }
            false
        }
    }

    // ---------------- helpers ----------------

    private fun parseSubmit(data: JsonElement?): RhSubmitResult {
        val o = data?.takeIf { it.isJsonObject }?.asJsonObject
        val taskId = str(o, "taskId")?.takeIf { it.isNotBlank() } ?: throw RhApiException(null, "提交成功但未返回 taskId")
        return RhSubmitResult(taskId = taskId, taskStatus = str(o, "taskStatus"), promptTips = str(o, "promptTips"))
    }

    private suspend fun <T> runCatchingIo(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            Result.success(block())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: RhApiException) {
            Result.failure(e)
        } catch (e: java.io.IOException) {
            // Network failure: keep the IOException type (callers treat it as "connection failed"),
            // but never let a URL with a key reach the UI through the message.
            Result.failure(java.io.IOException(SecretRedactor.redact(e.message ?: e.javaClass.simpleName), e))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Official docs: data.records is an array of {id, resourceName, resourceType, nodeModelName,
     * posterUrl, thumbnailUrl, versions:[{versionResourceName, baseModel, triggerWords}, ...]}.
     * Also accepts data as the array itself, or list/items/rows, so a real-key payload still parses
     * if RunningHub wraps it slightly differently.
     */
    private fun parseResourcePage(data: JsonElement?): RhResourcePageData {
        if (data == null || data.isJsonNull) return RhResourcePageData()
        if (data.isJsonArray) {
            val records = data.asJsonArray.mapNotNull { parseResourceItem(it) }
            return RhResourcePageData(records = records, total = records.size, current = 1, size = records.size, hasNext = false)
        }
        if (!data.isJsonObject) return RhResourcePageData()
        val o = data.asJsonObject
        val arr = sequenceOf("records", "list", "items", "rows", "resourceList")
            .mapNotNull { o.get(it) }
            .firstOrNull { it.isJsonArray || it.isJsonObject }
        val records = when {
            arr == null || arr.isJsonNull -> emptyList()
            arr.isJsonArray -> arr.asJsonArray.mapNotNull { parseResourceItem(it) }
            arr.isJsonObject -> listOfNotNull(parseResourceItem(arr))
            else -> emptyList()
        }
        val total = intField(o, "total") ?: records.size
        val current = intField(o, "current") ?: intField(o, "page") ?: 1
        val size = intField(o, "size") ?: intField(o, "pageSize") ?: records.size.coerceAtLeast(1)
        val pages = intField(o, "pages") ?: if (size > 0) (total + size - 1) / size else 0
        val hasNext = boolField(o, "hasNext") ?: (current * size < total)
        return RhResourcePageData(records, total, current, size, pages, hasNext)
    }

    private fun parseResourceItem(el: JsonElement?): RhResourceItem? {
        if (el == null || !el.isJsonObject) return null
        val o = el.asJsonObject
        val resourceName = str(o, "resourceName") ?: str(o, "name") ?: str(o, "title")
        val nodeModelName = str(o, "nodeModelName") ?: str(o, "modelName") ?: str(o, "fileName")
        val versionsEl = o.get("versions")
        val versions = if (versionsEl != null && versionsEl.isJsonArray) {
            versionsEl.asJsonArray.mapNotNull { vEl ->
                if (vEl == null || !vEl.isJsonObject) return@mapNotNull null
                val v = vEl.asJsonObject
                RhResourceVersion(
                    id = v.get("id")?.takeUnless { it.isJsonNull },
                    version = str(v, "version"),
                    versionResourceName = str(v, "versionResourceName") ?: str(v, "resourceName"),
                    baseModel = str(v, "baseModel"),
                    triggerWords = str(v, "triggerWords") ?: str(v, "triggerWord")
    )
            }
        } else emptyList()
        if (resourceName.isNullOrBlank() && nodeModelName.isNullOrBlank() && versions.isEmpty()) return null
        return RhResourceItem(
            id = o.get("id")?.takeUnless { it.isJsonNull },
            resourceName = resourceName,
            resourceType = str(o, "resourceType"),
            nodeModelName = nodeModelName,
            posterUrl = str(o, "posterUrl") ?: str(o, "coverUrl"),
            thumbnailUrl = str(o, "thumbnailUrl") ?: str(o, "thumbUrl"),
            desc = str(o, "desc") ?: str(o, "description"),
            versions = versions.ifEmpty { null }
    )
    }

    private fun intField(o: JsonObject, key: String): Int? {
        val el = o.get(key) ?: return null
        if (el.isJsonNull) return null
        if (el.isJsonPrimitive) {
            val p = el.asJsonPrimitive
            if (p.isNumber) return p.asNumber.toInt()
            if (p.isString) return p.asString.trim().toDoubleOrNull()?.toInt()
        }
        return null
    }

    private fun boolField(o: JsonObject, key: String): Boolean? {
        val el = o.get(key) ?: return null
        if (el.isJsonNull || !el.isJsonPrimitive) return null
        val p = el.asJsonPrimitive
        return when {
            p.isBoolean -> p.asBoolean
            p.isNumber -> p.asNumber.toInt() != 0
            p.isString -> p.asString.equals("true", true) || p.asString == "1"
            else -> null
        }
    }

    companion object {
        private const val TAG = "RhHttp"

        fun str(el: JsonElement?): String? = when {
            el == null || el.isJsonNull -> null
            el.isJsonPrimitive -> el.asString
            else -> el.toString()
        }

        fun str(o: JsonObject?, key: String): String? = str(o?.get(key))

        /** RunningHub-owned hosts (www.runninghub.cn / .ai and subdomains): always strict TLS. */
        fun isRunningHubHost(host: String?): Boolean {
            val h = host?.trim()?.lowercase()?.trimEnd('.') ?: return false
            return h == "runninghub.cn" || h == "runninghub.ai" ||
                h.endsWith(".runninghub.cn") || h.endsWith(".runninghub.ai")
        }
    }
}