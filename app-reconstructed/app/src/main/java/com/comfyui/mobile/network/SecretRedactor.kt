package com.comfyui.mobile.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Masks secrets (RunningHub API keys embedded in proxy URLs, apiKey query values, bearer tokens)
 * before text reaches logcat or the exportable in-app console. Also offers a strict base URL check
 * so malformed URLs are rejected instead of tearing down a healthy connection.
 */
object SecretRedactor {
    private val proxyKeyRegex = Regex("""(/proxy(?:-plus)?/)[^/?#\s"'<>]+""", RegexOption.IGNORE_CASE)
    private val queryKeyRegex = Regex("""([?&](?:apiKey|api_key|apikey|key|token)=)[^&#\s"'<>]+""", RegexOption.IGNORE_CASE)
    private val bearerRegex = Regex("""(Bearer\s+)[A-Za-z0-9._\-]+""", RegexOption.IGNORE_CASE)
    private val jsonKeyRegex = Regex("""("(?:apiKey|apikey|api_key)"\s*:\s*")[^"]*""", RegexOption.IGNORE_CASE)

    fun redact(text: String?): String {
        if (text.isNullOrEmpty()) return text.orEmpty()
        var out: String = text
        out = proxyKeyRegex.replace(out, "\$1***")
        out = queryKeyRegex.replace(out, "\$1***")
        out = bearerRegex.replace(out, "\$1***")
        out = jsonKeyRegex.replace(out, "\$1***")
        return out
    }

    /**
     * True only for a parseable http(s) base URL with a real host. Rejects values such as
     * "https://https:/www.runninghub.cn" that OkHttp would otherwise parse with host "https".
     */
    fun isValidBaseUrl(url: String?): Boolean {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isBlank()) return false
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) return false
        val parsed = trimmed.toHttpUrlOrNull() ?: return false
        val host = parsed.host
        if (host.isBlank()) return false
        if (host.equals("http", ignoreCase = true) || host.equals("https", ignoreCase = true)) return false
        return true
    }
}