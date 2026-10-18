package com.comfyui.mobile.market

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class CivitaiMarketContentRegressionTest {
    @Test fun redModelLinksResolveToTheSameOfficialModelId() {
        val actual = CivitaiJson.modelId("https://civitai.red/models/2779381/sample")
        println("MARKET_RED_MODEL_ID=$actual")
        assertEquals(2779381L, actual)
    }

    @Test fun modelContentFlagsSurviveMappingInsteadOfBeingDiscarded() {
        val model = CivitaiJson.model("""{"id":2779381,"nsfw":false,"nsfwLevel":1,
            "minor":true,"sfwOnly":true}""")
        val json = JsonParser.parseString(Gson().toJson(model)).asJsonObject
        println("MARKET_CONTENT_FLAGS minor=${json.get("minor")} sfwOnly=${json.get("sfwOnly")}")
        assertEquals(true, json.get("minor")?.asBoolean)
        assertEquals(true, json.get("sfwOnly")?.asBoolean)
        assertEquals(false, json.get("nsfw")?.asBoolean)
        assertEquals(1, json.get("nsfwLevel")?.asInt)
    }
}
