package com.comfyui.mobile.agent

import com.comfyui.mobile.model.CharacterLore
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** A character's own LoRA must be in the workflow before the turn submits, without the model remembering. */
class AgentCharacterLoraTest {

    private val options = listOf("detail.safetensors", "chars\\miku.safetensors")

    private fun workflow(free: String = "None") = mapOf(
        "10" to mutableMapOf<String, Any>("class_type" to "LoraLoader", "inputs" to mutableMapOf<String, Any>(
            "lora_name" to "detail.safetensors", "strength_model" to 1.0, "strength_clip" to 1.0)),
        "11" to mutableMapOf<String, Any>("class_type" to "LoraLoader", "inputs" to mutableMapOf<String, Any>(
            "lora_name" to free, "strength_model" to 1.0, "strength_clip" to 1.0)),
        "3" to mutableMapOf<String, Any>("class_type" to "KSampler", "inputs" to mutableMapOf<String, Any>(
            "steps" to 20)))

    private fun snapshot(free: String = "None") = AgentWorkflowSnapshot(workflow(free), listOf(
        AgentFieldSpec("10", "lora_name", "LoraLoader", AgentFieldKind.TEXT, options = options),
        AgentFieldSpec("10", "strength_model", "LoraLoader", AgentFieldKind.DECIMAL, min = -10.0, max = 10.0),
        AgentFieldSpec("11", "lora_name", "LoraLoader", AgentFieldKind.TEXT, options = options),
        AgentFieldSpec("11", "strength_model", "LoraLoader", AgentFieldKind.DECIMAL, min = -10.0, max = 10.0),
        AgentFieldSpec("3", "steps", "KSampler", AgentFieldKind.INTEGER, min = 1.0, max = 60.0)))

    private val miku = AgentLoraInfo(file = "chars\\miku.safetensors", name = "miku", character = "miku",
        triggerWords = listOf("hatsune miku"))

    private fun character() = CharacterLore(name = "miku", triggerWords = "hatsune miku")

    private fun toolbox(free: String = "None", loras: List<AgentLoraInfo> = listOf(miku),
        characters: List<CharacterLore> = listOf(character())) =
        AgentDrawToolbox(snapshot(free), loras = loras, activeCharacters = characters)

    private fun args(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    @Test fun submitInstallsTheCharactersLoraWhenASlotIsFree() = runBlocking {
        val box = toolbox()
        val result = box.call(AgentDrawToolbox.SUBMIT, args("""{"reply":"画好了","prompt":"1girl, blue sky"}"""))
        assertTrue(result.error, result.ok)
        assertTrue(box.changes.any {
            it.nodeId == "11" && it.key == "lora_name" && it.value == "chars\\miku.safetensors"
        })
        val summary = (result.data as Map<*, *>)["summary"].toString()
        assertTrue(summary, summary.contains("已自动装上角色 LoRA"))
        assertTrue(box.prompt!!, box.prompt!!.contains("hatsune miku"))
    }

    @Test fun submitLeavesAnAlreadyLoadedCharacterLoraAlone() = runBlocking {
        val box = toolbox(free = "chars\\miku.safetensors")
        val result = box.call(AgentDrawToolbox.SUBMIT, args("""{"reply":"画好了","prompt":"1girl"}"""))
        assertTrue(result.error, result.ok)
        assertFalse(box.changes.any { it.key == "lora_name" })
        assertFalse((result.data as Map<*, *>)["summary"].toString().contains("已自动装上"))
    }

    @Test fun submitExplainsWhichSlotsBlockTheCharacterLora() = runBlocking {
        val box = toolbox(free = "detail.safetensors")
        val result = box.call(AgentDrawToolbox.SUBMIT, args("""{"reply":"画好了","prompt":"1girl"}"""))
        assertFalse(result.ok)
        val error = result.error!!
        assertTrue(error, error.contains("槽位都被占用了"))
        assertTrue(error, error.contains("11"))
        assertTrue(error, error.contains("set_lora"))
    }

    @Test fun briefingNamesTheCharactersOwnLora() = runBlocking {
        val briefing = toolbox().briefing()
        assertTrue(briefing, briefing.contains("本轮角色 LoRA"))
        assertTrue(briefing, briefing.contains("miku → chars\\miku.safetensors"))
    }

    @Test fun aCharacterWithoutItsOwnLoraIsNotReported() = runBlocking {
        val box = toolbox(characters = listOf(CharacterLore(name = "miku", triggerWords = "hatsune miku")),
            loras = listOf(AgentLoraInfo(file = "style\\watercolor.safetensors", name = "watercolor")))
        val result = box.call(AgentDrawToolbox.SUBMIT, args("""{"reply":"画好了","prompt":"1girl"}"""))
        assertTrue(result.error, result.ok)
        assertFalse(box.changes.any { it.key == "lora_name" })
    }
}
