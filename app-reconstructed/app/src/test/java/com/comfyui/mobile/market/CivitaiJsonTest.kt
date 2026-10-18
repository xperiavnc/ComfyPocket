package com.comfyui.mobile.market

import org.junit.Assert.*
import org.junit.Test

class CivitaiJsonTest {
    @Test fun defaultSearchDoesNotSilentlyRestrictToLora() {
        val url = CivitaiJson.searchUrl(MarketQuery("fixture"), 1, null, CivitaiSettings())
        println("MARKET_DEFAULT_TYPES ${url.queryParameterValues("types")}")
        assertTrue(url.queryParameterValues("types").isEmpty())
    }

    @Test fun loraFamilyIncludesLoconAndDoraVariants() {
        val url = CivitaiJson.searchUrl(MarketQuery(type = "LORA"), 1, null, CivitaiSettings())
        assertEquals(listOf("LORA", "LoCon", "DoRA"), url.queryParameterValues("types"))
    }

    @Test fun normalBrowsingDoesNotForceEarlyAccessOnly() {
        val url = CivitaiJson.searchUrl(MarketQuery(), 1, null, CivitaiSettings())
        assertNull(url.queryParameter("earlyAccess"))
        assertEquals("20", url.queryParameter("limit"))
    }

    @Test fun checkpointAndUnetUseDifferentOfficialTypes() {
        fun types(type: String) = CivitaiJson.searchUrl(
            MarketQuery(type = type), 1, null, CivitaiSettings()
        ).queryParameterValues("types")
        assertEquals(listOf("Checkpoint"), types("CHECKPOINT"))
        assertEquals(listOf("UNet"), types("UNET"))
        assertEquals(listOf("LORA", "LoCon", "DoRA"), types("LORA"))
    }

    @Test fun ggufUsesFormatArrayAcrossCheckpointAndUnet() {
        val url = CivitaiJson.searchUrl(MarketQuery(type = "GGUF"), 1, null, CivitaiSettings())
        assertEquals(listOf("Checkpoint", "UNet"), url.queryParameterValues("types"))
        assertEquals(listOf("GGUF", "GGUF"), url.queryParameterValues("fileFormats"))
        assertNull(url.queryParameter("earlyAccess"))
    }

    @Test fun keywordSearchUsesOpaqueCursorAndNeverPageOrSort() {
        val url = CivitaiJson.searchUrl(MarketQuery("cat & dog"), 7, "a|b+c/=", CivitaiSettings())
        assertEquals("cat & dog", url.queryParameter("query"))
        assertEquals("a|b+c/=", url.queryParameter("cursor"))
        assertNull(url.queryParameter("page"))
        assertNull(url.queryParameter("sort"))
        assertEquals("false", url.queryParameter("nsfw"))
    }

    @Test fun firstSearchDoesNotSendPage() {
        assertNull(CivitaiJson.searchUrl(MarketQuery(), 1, null, CivitaiSettings()).queryParameter("page"))
    }

    @Test fun authorAndTimeAndSortUseOfficialParameters() {
        for (sort in MarketSort.entries) for (period in MarketPeriod.entries) {
            val url = CivitaiJson.searchUrl(MarketQuery(author = " @Some_Author ",
                sort = sort, period = period), 2, null, CivitaiSettings())
            assertEquals("Some_Author", url.queryParameter("username"))
            assertEquals(sort.apiValue, url.queryParameter("sort"))
            assertEquals(period.apiValue, url.queryParameter("period"))
            assertEquals("2", url.queryParameter("page"))
            assertNull(url.queryParameter("query"))
        }
    }

    @Test fun keywordAndAuthorCombineButDoNotClaimServerSortedFullTextSearch() {
        val url = CivitaiJson.searchUrl(MarketQuery("rain", author = "artist",
            sort = MarketSort.DOWNLOADS, period = MarketPeriod.WEEK), 4, "token|2", CivitaiSettings())
        assertEquals("rain", url.queryParameter("query"))
        assertEquals("artist", url.queryParameter("username"))
        assertEquals("Week", url.queryParameter("period"))
        assertEquals("token|2", url.queryParameter("cursor"))
        assertNull(url.queryParameter("page"))
        assertNull(url.queryParameter("sort"))
    }

