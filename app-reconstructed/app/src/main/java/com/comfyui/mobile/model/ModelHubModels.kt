package com.comfyui.mobile.model

data class PluginItem(
    val name: String = "",
    val id: String = "",
    val description: String? = null,
    val enabled: Boolean = true
)

data class AssetsSummaryData(
    val totalModels: Int = 0,
    val checkpointsCount: Int = 0,
    val lorasCount: Int = 0,
    val unetCount: Int = 0,
    val totalPlugins: Int = 0,
    val plugins: List<PluginItem>? = null,
    val loraManagerInstalled: Boolean = false,
    val comfyManagerInstalled: Boolean = false
)

data class AssetsSummaryResponse(
    val code: Int = -1,
    val msg: String? = null,
    val data: AssetsSummaryData? = null
)

data class CivitaiFileItem(
    val id: Long = 0,
    val name: String = "",
    val sizeKB: Double = 0.0,
    val downloadUrl: String = "",
    val format: String = "",
    val type: String = "Model",
    val primary: Boolean = false,
    /** 桥接给出的 "model" / "component"；旧桥接为空时按 type 判断 */
    val role: String = "",
    val precision: String = ""
) {
    /** Civitai files[].type 为 VAE / Text Encoder 视为必需组件（role 仅作补充，不能用 model 覆盖 type） */
    val isRequiredComponent: Boolean
        get() {
            val byType = type.equals("VAE", true) ||
                type.equals("Text Encoder", true) ||
                type.equals("TextEncoder", true)
            val byRole = role.equals("component", true)
            return byType || byRole
        }

    val componentTypeLabel: String
        get() = when {
            type.equals("VAE", true) -> "VAE"
            type.equals("Text Encoder", true) -> "文本编码"
            else -> type
        }

    /** Chip 文案：以 API 文件名为准（含扩展名）。横滑 Chip 优先完整名；过长只从前面省略，避免中间截断看起来像主模型名 */
    val displayLabel: String
        get() {
            val n = name.substringAfterLast('/').ifBlank { name.ifBlank { "文件" } }
            return if (n.length <= 64) n else "…" + n.takeLast(63)
        }
}

data class CivitaiPreviewItem(
    val id: Long = 0,
    val url: String = "",
    val prompt: String = "",
    val negativePrompt: String = "",
    val sampler: String = "",
    val steps: String = "",
    val seed: String = "",
    val cfgScale: String = "",
    val communityExample: Boolean = false
)

data class CivitaiVersionItem(
    val id: Long = 0,
    val name: String = "",
    val baseModel: String = "",
    val trainedWords: List<String> = emptyList(),
    val description: String = "",
    val downloadUrl: String = "",
    val sizeKB: Double = 0.0,
    val previewUrl: String? = null,
    val files: List<CivitaiFileItem> = emptyList(),
    val images: List<CivitaiPreviewItem> = emptyList(),
    val availability: String = "",
    val canDownload: Boolean? = null
) {
    val authorFiles: List<CivitaiFileItem> get() = files.filterNot { it.isRequiredComponent }
    val componentFiles: List<CivitaiFileItem> get() = files.filter { it.isRequiredComponent }

    fun preferredFile(): CivitaiFileItem? =
        authorFiles.firstOrNull { it.primary } ?: authorFiles.firstOrNull()
            ?: files.firstOrNull { it.primary } ?: files.firstOrNull()
}

data class CivitaiModelItem(
    val id: Long = 0,
    val name: String = "",
    val type: String = "LORA",
    val creator: String = "",
    val description: String = "",
    val downloadCount: Long = 0,
    val rating: Double = 0.0,
    val thumbsUpCount: Long = 0,
    val tags: List<String> = emptyList(),
    val previewUrl: String? = null,
    val versions: List<CivitaiVersionItem> = emptyList()
)

data class CivitaiSearchPageData(
    val items: List<CivitaiModelItem> = emptyList(),
    val nextCursor: String? = null,
    val totalItems: Int = 0,
    val currentPage: Int = 1,
    val pageSize: Int = 20,
    val totalPages: Int = 1,
    val nextPage: Int? = null
)

data class CivitaiSearchResponse(
    val code: Int = -1,
    val msg: String? = null,
    val data: CivitaiSearchPageData? = null
)

data class CivitaiDownloadRequest(
    val modelId: Long,
    val modelVersionId: Long,
    val modelType: String? = null,
    val modelRoot: String? = null,
    val subfolder: String? = null,
    /** Civitai file id; Bridge resolves the URL and SHA-256 from official metadata. */
    val fileId: Long? = null,
    val downloadUrl: String? = null,
    /** Civitai files[].type, e.g. Model / VAE / Text Encoder */
    val fileType: String? = null,
    /** "model" / "component" */
    val role: String? = null
)

data class ModelFolderItem(
    val name: String,
    val root: String,
    val subfolder: String
)

data class ModelFoldersData(
    val folders: List<ModelFolderItem> = emptyList()
)

data class ModelFoldersResponse(
    val code: Int = -1,
    val msg: String? = null,
    val data: ModelFoldersData? = null
)

data class CivitaiTaskActionRequest(
    val action: String,
    val downloadId: String
)

data class CivitaiDownloadTaskItem(
    val download_id: String? = null,
    val model_id: Any? = null,
    val model_version_id: Any? = null,
    val model_name: String? = null,
    val filename: String? = null,
    val file_name: String? = null,
    val status: String? = null,
    val progress: Double? = null,
    val speed: String? = null,
    val speed_bytes: Double? = null,
    val total_size: Double? = null,
    val downloaded_size: Double? = null,
    val error: String? = null,
    val preview_url: String? = null
) {
    val effectiveName: String
        get() = model_name ?: filename ?: file_name ?: "下载任务"
}

data class CivitaiTasksData(
    val queue: List<CivitaiDownloadTaskItem>? = null,
    val stats: Map<String, Any>? = null
)

data class CivitaiTasksResponse(
    val code: Int = -1,
    val msg: String? = null,
    val data: CivitaiTasksData? = null
)

data class SimpleActionResponse(
    val code: Int = -1,
    val msg: String? = null
)
