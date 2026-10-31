package com.comfyui.mobile.agent

import com.comfyui.mobile.modelcenter.LocalModel
import com.comfyui.mobile.modelcenter.ModelAnnotation
import com.comfyui.mobile.modelcenter.ModelProvider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AgentLoraToolsTest {
    private val options = listOf("style\\watercolor_v2.safetensors", "chars\\miku.safetensors", "detail.safetensors")

    private fun workflow(): Map<String, Any> = mapOf(
        "10" to mutableMapOf<String, Any>("class_type" to "LoraLoader", "inputs" to mutableMapOf<String, Any>(
            "lora_name" to "detail.safetensors", "strength_model" to 1.0, "strength_clip" to 1.0,
            "model" to listOf("4", 0), "clip" to listOf("4", 1))),
        "3" to mutableMapOf<String, Any>("class_type" to "KSampler", "inputs" to mutableMapOf<String, Any>("steps" to 20)))

    private fun snapshot() = AgentWorkflowSnapshot(workflow(), listOf(
        AgentFieldSpec("10", "lora_name", "LoraLoader", AgentFieldKind.TEXT, options = options),
        AgentFieldSpec("10", "strength_model", "LoraLoader", AgentFieldKind.DECIMAL, min = -10.0, max = 10.0),
        AgentFieldSpec("10", "strength_clip", "LoraLoader", AgentFieldKind.DECIMAL, min = -10.0, max = 10.0)))

    private val catalog = AgentLoraCatalog.from(listOf(
        LocalModel(category = "LORA", present = true, name = "watercolor_v2", nodeModelName = "style\\watercolor_v2.safetensors",
            sha256 = "a".repeat(64),
            annotation = ModelAnnotation(alias = "水彩画风", useCases = listOf("水彩", "插画"), preferredStrength = 0.7, notes = "背景很淡")),
        LocalModel(category = "LORA", present = true, name = "miku", nodeModelName = "chars\\miku.safetensors",
            provider = ModelProvider(trainedWords = listOf("hatsune miku"), description = "<p>Miku <b>character</b> LoRA</p>")),
        LocalModel(category = "CHECKPOINT", present = true, name = "base", nodeModelName = "base.safetensors"),
        LocalModel(category = "LORA", present = false, name = "gone", nodeModelName = "gone.safetensors")
    ), mapOf("a".repeat(64) to ModelProvider(trainedWords = listOf("watercolor"), baseModel = "SDXL 1.0")))

    private fun args(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    @Test fun catalogKeepsOnlyPresentLorasAndPrefersUserNotes() {
        assertEquals(listOf("水彩画风", "miku"), catalog.map { it.name })
        val watercolor = catalog.first()
        assertEquals(listOf("watercolor"), watercolor.triggerWords)
        assertEquals("SDXL 1.0", watercolor.baseModel)
        assertEquals(0.7, watercolor.preferredStrength!!, 0.0)
        assertEquals("Miku character LoRA", catalog[1].description)
        assertEquals(listOf("水彩画风"), AgentLoraCatalog.search(catalog, "水彩").map { it.name })
        assertEquals(listOf("miku"), AgentLoraCatalog.search(catalog, "hatsune").map { it.name })
    }

    @Test fun resolveMatchesPathsSeparatorsAndDisplayNames() {
        assertEquals("chars\\miku.safetensors", AgentLoraCatalog.resolve("chars/miku.safetensors", options, catalog))
        assertEquals("chars\\miku.safetensors", AgentLoraCatalog.resolve("miku", options, catalog))
        assertEquals("style\\watercolor_v2.safetensors", AgentLoraCatalog.resolve("水彩画风", options, catalog))
        assertNull(AgentLoraCatalog.resolve("nope", options, catalog))
    }

    @Test fun loraToolsOnlyAppearWhenTheWorkflowHasASlot() {
        val names = AgentDrawToolbox(snapshot(), loras = catalog).definitions.map { it.function.name }
        assertTrue(AgentDrawToolbox.LIST_LORAS in names && AgentDrawToolbox.SET_LORA in names)
        val plain = AgentWorkflowSnapshot(workflow(), listOf(AgentFieldSpec("3", "steps", "KSampler", AgentFieldKind.INTEGER)))
        assertFalse(AgentDrawToolbox(plain, loras = catalog).definitions.any { it.function.name == AgentDrawToolbox.LIST_LORAS })
    }

    @Test fun listShowsSlotsAndAnnotatedLoras() = runBlocking {
        val box = AgentDrawToolbox(snapshot(), titles = mapOf("10" to "LoRA 加载"), loras = catalog)
        val result = box.call(AgentDrawToolbox.LIST_LORAS, args("{}"))
        assertTrue(result.ok)
        val data = result.data as Map<*, *>
        val slot = (data["slots"] as List<*>).single() as Map<*, *>
        assertEquals("10:lora_name", slot["slot"])
        assertEquals("LoRA 加载", slot["node"])
        assertEquals("detail.safetensors", slot["current"])
        val loras = (data["loras"] as List<*>).map { it as Map<*, *> }
        assertEquals(listOf("style\\watercolor_v2.safetensors", "chars\\miku.safetensors", "detail.safetensors"), loras.map { it["file"] })
        assertEquals(listOf("水彩", "插画"), loras[0]["use_cases"])
        assertEquals("没有本地资料", loras[2]["note"])
    }

    @Test fun setLoraStagesFileAndPreferredStrengthAndReturnsTriggerWords() = runBlocking {
        val box = AgentDrawToolbox(snapshot(), loras = catalog)
        val result = box.call(AgentDrawToolbox.SET_LORA, args("""{"slot":"10:lora_name","lora":"水彩画风"}"""))
        assertTrue(result.error, result.ok)
        assertEquals(mapOf(
            "10:lora_name" to "style\\watercolor_v2.safetensors",
            "10:strength_model" to 0.7, "10:strength_clip" to 0.7
        ), box.changes.associate { "${it.nodeId}:${it.key}" to it.value })
        assertEquals(listOf("watercolor"), (result.data as Map<*, *>)["trigger_words"])
    }

    @Test fun setLoraRejectsUnknownFilesAndCanDisableASlot() = runBlocking {
        val box = AgentDrawToolbox(snapshot(), loras = catalog)
        val missing = box.call(AgentDrawToolbox.SET_LORA, args("""{"slot":"10:lora_name","lora":"nope"}"""))
        assertFalse(missing.ok)
        assertTrue(missing.error!!.contains("list_loras"))
        assertTrue(box.changes.isEmpty())
        val off = box.call(AgentDrawToolbox.SET_LORA, args("""{"slot":"10","lora":""}"""))
        assertTrue(off.error, off.ok)
        assertEquals(setOf("10:strength_model", "10:strength_clip"), box.changes.map { "${it.nodeId}:${it.key}" }.toSet())
        assertTrue(box.changes.all { (it.value as Number).toDouble() == 0.0 })
    }

    @Test fun stackNodesExposeNumberedSlotsWithTheirOwnStrength() {
        val graph: Map<String, Any> = mapOf("7" to mapOf("class_type" to "LoraStack", "inputs" to mapOf(
            "lora_1" to "a.safetensors", "strength_1" to 0.8, "lora_2" to "None", "strength_2" to 1.0, "lora_12" to "b", "strength_12" to 0.5)))
        val slots = AgentLoraCatalog.slots(graph) { _, _ -> emptyList() }.associateBy { it.nameKey }
        assertEquals(listOf("strength_1"), slots.getValue("lora_1").strengthKeys)
        assertEquals(listOf("strength_12"), slots.getValue("lora_12").strengthKeys)
    }
}
