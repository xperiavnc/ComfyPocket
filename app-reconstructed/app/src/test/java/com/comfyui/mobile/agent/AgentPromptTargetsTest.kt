package com.comfyui.mobile.agent

import org.junit.Assert.*
import org.junit.Test

class AgentPromptTargetsTest {
    private fun node(type: String, inputs: Map<String, Any>, title: String = ""): Map<String, Any> =
        mapOf("class_type" to type, "inputs" to inputs, "_meta" to mapOf("title" to title))

    private fun graph() = linkedMapOf<String, Any>(
        // Deliberately put negative first and give it the longer text.
        "2" to node("CLIPTextEncode", mapOf("text" to "very long original negative text")),
        "1" to node("CLIPTextEncode", mapOf("text" to "cat")),
        "3" to node("KSampler", mapOf("positive" to listOf("1", 0), "negative" to listOf("2", 0)))
    )

    @Test fun conditioningLinksIdentifyUnlabelledPositiveAndNegative() {
        val graph = graph()
        assertEquals(listOf("1" to "text"), AgentPromptTargets.positive(graph))
        assertEquals(listOf("2" to "text"), AgentPromptTargets.negative(graph))
        assertEquals("very long original negative text", AgentPromptTargets.negativeContext(graph))
    }

    @Test fun followsConditioningTransformsAndBothSdxlTextFields() {
        val graph = graph().apply {
            this["2"] = node("CLIPTextEncodeSDXL", mapOf("text_g" to "old g", "text_l" to "old l"))
            this["4"] = node("ConditioningSetArea", mapOf("conditioning" to listOf("2", 0), "width" to 512))
            this["3"] = node("KSampler", mapOf("positive" to listOf("1", 0), "negative" to listOf("4", 0)))
        }
        assertEquals(listOf("2" to "text_g", "2" to "text_l"), AgentPromptTargets.negative(graph))
    }

    @Test fun followsLinkedTextWithoutReplacingItsLink() {
        val graph = graph().apply {
            this["2"] = node("CLIPTextEncode", mapOf("text" to listOf("5", 0)))
            this["5"] = node("PrimitiveNode", mapOf("value" to "old negative"))
        }
        assertEquals(listOf("5" to "value"), AgentPromptTargets.negative(graph))
    }

    @Test fun zeroedOrSharedPositiveConditioningIsNotANegativeTextTarget() {
        val graph = graph().apply {
            this["4"] = node("ConditioningZeroOut", mapOf("conditioning" to listOf("1", 0)))
            this["3"] = node("KSampler", mapOf("positive" to listOf("1", 0), "negative" to listOf("4", 0)))
        }
        assertTrue(AgentPromptTargets.negative(graph).isEmpty())
        graph["3"] = node("KSampler", mapOf("positive" to listOf("1", 0), "negative" to listOf("1", 0)))
        assertTrue(AgentPromptTargets.negative(graph).isEmpty())
    }

    @Test fun titleFallbackAndInlineNegativeAreSupportedWithoutASampler() {
        val graph = linkedMapOf<String, Any>(
            "1" to node("CLIPTextEncode", mapOf("text" to ""), "负向提示词"),
            "2" to node("CustomGenerator", mapOf("negative_prompt" to "old", "steps" to 20))
        )
        assertEquals(setOf("1" to "text", "2" to "negative_prompt"), AgentPromptTargets.negative(graph).toSet())
        assertEquals(listOf("2" to "negative_prompt"), AgentPromptTargets.negative(graph, "1" to "text"))
    }

    @Test fun conditioningCycleTerminatesWithoutGuessingAnUnrelatedTextNode() {
        val graph = graph().apply {
            this["4"] = node("ConditioningCombine", mapOf("conditioning_1" to listOf("4", 0)))
            this["3"] = node("KSampler", mapOf("positive" to listOf("1", 0), "negative" to listOf("4", 0)))
        }
        assertTrue(AgentPromptTargets.negative(graph).isEmpty())
    }
}
