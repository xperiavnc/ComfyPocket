package com.comfyui.mobile.danbooru

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class DanbooruModelsTest {
    private val json = """{"id":42,"rating":"g","file_ext":"jpg","image_width":100,"image_height":80,
        "file_url":"https://cdn.donmai.us/original/a.jpg","large_file_url":"https://cdn.donmai.us/sample/a.jpg",
        "preview_file_url":"https://cdn.donmai.us/180x180/a.jpg",
        "tag_string_artist":"artist_a","tag_string_character":"char_a char_b",
        "tag_string_copyright":"series_a","tag_string_general":"blue_sky cloud_blue",
        "tag_string_meta":"highres"}"""
    @Test fun parsesAllFiveGroupsAndOptionalMedia() {
        val post = DanbooruJson.post(JsonParser.parseString(json).asJsonObject)
        assertEquals(42L, post.id)
        assertEquals(listOf("char_a", "char_b"), post.tags[TagGroup.CHARACTER])
        assertEquals("https://cdn.donmai.us/original/a.jpg", post.fileUrl)
    }
    @Test fun explicitRatingOverridesDefaultAndOrderSelectsNumericPages() {
        val q = DanbooruQuery.resolve("blue rating:s order:score", ContentFilter.GENERAL)
        assertEquals(ContentFilter.SENSITIVE, q.filter)
        assertFalse(q.cursorPaging)
        assertTrue(q.tags.contains("rating:s"))
        assertTrue(DanbooruQuery.withFilter("blue rating:e", ContentFilter.GENERAL).contains("rating:g"))
    }
    @Test fun copiesPromptAndRawWithoutReorderingGroups() {
        val post = DanbooruJson.post(JsonParser.parseString(json).asJsonObject)
        val prompt = TagCopyFormatter.format(post, TagCopyOptions(), setOf(TagGroup.CHARACTER, TagGroup.GENERAL))
        assertEquals("char a, char b, blue sky, cloud blue", prompt.text)
        val raw = TagCopyFormatter.format(post, TagCopyOptions(TagCopyMode.RAW), setOf(TagGroup.GENERAL))
        assertEquals("blue_sky cloud_blue", raw.text)
    }
    @Test fun returnsOneBackLayerAtATime() {
        assertEquals(DanbooruBack.SETTINGS, danbooruBack(true, true, true))
        assertEquals(DanbooruBack.SELECTION, danbooruBack(false, true, true))
        assertEquals(DanbooruBack.DETAIL, danbooruBack(false, false, true))
        assertEquals(DanbooruBack.ROOT, danbooruBack(false, false, false))
    }
    @Test fun retryAfterAndApiAuthRulesAreBoundedAndPrecise() {
        assertEquals(3000L, DanbooruHttp.retryDelay("3", 0))
        assertEquals("Basic dXNlcjprZXk=", okhttp3.Credentials.basic("user", "key"))
        val settings = DanbooruSettings("user", "key")
        val auth = DanbooruAuthInterceptor(settings)
        val api = okhttp3.Request.Builder().url("https://danbooru.donmai.us/posts.json").build()
        val cdn = okhttp3.Request.Builder().url("https://cdn.donmai.us/x.jpg").build()
        assertNotNull(auth.authenticated(api).header("Authorization"))
        assertNull(auth.authenticated(cdn).header("Authorization"))
    }
}
