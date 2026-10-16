package com.comfyui.mobile.market

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest

enum class MarketConnectionMode { DIRECT, BRIDGE }

/** Credentials never enter SavedState, normal preferences, diagnostic text or request URLs. */
data class CivitaiSettings(
    val mode: MarketConnectionMode = MarketConnectionMode.DIRECT,
    val proxyEnabled: Boolean = false,
    val proxyAddress: String = "",
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    val apiKey: String = "",
    val includeMatureContent: Boolean = false
) {
    fun proxyUrl(): HttpUrl? {
        if (!proxyEnabled) return null
        val address = proxyAddress.trim()
        val url = (if ("://" in address) address else "http://$address").toHttpUrlOrNull()
        require(url != null && url.scheme == "http" && url.host.isNotBlank() &&
            url.username.isEmpty() && url.password.isEmpty() &&
            url.encodedPath == "/" && url.query == null && url.fragment == null) {
            "代理地址请填写 http://主机:端口；认证信息在下方单独填写"
        }
        return url
    }

    fun validated(): CivitaiSettings {
        val result = copy(
            proxyAddress = proxyAddress.trim(),
            proxyUsername = proxyUsername.trim(),
            apiKey = apiKey.trim()
        )
        result.proxyUrl()
        require(result.proxyUsername.none { it == ':' || it == '\r' || it == '\n' }) {
            "代理用户名请勿包含冒号或换行"
        }
        require(result.apiKey.length <= 4096 &&
            result.apiKey.all { it.code in 33..126 }) {
            "请填写原始 Civitai Key，不含 Bearer 前缀、空格或换行"
        }
        return result
    }

    fun cacheNamespace(bridgeIdentity: String = ""): String = marketHash(
        listOf(mode.name, if (mode == MarketConnectionMode.BRIDGE) bridgeIdentity else "",
            apiKey, includeMatureContent.toString()).joinToString("\u0000")
    )

    override fun toString(): String =
        "CivitaiSettings(mode=$mode, proxyEnabled=$proxyEnabled, credentials=<redacted>)"
}

internal fun marketHash(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
