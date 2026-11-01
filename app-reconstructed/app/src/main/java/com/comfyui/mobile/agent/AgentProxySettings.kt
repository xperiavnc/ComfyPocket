package com.comfyui.mobile.agent

import android.content.SharedPreferences
import com.comfyui.mobile.data.SecretStore
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * HTTP proxy for Agent model API calls only (chat, streaming, model list).
 * LAN / localhost endpoints always bypass it. The password lives in [SecretStore], never in prefs.
 */
data class AgentProxySettings(
    val enabled: Boolean = false,
    val address: String = "",
    val username: String = "",
    val password: String = ""
) {
    /** Null when disabled; throws with a user-facing message when the address is malformed. */
    fun proxyUrl(): HttpUrl? {
        if (!enabled) return null
        val raw = address.trim()
        require(raw.isNotEmpty()) { "请填写代理地址，例如 http://192.168.1.10:7890" }
        val url = (if ("://" in raw) raw else "http://$raw").toHttpUrlOrNull()
        require(url != null && url.scheme == "http" && url.host.isNotBlank() &&
            url.username.isEmpty() && url.password.isEmpty() &&
            url.encodedPath == "/" && url.query == null && url.fragment == null) {
            "代理地址请填写 http://主机:端口；认证信息在下方单独填写"
        }
        return url
    }

    fun validated(): AgentProxySettings {
        val result = copy(address = address.trim(), username = username.trim())
        result.proxyUrl()
        require(result.username.none { it == ':' || it == '\r' || it == '\n' }) { "代理用户名请勿包含冒号或换行" }
        return result
    }

    /** Short label for summaries, e.g. "192.168.1.10:7890". */
    fun label(): String? = runCatching { proxyUrl() }.getOrNull()?.let { "${it.host}:${it.port}" }

    override fun toString(): String = "AgentProxySettings(enabled=$enabled, address=$address, credentials=<redacted>)"
}

class AgentProxySettingsStore(private val prefs: SharedPreferences, private val secrets: SecretStore?) {
    fun load(): AgentProxySettings = AgentProxySettings(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        address = prefs.getString(KEY_ADDRESS, "").orEmpty(),
        username = prefs.getString(KEY_USERNAME, "").orEmpty(),
        password = secrets?.getAgentProxyPassword().orEmpty()
    )

    fun save(settings: AgentProxySettings): AgentProxySettings {
        val value = settings.validated()
        secrets?.putAgentProxyPassword(value.password)
        check(prefs.edit()
            .putBoolean(KEY_ENABLED, value.enabled)
            .putString(KEY_ADDRESS, value.address)
            .putString(KEY_USERNAME, value.username)
            .commit()) { "保存代理设置失败，请重试" }
        return value
    }

    companion object {
        const val KEY_ENABLED = "agent_proxy_enabled"
        const val KEY_ADDRESS = "agent_proxy_address"
        const val KEY_USERNAME = "agent_proxy_username"
    }
}
