package com.comfyui.mobile.agent

import com.comfyui.mobile.model.CharacterLore
import com.comfyui.mobile.model.CharacterOutfit
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AgentSelfSaveToolsTest {
    private fun snapshot() = AgentWorkflowSnapshot(mapOf(
        "3" to mutableMapOf<String, Any>("class_type" to "KSampler", "inputs" to mutableMapOf<String, Any>("steps" to 20)),
        "7" to mutableMapOf<String, Any>("class_type" to "PrimitiveString", "inputs" to mutableMapOf<String, Any>("value" to "artist_a"))
    ), listOf(
        AgentFieldSpec("3", "steps", "KSampler", AgentFieldKind.INTEGER, min = 1.0, max = 60.0),
        AgentFieldSpec("7", "value", "PrimitiveString", AgentFieldKind.TEXT)))

    private fun json(text: String) = JsonParser.parseString(text).asJsonObject

    @Test fun styleFieldIsLabelledAndSubmitCanAskToSaveDefaults() = runBlocking {
        val box = AgentDrawToolbox(snapshot(), styleField = "7:value", favoriteArtists = listOf("artist_b"))
        val fields = (box.call(AgentDrawToolbox.INSPECT, json("{}")).data as Map<*, *>)["fields"] as List<*>
        val style = fields.map { it as Map<*, *> }.single { it["field"] == "7:value" }
        assertEquals("画风/画师", style["role"])
        assertEquals(listOf("artist_b"), style["favorite_artists"])
        assertTrue(box.call(AgentDrawToolbox.PATCH, json("""{"changes":[{"field":"7:value","value":"artist_b"}]}""")).ok)
        box.call(AgentDrawToolbox.SET_PROMPT, json("""{"prompt":"1girl"}"""))
        assertFalse(box.saveDefaults)
        assertTrue(box.call(AgentDrawToolbox.SUBMIT, json("""{"reply":"好","save_defaults":true}""")).ok)
        assertTrue(box.saveDefaults)
        assertEquals("artist_b", box.changes.single().value)
    }

    @Test fun saveCharacterToolIsOnlyOfferedWithAStore() {
        assertFalse(AgentDrawToolbox(snapshot()).definitions.any { it.function.name == AgentDrawToolbox.SAVE_CHARACTER })
        assertTrue(AgentDrawToolbox(snapshot(), saveCharacter = { null }).definitions
            .any { it.function.name == AgentDrawToolbox.SAVE_CHARACTER })
    }

    @Test fun newCharacterIsCreatedAndThenReadableInTheSameTurn() = runBlocking {
        val saved = mutableListOf<CharacterLore>()
        val box = AgentDrawToolbox(snapshot(), saveCharacter = { saved += it; null })
        val result = box.call(AgentDrawToolbox.SAVE_CHARACTER, json("""{"name":"铃兰","appearance_tags":"1girl, fox ears, green eyes",
            "aliases":["小铃"],"outfits":[{"name":"春颂","tags":"white shirt, blue skirt"}],"default_outfit":"春颂"}"""))
        assertTrue(result.error, result.ok)
        val lore = saved.single()
        assertEquals("铃兰", lore.name)
        assertEquals(listOf("小铃"), lore.aliases)
        assertEquals(lore.outfits!!.single().id, lore.defaultOutfitId)
        assertTrue(box.call(AgentDrawToolbox.PROFILE, json("""{"name":"小铃"}""")).ok)
    }

    @Test fun existingCharacterKeepsIdentityAndMergesOutfits() = runBlocking {
        val winter = CharacterOutfit("w", "冬装", "coat")
        val old = CharacterLore("k", "小黑", "cat ears", enabled = false, aliases = listOf("kuro"),
            fixedTriggerWords = "kuro", outfits = listOf(winter), defaultOutfitId = "w")
        val saved = mutableListOf<CharacterLore>()
        val box = AgentDrawToolbox(snapshot(), characters = listOf(old), saveCharacter = { saved += it; null })
        assertTrue(box.call(AgentDrawToolbox.SAVE_CHARACTER, json("""{"name":"kuro","appearance_tags":"cat ears, gradient hair",
            "outfits":[{"name":"冬装","tags":"long coat"},{"name":"校服","tags":"school uniform"}]}""")).ok)
        val lore = saved.single()
        assertEquals("k", lore.id)
        assertEquals("小黑", lore.name)
        assertEquals(false, lore.enabled)
        assertEquals("kuro", lore.fixedTriggerWords)
        assertEquals("cat ears, gradient hair", lore.triggerWords)
        assertEquals(listOf("long coat", "school uniform"), lore.outfits!!.map { it.triggerWords })
        assertEquals("w", lore.outfits!!.first().id)
        assertEquals("w", lore.defaultOutfitId)
    }

    @Test fun storeErrorsAndBadDefaultsGoBackToTheModel() = runBlocking {
        val box = AgentDrawToolbox(snapshot(), saveCharacter = { "角色名已存在，请使用不同名称" })
        assertEquals("角色名已存在，请使用不同名称", box.call(AgentDrawToolbox.SAVE_CHARACTER,
            json("""{"name":"a","appearance_tags":"x"}""")).error)
        val bad = box.call(AgentDrawToolbox.SAVE_CHARACTER, json("""{"name":"a","appearance_tags":"x","default_outfit":"不存在"}"""))
        assertFalse(bad.ok)
        assertTrue(bad.error!!.contains("default_outfit"))
    }

    @Test fun executorSavesDefaultsAfterApplyingOnlyWhenAsked() = runBlocking {
        val events = mutableListOf<String>()
        val port = object : AgentWorkflowPort {
            override suspend fun prepare() {}
            override suspend fun patchPrompt(prompt: String, negativePrompt: String?) { events += "patch" }
            override suspend fun promptApplied(prompt: String) { events += "applied" }
            override suspend fun saveDefaults() { events += "save" }
            override suspend fun submit(): String { events += "submit"; return "id" }
        }
        AgentDrawExecutor(port).execute(AgentReply("好", "1girl", shouldDraw = true, saveDefaults = true), {}) {}
        assertEquals(listOf("patch", "applied", "save", "submit"), events)
        events.clear()
        AgentDrawExecutor(port).execute(AgentReply("好", "1girl"), {}) {}
        assertEquals(listOf("patch", "applied"), events)
    }
}
