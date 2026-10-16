package com.comfyui.mobile.ui.screens.modelbrowser

import android.content.Context

/** Grouping structure for base model ecosystem tags in management dialog. */
data class BaseModelGroup(
    val categoryName: String,
    val items: List<String>
)

val DEFAULT_FILTER_TAGS = listOf(
    "SDXL",
    "Illustrious",
    "Anima",
    "Pony",
    "Flux",
    "Wan",
    "SD 1.5"
)

val ALL_BASE_MODEL_GROUPS = listOf(
    BaseModelGroup(
        categoryName = "🌸 动漫 / 二次元",
        items = listOf("Anima", "Illustrious", "Pony", "NoobAI")
    ),
    BaseModelGroup(
        categoryName = "🎨 主流生图 (SD / Flux)",
        items = listOf("SDXL", "Flux", "SD 1.5", "SD 3.5", "Kolors", "Stable Cascade")
    ),
    BaseModelGroup(
        categoryName = "🎬 视频模型",
        items = listOf("Wan", "HunyuanVideo", "LTX", "Minimax", "SVD", "Kling", "Vidu")
    ),
    BaseModelGroup(
        categoryName = "🤖 多模态与通用",
        items = listOf("Qwen", "Z-Image", "Hunyuan", "HiDream")
    )
)

private const val PREF_PINNED_FILTER_TAGS = "pinned_model_filter_tags"
private const val PREFS_NAME = "comfy_prefs"

fun loadPinnedFilterTags(context: Context): List<String> {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val saved = prefs.getString(PREF_PINNED_FILTER_TAGS, null)
    if (saved.isNullOrBlank()) return DEFAULT_FILTER_TAGS
    val list = saved.split(",").map { it.trim() }.filter { it.isNotBlank() }
    return if (list.isNotEmpty()) list else DEFAULT_FILTER_TAGS
}

fun savePinnedFilterTags(context: Context, tags: List<String>) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit().putString(PREF_PINNED_FILTER_TAGS, tags.joinToString(",")).apply()
}

/**
 * Case-insensitive and cross-platform (RunningHub official + Civitai official)
 * alias expansion for model base ecosystem tags.
 */
