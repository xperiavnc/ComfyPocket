package com.comfyui.mobile.market

import com.comfyui.mobile.model.CivitaiFileItem
import com.comfyui.mobile.model.CivitaiModelItem
import com.comfyui.mobile.model.CivitaiSearchPageData
import com.comfyui.mobile.model.CivitaiVersionItem
import org.junit.Assert.*
import org.junit.Test

class MarketModelFiltersTest {
    private val safe = CivitaiFileItem(id = 1, name = "model.safetensors", format = "SafeTensor", primary = true)
    private val gguf = CivitaiFileItem(id = 2, name = "model-q4.gguf", format = "GGUF")
    private fun model(type: String = "Checkpoint", vararg versions: CivitaiVersionItem) =
        CivitaiModelItem(id = 10, type = type, versions = versions.toList())
    private fun version(id: Long, base: String = "SDXL 1.0", vararg files: CivitaiFileItem) =
        CivitaiVersionItem(id = id, baseModel = base, files = files.toList(), previewUrl = "image-$id")

    @Test fun unetDoesNotFallBackToCheckpointCards() {
        val checkpoint = model("Checkpoint", version(1, "SDXL 1.0", safe))
        assertNull(MarketModelFilters.model(checkpoint, MarketQuery(type = "UNET")))
        val unet = checkpoint.copy(type = "UNet")
        assertNotNull(MarketModelFilters.model(unet, MarketQuery(type = "UNET")))
        assertNull(MarketModelFilters.model(unet, MarketQuery(type = "CHECKPOINT")))
    }

    @Test fun ggufKeepsOnlyMatchingVersionsAndAuthorFilesButPreservesComponents() {
        val vae = CivitaiFileItem(id = 3, name = "vae.safetensors", type = "VAE")
        val input = model("Checkpoint", version(1, "SDXL 1.0", safe),
            version(2, "SDXL 1.0", safe, gguf, vae))
        val result = MarketModelFilters.model(input, MarketQuery(type = "GGUF"))!!
        assertEquals(listOf(2L), result.versions.map { it.id })
        assertEquals(listOf(2L), result.versions.single().authorFiles.map { it.id })
        assertEquals(listOf(3L), result.versions.single().componentFiles.map { it.id })
        assertEquals(2L, result.versions.single().preferredFile()!!.id)
        assertEquals("image-2", result.previewUrl)
    }

    @Test fun ggufComponentDoesNotTurnASafetensorsModelIntoAGgufModel() {
        val input = model("Checkpoint", version(1, "SDXL 1.0", safe,
            gguf.copy(type = "Text Encoder")))
        assertNull(MarketModelFilters.model(input, MarketQuery(type = "GGUF")))
    }

    @Test fun baseModelTagProjectsTheMatchingVersionInsteadOfShowingAnotherEcosystem() {
        val input = model("LORA", version(1, "SDXL 1.0", safe),
            version(2, "Krea 2", safe))
        val result = MarketModelFilters.model(input, MarketQuery(baseModel = "Krea 2"))!!
        assertEquals(listOf("Krea 2"), result.versions.map { it.baseModel })
        assertEquals("image-2", result.previewUrl)
    }

    @Test fun unavailableFileListDoesNotHideGatedMetadataForOrdinaryTypes() {
        val input = model("Checkpoint", version(1, "SDXL 1.0").copy(canDownload = false))
        assertNotNull(MarketModelFilters.model(input, MarketQuery(type = "CHECKPOINT")))
        assertNull(MarketModelFilters.model(input, MarketQuery(type = "GGUF")))
    }

    @Test fun emptyProjectedPageKeepsItsContinuation() {
        val input = CivitaiSearchPageData(
            items = listOf(model("Checkpoint", version(1, "SDXL 1.0", safe))),
            nextCursor = "next|page", currentPage = 3
        )
        val result = MarketModelFilters.page(input, MarketQuery(type = "GGUF"))
        assertTrue(result.items.isEmpty())
        assertEquals("next|page", result.nextCursor)
        assertEquals(3, result.currentPage)
    }
}
