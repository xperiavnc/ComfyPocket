package com.comfyui.mobile.market

import org.junit.Assert.*
import org.junit.Test

class CivitaiJsonTest {
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
