package com.comfyui.mobile.agent

import com.comfyui.mobile.taglib.LocalTagEntry
import com.comfyui.mobile.model.AgentChatMessage
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class AgentPromptAliasesTest {
    @Test fun commaContainingVisualSentenceKeepsItsChineseMeaningAsOneUnit() {
        val sentence = "On the left, Mira holds a sign."
        val aliases = listOf(AgentPromptAlias(sentence, "左侧的米拉举着标牌。"),
            AgentPromptAlias("rain", "雨"))
        assertEquals(aliases, AgentPromptAliases.align("$sentence, rain", aliases))
        assertNull(AgentPromptAliases.align("Mira holds a sign.", aliases))
    }

    @Test fun aliasesFollowFinalOrderAndDiscardRemovedOrUnrelatedTerms() {
        val aliases = listOf(AgentPromptAlias("rain", "雨"), AgentPromptAlias("old_coat", "旧外套"),
            AgentPromptAlias("blue_hair", "蓝发"), AgentPromptAlias("blue_hair", "重复"))
        assertEquals(listOf(AgentPromptAlias("(blue hair:1.2)", "蓝发"), AgentPromptAlias("rain", "雨")),
            AgentPromptAliases.align("(blue hair:1.2), rain", aliases))
        assertNull(AgentPromptAliases.align("", aliases))
        assertNull(AgentPromptAliases.align(null, aliases))
        assertNull(AgentPromptAliases.align("untranslated_trigger", aliases))
    }

    @Test fun optionalDictionarySuppliesOnlyExactSingleTagTranslations() {
        val entries = listOf(
            LocalTagEntry("shirt", "衬衫", "", "", "tag", "shirt", "", "", false),
            LocalTagEntry("blue_hair", "蓝色头发", "", "", "tag", "blue_hair", "", "", false),
            LocalTagEntry("character", "角色中文名", "", "", "character", "token, coat", "", "", true))
        assertEquals(listOf(AgentPromptAlias("shirt", "衬衫"), AgentPromptAlias("blue hair", "蓝色头发")),
            AgentPromptAliases.align("shirt, blue hair, token, coat",
                listOf(AgentPromptAlias("blue hair", "头发")), entries))
    }

    @Test fun syntaxAndQuotedTextAreNeverChangedByAliases() {
        val prompt = """<lora:known:0.8>, name \(series\), sign reading "a,b"."""
        val aliases = listOf(AgentPromptAlias("<lora:known:0.8>", "指定模型"),
            AgentPromptAlias("""name \(series\)""", "角色标签"),
            AgentPromptAlias("""sign reading "a,b".""", "标牌写着 a,b"))
        assertEquals(aliases, AgentPromptAliases.align(prompt, aliases))
        assertTrue(AgentPromptAliases.text(aliases).contains("<lora:known:0.8> → 指定模型"))
    }

    @Test fun malformedMetadataDoesNotInvalidatePromptsAndOldJsonRemainsReadable() {
        assertNull(AgentPromptAliases.parse(JsonParser.parseString("""{"tag":"cat"}""")))
        assertEquals(listOf(AgentPromptAlias("cat", "猫")), AgentPromptAliases.parse(
            JsonParser.parseString("""[null,42,{},{"tag":"cat","zh":"猫"},{"tag":{},"zh":"无"}]""")))
        val gson = Gson()
        assertNull(gson.fromJson("""{"positive":"cat","negative":""}""", AgentPromptOutput::class.java).positiveAliases)
        assertNull(gson.fromJson("""{"id":"a","role":"assistant","content":"好"}""", AgentChatMessage::class.java).negativeAliases)
        val output = AgentPromptOutput("cat", "blurry", positiveAliases = listOf(AgentPromptAlias("cat", "猫")),
            negativeAliases = listOf(AgentPromptAlias("blurry", "模糊")))
        assertEquals(output, gson.fromJson(gson.toJson(output), AgentPromptOutput::class.java))
    }
}