fun getBaseModelAliases(tag: String): List<String> {
    val t = tag.trim()
    return when {
        t.equals("Anima", ignoreCase = true) || t.equals("Animagine", ignoreCase = true) -> listOf(
            "anima", "Anima", "ANIMA", "Animagine", "animagine", "ANIMAGINE",
            "Animagine XL", "animagine xl", "Animagine-XL", "anima pencil", "AnimaPencil"
        )
        t.equals("Illustrious", ignoreCase = true) || t.equals("IL-XL", ignoreCase = true) -> listOf(
            "IL-XL", "NoobAI-XL", "Illustrious", "illustrious", "ILLUSTRIOUS",
            "Illustrious-XL", "illustrious-xl", "Illustrious XL", "illustrious xl",
            "NoobAI", "noobai", "IL", "il"
        )
        t.equals("Pony", ignoreCase = true) || t.equals("Pony-XL", ignoreCase = true) -> listOf(
            "Pony-XL", "Pony", "pony", "PONY", "Pony V7", "pony v7",
            "PDXL", "pdxl", "Pony XL", "pony xl", "Pony Diffusion V6 XL"
        )
        t.equals("SDXL", ignoreCase = true) -> listOf(
            "SDXL 1.0", "SDXL", "sdxl", "SDXL Turbo", "SDXL Lighting", "SDXL Lightning",
            "SDXL 1.0 LCM", "SDXL Hyper", "SDXL 0.9", "sdxl turbo", "sdxl lightning", "sdxl 1.0"
        )
        t.equals("SD 1.5", ignoreCase = true) || t.equals("1.5", ignoreCase = true) -> listOf(
            "SD 1.5", "sd 1.5", "SD1.5", "sd1.5", "SD 1.5 LCM", "SD 1.4", "v1-5", "1.5", "SD 1.x", "sd 1.x"
        )
        t.equals("Flux", ignoreCase = true) -> listOf(
            "F1基础 D", "F1基础 S", "F1基础-Kontext", "F1基础-Krea", "Flux2-Klein-4B", "Flux2-Klein-9B",
            "F2基础", "Flux.1 D", "Flux.1 S", "Flux.1 Krea", "Flux.1 Kontext", "Flux.2 D",
            "Flux.2 Klein 9B", "Flux.2 Klein 9B-base", "Flux.2 Klein 4B", "Flux.2 Klein 4B-base",
            "Flux", "flux", "FLUX.1", "flux.1", "FLUX"
        )
        t.equals("Wan", ignoreCase = true) -> listOf(
            "WAN2.1", "WAN2.2", "Wan Video", "Wan", "wan", "WAN", "Wan2.1", "wan2.1", "Wan 2.1",
            "wan 2.1", "WanVideo", "wanvideo", "Wan Video 14B", "Wan Video 1.3B"
        )
        t.equals("Qwen", ignoreCase = true) -> listOf(
            "Qwen-image", "qwen-image-2.1", "Qwen-Image-2512", "Qwen-Edit-2509", "Qwen-Edit-2511",
            "Qwen", "qwen", "QWEN", "Qwen 2.1", "qwen 2.1", "qwen-image"
        )
        t.equals("HunyuanVideo", ignoreCase = true) -> listOf(
            "HunyuanVideo1.5", "HunyuanVideo", "hunyuanvideo", "Hunyuan Video", "hunyuan video"
        )
        t.equals("Hunyuan", ignoreCase = true) -> listOf(
            "HunyuanImage", "Hunyuan 1", "Hunyuan", "hunyuan"
        )
        t.equals("LTX", ignoreCase = true) || t.equals("LTXV", ignoreCase = true) -> listOf(
            "LTX2.3", "Lightricks/LTX-2", "LTXV", "LTXV2", "LTXV 2.3", "LTXV 2.5", "LTX-Video", "ltx-video", "ltx"
        )
        t.equals("Minimax", ignoreCase = true) -> listOf(
            "minimax-h3", "MiniMax H3", "minimax", "MiniMax", "minimax-music3"
        )
        t.equals("Z-Image", ignoreCase = true) -> listOf(
            "Z-image-turbo", "Z-image-base", "ZImageTurbo", "ZImageBase", "z-image", "Z-Image"
        )
        t.equals("NoobAI", ignoreCase = true) -> listOf(
            "NoobAI-XL", "NoobAI", "noobai", "noobai-xl", "NoobAI-XL (NAI-XL)"
        )
        t.equals("SD 3.5", ignoreCase = true) || t.equals("SD3.5", ignoreCase = true) -> listOf(
            "SD3.5", "SD 3.5", "SD 3.0", "sd3.5", "sd 3.5", "SD 3.5 Large", "SD 3.5 Medium"
        )
        t.equals("Kolors", ignoreCase = true) -> listOf(
            "Kolors", "kolors"
        )
        t.equals("SVD", ignoreCase = true) -> listOf(
            "SVD XT", "SVD", "svd", "svd xt"
        )
        t.equals("Kling", ignoreCase = true) -> listOf(
            "kling", "Kling"
        )
        t.equals("Vidu", ignoreCase = true) -> listOf(
            "vidu", "Vidu", "Vidu Q1"
        )
        t.equals("HiDream", ignoreCase = true) -> listOf(
            "HiDream", "hidream"
        )
        t.equals("Stable Cascade", ignoreCase = true) -> listOf(
            "Stable Cascade", "stable cascade"
        )
        t.equals("全部", ignoreCase = true) || t.equals("All", ignoreCase = true) -> emptyList()
        else -> listOf(t, t.lowercase(), t.uppercase(), t.replaceFirstChar { it.uppercase() })
    }
}
