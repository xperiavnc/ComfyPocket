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
        assertEquals(141007 to 101952, library.counts)
        assertEquals("full-toolbox-v3-fa8003a0311f6b09", library.revision)
        assertEquals(240851, library.translatedCount)
        assertEquals(2108, library.untranslatedCount)
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
    @Test fun characterSpecificTermsAndTheirAttachmentsReachTheAgentContext() = runBlocking {
        val character = library.search("ayanami_(azur_lane)", "character").first { it.name == "ayanami_(azur_lane)" }
        val data = JsonParser.parseString(character.detail).asJsonObject
        assertTrue(data["optionalSpecificAppearance"].asJsonArray.size() > 0)
        val context = library.context(LocalTagReport(entries = listOf(character)))
        val candidate = JsonParser.parseString(context.substringAfter("\n").substringBefore("\n")).asJsonArray[0].asJsonObject
        assertTrue(candidate["optionalSpecificAppearance"].asJsonArray.size() > 0)
        assertTrue(library.search("a certain high school gym uniform").any { it.exact })
    }
    @Test fun missingChineseIsExplicitDataAndDoesNotInvalidateTheSourceWord() {
        val entry = LocalTagEntry("source_term", "", "other", "", "tag", "", "", "source term", true)
        val context = library.context(LocalTagReport(entries = listOf(entry)))
        assertTrue(context.contains("\"translationStatus\":\"missing\""))
        assertTrue(context.contains("不是无效词"))
        assertTrue(context.contains("\"tag\":\"source_term\""))
    }
    @Test fun negativeCategoryIsAvailableForTheModelToChoose() = runBlocking {
        val result = library.search("blurry").first { it.name == "blurry" }
        assertEquals("negative", result.category)
        assertTrue(library.forRequest("蓝发，不要模糊，no bad anatomy").entries.any { it.category == "negative" })
    }
    @Test fun newDanbooruTranslationsAreSearchableInBothLanguagesAndChineseSentences() = runBlocking {
        for (query in listOf("机械魔法少女", "mechanical_magical_girl", "mechanical magical girl")) {
            val result = library.search(query).first { it.name == "mechanical_magical_girl" }
            assertEquals("机械魔法少女", result.translation)
            assertTrue(result.exact)
        }
        val report = library.forRequest("画一位机械魔法少女，留着超长辫子")
        assertTrue(report.entries.any { it.name == "mechanical_magical_girl" && it.matchedTerm == "机械魔法少女" })
        assertTrue(report.entries.any { it.name == "very_long_braid" })
        assertTrue(library.context(report).contains("中文词义"))
    }
    @Test fun chineseRoleNameMatchesWithoutItsParenthesizedSeries() = runBlocking {
        val report = library.forRequest("画初音未来，蓝发，撑伞")
        assertTrue(report.entries.any { it.name == "hatsune_miku" && it.kind == "character" })
        assertTrue(report.entries.all { it.translation.any { ch -> ch in '\u3400'..'\u9fff' } })
        val newer = library.search("阿格莱雅", "character").first { it.name == "aglaea_(honkai:_star_rail)" }
        assertEquals("aglaea_(honkai:_star_rail)", newer.trigger)
        assertTrue(newer.exact)
        assertTrue(newer.detail.isEmpty()) // Identity-only rows must not fabricate appearance tags.
        assertTrue(library.forRequest("画阿格莱雅，站在花园中").entries.any { it.name == newer.name })
    }
    @Test fun singleChineseWeatherConceptsAreUsableWithoutMatchingArtistNames() = runBlocking {
        val report = library.forRequest("雨")
        assertTrue(report.entries.any { LocalTagLibrary.normalize(it.name) == "rain" })
        assertTrue(report.entries.all { it.kind == "tag" && it.category !in setOf("artist", "copyright") })
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
        // The enlarged catalogue contains literal percent-sign tags. They may match, but
        // "%" must not act as a SQL wildcard and return unrelated vocabulary.
        assertTrue(library.search("%").all { "%" in it.matchedTerm })
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
