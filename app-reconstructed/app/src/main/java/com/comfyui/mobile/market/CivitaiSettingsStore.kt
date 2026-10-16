package com.comfyui.mobile.market

import android.content.Context
import com.comfyui.mobile.data.SecretStore

class CivitaiSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "civitai_market", Context.MODE_PRIVATE
    )
    private val secrets = SecretStore.get(context)

    fun load(): CivitaiSettings = CivitaiSettings(
        mode = runCatching {
            MarketConnectionMode.valueOf(prefs.getString("mode", "DIRECT").orEmpty())
        }.getOrDefault(MarketConnectionMode.DIRECT),
        proxyEnabled = prefs.getBoolean("proxy_enabled", false),
        proxyAddress = prefs.getString("proxy_address", "").orEmpty(),
        proxyUsername = prefs.getString("proxy_username", "").orEmpty(),
        proxyPassword = secrets.getCivitaiProxyPassword(),
        apiKey = secrets.getCivitaiKey(),
        includeMatureContent = prefs.getBoolean("mature_content", false)
    )

    fun save(settings: CivitaiSettings) {
        val value = settings.validated()
        secrets.putCivitaiKey(value.apiKey)
        secrets.putCivitaiProxyPassword(value.proxyPassword)
        check(prefs.edit()
            .putString("mode", value.mode.name)
            .putBoolean("proxy_enabled", value.proxyEnabled)
            .putString("proxy_address", value.proxyAddress)
            .putString("proxy_username", value.proxyUsername)
            .putBoolean("mature_content", value.includeMatureContent)
            .commit()) { "保存集市设置失败，请重试" }
    }
}
