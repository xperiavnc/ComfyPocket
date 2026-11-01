package com.comfyui.mobile.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MissingLoraTest {
    private fun node(type: String, inputs: Map<String, Any>): Map<String, Any> =
        mapOf("class_type" to type, "inputs" to inputs)

    private val stackInfo = mapOf("easy loraStack" to mapOf("input" to mapOf("required" to mapOf(
        "lora_1_name" to listOf(listOf("None", "Anima/4x6_Ani_v1.safetensors")),
        "lora_2_name" to listOf(listOf("None", "Anima/4x6_Ani_v1.safetensors"))))))

    @Test fun loraNoLongerOnTheServerBecomesNoneWithZeroStrength() {
        val graph = linkedMapOf<String, Any>("13" to node("easy loraStack", mapOf(
            "lora_1_name" to "Anima/4x6_Ani_v1.safetensors", "lora_1_strength" to 1.0,
            "lora_2_name" to "Anima/add_detail.safetensors", "lora_2_strength" to 0.0,
            "lora_2_model_strength" to 0.8)))
        val (out, cleared) = WorkflowGraph.clearMissingLoras(graph, stackInfo)
        val inputs = (out["13"] as Map<*, *>)["inputs"] as Map<*, *>
        assertEquals("None", inputs["lora_2_name"])
        assertEquals(0.0, inputs["lora_2_model_strength"])
        assertEquals("Anima/4x6_Ani_v1.safetensors", inputs["lora_1_name"])
        assertEquals(1.0, inputs["lora_1_strength"])
        assertEquals(listOf("13:lora_2_name -> Anima/add_detail.safetensors"), cleared)
    }

    @Test fun presentLorasUnknownListsAndLoadersWithoutNoneStayUntouched() {
        val graph = linkedMapOf<String, Any>(
            "1" to node("easy loraStack", mapOf("lora_1_name" to "anima\\4x6_ani_v1.safetensors")),
            "2" to node("LoraLoader", mapOf("lora_name" to "gone.safetensors", "strength_model" to 1.0)))
        val info = stackInfo + mapOf("LoraLoader" to mapOf("input" to mapOf("required" to mapOf(
            "lora_name" to listOf(listOf("other.safetensors"))))))
        val (out, cleared) = WorkflowGraph.clearMissingLoras(graph, info)
        assertTrue(cleared.isEmpty())
        assertSame(graph["1"], out["1"])
        assertSame(graph["2"], out["2"])
        assertSame(graph, WorkflowGraph.clearMissingLoras(graph, emptyMap()).first)
    }
}
