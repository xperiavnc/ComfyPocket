package com.comfyui.mobile.network

/**
 * RunningHub official API (task/openapi, uc/openapi, openapi/v2) error-code mapping.
 * Source: https://www.runninghub.ai/runninghub-api-doc-en/doc-8287467 (error codes).
 */
object RhError {
    const val OK = 0
    const val OK_V2 = 200
    const val TASK_RUNNING = 804
    const val TASK_FAILED = 805
    const val TASK_NOT_FOUND = 807
    const val TASK_QUEUED = 813
    const val WEBAPP_NOT_FOUND = 901

    /** Task still in progress (not an error). */
    fun isNonTerminal(code: Int?): Boolean = code == TASK_RUNNING || code == TASK_QUEUED

    /** Transient conditions where retrying later is reasonable. */
    fun isRetryable(code: Int?): Boolean = when (code) {
        415, 416, 421, 1003, 1005, 1010, 1011, 1006, 500 -> true
        else -> false
    }

    /** Key / account problems: re-check the API key configured on the RunningHub server profile. */
    fun isKeyError(code: Int?): Boolean = when (code) {
        801, 802, 806, 811, 332, 412, 1002 -> true
        else -> false
    }

    /**
     * The API key itself is rejected (invalid / disabled / user not found / free user / bad enterprise key).
     * The profile is then marked unauthorized: no WebSocket / polling until the key is changed or the user retries.
     * 412 (TOKEN_INVALID / URL error) is deliberately excluded because it can also mean a wrong path.
     */
    fun isAuthFailure(code: Int?): Boolean = when (code) {
        801, 802, 806, 811, 332, 1002 -> true
        else -> false
    }

    fun isParamError(code: Int?): Boolean = when (code) {
        301, 803, 433, 435 -> true
        else -> false
    }

    fun message(code: Int?, msg: String? = null): String {
        val base = when (code) {
            null -> null
            301 -> "参数错误"
            380 -> "工作流不存在"
            412 -> "Token 无效或接口地址错误"
            415 -> "实例繁忙，请稍后重试"
            416 -> "RunningHub 余额不足"
            421 -> "并发任务数已达上限，请等待已有任务完成"
            423 -> "任务不存在"
            433 -> "参数校验失败（节点字段值不合法）"
            435 -> "实例类型不存在"
            436 -> "专属会员已过期"
            500 -> "RunningHub 服务器内部错误"
            801 -> "免费用户无法调用 API，请升级账户"
            332 -> "API Key 无效（用户不存在），请检查 Key"
            802 -> "API Key 无效或已被禁用，请检查 Key"
            803 -> "节点信息不匹配（nodeId / fieldName 与应用不一致）"
            804 -> "任务运行中"
            805 -> "任务运行失败"
            806 -> "API Key 无效（用户不存在），请检查 Key"
            807 -> "任务不存在或已过期"
            808 -> "文件上传失败"
            809 -> "文件超过大小限制（30MB）"
            810 -> "工作流未保存或从未成功运行，请先在网页端运行一次"
            811 -> "企业 API Key 无效或无企业权限，请检查 Key"
            812 -> "企业账户余额不足"
            813 -> "任务排队中"
            901 -> "AI 应用不存在或已下架"
            1002 -> "API Key 无效，请检查 Key"
            1003 -> "请求过于频繁，请稍后重试"
            1005, 1010, 1011 -> "服务繁忙，请稍后重试"
            1006 -> "请求超时"
            1501, 1505 -> "内容审核未通过"
            1504 -> "内容违规"
            else -> null
        }
        val cleanMsg = SecretRedactor.redact(msg?.trim()).takeIf { it.isNotBlank() && !it.equals("success", true) }
        return when {
            // Bare enum identifiers (e.g. APIKEY_USER_NOT_FOUND) add nothing to the Chinese text.
            base != null && cleanMsg != null && cleanMsg.matches(Regex("[A-Z0-9_ ]+")) -> "$base（$code）"
            base != null && cleanMsg != null && !cleanMsg.equals(base, true) -> "$base（$code: $cleanMsg）"
            base != null -> "$base（$code）"
            cleanMsg != null -> if (code != null) "RunningHub 错误 $code: $cleanMsg" else cleanMsg
            code != null -> "RunningHub 错误代码 $code"
            else -> "RunningHub 未知错误"
        }
    }
}

/** Thrown by the isolated RunningHub client for non-success envelopes / HTTP errors. */
class RhApiException(
    val code: Int?,
    val rawMsg: String?,
    val httpCode: Int? = null
    ) : Exception(
    if (code != null || httpCode == null) RhError.message(code, rawMsg)
    else "HTTP $httpCode" + (SecretRedactor.redact(rawMsg).takeIf { it.isNotBlank() }?.let { ": ${it.take(200)}" } ?: "")
) {
    val retryable: Boolean get() = RhError.isRetryable(code) || (httpCode != null && httpCode >= 500)
}