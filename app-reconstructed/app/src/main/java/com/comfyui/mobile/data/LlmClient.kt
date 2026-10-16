package com.comfyui.mobile.data

import com.comfyui.mobile.network.ChatCompletionRequest
import com.comfyui.mobile.network.ChatCompletionResponse
import com.comfyui.mobile.network.ChatMessageDto
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class LlmCompletionOutput(
    val content: String,
    val reasoning: String? = null
    )

class LlmClient(
    private val gson: Gson = Gson(),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        // 手机开着 Clash/VPN 系统代理时，局域网/本机地址的请求会被代理接管，代理连不上就回 503。
        .proxySelector(LanBypassProxySelector)
        .build()
    ) {
    @Volatile private var activeCall: okhttp3.Call? = null

    /** Cancels the request in flight (stop button); the caller sees an IOException. */
    fun cancelActive() {
        activeCall?.cancel()
    }

    companion object {
        fun resolveEndpoint(baseUrl: String): String {
            var root = baseUrl.trim().trimEnd('/')
            if (root.isBlank()) return ""
            if (!root.startsWith("http://", ignoreCase = true) && !root.startsWith("https://", ignoreCase = true)) {
                val isLocal = root.startsWith("127.") || root.startsWith("192.168.") || root.startsWith("10.") || root.startsWith("localhost")
                root = if (isLocal) "http://$root" else "https://$root"
            }
            return when {
                root.endsWith("/chat/completions") -> root
                root.endsWith("/v1") -> "$root/chat/completions"
                else -> "$root/v1/chat/completions"
            }
        }
    }

    /**
     * Vision: images are data URLs (data:image/jpeg;base64,...). They are attached to the LAST user
     * message as an OpenAI content array: [{type:text},{type:image_url,image_url:{url}}].
     */
    private fun buildBody(
        request: ChatCompletionRequest,
        images: List<String>,
        messageImages: Map<Int, List<String>>
    ): String {
        val tree = gson.toJsonTree(request).asJsonObject
        val msgs = tree.getAsJsonArray("messages")
        val all = messageImages.toMutableMap()
        if (images.isNotEmpty()) {
            val lastUser = (msgs.size() - 1 downTo 0).firstOrNull {
                msgs[it].asJsonObject.get("role")?.asString == "user"
            }
            if (lastUser != null) all[lastUser] = all[lastUser].orEmpty() + images
        }
        all.forEach { (idx, imgs) ->
            if (idx in 0 until msgs.size() && imgs.isNotEmpty()) attachImages(msgs[idx].asJsonObject, imgs)
        }
        return gson.toJson(tree)
    }

    private fun attachImages(msg: com.google.gson.JsonObject, images: List<String>) {
        val text = msg.get("content")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        val parts = com.google.gson.JsonArray()
        parts.add(com.google.gson.JsonObject().apply { addProperty("type", "text"); addProperty("text", text) })
        images.forEach { url ->
            parts.add(com.google.gson.JsonObject().apply {
                addProperty("type", "image_url")
                add("image_url", com.google.gson.JsonObject().apply { addProperty("url", url) })
            })
        }
        msg.add("content", parts)
    }

    suspend fun chatCompletions(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
        images: List<String> = emptyList(),
        messageImages: Map<Int, List<String>> = emptyMap()
    ): Result<LlmCompletionOutput> = withContext(Dispatchers.IO) {
        var currentEndpoint = ""
        var isConnecting = true
        try {
            if (baseUrl.isBlank()) {
                throw LlmException(
                    errorType = LlmErrorType.CONFIG_INVALID,
                    userMessage = "请先在右上角「Agent 设置」填写 API Base URL",
                    suggestion = "例如：https://api.deepseek.com/v1"
                )
            }
            if (apiKey.isBlank()) {
                throw LlmException(
                    errorType = LlmErrorType.CONFIG_INVALID,
                    userMessage = "请先在右上角「Agent 设置」填写 API Key",
                    suggestion = "请填写对应服务商提供的 Bearer Token / API Key"
                )
            }
            if (model.isBlank()) {
                throw LlmException(
                    errorType = LlmErrorType.CONFIG_INVALID,
                    userMessage = "请先在右上角「Agent 设置」填写模型名称",
                    suggestion = "例如：deepseek-chat、gpt-4o-mini 等"
                )
            }

            currentEndpoint = resolveEndpoint(baseUrl)

            val bodyJson = buildBody(ChatCompletionRequest(model = model, messages = messages, temperature = 0.7), images, messageImages)
            val req = Request.Builder()
                .url(currentEndpoint)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val resp = try {
                val r = client.newCall(req).also { activeCall = it }.execute()
                isConnecting = false
                r
            } catch (e: Exception) {
                throw LlmErrorMapper.map(
                    throwable = e,
                    endpoint = currentEndpoint,
                    isConnectingPhase = isConnecting
                )
            }

            resp.use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw LlmErrorMapper.map(
                        throwable = IllegalStateException("HTTP ${response.code}"),
                        endpoint = currentEndpoint,
                        httpCode = response.code,
                        rawResponseBody = raw
                    )
                }
                val parsed = try {
                    gson.fromJson(raw, ChatCompletionResponse::class.java)
                } catch (e: Exception) {
                    throw LlmException(
                        errorType = LlmErrorType.UNKNOWN,
                        userMessage = "无法解析大模型响应内容（JSON 格式异常）",
                        suggestion = "服务商可能返回了 HTML 错误页面或网关拦截信息",
                        endpoint = currentEndpoint,
                        httpCode = response.code,
                        technicalDetail = raw.take(200),
                        cause = e
                    )
                }

                val message = parsed?.choices?.firstOrNull()?.message
                val content = message?.content.orEmpty().trim()
                val reasoning = message?.reasoning_content?.trim()
                if (content.isBlank() && reasoning.isNullOrBlank()) {
                    throw LlmException(
                        errorType = LlmErrorType.EMPTY_RESPONSE,
                        userMessage = "大模型返回了空响应",
                        suggestion = "模型可能拒绝回答或上下文超过限制，请简化输入后重试",
                        endpoint = currentEndpoint,
                        httpCode = response.code
                    )
                }
                Result.success(
                    LlmCompletionOutput(
                        content = if (content.isNotBlank()) content else reasoning.orEmpty(),
                        reasoning = reasoning.takeIf { !it.isNullOrBlank() }
                    )
                )
            }
        } catch (e: Throwable) {
            Result.failure(LlmErrorMapper.map(e, endpoint = currentEndpoint, isConnectingPhase = isConnecting))
        }
    }

    suspend fun chatCompletionsStream(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
        images: List<String> = emptyList(),
        messageImages: Map<Int, List<String>> = emptyMap(),
        onProgress: (reasoningChunk: String, contentChunk: String, fullReasoning: String, fullContent: String) -> Unit
    ): Result<LlmCompletionOutput> = withContext(Dispatchers.IO) {
        var currentEndpoint = ""
        var isConnecting = true
        try {
            if (baseUrl.isBlank()) {
                throw LlmException(
                    errorType = LlmErrorType.CONFIG_INVALID,
                    userMessage = "请先在右上角「Agent 设置」填写 API Base URL",
                    suggestion = "例如：https://api.deepseek.com/v1"
                )
            }
            if (apiKey.isBlank()) {
                throw LlmException(
                    errorType = LlmErrorType.CONFIG_INVALID,
                    userMessage = "请先在右上角「Agent 设置」填写 API Key",
                    suggestion = "请填写对应服务商提供的 Bearer Token / API Key"
                )
            }
            if (model.isBlank()) {
                throw LlmException(
                    errorType = LlmErrorType.CONFIG_INVALID,
                    userMessage = "请先在右上角「Agent 设置」填写模型名称",
                    suggestion = "例如：deepseek-chat、gpt-4o-mini 等"
                )
            }

            currentEndpoint = resolveEndpoint(baseUrl)

            val bodyJson = buildBody(ChatCompletionRequest(model = model, messages = messages, temperature = 0.7, stream = true), images, messageImages)
            val req = Request.Builder()
                .url(currentEndpoint)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .addHeader("Cache-Control", "no-cache")
                .addHeader("X-Accel-Buffering", "no")
                .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val resp = try {
                val r = client.newCall(req).also { activeCall = it }.execute()
                isConnecting = false
                r
            } catch (e: Exception) {
                throw LlmErrorMapper.map(
                    throwable = e,
                    endpoint = currentEndpoint,
                    isConnectingPhase = isConnecting
                )
            }

            resp.use { response ->
                if (!response.isSuccessful) {
                    val raw = response.body?.string().orEmpty()
                    throw LlmErrorMapper.map(
                        throwable = IllegalStateException("HTTP ${response.code}"),
                        endpoint = currentEndpoint,
                        httpCode = response.code,
                        rawResponseBody = raw
                    )
                }

                val source = response.body?.source() ?: throw LlmException(
                    errorType = LlmErrorType.EMPTY_RESPONSE,
                    userMessage = "大模型服务返回的响应流为空",
                    endpoint = currentEndpoint,
                    httpCode = response.code
                )
                val fullContent = StringBuilder()
                val fullReasoning = StringBuilder()

                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("data:")) continue
                    val data = trimmed.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    if (data.isBlank()) continue

                    val chunk = runCatching {
                        gson.fromJson(data, com.comfyui.mobile.network.ChatStreamChunk::class.java)
                    }.getOrNull() ?: continue

                    val streamErr = chunk.error?.message
                    if (!streamErr.isNullOrBlank()) {
                        throw LlmException(
                            errorType = LlmErrorType.SERVER_ERROR,
                            userMessage = "大模型服务在流式输出中返回错误：$streamErr",
                            endpoint = currentEndpoint,
                            httpCode = response.code,
                            technicalDetail = data.take(600)
                        )
                    }

                    val delta = chunk.choices?.firstOrNull()?.delta
                    val rChunk = delta?.reasoning_content.orEmpty()
                    val cChunk = delta?.content.orEmpty()

                    if (rChunk.isNotEmpty()) fullReasoning.append(rChunk)
                    if (cChunk.isNotEmpty()) fullContent.append(cChunk)

                    if (rChunk.isNotEmpty() || cChunk.isNotEmpty()) {
                        onProgress(rChunk, cChunk, fullReasoning.toString(), fullContent.toString())
                    }
                }

                val finalContent = fullContent.toString().trim()
                val finalReasoning = fullReasoning.toString().trim()

                if (finalContent.isBlank() && finalReasoning.isBlank()) {
                    return@withContext chatCompletions(baseUrl, apiKey, model, messages, images, messageImages)
                }

                Result.success(
                    LlmCompletionOutput(
                        content = if (finalContent.isNotBlank()) finalContent else finalReasoning,
                        reasoning = finalReasoning.takeIf { it.isNotBlank() }
                    )
                )
            }
        } catch (e: Throwable) {
            Result.failure(LlmErrorMapper.map(e, endpoint = currentEndpoint, isConnectingPhase = isConnecting))
        }
    }
}

