package com.comfyui.mobile.network

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// --- 账户状态与余额 ---

data class RhAccountData(
    val currentTaskCounts: Any? = null,
    val balance: Any? = null,
    val remainCoins: Any? = null,
    val remainCoin: Any? = null,
    val coins: Any? = null,
    val coin: Any? = null,
    val userCoins: Any? = null,
    val points: Any? = null,
    val credit: Any? = null,
    val credits: Any? = null,
    val userName: String? = null,
    val accountName: String? = null,
    /** RMB wallet balance reported by /uc/openapi/accountStatus (nullable, may be absent). */
    val remainMoney: Any? = null,
    val currency: String? = null,
    val apiType: String? = null,
    val rawMap: Map<String, Any?>? = null
) {
    private fun parseNum(v: Any?): Double? {
        return when (v) {
            is Number -> v.toDouble()
            is String -> v.trim().replace(",", "").toDoubleOrNull()
            else -> null
        }
    }

    /** Null when the response carried no recognizable balance field (never pretend it is 0). */
    val displayCoins: Double?
        get() = parseNum(remainCoins)
            ?: parseNum(remainCoin)
            ?: parseNum(coins)
            ?: parseNum(coin)
            ?: parseNum(userCoins)
            ?: parseNum(balance)
            ?: parseNum(points)
            ?: parseNum(credit)
            ?: parseNum(credits)

    val displayMoney: Double?
        get() = parseNum(remainMoney)

    val taskCounts: Int
        get() = (parseNum(currentTaskCounts) ?: 0.0).toInt()

    val displayUser: String
        get() = userName?.ifBlank { null } ?: accountName?.ifBlank { null } ?: ""
}

data class RhAccountStatusResponse(
    val code: Int = -1,
    val msg: String? = null,
    val data: Any? = null
)

// --- 云端任务记录 ---

data class RhTaskRecord(
    val taskId: String,
    val costCoins: Double = 0.0,
    val taskStatus: String = "已完成",
    val timestamp: Long = System.currentTimeMillis(),
    val taskName: String? = null,
    /** true = cost could not be measured from a clean balance delta (costCoins is then 0 / unknown). */
    val costEstimated: Boolean = false,
    /** GPU tier display name captured at submit time. */
    val gpuTier: String? = null,
    val errorMessage: String? = null,
    // --- Phase 2a view fields (derived from TaskStore; absent in the legacy pref JSON) ---
    val localId: String? = null,
    /** "API" (real charge from the task API), "BALANCE_DELTA", "ESTIMATE" or null (unknown). */
    val costSource: String? = null,
    val costMoney: Double? = null,
    val durationSec: Double? = null,
    /** "official_api" or "proxy". */
    val channel: String? = null
    )

// --- 工作流 API JSON 获取 ---

data class RhWorkflowPromptData(
    val prompt: String? = null,
    val workflowTitle: String? = null,
    val workflowName: String? = null,
    val title: String? = null,
    val name: String? = null
)

data class RhWorkflowResult(
    val prompt: String,
    val title: String? = null
)

data class RhWorkflowJsonResponse(
    val code: Int = -1,
    val msg: String? = null,
    val data: Any? = null
)

// --- AI 应用详情获取 ---

data class RhWebAppCover(
    val id: Any? = null,
    val url: String? = null,
    val thumbnailUri: String? = null
)

data class RhWebAppDemoData(
    val webappName: String? = null,
    val nodeInfoList: List<Map<String, Any>>? = null,
    val covers: List<RhWebAppCover>? = null,
    val statisticsInfo: Map<String, Any>? = null
)

data class RhWebAppDemoResponse(
    val code: Int = -1,
    val msg: String? = null,
    val data: RhWebAppDemoData? = null
)

// --- 在线模型与 LoRA 资源 ---

data class RhResourceListRequest(
    val resourceType: String = "CHECKPOINT",
    val resourceName: String = "",
    val current: Int = 1,
    val size: Int = 20,
    val baseModels: List<String>? = null,
    val apiKey: String? = null
)

data class RhResourceVersion(
    val id: Any? = null,
    val version: String? = null,
    val versionResourceName: String? = null,
    val baseModel: String? = null,
    val triggerWords: String? = null
)

data class RhResourceItem(
    val id: Any? = null,
    val resourceName: String? = "",
    val resourceType: String? = "",
    val nodeModelName: String? = null,
    val posterUrl: String? = null,
    val thumbnailUrl: String? = null,
    val desc: String? = null,
    val versions: List<RhResourceVersion>? = null,
    /** Present only for the local picker; same record as model-center detail. */
    val localModel: com.comfyui.mobile.modelcenter.LocalModel? = null
) {
    val effectiveId: String
        get() = id?.toString().orEmpty().ifBlank { "${resourceName.orEmpty()}_${effectiveModelName}" }

    val effectiveResourceName: String
        get() = resourceName?.toString().orEmpty().ifBlank { effectiveModelName }

    val effectiveModelName: String
        get() = nodeModelName?.toString()?.trim()?.takeIf { it.isNotBlank() }
            ?: versions?.firstOrNull()?.versionResourceName?.toString()?.substringAfterLast('/')?.trim()?.takeIf { it.isNotBlank() }
            ?: "${resourceName?.toString().orEmpty()}.safetensors"

    val triggerWords: String?
        get() = versions?.firstOrNull()?.triggerWords?.toString()?.trim()?.takeIf { it.isNotBlank() }

    val baseModel: String?
        get() = versions?.firstOrNull()?.baseModel?.toString()?.trim()?.takeIf { it.isNotBlank() }
}

