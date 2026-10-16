package com.comfyui.mobile.data

import com.google.gson.Gson
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

enum class LlmErrorType(val title: String) {
    CONFIG_INVALID("配置不完整"),
    TIMEOUT_CONNECT("连接服务超时"),
    TIMEOUT_READ("模型响应超时"),
    DNS_FAILURE("域名无法解析"),
    CONNECTION_REFUSED("连接被目标拒绝"),
    SSL_ERROR("SSL 安全握手失败"),
    UNAUTHORIZED("API Key 认证失败 (401)"),
    FORBIDDEN("无访问权限 / 地区受限 (403)"),
    NOT_FOUND("接口未找到 (404)"),
    RATE_LIMIT_OR_QUOTA("频次超限 / 额度耗尽 (429)"),
    SERVER_ERROR("模型服务端异常 (5xx)"),
    EMPTY_RESPONSE("模型返回空内容"),
    NETWORK_GENERAL("网络连接中断"),
    UNKNOWN("接口请求失败")
}

class LlmException(
    val errorType: LlmErrorType,
    val userMessage: String,
    val suggestion: String = "",
    val endpoint: String? = null,
    val httpCode: Int? = null,
    val technicalDetail: String? = null,
    cause: Throwable? = null
    ) : Exception(userMessage, cause) {

    fun toDisplayString(): String {
        return buildString {
            append("【").append(errorType.title).append("】\n")
            append(userMessage)
            if (suggestion.isNotBlank()) {
                append("\n\n💡 排查建议：\n").append(suggestion.trim())
            }
            if (!endpoint.isNullOrBlank()) {
                append("\n\n🔗 请求目标：").append(endpoint)
            }
            if (httpCode != null && httpCode > 0) {
                append("\n📡 状态码：HTTP ").append(httpCode)
            }
            if (!technicalDetail.isNullOrBlank() && technicalDetail != userMessage) {
                val clean = technicalDetail.replace(Regex("""<[^>]*>"""), " ").trim()
                if (clean.isNotBlank()) {
                    append("\n🔍 服务器返回：").append(clean.replace(Regex("""\s+"""), " ").take(600))
                }
            }
        }
    }
}

object LlmErrorMapper {
    private val gson = Gson()

