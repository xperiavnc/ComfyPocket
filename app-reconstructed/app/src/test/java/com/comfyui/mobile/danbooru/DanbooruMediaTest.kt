package com.comfyui.mobile.danbooru

import org.junit.Assert.*
import org.junit.Test

class DanbooruMediaTest {
    private val video = DanbooruPost(1, emptyMap(), extension = "mp4",
        sampleUrl = "https://cdn.invalid/sample.mp4",
        previewUrl = "https://cdn.invalid/poster.jpg",
        fileUrl = "https://cdn.invalid/original.mp4")

    @Test fun videoHasSeparatePosterAndPlaybackCandidates() {
        assertEquals(listOf(video.previewUrl), video.previewCandidates().map { it.url })
        assertEquals(listOf(video.sampleUrl, video.fileUrl), video.playbackCandidates().map { it.url })
        assertTrue(video.previewCandidates(originalOnly = true).isEmpty())
    }
    @Test fun imagesTrySampleThenThumbnailThenOriginalAndKeepTheirCacheRoles() {
        val post = video.copy(extension = "png", sampleUrl = "https://cdn.invalid/sample.png",
            previewUrl = "https://cdn.invalid/thumbnail.png", fileUrl = "https://cdn.invalid/original.png")
        assertEquals(listOf("sample", "thumbnail", "original"), post.previewCandidates().map { it.representation })
        assertEquals(listOf(post.fileUrl), post.previewCandidates(originalOnly = true).map { it.url })
        assertTrue(post.playbackCandidates().isEmpty())
    }
    @Test fun equalUrlsAreTriedOnlyOnceIncludingSignedUrls() {
        val url = "https://cdn.invalid/a.MP4?signature=fixture.jpg"
        val post = video.copy(sampleUrl = url, fileUrl = url, previewUrl = null)
        assertTrue(post.previewCandidates().isEmpty())
        assertEquals(listOf(url), post.playbackCandidates().map { it.url })
        assertEquals("mp4", post.playbackCandidates().single().extension)
    }
    @Test fun availableAssetVariantsProvideCoversAndPlayableTranscodes() {
        val post = DanbooruJson.detail("""{
          "id":2,"file_ext":"webm","large_file_url":"https://cdn.invalid/a.webm",
          "tag_string_general":"blue_sky","media_asset":{"variants":[
            {"type":"180x180","url":"https://cdn.invalid/small.jpg","file_ext":"jpg","width":180},
            {"type":"720x720","url":"https://cdn.invalid/large.jpg","file_ext":"jpg","width":720},
            {"type":"sample","url":"https://cdn.invalid/transcode.mp4","file_ext":"mp4","width":720}
          ]}}""")
        assertEquals(listOf("https://cdn.invalid/large.jpg", "https://cdn.invalid/small.jpg"),
            post.previewCandidates().map { it.url })
        assertEquals(listOf("https://cdn.invalid/a.webm", "https://cdn.invalid/transcode.mp4"),
            post.playbackCandidates().map { it.url })
        assertEquals(listOf("blue_sky"), post.tags[TagGroup.GENERAL])
    }
    @Test fun originalVideoWithoutASuffixStillHasItsDeclaredMimeType() {
        val source = video.copy(sampleUrl = null, fileUrl = "https://cdn.invalid/media?id=123").playbackCandidates().single()
        assertEquals("video/mp4", source.videoMimeType())
        assertEquals("video/webm", video.copy(extension = "webm", sampleUrl = null,
            fileUrl = "https://cdn.invalid/media?id=2").playbackCandidates().single().videoMimeType())
    }
    @Test fun archivedAnimationAndFlashAreNotPassedToTheVideoPlayerOrImageDecoder() {
        for (ext in listOf("zip", "swf")) {
            val post = video.copy(extension = ext, sampleUrl = "https://cdn.invalid/a.$ext",
                fileUrl = "https://cdn.invalid/a.$ext")
            assertFalse(post.isVideo)
            assertFalse(post.isImage)
            assertEquals(listOf(video.previewUrl), post.previewCandidates().map { it.url })
            assertTrue(post.playbackCandidates().isEmpty())
        }
    }
    @Test fun absentOrMalformedAssetsKeepThePostsTagsUsable() {
        for (asset in listOf("null", "[]", """{"variants":null}""", """{"variants":[null,42,{}]}""")) {
            val post = DanbooruJson.detail("""{"id":3,"file_ext":"mp4",
                "tag_string_general":"cloud","media_asset":$asset}""")
            assertTrue(post.previewCandidates().isEmpty())
            assertTrue(post.playbackCandidates().isEmpty())
            assertEquals(listOf("cloud"), post.tags[TagGroup.GENERAL])
        }
    }
    @Test fun variantParsingUsesTheSameHttpsAddressRulesAsTheOtherMediaFields() {
        val post = DanbooruJson.detail("""{"id":4,"media_asset":{"variants":[
          {"url":"http://cdn.invalid/a.jpg","file_ext":"jpg"},
          {"url":"https://user:password@cdn.invalid/a.jpg","file_ext":"jpg"},
          {"url":"file:///fixture.jpg","file_ext":"jpg"},
          {"url":"https://cdn.invalid/valid.jpg","file_ext":"jpg"}]}}""")
        assertEquals(listOf("https://cdn.invalid/valid.jpg"), post.variants.map { it.url })
    }
}