    @Test fun modelNumbersAndOfficialLinksUseIdsNotTextIndex() {
        for (input in listOf("123", "#123", " https://civitai.com/models/123/sample?modelVersionId=456 ")) {
            val url = CivitaiJson.searchUrl(MarketQuery(input), 1, null, CivitaiSettings())
            assertEquals("123", url.queryParameter("ids"))
            assertEquals("false", url.queryParameter("nsfw"))
            assertNull(url.queryParameter("query"))
        }
        for (input in listOf("0", "-1", "9007199254740992", "999999999999999999999999999",
                "https://civitai.com.evil.invalid/models/123", "https://elsewhere.invalid/models/123",
                "http://civitai.com/models/123")) {
            assertNull(input, CivitaiJson.modelId(input))
        }
    }

    @Test fun publicationDatesAndPopularityAreMappedForLoadedResultOrdering() {
        val model = CivitaiJson.model("""{"id":10,"stats":{"downloadCount":20,"thumbsUpCount":30},
            "modelVersions":[{"id":11,"publishedAt":"2026-10-01T00:00:00Z"},
            {"id":12,"createdAt":"2026-10-17T00:00:00Z"}]}""")
        assertEquals(20L, model.downloadCount)
        assertEquals(30L, model.thumbsUpCount)
        assertEquals("2026-10-17T00:00:00Z", model.publishedAt)
        assertEquals("2026-10-01T00:00:00Z", model.versions.first().publishedAt)
    }

    @Test fun listingCanUsePageOnlyWhenThereIsNoCursor() {
        val listing = CivitaiJson.searchUrl(MarketQuery(type = "GGUF"), 2, null, CivitaiSettings())
        assertEquals("2", listing.queryParameter("page"))
        assertEquals("Checkpoint", listing.queryParameter("types"))
        assertNull(CivitaiJson.searchUrl(MarketQuery(), 2, "cursor", CivitaiSettings()).queryParameter("page"))
    }

    @Test fun filtersUseCanonicalApiValuesAndDoNotCarryTheKey() {
        val url = CivitaiJson.searchUrl(MarketQuery(baseModel = "Flux"), 1, null,
            CivitaiSettings(apiKey = "private-key", includeMatureContent = true))
        assertTrue("Flux.1 D" in url.queryParameterValues("baseModels"))
        assertEquals("true", url.queryParameter("nsfw"))
        assertFalse(url.toString().contains("private-key"))
        assertNull(url.queryParameter("token"))
    }

    @Test fun krea2FilterUsesCanonicalApiValue() {
        for ((type, apiType) in listOf("LORA" to "LORA", "CHECKPOINT" to "Checkpoint")) {
            val url = CivitaiJson.searchUrl(
                MarketQuery(type = type, baseModel = "Krea 2"), 1, null, CivitaiSettings()
            )
            assertEquals(listOf("Krea 2"), url.queryParameterValues("baseModels"))
            assertEquals(apiType, url.queryParameter("types"))
            assertNull(url.queryParameter("query"))
        }
    }

    @Test fun parsesOfficialShapeAndNullableFields() {
        val result = CivitaiJson.page("""
            {"items":[{"id":1,"name":"A","creator":null,"description":null,
             "tags":[null,"tag"],"modelVersions":null},{"id":1,"name":"duplicate"}],
             "metadata":{"nextCursor":"a|b","nextPage":null}}
        """.trimIndent(), 1)
        assertEquals(listOf(1L), result.items.map { it.id })
        assertEquals("", result.items.first().creator)
        assertEquals(listOf("tag"), result.items.first().tags)
        assertEquals("a|b", result.nextCursor)
    }

