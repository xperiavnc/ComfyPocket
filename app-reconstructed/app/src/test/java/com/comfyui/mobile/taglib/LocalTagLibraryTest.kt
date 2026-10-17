package com.comfyui.mobile.taglib

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalTagLibraryTest {
    private lateinit var library: LocalTagLibrary
    @Before fun setup() { library = LocalTagLibrary(RuntimeEnvironment.getApplication()) }

    @Test fun packagedCatalogueContainsThePinnedCounts() {
        assertEquals(13358 to 34122, library.counts)
        assertEquals("2186c68862ea12a6a191f44e23bfc1699f8149fa", library.revision)
    }
    @Test fun chineseEnglishAndUnderscoreAllFindTheExactConcept() = runBlocking {
        for (query in listOf("蓝发", "blue hair", "blue_hair", "BLUE HAIR")) {
            val result = library.search(query).first()
            assertEquals("blue hair", result.name)
            assertTrue(result.exact)
            assertEquals(LocalTagLibrary.normalize(query), result.matchedTerm)
        }
    }
    @Test fun characterIdentityIsSeparateFromGenericVocabulary() = runBlocking {
        val character = library.search("初音未来", "character").first()
        assertEquals("hatsune_miku", character.name)
        assertEquals("character", character.kind)
        assertTrue(character.trigger.isNotBlank())
        // Cosplay/event concepts may mention a character, but identity rows stay separate.
        val generic = library.search("初音未来", "tag")
        assertTrue(generic.all { it.kind == "tag" })
        assertFalse(generic.any { it.name == "hatsune_miku" })
    }
    @Test fun negativeCategoryIsAvailableForTheModelToChoose() = runBlocking {
        val result = library.search("blurry").first { it.name == "blurry" }
        assertEquals("negative", result.category)
        assertTrue(library.forRequest("蓝发，不要模糊，no bad anatomy").entries.any { it.category == "negative" })
    }
    @Test fun requestRetrievesMixedLanguageCandidatesAndPreservesUnknowns() = runBlocking {
        val result = library.forRequest("画蓝发人物，blue_hair, blurry, __my_custom_trigger__, <lora:fixture:0.8>")
        assertTrue(result.entries.any { it.name == "blue hair" })
        assertTrue(result.entries.any { it.name == "blurry" })
        assertTrue(result.entries.size <= 18)
        assertTrue(library.context(result).contains("未命中不是错词"))
        assertTrue(library.context(result).contains("negative_prompt"))
    }
    @Test fun unknownTermsDoNotBecomeInvalidTags() = runBlocking {
        val result = library.forRequest("zzzxxyynotinthelibrary")
        assertTrue(result.entries.isEmpty())
        assertTrue(result.summary.contains("保留自定义词"))
    }
    @Test fun wildcardAndSqlPunctuationAreLiteralSearchInput() = runBlocking {
        assertTrue(library.search("%").isEmpty())
        assertTrue(library.search("' OR 1=1 --").isEmpty())
        assertEquals(4, library.search("hair", limit = 4).size)
        assertTrue(library.search(" ").isEmpty())
    }
    @Test fun longCandidateContextRemainsCompleteJson() {
        val entries = (1..40).map {
            LocalTagEntry("tag$it", "译".repeat(500), "hair", "section", "character",
                "identity".repeat(100), "detail".repeat(1000), "matched", true)
        }
        val context = library.context(LocalTagReport(entries))
        val json = context.substringAfter("\n").substringBefore("\n")
        assertTrue(json.length <= 6500)
        assertTrue(JsonParser.parseString(json).isJsonArray)
    }
    @Test fun chineseSentenceDoesNotCrowdOutEnglishQueries() {
        val terms = LocalTagLibrary.queryTerms("蓝发人物".repeat(200) + ", blurry, blue_hair")
        assertTrue(terms.contains("blurry"))
        assertTrue(terms.contains("blue hair"))
        assertTrue(terms.size <= 400)
        assertFalse(LocalTagLibrary.queryTerms("<lora:private_trigger:1.0>").contains("private trigger"))
    }
}