/** 局域网 / 本机地址直连，其余走系统默认代理。 */
object LanBypassProxySelector : java.net.ProxySelector() {
    private val default: java.net.ProxySelector? = java.net.ProxySelector.getDefault()

    fun isPrivateHost(host: String?): Boolean {
        val h = host?.trim()?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return false
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan")) return true
        if (h == "::1" || h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")) return h.contains(":")
        val p = h.split(".").mapNotNull { it.toIntOrNull() }
        if (p.size != 4 || h.count { it == '.' } != 3) return false
        return p[0] == 10 || p[0] == 127 ||
            (p[0] == 192 && p[1] == 168) ||
            (p[0] == 172 && p[1] in 16..31) ||
            (p[0] == 169 && p[1] == 254) ||
            (p[0] == 100 && p[1] in 64..127)
    }

    override fun select(uri: java.net.URI?): MutableList<java.net.Proxy> {
        if (uri == null || isPrivateHost(uri.host)) return mutableListOf(java.net.Proxy.NO_PROXY)
        return default?.select(uri)?.toMutableList() ?: mutableListOf(java.net.Proxy.NO_PROXY)
    }

    override fun connectFailed(uri: java.net.URI?, sa: java.net.SocketAddress?, ioe: java.io.IOException?) {
        default?.connectFailed(uri, sa, ioe)
    }
}
