package com.comfyui.mobile.danbooru

import android.content.Context
import com.comfyui.mobile.data.SecretStore
import com.google.gson.Gson
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Never saved in SavedState, diagnostic exports, normal preferences, or URLs. */
data class DanbooruSettings(
    val username: String = "",
    val apiKey: String = "",
    val proxyEnabled: Boolean = false,
    val proxyAddress: String = "",
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    val defaultFilter: ContentFilter = ContentFilter.GENERAL,
    val copyOptions: TagCopyOptions = TagCopyOptions()
) {
    val loggedIn get() = username.isNotBlank() && apiKey.isNotBlank()
    fun proxyUrl(): HttpUrl? {
        if (!proxyEnabled) return null
        val address = proxyAddress.trim()
        val url = (if ("://" in address) address else "http://$address").toHttpUrlOrNull()
        require(url != null && url.scheme == "http" && url.host.isNotBlank() &&
            url.username.isEmpty() && url.password.isEmpty() && url.encodedPath == "/" &&
            url.query == null && url.fragment == null && ':' in address.substringAfter("://")) {
            "代理请填写 http://主机:端口；认证单独填写"
        }
        return url
    }
    fun validated(): DanbooruSettings {
        val result = copy(username = username.trim(), apiKey = apiKey.trim(),
            proxyAddress = proxyAddress.trim(), proxyUsername = proxyUsername.trim())
        require(result.username.isBlank() == result.apiKey.isBlank()) { "用户名和 API Key 请同时填写，或同时留空匿名浏览" }
        require(result.username.none { it == ':' || it == '\r' || it == '\n' } &&
            result.proxyUsername.none { it == ':' || it == '\r' || it == '\n' }) { "用户名请勿包含冒号或换行" }
        require(result.apiKey.length <= 4096 && result.apiKey.all { it.code in 33..126 }) { "API Key 请填写原文，不含空格、换行或 Basic 前缀" }
        result.proxyUrl()
        return result
    }
    // Includes routing as well as identity: a different connection configuration gets a new scope.
    fun namespace(): String = danbooruHash(listOf("https://danbooru.donmai.us", username.lowercase(), apiKey,
        proxyEnabled.toString(), proxyAddress, proxyUsername, proxyPassword).joinToString("\u0000"))
    override fun toString() = "DanbooruSettings(credentials=<redacted>)"
}

interface DanbooruConfigStore {
    fun load(): DanbooruSettings
    fun save(settings: DanbooruSettings)
}

/** One encrypted envelope + one atomic SecretStore write: username/key/options never half-save.
 * Ordinary fields are also encrypted instead of maintaining a second, non-transactional prefs copy.
 */
class DanbooruSettingsStore(context: Context) : DanbooruConfigStore {
    private val secrets = SecretStore.get(context)
    private val gson = Gson()
    override fun load(): DanbooruSettings {
        val raw = secrets.getDanbooruConfig()
        if (raw.isBlank()) return DanbooruSettings()
        return gson.fromJson(raw, DanbooruSettings::class.java).validated()
    }
    override fun save(settings: DanbooruSettings) {
        secrets.putDanbooruConfig(gson.toJson(settings.validated()))
    }
}