    fun map(
        throwable: Throwable,
        endpoint: String? = null,
        httpCode: Int? = null,
        rawResponseBody: String? = null,
        isConnectingPhase: Boolean = false
    ): LlmException {
        if (throwable is LlmException) return throwable

        val cause = throwable.cause ?: throwable
        val msg = cause.message.orEmpty()
        val lowerMsg = msg.lowercase()

        // 1. HTTP 错误解析
        if (httpCode != null && httpCode !in 200..299) {
            val apiErrorMessage = extractApiErrorMessage(rawResponseBody)
            return when (httpCode) {
                401 -> LlmException(
                    errorType = LlmErrorType.UNAUTHORIZED,
                    userMessage = "API Key 无效、过期或无权访问该模型。",
                    suggestion = "请前往右上角「Agent 设置」，检查 API Key 是否填写正确且前后无多余空格。",
                    endpoint = endpoint,
                    httpCode = httpCode,
                    technicalDetail = apiErrorMessage ?: rawResponseBody,
                    cause = throwable
    )
                403 -> LlmException(
                    errorType = LlmErrorType.FORBIDDEN,
                    userMessage = "访问被目标服务拒绝 (403 Forbidden)。",
                    suggestion = "可能是 API 提供商的地区封锁（如 OpenAI 限制特定地区 IP）、或当前代理节点被中转防御拦截。",
                    endpoint = endpoint,
                    httpCode = httpCode,
                    technicalDetail = apiErrorMessage ?: rawResponseBody,
                    cause = throwable
    )
                404 -> LlmException(
                    errorType = LlmErrorType.NOT_FOUND,
                    userMessage = "未找到 API 接口 (404 Not Found)。",
                    suggestion = "请检查 API Base URL 格式。标准格式通常如 https://api.deepseek.com/v1，程序会自动补充 /chat/completions；若为特殊反代中转，请检查路径层级。",
                    endpoint = endpoint,
                    httpCode = httpCode,
                    technicalDetail = apiErrorMessage ?: rawResponseBody,
                    cause = throwable
    )
                429 -> LlmException(
                    errorType = LlmErrorType.RATE_LIMIT_OR_QUOTA,
                    userMessage = "API 触发频率超限，或账户余额/Tokens 已耗尽 (429 Too Many Requests)。",
                    suggestion = "1. 请检查您的 API 账户余额与可用额度；\n2. 若使用了免费或并发限制模型，请稍等几秒再试。",
                    endpoint = endpoint,
                    httpCode = httpCode,
                    technicalDetail = apiErrorMessage ?: rawResponseBody,
                    cause = throwable
    )
                503 -> LlmException(
                    errorType = LlmErrorType.SERVER_ERROR,
                    userMessage = if (apiErrorMessage != null) "服务暂不可用 (503)：$apiErrorMessage" else "服务暂不可用 (HTTP 503)。",
                    suggestion = buildString {
                        appendLine("1. 中转站/One-API/New-API：多为该模型名没有可用渠道，或渠道全部失效/余额不足，请核对模型名是否和中转站后台完全一致；")
                        appendLine("2. 本地 Ollama / LM Studio：模型未加载或正在加载，请确认已启动并载入该模型；")
                        appendLine("3. 若手机开着代理/VPN，代理连不上目标时也会返回 503，可关闭代理或把目标加入直连；")
                        append("4. 官方服务高峰期过载，稍后重试。")
                    },
                    endpoint = endpoint,
                    httpCode = httpCode,
                    technicalDetail = rawResponseBody?.takeIf { it.isNotBlank() } ?: apiErrorMessage,
                    cause = throwable
    )
                in 500..599 -> LlmException(
                    errorType = LlmErrorType.SERVER_ERROR,
                    userMessage = if (apiErrorMessage != null) "大模型服务端错误 (HTTP $httpCode)：$apiErrorMessage" else "大模型服务端发生故障或网关超时 (HTTP $httpCode)。",
                    suggestion = "服务商服务器过载、正在维护或反向代理超时。通常非客户端原因，请稍后重试或切换备用模型。",
                    endpoint = endpoint,
                    httpCode = httpCode,
                    technicalDetail = apiErrorMessage ?: rawResponseBody,
                    cause = throwable
    )
                else -> LlmException(
                    errorType = LlmErrorType.UNKNOWN,
                    userMessage = "大模型服务返回异常状态 (HTTP $httpCode)。",
                    suggestion = "请查看下方详细错误，或前往设置检查模型名与配置。",
                    endpoint = endpoint,
                    httpCode = httpCode,
                    technicalDetail = apiErrorMessage ?: rawResponseBody,
                    cause = throwable
    )
            }
        }

        // 2. 网络与 Socket 超时映射（关键解决“扩写失败：timeout”问题）
        if (cause is SocketTimeoutException || lowerMsg.contains("timeout") || lowerMsg.contains("timed out")) {
            val isConnectTimeout = isConnectingPhase || lowerMsg.contains("connect")
            return if (isConnectTimeout) {
                LlmException(
                    errorType = LlmErrorType.TIMEOUT_CONNECT,
                    userMessage = "连接大模型服务器超时（未能在时限内建立连接）。",
                    suggestion = buildString {
                        appendLine("1. 若使用境外服务（如 OpenAI、Claude 等），请确保手机已开启科学上网/网络代理；")
                        appendLine("2. 若使用国内服务（如 DeepSeek、通义千问），请检查手机网络信号或 Wi-Fi 是否通畅；")
                        appendLine("3. 若使用局域网本地模型（如 Ollama、LM Studio），请确认手机与电脑在同一 Wi-Fi，且电脑防火墙已放行相应端口；")
                        append("4. 请在右上角设置中检查 Base URL 拼写是否有误。")
                    },
                    endpoint = endpoint,
                    technicalDetail = msg,
                    cause = throwable
    )
            } else {
                LlmException(
                    errorType = LlmErrorType.TIMEOUT_READ,
                    userMessage = "等待大模型生成响应超时（已建立连接，但长时间未返回数据）。",
                    suggestion = buildString {
                        appendLine("1. 若使用的是深度思考型模型（如 DeepSeek-R1），官方算力在高峰期可能严重拥堵排队，可尝试重试，或切换为 deepseek-chat；")
                        appendLine("2. 若使用了第三方 API 中转站，请确认中转站是否正常工作，且未开启不合理的缓冲等待；")
                        append("3. 手机端连接如果中途跳变或断网也会导致读取中断，建议保持网络稳定。")
                    },
                    endpoint = endpoint,
                    technicalDetail = msg,
                    cause = throwable
    )
            }
        }

        // 3. DNS 域名解析失败
        if (cause is UnknownHostException) {
            return LlmException(
                errorType = LlmErrorType.DNS_FAILURE,
                userMessage = "无法解析 API 域名（找不到对应主机）。",
                suggestion = "请检查右上角「Agent 设置」里的 API Base URL 是否输入了错误的域名，或检查手机网络 DNS 配置。",
                endpoint = endpoint,
                technicalDetail = msg,
                cause = throwable
    )
        }

        // 4. 连接被拒绝
        if (cause is ConnectException || lowerMsg.contains("connection refused")) {
            return LlmException(
                errorType = LlmErrorType.CONNECTION_REFUSED,
                userMessage = "目标服务器拒绝连接。",
                suggestion = "目标主机的指定端口未开放任何服务。若填写的是局域网 IP，请确认电脑上的 Ollama/本地服务已经启动并正在监听所有网卡（0.0.0.0）。",
                endpoint = endpoint,
                technicalDetail = msg,
                cause = throwable
    )
        }

        // 5. SSL / TLS 握手失败
        if (cause is SSLException || lowerMsg.contains("ssl") || lowerMsg.contains("certificate")) {
            return LlmException(
                errorType = LlmErrorType.SSL_ERROR,
                userMessage = "HTTPS 安全证书校验失败。",
                suggestion = "1. 请检查协议是否应为 http:// 而非 https://（特别是本地局域网服务）；\n2. 若手机开启了某些抓包调试软件或自定义根证书，请检查是否拦截了 HTTPS 请求。",
                endpoint = endpoint,
                technicalDetail = msg,
                cause = throwable
    )
        }

        // 6. 常见的参数缺失或非法 URL
        if (cause is IllegalArgumentException) {
            return LlmException(
                errorType = LlmErrorType.CONFIG_INVALID,
                userMessage = msg.ifBlank { "配置参数有误" },
                suggestion = "请进入右上角「Agent 设置」，确保 API Base URL、API Key 与模型名称均已正确填写。",
                endpoint = endpoint,
                technicalDetail = msg,
                cause = throwable
    )
        }

        // 7. 通用网络断开 / Socket 异常
        if (cause is SocketException || cause is IOException) {
            return LlmException(
                errorType = LlmErrorType.NETWORK_GENERAL,
                userMessage = "网络连接异常中断：$msg",
                suggestion = "连接被对端重置或网络突然断开。请检查网络状态后重试。",
                endpoint = endpoint,
                technicalDetail = msg,
                cause = throwable
    )
        }

        // 8. 兜底
        return LlmException(
            errorType = LlmErrorType.UNKNOWN,
            userMessage = msg.ifBlank { "未知异常" },
            suggestion = "请查看控制台日志以获取完整堆栈跟踪，并确认 API 服务商状态。",
            endpoint = endpoint,
            technicalDetail = msg,
            cause = throwable
    )
    }

    private fun extractApiErrorMessage(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val root = com.google.gson.JsonParser.parseString(raw)
            if (!root.isJsonObject) return@runCatching null
            val o = root.asJsonObject
            fun str(e: com.google.gson.JsonElement?): String? = when {
                e == null || e.isJsonNull -> null
                e.isJsonPrimitive -> e.asString
                e.isJsonObject -> str(e.asJsonObject.get("message")) ?: str(e.asJsonObject.get("msg")) ?: e.toString()
                else -> e.toString()
            }?.takeIf { it.isNotBlank() }
            str(o.get("error")) ?: str(o.get("message")) ?: str(o.get("detail")) ?: str(o.get("msg"))
        }.getOrNull()
    }
}
