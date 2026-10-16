package com.comfyui.mobile.model

import androidx.compose.runtime.Immutable
import com.google.gson.annotations.SerializedName

enum class ServerType {
    LOCAL_COMFYUI,
    RUNNINGHUB
}

enum class RunningHubSite(
    val id: String,
    val displayName: String,
    val hostUrl: String,
    val llmUrl: String
) {
    @SerializedName("DOMESTIC_CN", alternate = ["cn", "CN", "https://www.runninghub.cn", "runninghub.cn"])
    DOMESTIC_CN("cn", "CN站", "https://www.runninghub.cn", "https://llm.runninghub.cn/v1"),

    @SerializedName("OVERSEAS_AI", alternate = ["ai", "AI", "https://www.runninghub.ai", "runninghub.ai"])
    OVERSEAS_AI("ai", "AI站", "https://www.runninghub.ai", "https://llm.runninghub.ai/v1");

    companion object {
        fun fromId(id: String?): RunningHubSite {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) || it.name.equals(id, ignoreCase = true) }
                ?: DOMESTIC_CN
        }

        fun fromUrl(url: String?): RunningHubSite? {
            if (url.isNullOrBlank()) return null
            return when {
                url.contains("runninghub.cn", ignoreCase = true) -> DOMESTIC_CN
                url.contains("runninghub.ai", ignoreCase = true) -> OVERSEAS_AI
                else -> null
            }
        }
    }
}

enum class RunningHubGpuTier(
    val id: String,
    val displayName: String,
    val pathPrefix: String,
    /** instanceType for the official task API ("default" = 24G, "plus" = 48G, "ultra" = 84G). */
    val instanceType: String
    ) {
    STANDARD_24G("24g", "标准 (24G)", "proxy", "default"),
    PLUS_48G("48g", "Plus (48G)", "proxy-plus", "plus"),
    /** pathPrefix only builds the read-only base URL (object_info / view); it has no 84G path. */
    ULTRA_84G("84g", "Ultra (84G)", "proxy-plus", "ultra");

    companion object {
        fun fromId(id: String?): RunningHubGpuTier {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) || it.name.equals(id, ignoreCase = true) }
                ?: STANDARD_24G
        }
    }
}

@Immutable
data class ServerProfile(
    val id: String? = null,
    val name: String? = null,
    val scheme: String? = "http",
    val host: String? = "",
    val port: String? = "8188",
    val serverType: ServerType? = ServerType.LOCAL_COMFYUI,
    val rhSite: RunningHubSite? = RunningHubSite.DOMESTIC_CN,
    val rhApiKey: String? = "",
    val rhGpuTier: RunningHubGpuTier? = RunningHubGpuTier.STANDARD_24G,
    /** Obsolete (former run mode). Kept only so older saved profiles still deserialize; ignored. */
    val rhRunMode: String? = null
) {
    val effectiveId: String
        get() = id.orEmpty()

    val effectiveName: String
        get() = name.orEmpty()

    val effectiveScheme: String
        get() = scheme.orEmpty().ifBlank { "http" }

    val effectiveHost: String
        get() = host.orEmpty()

    val effectivePort: String
        get() = port.orEmpty().ifBlank { if (effectiveScheme == "https") "443" else "8188" }

    val effectiveServerType: ServerType
        get() = serverType ?: ServerType.LOCAL_COMFYUI

    val isRunningHub: Boolean
        get() = effectiveServerType == ServerType.RUNNINGHUB

    val effectiveRhSite: RunningHubSite
        get() = rhSite ?: when {
            host?.contains("runninghub.ai", ignoreCase = true) == true -> RunningHubSite.OVERSEAS_AI
            host?.contains("runninghub.cn", ignoreCase = true) == true -> RunningHubSite.DOMESTIC_CN
            else -> RunningHubSite.DOMESTIC_CN
        }

    val effectiveRhGpuTier: RunningHubGpuTier
        get() = rhGpuTier ?: RunningHubGpuTier.STANDARD_24G

    val effectiveRhApiKey: String
        get() = rhApiKey.orEmpty().trim()

    val displayName: String
        get() = if (isRunningHub) {
            val custom = effectiveName.takeIf { it.isNotBlank() && !isDefaultRhName(it) }
            custom ?: "RunningHub (${effectiveRhSite.displayName})"
        } else {
            effectiveName.ifBlank { effectiveHost.ifBlank { "本地服务器" } }
        }

    val addressLabel: String
        get() = if (isRunningHub) {
            "${effectiveRhSite.hostUrl} [${effectiveRhGpuTier.displayName}]"
        } else {
            baseUrl
        }

    val baseUrl: String
        get() = if (isRunningHub) {
            val root = effectiveRhSite.hostUrl.trimEnd('/')
            val tier = effectiveRhGpuTier.pathPrefix
            val key = effectiveRhApiKey
            if (key.isBlank()) "$root/$tier" else "$root/$tier/$key"
        } else {
            // A host that already carries a scheme (e.g. "https://example.com") must not be
            // prefixed again, otherwise we build "https://https:/example.com".
            val rawHost = effectiveHost.trim()
            val hostScheme = when {
                rawHost.startsWith("https://", ignoreCase = true) -> "https"
                rawHost.startsWith("http://", ignoreCase = true) -> "http"
                else -> null
            }
            val hostNoScheme = when (hostScheme) {
                "https" -> rawHost.substring(8)
                "http" -> rawHost.substring(7)
                else -> rawHost
            }.trimEnd('/')
            val s = hostScheme ?: effectiveScheme.lowercase()
            val portPart = if ((s == "https" && effectivePort == "443") || (s == "http" && effectivePort == "80") || effectivePort.isBlank()) {
                ""
            } else {
                ":$effectivePort"
            }
            if (hostNoScheme.contains("/")) {
                val domain = hostNoScheme.substringBefore("/")
                val path = "/" + hostNoScheme.substringAfter("/").trimStart('/')
                "$s://$domain$portPart$path"
            } else {
                "$s://$hostNoScheme$portPart"
            }
        }

    companion object {
        fun isDefaultRhName(name: String?): Boolean {
            if (name.isNullOrBlank()) return true
            val t = name.trim()
            if (t == "RunningHub") return true
            if (t == "RunningHub (CN站)" || t == "RunningHub (AI站)") return true
            if (t == "RunningHub CN站" || t == "RunningHub AI站") return true
            if (t.startsWith("RunningHub") && (t.contains("24G") || t.contains("48G") || t.contains("84G") || t.contains("CN站") || t.contains("AI站"))) {
                return true
            }
            return false
        }
    }
}
