package com.comfyui.mobile.data

import com.comfyui.mobile.agent.AgentReasoningEffort
import com.comfyui.mobile.agent.AgentGenerationLimits
import com.comfyui.mobile.agent.OutputTokenParameter
import com.comfyui.mobile.network.ChatCompletionRequest
import com.comfyui.mobile.network.ChatCompletionResponse
import com.comfyui.mobile.network.ChatMessageDto
import com.comfyui.mobile.network.ChatStreamChunk
import com.google.gson.Gson
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

data class LlmCompletionOutput(
    val content: String,
    val reasoning: String? = null,
    val requestId: String = "",
    val toolCalls: List<com.comfyui.mobile.network.ChatToolCall> = emptyList(),
    val finishReason: String? = null,
    /** Server-reported usage, when the provider sends it. */
    val usage: com.comfyui.mobile.network.ChatUsageDto? = null,
    /** Milliseconds from request start to the first streamed token (null for non-stream calls). */
    val firstTokenMillis: Long? = null,
    val totalMillis: Long? = null
)

class LlmClient(
    private val gson: Gson = Gson(),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .proxySelector(LanBypassProxySelector)
        .build(),
    private val totalTimeoutMillis: Long = 240_000
) {
    private class ActiveCall(val call: Call, val cancelled: AtomicBoolean = AtomicBoolean(false))
    private val activeCalls = ConcurrentHashMap<String, ActiveCall>()
    // Session-local capability evidence only; never persist credentials or guess by model name.
    private val textOnlyTools = ConcurrentHashMap.newKeySet<String>()
    private val autoOnlyTools = ConcurrentHashMap.newKeySet<String>()
    fun usesTextToolProtocol(baseUrl: String, model: String) = "${resolveEndpoint(baseUrl)}\n$model" in textOnlyTools
    fun useTextToolProtocol(baseUrl: String, model: String) { textOnlyTools += "${resolveEndpoint(baseUrl)}\n$model" }
    fun usesAutoToolChoice(baseUrl: String, model: String) = "${resolveEndpoint(baseUrl)}\n$model" in autoOnlyTools
    fun useAutoToolChoice(baseUrl: String, model: String) { autoOnlyTools += "${resolveEndpoint(baseUrl)}\n$model" }
    val activeRequestIds: Set<String> get() = buildSet { activeCalls.forEach { (id, _) -> add(id) } }

    fun cancelRequest(requestId: String) {
        activeCalls[requestId]?.let { it.cancelled.set(true); it.call.cancel() }
    }

    /** Owner teardown only. The stop button uses cancelRequest(turn.id), never this broad operation. */
    fun cancelActive() { activeCalls.forEach { (_, active) -> active.cancelled.set(true); active.call.cancel() } }

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

    private fun validate(baseUrl: String, apiKey: String, model: String) {
        if (baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) {
            throw LlmException(
                LlmErrorType.CONFIG_INVALID,
                "请先在右上角「Agent 设置」填写 API Base URL、Key 和模型名。",
                suggestion = "同时核对当前选择的模型配置。"
            )
        }
    }

    /** Vision content remains per-message; a reasoning setting never alters message/image payloads. */
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

    /**
     * The cancellation continuation lives while headers AND body are being read. Cancelling it
     * closes the actual socket, even when the IO worker is blocked in a streaming read.
     */
    private suspend fun <T> executeCall(
        requestId: String,
        request: Request,
        read: suspend (Response) -> T
    ): T = coroutineScope {
        require(requestId.isNotBlank()) { "Request ID is empty" }
        require(totalTimeoutMillis > 0) { "Total timeout must be positive" }
        currentCoroutineContext().ensureActive()
        val entry = ActiveCall(client.newCall(request))
        entry.call.timeout().timeout(totalTimeoutMillis, TimeUnit.MILLISECONDS)
        check(activeCalls.putIfAbsent(requestId, entry) == null) { "Duplicate active request ID" }
        try {
            suspendCancellableCoroutine { continuation ->
                val worker = launch(Dispatchers.IO) {
                    try {
                        val result = entry.call.execute().use { read(it) }
                        if (entry.cancelled.get()) throw CancellationException("LLM request stopped")
                        continuation.resume(result)
                    } catch (e: CancellationException) {
                        continuation.cancel(e)
                    } catch (e: Exception) {
                        if (entry.cancelled.get()) continuation.cancel(CancellationException("LLM request stopped", e))
                        else continuation.resumeWithException(e)
                    }
                }
                continuation.invokeOnCancellation {
                    entry.call.cancel()
                    worker.cancel()
                }
            }
        } finally {
            activeCalls.remove(requestId, entry)
        }
    }

    private fun request(endpoint: String, apiKey: String, body: String, stream: Boolean): Request =
        Request.Builder().url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .apply {
                if (stream) {
                    header("Accept", "text/event-stream")
                    header("Cache-Control", "no-cache")
                    header("X-Accel-Buffering", "no")
                }
            }
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

    private fun checkHttp(response: Response, endpoint: String) {
        if (!response.isSuccessful) {
            throw LlmErrorMapper.map(
                IllegalStateException("HTTP " + response.code), endpoint, response.code,
                response.body?.string().orEmpty()
            )
        }
    }

    private fun incomplete(endpoint: String) = LlmException(
        LlmErrorType.INCOMPLETE_RESPONSE,
        "模型输出未完整结束，本轮未继续提交。",
        suggestion = "请重试或增加服务商的输出长度限制；不会自动重发请求。",
        endpoint = endpoint
    )

    suspend fun chatCompletions(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
        images: List<String> = emptyList(),
        messageImages: Map<Int, List<String>> = emptyMap(),
        requestId: String = UUID.randomUUID().toString(),
        reasoningEffort: AgentReasoningEffort = AgentReasoningEffort.DEFAULT,
        tools: List<com.comfyui.mobile.network.ChatToolDefinition>? = null,
        generationLimits: AgentGenerationLimits = AgentGenerationLimits(),
        toolChoice: String? = null
    ): Result<LlmCompletionOutput> = withContext(Dispatchers.IO) {
        var endpoint = ""
        var connecting = true
        try {
            validate(baseUrl, apiKey, model)
            generationLimits.validated()
            endpoint = resolveEndpoint(baseUrl)
            val body = buildBody(ChatCompletionRequest(
                model, messages, temperature = if (reasoningEffort == AgentReasoningEffort.DEFAULT) 0.7 else null,
                reasoning_effort = reasoningEffort.wireValue, tools = tools, tool_choice = toolChoice,
                parallel_tool_calls = if (tools.isNullOrEmpty()) null else false,
                max_completion_tokens = generationLimits.outputTokens.takeIf {
                    generationLimits.outputParameter == OutputTokenParameter.COMPLETION },
                max_tokens = generationLimits.outputTokens.takeIf {
                    generationLimits.outputParameter == OutputTokenParameter.LEGACY }
            ), images, messageImages)
            val started = System.nanoTime()
            val output = executeCall(requestId, request(endpoint, apiKey, body, false)) { response ->
                connecting = false
                checkHttp(response, endpoint)
                val parsed = try {
                    gson.fromJson(response.body?.string().orEmpty(), ChatCompletionResponse::class.java)
                } catch (e: Exception) {
                    throw LlmException(LlmErrorType.INCOMPLETE_RESPONSE, "模型响应 JSON 格式异常，本轮未继续提交。", endpoint = endpoint, cause = e)
                }
                if (parsed?.error != null) {
                    throw LlmException(LlmErrorType.SERVER_ERROR, "模型服务返回错误。", endpoint = endpoint)
                }
                val choice = parsed?.choices?.firstOrNull()
                val calls = choice?.message?.tool_calls.orEmpty()
                if (calls.isNotEmpty()) {
                    if (tools.isNullOrEmpty()) throw incomplete(endpoint)
                    try { validateToolCalls(calls) } catch (_: Exception) { throw incomplete(endpoint) }
                }
                if (choice?.finish_reason != null && choice.finish_reason != "stop" &&
                    !(choice.finish_reason == "tool_calls" && !tools.isNullOrEmpty() && calls.isNotEmpty())) throw incomplete(endpoint)
                val content = choice?.message?.content.orEmpty().trim()
                val reasoning = choice?.message?.reasoning_content?.trim().orEmpty()
                if (content.isBlank() && reasoning.isBlank() && calls.isEmpty()) {
                    throw LlmException(LlmErrorType.EMPTY_RESPONSE, "大模型返回了空响应。", endpoint = endpoint)
                }
                LlmCompletionOutput(content, reasoning.ifBlank { null }, requestId, calls, choice?.finish_reason,
                    usage = parsed?.usage, totalMillis = (System.nanoTime() - started) / 1_000_000)
            }
            Result.success(output)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            Result.failure(mapFailure(e, endpoint, connecting))
        }
    }

    suspend fun chatCompletionsStream(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
        images: List<String> = emptyList(),
        messageImages: Map<Int, List<String>> = emptyMap(),
        requestId: String = UUID.randomUUID().toString(),
        reasoningEffort: AgentReasoningEffort = AgentReasoningEffort.DEFAULT,
        generationLimits: AgentGenerationLimits = AgentGenerationLimits(),
        tools: List<com.comfyui.mobile.network.ChatToolDefinition>? = null,
        toolChoice: String? = null,
        onProgress: suspend (reasoningChunk: String, contentChunk: String, fullReasoning: String, fullContent: String) -> Unit
    ): Result<LlmCompletionOutput> = withContext(Dispatchers.IO) {
        var endpoint = ""
        var connecting = true
        try {
            validate(baseUrl, apiKey, model)
            generationLimits.validated()
            endpoint = resolveEndpoint(baseUrl)
            val body = buildBody(ChatCompletionRequest(
                model, messages, temperature = if (reasoningEffort == AgentReasoningEffort.DEFAULT) 0.7 else null,
                stream = true, reasoning_effort = reasoningEffort.wireValue,
                tools = tools, tool_choice = toolChoice,
                stream_options = com.comfyui.mobile.network.ChatStreamOptions(),
                parallel_tool_calls = if (tools.isNullOrEmpty()) null else false,
                max_completion_tokens = generationLimits.outputTokens.takeIf {
                    generationLimits.outputParameter == OutputTokenParameter.COMPLETION },
                max_tokens = generationLimits.outputTokens.takeIf {
                    generationLimits.outputParameter == OutputTokenParameter.LEGACY }
            ), images, messageImages)
            val started = System.nanoTime()
            val output = executeCall(requestId, request(endpoint, apiKey, body, true)) { response ->
                connecting = false
                checkHttp(response, endpoint)
                val source = response.body?.source()
                    ?: throw LlmException(LlmErrorType.EMPTY_RESPONSE, "模型响应流为空。", endpoint = endpoint)
                val content = StringBuilder()
                val reasoning = StringBuilder()
                val toolCalls = LlmToolCallAccumulator()
                var finished = false
                var finishReason: String? = null
                var usage: com.comfyui.mobile.network.ChatUsageDto? = null
                var firstToken: Long? = null
                streamLoop@ while (!source.exhausted()) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line()?.trim() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") { finished = true; break }
                    if (data.isEmpty()) continue
                    val chunk = try {
                        gson.fromJson(data, ChatStreamChunk::class.java)
                    } catch (e: Exception) {
                        throw incomplete(endpoint)
                    } ?: throw incomplete(endpoint)
                    if (chunk.error != null) {
                        throw LlmException(LlmErrorType.SERVER_ERROR, "模型在流式输出中返回错误。", endpoint = endpoint)
                    }
                    chunk.usage?.let { usage = it }
                    val choice = chunk.choices?.firstOrNull()
                    val finish = choice?.finish_reason
                    val r = choice?.delta?.reasoning_content.orEmpty()
                    val c = choice?.delta?.content.orEmpty()
                    try { toolCalls.append(choice?.delta?.tool_calls.orEmpty()) }
                    catch (_: Exception) { throw incomplete(endpoint) }
                    reasoning.append(r)
                    content.append(c)
                    if (r.isNotEmpty() || c.isNotEmpty() || !choice?.delta?.tool_calls.isNullOrEmpty()) {
                        if (firstToken == null) firstToken = System.nanoTime()
                    }
                    if (r.isNotEmpty() || c.isNotEmpty()) {
                        onProgress(r, c, reasoning.toString(), content.toString())
                    }
                    if (finish != null) {
                        if (finish != "stop" && finish != "tool_calls") throw incomplete(endpoint)
                        finishReason = finish
                        finished = true
                        if (usage == null) usage = readTrailingUsage(source)
                        break@streamLoop
                    }
                }
                val calls = try { toolCalls.completed() } catch (_: Exception) { throw incomplete(endpoint) }
                if ((calls.isNotEmpty() && tools.isNullOrEmpty()) ||
                    (finishReason == "tool_calls" && calls.isEmpty())) throw incomplete(endpoint)
                val finalContent = content.toString().trim()
                val finalReasoning = reasoning.toString().trim()
                if (finalContent.isBlank() && finalReasoning.isBlank() && calls.isEmpty()) {
                    // No silent non-stream retry: avoid a second billable request.
                    throw LlmException(LlmErrorType.EMPTY_RESPONSE, "模型返回了空响应流；未自动重发。", endpoint = endpoint)
                }
                if (!finished) throw incomplete(endpoint)
                // A reasoning-only result is not a final answer and never becomes a prompt/action.
                val now = System.nanoTime()
                LlmCompletionOutput(finalContent, finalReasoning.ifBlank { null }, requestId, calls, finishReason,
                    usage = usage, firstTokenMillis = firstToken?.let { (it - started) / 1_000_000 },
                    totalMillis = (now - started) / 1_000_000)
            }
            Result.success(output)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            Result.failure(mapFailure(e, endpoint, connecting))
        }
    }

    /**
     * With include_usage the usage arrives in a separate chunk after finish_reason. Read briefly
     * for it; a server that never sends [DONE] must not hold the turn open, and the answer is
     * already complete, so any read problem here only costs the statistics.
     */
    private fun readTrailingUsage(source: okio.BufferedSource): com.comfyui.mobile.network.ChatUsageDto? {
        val timeout = source.timeout()
        val previous = timeout.timeoutNanos()
        timeout.timeout(1500, TimeUnit.MILLISECONDS)
        return try {
            var lines = 0
            while (lines++ < 8 && !source.exhausted()) {
                val line = source.readUtf8Line()?.trim() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                if (data.isEmpty()) continue
                val usage = runCatching { gson.fromJson(data, ChatStreamChunk::class.java)?.usage }.getOrNull()
                if (usage != null) return usage
            }
            null
        } catch (_: java.io.IOException) {
            null
        } finally {
            timeout.timeout(previous, TimeUnit.NANOSECONDS)
        }
    }

    private fun mapFailure(e: Exception, endpoint: String, connecting: Boolean): LlmException {
        if (e is java.io.InterruptedIOException && e !is java.net.SocketTimeoutException &&
            e.message?.contains("timeout", ignoreCase = true) == true) {
            return LlmException(LlmErrorType.TIMEOUT_TOTAL, "本轮模型请求超过总时限。", endpoint = endpoint, cause = e)
        }
        return LlmErrorMapper.map(e, endpoint = endpoint, isConnectingPhase = connecting)
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
