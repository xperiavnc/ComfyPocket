package com.comfyui.mobile.modelcenter

/** Exact filesystem precondition, not an annotation alias or a phone-side path. */
data class ModelFileWrite(
    val action: String, val expectedPath: String, val expectedSize: Long?,
    val expectedMtimeNs: Long?, val name: String? = null,
    val rootId: String? = null, val directory: String? = null, val confirmName: String? = null
) {
    companion object {
        fun forItem(item: LocalModel, action: String, name: String? = null,
                    rootId: String? = null, directory: String? = null) = ModelFileWrite(
            action, item.relativePath, item.size, item.mtimeNs, name, rootId, directory,
            if (action == "delete") item.name else null)
    }
}

data class ModelDirectories(
    val serverId: String = "", val rootId: String = "", val path: String = "",
    val directories: List<String> = emptyList(), val truncated: Boolean = false
)
data class ModelFileResult(
    val serverId: String = "", val action: String = "", val previousId: String = "",
    val item: LocalModel? = null, val relatedFiles: Int = 0,
    val warnings: List<String> = emptyList(), val message: String = ""
) {
    val feedback: String get() = (listOf(message) + warnings).filter(String::isNotBlank).joinToString("\n")
}
