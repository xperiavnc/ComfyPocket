package com.comfyui.mobile.market

import com.comfyui.mobile.model.CivitaiFileItem
import com.comfyui.mobile.model.CivitaiModelItem
import com.comfyui.mobile.model.CivitaiSearchPageData

/** The API matches models, but can return versions/files outside the selected filter. */
internal object MarketModelFilters {
    fun page(value: CivitaiSearchPageData, query: MarketQuery): CivitaiSearchPageData =
        value.copy(items = value.items.mapNotNull { model(it, query) })

    fun model(value: CivitaiModelItem, query: MarketQuery): CivitaiModelItem? {
        val expectedTypes = when (query.type.uppercase()) {
            "CHECKPOINT", "CHECKPOINTS" -> listOf("Checkpoint")
            "UNET" -> listOf("UNet")
            "GGUF" -> listOf("Checkpoint", "UNet")
            "LORA", "LORAS" -> listOf("LORA", "LoCon", "DoRA")
            else -> emptyList()
        }
        if (expectedTypes.isNotEmpty() && expectedTypes.none { it.equals(value.type, true) }) return null
        val gguf = query.type.equals("GGUF", true)
        val bases = CivitaiJson.baseModels(query.baseModel)
        // Keep gated metadata visible even when the current account cannot list its files.
        if (value.versions.isEmpty()) return value.takeUnless { gguf }
        val versions = value.versions.mapNotNull { version ->
            if (bases.isNotEmpty() && bases.none { it.equals(version.baseModel, true) }) return@mapNotNull null
            if (!gguf) return@mapNotNull version
            if (version.authorFiles.none { it.isGguf() }) return@mapNotNull null
            version.copy(files = version.files.filter { it.isRequiredComponent || it.isGguf() })
        }
        if (versions.isEmpty()) return null
        return value.copy(
            versions = versions,
            previewUrl = versions.firstNotNullOfOrNull { it.previewUrl } ?: value.previewUrl
        )
    }

    private fun CivitaiFileItem.isGguf(): Boolean =
        format.equals("GGUF", true) || name.endsWith(".gguf", true)
}
