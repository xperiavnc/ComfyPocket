package com.comfyui.mobile.danbooru

import java.net.URI
import java.util.Locale

internal val DANBOORU_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "avif")
internal val DANBOORU_VIDEO_EXTENSIONS = setOf("webm", "mp4", "mkv", "mov")

data class DanbooruMediaVariant(val url: String, val extension: String, val type: String, val width: Int? = null)
data class DanbooruMediaSource(val url: String, val representation: String, val extension: String = "")

private fun String.mediaExtension(): String = runCatching {
    URI(this).path.orEmpty().substringAfterLast('.', "").lowercase(Locale.ROOT)
}.getOrDefault("")

/**
 * A large_file_url can be an MP4, original ZIP or SWF, not an image sample.
 * Both list and detail use the same ordered, deduplicated image candidates/cache keys.
 * No video is downloaded just to paint a gallery tile.
 */
fun DanbooruPost.previewCandidates(originalOnly: Boolean = false): List<DanbooruMediaSource> {
    fun source(url: String?, representation: String, declared: String = ""): DanbooruMediaSource? {
        if (url.isNullOrBlank()) return null
        val ext = declared.lowercase(Locale.ROOT).ifBlank { url.mediaExtension() }
        if (ext in DANBOORU_VIDEO_EXTENSIONS || ext in setOf("zip", "swf")) return null
        if (!isImage && representation != "thumbnail" && ext !in DANBOORU_IMAGE_EXTENSIONS) return null
        return DanbooruMediaSource(url, representation, ext)
    }
    if (originalOnly) return if (isImage) listOfNotNull(source(fileUrl, "original", extension)) else emptyList()
    val images = variants.filter { it.extension.lowercase(Locale.ROOT) in DANBOORU_IMAGE_EXTENSIONS }
        .sortedByDescending { it.width ?: 0 }
        .mapNotNull { source(it.url, if (it.type == "original") "original" else "variant:${it.type}", it.extension) }
    return (if (isImage) {
        listOfNotNull(source(sampleUrl, "sample")) + images +
            listOfNotNull(source(previewUrl, "thumbnail"), source(fileUrl, "original", extension))
    } else {
        // Prefer a useful static variant; the API thumbnail remains a cheap fallback.
        images + listOfNotNull(source(previewUrl, "thumbnail"), source(sampleUrl, "sample"))
    }).distinctBy { it.url }
}

/** Poster URLs never enter the video player; archived animations/Flash are not mislabeled as MP4. */
fun DanbooruPost.playbackCandidates(): List<DanbooruMediaSource> {
    if (!isVideo) return emptyList()
    fun source(url: String?, representation: String, declared: String = ""): DanbooruMediaSource? {
        if (url.isNullOrBlank()) return null
        val ext = declared.lowercase(Locale.ROOT).ifBlank { url.mediaExtension() }
        if (ext !in DANBOORU_VIDEO_EXTENSIONS) return null
        return DanbooruMediaSource(url, representation, ext)
    }
    return (listOfNotNull(source(sampleUrl, "video-sample")) +
        variants.filter { it.extension.lowercase(Locale.ROOT) in DANBOORU_VIDEO_EXTENSIONS }
            .sortedBy { if (it.extension.equals("mp4", true)) 0 else 1 }
            .mapNotNull { source(it.url, "video:${it.type}", it.extension) } +
        listOfNotNull(source(fileUrl, "video-original", extension))).distinctBy { it.url }
}

/** Download the original when available, not the lower-resolution playback sample. */
fun DanbooruPost.videoDownloadSource(): DanbooruMediaSource? {
    if (!isVideo) return null
    if (!fileUrl.isNullOrBlank()) return DanbooruMediaSource(fileUrl, "video-original", extension.lowercase(Locale.ROOT))
    return playbackCandidates().firstOrNull { it.representation == "video:original" }
        ?: playbackCandidates().firstOrNull()
}

internal fun DanbooruMediaSource.videoMimeType(): String = when (extension) {
    "webm" -> "video/webm"
    "mov" -> "video/quicktime"
    "mkv" -> "video/x-matroska"
    else -> "video/mp4"
}
