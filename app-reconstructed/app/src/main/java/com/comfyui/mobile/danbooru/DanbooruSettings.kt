package com.comfyui.mobile.danbooru

import android.content.SharedPreferences
import com.comfyui.mobile.agent.AgentPromptFormat
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest

data class DanbooruSettings(
    val enabled: Boolean = true,
    val proxyEnabled: Boolean = false,
    val proxyAddress: String = "",
    val callMode: TagCallMode = TagCallMode.AUTO,
    val promptFormat: AgentPromptFormat = AgentPromptFormat.TAGS
) {
    fun proxyUrl(): HttpUrl? {
        if (!proxyEnabled) return null
        val raw = proxyAddress.trim()
        val url = (if ("://" in raw) raw else "http://$raw").toHttpUrlOrNull()
        require(url != null && url.scheme == "http" && url.username.isEmpty() && url.password.isEmpty() &&
            url.encodedPath == "/" && url.query == null && url.fragment == null) {
            "HTTP 代理请填写 http://主机:端口（不含账号密码与路径）"
        }
        return url
    }
    fun validated(): DanbooruSettings = copy(proxyAddress = proxyAddress.trim()).also { it.proxyUrl() }
    fun namespace(): String = MessageDigest.getInstance("SHA-256")
        .digest((proxyUrl()?.toString() ?: "direct").toByteArray())
        .joinToString("") { "%02x".format(it) }
}

class DanbooruSettingsStore(private val prefs: SharedPreferences) {
    fun load() = DanbooruSettings(
        enabled = prefs.getBoolean("agent_danbooru_enabled", true),
        proxyEnabled = prefs.getBoolean("agent_danbooru_proxy_enabled", false),
        proxyAddress = prefs.getString("agent_danbooru_proxy", "").orEmpty(),
        callMode = TagCallMode.entries.firstOrNull { it.name == prefs.getString("agent_danbooru_mode", null) } ?: TagCallMode.AUTO,
        promptFormat = AgentPromptFormat.fromStored(prefs.getString("agent_prompt_format", null))
    )
    fun save(settings: DanbooruSettings) {
        val s = settings.validated()
        prefs.edit().putBoolean("agent_danbooru_enabled", s.enabled)
            .putBoolean("agent_danbooru_proxy_enabled", s.proxyEnabled)
            .putString("agent_danbooru_proxy", s.proxyAddress).putString("agent_danbooru_mode", s.callMode.name)
            .putString("agent_prompt_format", s.promptFormat.name).apply()
    }
}
