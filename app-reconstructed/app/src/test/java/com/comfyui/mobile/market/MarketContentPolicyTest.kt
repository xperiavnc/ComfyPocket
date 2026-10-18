package com.comfyui.mobile.market

import com.comfyui.mobile.model.*
import org.junit.Assert.*
import org.junit.Test

class MarketContentPolicyTest {
    private val version = CivitaiVersionItem(id = 3, images = listOf(1, 2, 4, 8, 16, 32).map {
        CivitaiPreviewItem(id = it.toLong(), url = "https://image.civitai.com/fixture/$it.png",
            nsfwLevel = it, prompt = "fixture-$it")
    } + CivitaiPreviewItem(url = "https://image.civitai.com/fixture/unknown.png"))

    @Test fun ordinaryModelsAndUnknownRatingsNeverInheritAdultPreviews() {
        val model = CivitaiModelItem(id = 1, nsfw = false, versions = listOf(version),
            previewUrl = "https://image.civitai.com/fixture/8.png")
        val visible = MarketContentPolicy.model(model, CivitaiContentChannel.STANDARD, true)!!
        assertEquals(listOf(1L), visible.versions.single().images.map { it.id })
        assertTrue(visible.previewUrl!!.endsWith("/1.png"))
    }

    @Test fun minorAndSfwOnlyStayOrdinaryEvenWhenMatureBrowsingIsEnabled() {
        for (flags in listOf(true to false, false to true)) {
            val model = CivitaiModelItem(id = 2779381, nsfw = false,
                minor = flags.first, sfwOnly = flags.second, versions = listOf(version))
            val visible = MarketContentPolicy.model(model, CivitaiContentChannel.MATURE, true)!!
            assertEquals(CivitaiContentChannel.STANDARD, visible.contentChannel)
            assertEquals(model.minor, visible.minor)
            assertEquals(model.sfwOnly, visible.sfwOnly)
            assertEquals(listOf(1L), visible.versions.single().images.map { it.id })
        }
    }

    @Test fun matureScopeRespectsKnownRatingsAndDropsBlockedOrUnknownImages() {
        val visible = MarketContentPolicy.model(CivitaiModelItem(id = 1, versions = listOf(version)),
            CivitaiContentChannel.MATURE, true)!!
        assertEquals(listOf(1L, 2L, 4L, 8L, 16L), visible.versions.single().images.map { it.id })
    }

    @Test fun disabledMatureSettingAndConflictingMetadataDoNotGrantAdultScope() {
        val adult = CivitaiModelItem(id = 1, nsfw = true, nsfwLevel = 4)
        assertNull(MarketContentPolicy.model(adult, CivitaiContentChannel.MATURE, false))
        assertNull(MarketContentPolicy.model(adult.copy(sfwOnly = true), CivitaiContentChannel.MATURE, true))
        assertNull(MarketContentPolicy.model(adult.copy(minor = true), CivitaiContentChannel.MATURE, true))
    }

    @Test fun aMissingRatingDoesNotReuseAnUnclassifiedCover() {
        val visible = MarketContentPolicy.model(CivitaiModelItem(id = 1,
            previewUrl = "https://image.civitai.com/fixture/unknown.png",
            versions = listOf(CivitaiVersionItem(id = 2))), CivitaiContentChannel.STANDARD, true)!!
        assertNull(visible.previewUrl)
    }
}