data class RhResourcePageData(
    val records: List<RhResourceItem>? = null,
    val total: Int = 0,
    val current: Int = 1,
    val size: Int = 20,
    val pages: Int = 0,
    val hasNext: Boolean = false
)

data class RhResourceListResponse(
    val code: Int = -1,
    val msg: String? = null,
    val data: RhResourcePageData? = null
)

object RunningHubApiHelper {

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /** Maps well-known RunningHub error codes to a short Chinese message; null for success/unknown. */
    fun describeRhErrorCode(code: Any?, msg: String? = null): String? {
        val c = when (code) {
            is Number -> code.toInt()
            is String -> code.trim().toDoubleOrNull()?.toInt()
            else -> null
        } ?: return null
        if (c == 0 || c == 200) return null
        val base = when (c) {
            416 -> "RunningHub 余额不足，请充值后重试"
            421 -> "RunningHub 并发任务已达上限，请等待当前任务结束"
            433 -> "RunningHub 参数校验失败，请检查工作流"
            802 -> "RunningHub API Key 无效，请检查配置"
            else -> "RunningHub 错误 $c"
        }
        val detail = msg?.trim()?.ifBlank { null }
        return if (detail != null) "$base（$detail）" else base
    }

    /** Parses a raw JSON body for a RunningHub {code,msg} envelope; null if not one or code is success. */
    fun describeRhErrorBody(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            val obj = org.json.JSONObject(body)
            if (!obj.has("code")) return null
            describeRhErrorCode(obj.opt("code"), obj.optString("msg").ifBlank { null })
        } catch (_: Exception) {
            null
        }
    }

    suspend fun checkAccountStatus(
        client: OkHttpClient,
        gson: Gson,
        hostUrl: String,
        apiKey: String
    ): Result<RhAccountData> = withContext(Dispatchers.IO) {
        val trimmedHost = hostUrl.trim().trimEnd('/')
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("API Key 不能为空"))
        }

        val url = "$trimmedHost/uc/openapi/accountStatus?apiKey=$trimmedKey"
        val requestJson = gson.toJson(mapOf("apikey" to trimmedKey, "apiKey" to trimmedKey))
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $trimmedKey")
            .header("apikey", trimmedKey)
            .header("User-Agent", "ComfyController-Android/1.0")
            .post(requestJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            val response = client.newCall(request).execute()
            val body = response.body?.string().orEmpty()
            Log.d("RunningHubApi", "checkAccountStatus HTTP ${response.code}, ${body.length} bytes")
            if (!response.isSuccessful) {
                val safeBody = SecretRedactor.redact(if (body.length > 200) body.take(200) + "..." else body)
                val rhMsg = describeRhErrorBody(body)
                return@withContext Result.failure(
                    Exception(if (rhMsg != null) "$rhMsg (HTTP ${response.code})" else "HTTP ${response.code}: $safeBody")
                )
            }

            val parsed = gson.fromJson(body, RhAccountStatusResponse::class.java)
            if (parsed.code == 0) {
                val dataObj = parsed.data
                val accountData = if (dataObj != null) {
                    val dataJson = gson.toJson(dataObj)
                    val rawMap: Map<String, Any?> = try {
                        val type = object : com.google.gson.reflect.TypeToken<Map<String, Any?>>() {}.type
                        gson.fromJson(dataJson, type) ?: emptyMap()
                    } catch (_: Exception) {
                        emptyMap()
                    }
                    val typed = try {
                        gson.fromJson(dataJson, RhAccountData::class.java)
                    } catch (_: Exception) {
                        null
                    }
                    typed?.copy(rawMap = rawMap) ?: RhAccountData(
                        currentTaskCounts = rawMap["currentTaskCounts"],
                        balance = rawMap["balance"] ?: rawMap["remainCoin"] ?: rawMap["coins"],
                        remainCoins = rawMap["remainCoins"],
                        remainCoin = rawMap["remainCoin"] ?: rawMap["coins"] ?: rawMap["balance"],
                        remainMoney = rawMap["remainMoney"],
                        currency = rawMap["currency"]?.toString(),
                        apiType = rawMap["apiType"]?.toString(),
                        rawMap = rawMap
                    )
                } else {
                    RhAccountData()
                }

                val c = accountData.currency?.trim()?.uppercase()
                val isCnHost = trimmedHost.contains("runninghub.cn", ignoreCase = true)
                val isAiHost = trimmedHost.contains("runninghub.ai", ignoreCase = true)
                if (isCnHost && c == "USD") {
                    return@withContext Result.failure(Exception("Key属于AI站(USD)，站点不匹配"))
                }
                if (isAiHost && (c == "CNY" || c == "RMB")) {
                    return@withContext Result.failure(Exception("Key属于CN站(CNY)，站点不匹配"))
                }

                val queueReq = Request.Builder()
                    .url("$trimmedHost/openapi/v2/queue/status")
                    .header("Authorization", "Bearer $trimmedKey")
                    .header("User-Agent", "ComfyController-Android/1.0")
                    .get()
                    .build()
                val qResp = runCatching { client.newCall(queueReq).execute() }.getOrNull()
                if (qResp != null) {
                    val qBody = qResp.body?.string().orEmpty()
                    val qObj = runCatching { org.json.JSONObject(qBody) }.getOrNull()
                    val qCode = qObj?.optInt("code", 0) ?: 0
                    if (qCode != 0 && qCode != 200 && (qResp.code in 400..499 || qCode in 800..899 || qCode in 1600..1699)) {
                        val qMsg = qObj?.optString("msg")?.ifBlank { null } ?: "站点认证失败"
                        return@withContext Result.failure(Exception("$qMsg (Code $qCode)"))
                    }
                }

                Result.success(accountData)
            } else {
                val errorMsg = describeRhErrorCode(parsed.code, parsed.msg) ?: parsed.msg ?: "错误代码 ${parsed.code}"
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getWorkflowJson(
        client: OkHttpClient,
        gson: Gson,
        hostUrl: String,
        apiKey: String,
        workflowId: String
    ): Result<RhWorkflowResult> = withContext(Dispatchers.IO) {
        val trimmedHost = hostUrl.trim().trimEnd('/')
        val trimmedKey = apiKey.trim()
        val trimmedId = workflowId.trim()
        if (trimmedKey.isBlank()) return@withContext Result.failure(IllegalArgumentException("API Key 不能为空"))
        if (trimmedId.isBlank()) return@withContext Result.failure(IllegalArgumentException("工作流 ID 不能为空"))

        val url = "$trimmedHost/api/openapi/getJsonApiFormat?apiKey=$trimmedKey"
        val requestJson = gson.toJson(mapOf("apiKey" to trimmedKey, "apikey" to trimmedKey, "workflowId" to trimmedId))
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $trimmedKey")
            .header("User-Agent", "ComfyController-Android/1.0")
            .post(requestJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            val response = client.newCall(request).execute()
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${response.code}: $body"))
            }
            val parsed = gson.fromJson(body, RhWorkflowJsonResponse::class.java)
            if (parsed.code == 0 && parsed.data != null) {
                val dataJson = gson.toJson(parsed.data)
                val dataMap: Map<String, Any?> = try {
                    val type = object : com.google.gson.reflect.TypeToken<Map<String, Any?>>() {}.type
                    gson.fromJson(dataJson, type) ?: emptyMap()
                } catch (_: Exception) {
                    emptyMap()
                }
                val prompt = dataMap["prompt"]?.toString()
                val title = (dataMap["workflowTitle"] ?: dataMap["workflowName"] ?: dataMap["title"] ?: dataMap["name"])?.toString()?.trim()
                if (!prompt.isNullOrBlank()) {
                    Result.success(RhWorkflowResult(prompt = prompt, title = title))
                } else {
                    Result.failure(Exception("接口返回成功但 prompt 拓扑为空"))
                }
            } else {
                val errorMsg = describeRhErrorCode(parsed.code, parsed.msg) ?: parsed.msg ?: "无法获取工作流 JSON（code: ${parsed.code}）"
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getWebAppDetail(
        client: OkHttpClient,
        gson: Gson,
        hostUrl: String,
        apiKey: String,
        webappId: String
    ): Result<RhWebAppDemoData> = withContext(Dispatchers.IO) {
        val trimmedHost = hostUrl.trim().trimEnd('/')
        val trimmedKey = apiKey.trim()
        val trimmedId = webappId.trim()
        if (trimmedKey.isBlank()) return@withContext Result.failure(IllegalArgumentException("API Key 不能为空"))
        if (trimmedId.isBlank()) return@withContext Result.failure(IllegalArgumentException("WebApp ID 不能为空"))

        val url = "$trimmedHost/api/webapp/apiCallDemo?apiKey=$trimmedKey&webappId=$trimmedId"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $trimmedKey")
            .header("User-Agent", "ComfyController-Android/1.0")
            .get()
            .build()

        try {
            val response = client.newCall(request).execute()
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${response.code}: $body"))
            }
            val parsed = gson.fromJson(body, RhWebAppDemoResponse::class.java)
            if (parsed.code == 0 && parsed.data != null) {
                Result.success(parsed.data)
            } else {
                val errorMsg = describeRhErrorCode(parsed.code, parsed.msg) ?: parsed.msg ?: "无法获取应用详情（code: ${parsed.code}）"
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

}
