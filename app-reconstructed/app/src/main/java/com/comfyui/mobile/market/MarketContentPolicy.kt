package com.comfyui.mobile.market

import com.comfyui.mobile.model.CivitaiContentChannel
import com.comfyui.mobile.model.CivitaiModelItem
import com.comfyui.mobile.model.CivitaiVersionItem

/** Keep server classifications. Ordinary-only models never inherit an adult preview scope. */
internal object MarketContentPolicy {
    fun channel(model: CivitaiModelItem, requested: CivitaiContentChannel, allowMature: Boolean): CivitaiContentChannel {
        if (model.minor == true || model.sfwOnly == true) return CivitaiContentChannel.STANDARD
        return if (allowMature) requested else CivitaiContentChannel.STANDARD
    }

    fun model(model: CivitaiModelItem, requested: CivitaiContentChannel, allowMature: Boolean): CivitaiModelItem? {
        val channel = channel(model, requested, allowMature)
        val ordinary = channel == CivitaiContentChannel.STANDARD
        val adult = model.nsfw == true || (model.nsfwLevel ?: 0) >= 4
        // Conflicting adult + ordinary-only metadata does not grant wider access.
        if (ordinary && adult) return null
        val versions = model.versions.map { version(it, channel) }
        return model.copy(contentChannel = channel, versions = versions,
            previewUrl = CivitaiJson.thumbnail(versions.firstNotNullOfOrNull { it.previewUrl }, 450))
    }

    fun version(value: CivitaiVersionItem, channel: CivitaiContentChannel): CivitaiVersionItem {
        val images = value.images.filter { image ->
            // PG=1, PG13=2, R=4, X=8, XXX=16. Unknown classifications do not become previews.
            val level = image.nsfwLevel
            if (channel == CivitaiContentChannel.STANDARD) level == 1
            else level in setOf(1, 2, 4, 8, 16) && !(image.minor == true && level != 1 && level != 2)
        }
        return value.copy(images = images, previewUrl = images.firstOrNull()?.url)
    }
}