    @Test fun continuationUrlOnlySuppliesTrustedValues() {
        val result = CivitaiJson.page("""{"items":[],"metadata":{
            "nextPage":"https://civitai.com/api/v1/models?cursor=opaque%7Cnext"}}""", 1)
        assertEquals("opaque|next", result.nextCursor)
        assertNull(CivitaiJson.page("""{"items":[],"metadata":{
            "nextPage":"https://attacker.invalid/api/v1/models?cursor=evil"}}""", 1).nextCursor)
        assertNull(CivitaiJson.page("""{"items":[],"metadata":{
            "nextPage":"https://civitai.com.evil.invalid/api/v1/models?page=2"}}""", 1).nextPage)
    }

    @Test fun pageContinuationCannotMoveBackwards() {
        assertEquals(3, CivitaiJson.page("""{"items":[],"metadata":{
            "nextPage":"https://civitai.com/api/v1/models?page=3"}}""", 2).nextPage)
        assertNull(CivitaiJson.page("""{"items":[],"metadata":{
            "nextPage":"https://civitai.com/api/v1/models?page=1"}}""", 2).nextPage)
    }

    @Test fun mapsVersionsFilesTriggersAndImageMetadataWithoutLosingZeroSeed() {
        val version = CivitaiJson.version("""
            {"id":12,"name":"v2","baseModel":"SDXL 1.0","availability":"EarlyAccess",
             "canDownload":false,"trainedWords":["trigger","style"],
             "files":[
                {"id":20,"name":"vae.safetensors","type":"VAE","primary":true},
                {"id":21,"name":"model.gguf","type":"Model","sizeKB":1024,
                 "downloadUrl":"https://civitai.com/api/download/models/12?type=Model&token=secret",
                 "metadata":{"format":"GGUF","fp":"fp16"}}],
             "images":[{"id":5,"url":"https://image.civitai.com/x/width=200/image.jpeg",
                "meta":{"prompt":"positive","negativePrompt":"negative",
                "steps":20,"seed":0,"cfgScale":7,"sampler":"Euler"}}]}
        """.trimIndent())
        assertEquals(listOf("trigger", "style"), version.trainedWords)
        assertFalse(version.canDownload!!)
        assertEquals("EarlyAccess", version.availability)
        assertEquals(21L, version.preferredFile()?.id)
        assertEquals(1, version.componentFiles.size)
        assertEquals("fp16", version.preferredFile()?.precision)
        assertFalse(version.preferredFile()!!.downloadUrl.contains("secret"))
        assertEquals("positive", version.images.first().prompt)
        assertEquals("negative", version.images.first().negativePrompt)
        assertEquals("0", version.images.first().seed)
        assertTrue(version.images.first().url.contains("width=800"))
    }

    @Test fun missingImageMetadataIsNotInventedAndVideosAreSkipped() {
        val version = CivitaiJson.version("""{"id":1,"images":[null,
            {"type":"video","url":"https://image.civitai.com/movie.mp4"},
            {"url":"https://image.civitai.com/image.jpeg","meta":null}]}""")
        assertEquals(1, version.images.size)
        assertEquals("", version.images.first().prompt)
        assertEquals("", version.images.first().negativePrompt)
    }

    @Test fun examplesAreLabelledAsCommunityAndRespectTheLimit() {
        val examples = CivitaiJson.examples("""{"items":[{"id":1,
            "url":"https://image.civitai.com/example.png","meta":{"prompt":"example"}}]}""")
        assertTrue(examples.first().communityExample)
        assertEquals("example", examples.first().prompt)
    }

    @Test fun nonHttpsPreviewIsDiscarded() {
        assertNull(CivitaiJson.thumbnail("http://image.civitai.com/insecure.png", 800))
        assertNull(CivitaiJson.thumbnail("javascript:alert(1)", 800))
    }

    @Test(expected = IllegalArgumentException::class)
    fun bridgeEnvelopeIsNotTreatedAsAnOfficialResponse() {
        CivitaiJson.page("""{"code":0,"data":{"items":[]}}""", 1)
    }
}
