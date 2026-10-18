package com.comfyui.mobile.modelcenter

/** Shared browse/query contract for the UI and the future P3 Agent adapter. */
data class ModelAnnotation(
    val alias: String = "",
    val aliases: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val useCases: List<String> = emptyList(),
    val character: String = "",
    val outfit: String = "",
    val notes: String = "",
    val baseModel: String = "",
    val trainedWords: List<String> = emptyList(),
    val favorite: Boolean = false,
    val preferredStrength: Double? = null,
    val fieldLocks: List<String> = emptyList(),
    val classificationSource: String = "user",
    val classificationStatus: String = "unclassified"
)

data class ModelProvider(
    val provider: String = "", val matchedHash: String = "",
    val modelId: Long? = null, val versionId: Long? = null, val fileId: Long? = null,
    val name: String = "", val version: String = "", val author: String = "",
    val baseModel: String = "", val trainedWords: List<String> = emptyList(),
    val description: String = "", val fetchedAt: Long = 0, val source: String = "",
    val images: List<ModelPreview> = emptyList()
)
data class ModelPreview(val url: String = "")

data class LocalModel(
    val id: String = "", val serverId: String = "", val rootId: String = "",
    val category: String = "", val relativePath: String = "", val rootPath: String = "",
    val name: String = "", val format: String = "", val size: Long? = null,
    val mtime: Long? = null, val sha256: String? = null, val present: Boolean = false,
    val nodeModelName: String? = null, val loadStatus: String = "unresolved",
    val lastUsed: Long? = null, val annotation: ModelAnnotation = ModelAnnotation(),
    val revision: Int = 0, val provider: ModelProvider? = null, val matchBasis: String = ""
) {
    val displayName: String get() = annotation.alias.ifBlank { name }
    val baseModel: String get() = annotation.baseModel.ifBlank { provider?.baseModel.orEmpty() }
    // An explicitly locked empty override means "unknown", not a fallback to stale provider words.
    val triggerWords: List<String> get() = if ("trainedWords" in annotation.fieldLocks ||
        annotation.trainedWords.isNotEmpty()) annotation.trainedWords else provider?.trainedWords.orEmpty()
}
data class ModelRoot(val id: String = "", val category: String = "", val path: String = "", val status: String = "")
data class ModelScan(val status: String = "idle", val discovered: Int = 0, val hashed: Int = 0, val message: String = "") {
    val active: Boolean get() = status in setOf("scanning", "hashing")
}
data class ModelCapabilities(
    val schemaVersion: Int = 0, val serverId: String = "", val capabilities: List<String> = emptyList(),
    val categories: List<String> = emptyList(), val roots: List<ModelRoot> = emptyList(),
    val lastSync: Long = 0, val scanComplete: Boolean = false, val scan: ModelScan = ModelScan()
) {
    val supported: Boolean get() = schemaVersion == 1 && "model-index" in capabilities
}
data class ModelStats(
    val locations: Int = 0, val missing: Int = 0, val bytes: Long = 0,
    val hashed: Int = 0, val uniqueContents: Int = 0
)
data class ModelPage(
    val schemaVersion: Int = 1, val serverId: String = "", val items: List<LocalModel> = emptyList(),
    val page: Int = 1, val total: Int = 0, val hasMore: Boolean = false,
    val stats: ModelStats = ModelStats(), val lastSync: Long = 0, val scanComplete: Boolean = false,
    val scan: ModelScan = ModelScan()
)
data class ModelEnrichmentTask(
    val id: String = "", val locationId: String = "", val sha256: String? = null,
    val status: String = "", val message: String = "", val code: Int = 0,
    val retryAt: Long = 0, val updated: Long = 0
)
data class ModelTasks(val scan: ModelScan = ModelScan(), val enrichment: List<ModelEnrichmentTask> = emptyList())
data class ModelEnrichmentResult(
    val tasks: List<ModelEnrichmentTask> = emptyList(), val errors: List<ModelTaskError> = emptyList()
)
data class ModelTaskError(val locationId: String = "", val code: Int = 0, val message: String = "")
data class ModelInstallation(val serverId: String = "", val status: String = "not-indexed", val locations: List<String> = emptyList())
data class ModelEnvelope<T>(val code: Int = 0, val msg: String = "", val data: T? = null)

data class LocalModelQuery(
    val q: String = "", val category: String = "", val rootId: String = "", val useCase: String = "",
    val baseModel: String = "", val source: String = "", val favorite: Boolean = false,
    val sort: String = "name"
) {
    fun params(page: Int = 1) = mapOf(
        "q" to q, "category" to category, "rootId" to rootId, "useCase" to useCase,
        "baseModel" to baseModel, "source" to source, "favorite" to favorite.toString(),
        "sort" to sort, "page" to page.toString(), "pageSize" to PAGE_SIZE.toString()
    )
    fun matches(item: LocalModel): Boolean {
        if (category.isNotBlank() && category != item.category) return false
        if (rootId.isNotBlank() && rootId != item.rootId) return false
        if (favorite && !item.annotation.favorite) return false
        if (useCase == "unclassified" && item.annotation.useCases.isNotEmpty()) return false
        if (useCase.isNotBlank() && useCase != "unclassified" && useCase !in item.annotation.useCases) return false
        if (baseModel.isNotBlank() && item.baseModel != baseModel) return false
        if (source == "matched" && item.provider == null) return false
        if (source == "unknown" && item.provider != null) return false
        if (source == "missing" && item.present) return false
        if (source == "complete" && (item.baseModel.isBlank() || item.triggerWords.isEmpty())) return false
        val haystack = listOf(item.name, item.relativePath, item.rootPath, item.annotation.alias,
            item.annotation.aliases.joinToString(), item.annotation.tags.joinToString(),
            item.annotation.character, item.annotation.outfit, item.annotation.notes, item.baseModel,
            item.provider?.name.orEmpty(), item.provider?.version.orEmpty(), item.triggerWords.joinToString())
        return q.isBlank() || haystack.any { it.contains(q.trim(), ignoreCase = true) }
    }
    companion object { const val PAGE_SIZE = 40 }
}

val modelUseCases = linkedMapOf(
    "character" to "角色", "style" to "画风", "clothing" to "服装", "pose" to "动作",
    "scene" to "场景", "lighting" to "光影 / 质感", "other" to "其他"
)
fun modelCategoryLabel(category: String): String = when (category) {
    "LORA" -> "LoRA"; "CHECKPOINT" -> "大模型"; "UNET" -> "UNet"; "VAE" -> "VAE"
    "TEXT_ENCODER" -> "文本编码器"; else -> "全部类型"
}
