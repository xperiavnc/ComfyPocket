package com.comfyui.mobile.danbooru

import org.junit.Assert.*
import org.junit.Test

class DanbooruTagAndJsonTest {
    private val post = DanbooruPost(1, mapOf(
        TagGroup.ARTIST to listOf("artist_one"), TagGroup.COPYRIGHT to listOf("series_(title)"),
        TagGroup.CHARACTER to listOf("character_one", "blue_eyes"),
        TagGroup.GENERAL to listOf("blue_eyes", "open_sky", "cloud"), TagGroup.META to listOf("highres")))
    @Test fun allGroupsUseFixedOrderWithStableDeduplication() {
        val copied = TagCopyFormatter.format(post, TagCopyOptions(TagCopyMode.RAW))
        assertEquals("artist_one series_(title) character_one blue_eyes open_sky cloud highres", copied.text)
        assertEquals(7, copied.count)
    }
    @Test fun crossGroupSelectionPreservesGroupAndOriginalOrderRatherThanClickOrder() {
        val result = TagCopyFormatter.format(post, TagCopyOptions(), selected = linkedSetOf("cloud", "artist_one", "blue_eyes"))
        assertEquals("artist one, blue eyes, cloud", result.text)
        assertEquals(3, result.count)
    }
    @Test fun promptQuickCopyDefaultsAreGeneralAndCharacterNotArtistOrMetadata() {
        val result = TagCopyFormatter.format(post, TagCopyOptions(), setOf(TagGroup.CHARACTER, TagGroup.GENERAL))
        assertEquals("character one, blue eyes, open sky, cloud", result.text)
    }
    @Test fun parenthesesAndUnderscoresAreIndependentAndRawNeverEscapes() {
        val groups = setOf(TagGroup.COPYRIGHT)
        assertEquals("series (title)", TagCopyFormatter.format(post, TagCopyOptions(), groups).text)
        assertEquals("series_\\(title\\)", TagCopyFormatter.format(post, TagCopyOptions(underscoresToSpaces = false, escapeParentheses = true), groups).text)
        assertEquals("series_(title)", TagCopyFormatter.format(post, TagCopyOptions(TagCopyMode.RAW, true, true), groups).text)
    }
    @Test fun emptySelectionHasNoTextOrMisleadingCount() {
        assertEquals(CopiedTags("", 0), TagCopyFormatter.format(post, TagCopyOptions(), selected = emptySet()))
    }
    @Test fun nullableMissingAndUnknownJsonFieldsRetainUsableTags() {
        val parsed = DanbooruJson.detail("""{"id":1,"file_url":null,"preview_file_url":null,"unknown":{"x":1},"tag_string_general":"blue_sky cloud"}""")
        assertNull(parsed.fileUrl); assertNull(parsed.previewUrl)
        assertEquals("blue_sky cloud", TagCopyFormatter.format(parsed, TagCopyOptions(TagCopyMode.RAW)).text)
        assertFalse(parsed.isVideo)
        assertTrue(DanbooruJson.posts("[]").isEmpty())
    }
    @Test fun videoAndMissingOriginalStillHaveReadableTagsAndNoInventedUrl() {
        val parsed = DanbooruJson.detail("""{"id":1,"file_ext":"webm","tag_string_character":"character"}""")
        assertTrue(parsed.isVideo); assertNull(parsed.fileUrl)
        assertEquals("character", TagCopyFormatter.format(parsed, TagCopyOptions()).text)
    }
    @Test fun invalidListOrMissingIdIsAnErrorNotAnEmptyPage() {
        for (json in listOf("{}", "null", "[{}]", "[", """[{"id":null}]""")) {
            try { DanbooruJson.posts(json); fail(json) } catch (_: DanbooruException) { }
        }
    }
    @Test fun searchDoesNotSilentlyAppendConflictingRatingsOrChangeOrder() {
        val query = DanbooruQuery.resolve("rating:q blue_sky order:score", ContentFilter.GENERAL)
        assertEquals("rating:q blue_sky order:score", query.tags)
        assertEquals(ContentFilter.QUESTIONABLE, query.filter)
        assertFalse(query.cursorPaging)
        val custom = DanbooruQuery.resolve("-rating:e rating:s,g", ContentFilter.GENERAL)
        assertEquals(ContentFilter.CUSTOM, custom.filter)
        assertFalse(custom.tags.contains("rating:g"))
    }
    @Test fun defaultGeneralAndExplicitLatestUseIdCursorsWhileOtherOrdersUseNumbers() {
        assertEquals("rating:g", DanbooruQuery.resolve("", ContentFilter.GENERAL).tags)
        assertTrue(DanbooruQuery.resolve("order:id_desc", ContentFilter.GENERAL).cursorPaging)
        assertFalse(DanbooruQuery.resolve("order:random", ContentFilter.GENERAL).cursorPaging)
        assertEquals("blue_sky", DanbooruQuery.resolve("blue_sky", ContentFilter.ALL).tags)
    }
}
