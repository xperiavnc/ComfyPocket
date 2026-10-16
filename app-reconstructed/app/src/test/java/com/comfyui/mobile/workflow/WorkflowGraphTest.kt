package com.comfyui.mobile.workflow

import com.comfyui.mobile.model.EditableNode
import org.junit.Assert.*
import org.junit.Test

class WorkflowGraphTest {
    private fun node(type: String, inputs: Map<String, Any>, title: String = type): Map<String, Any> =
        mapOf("class_type" to type, "inputs" to inputs, "_meta" to mapOf("title" to title))

    @Test fun numericNormalizationKeepsFloatWeightsAndUnknownFields() {
        val input = mapOf("width" to 512.0, "seed" to 4294967296.0,
            "cfg" to 7.0, "lora_1_strength" to 1.0, "unknown" to 2.0, "steps" to 12.5)
        val output = WorkflowGraph.sanitizeNumbers(input) as Map<*, *>
        assertEquals(512, output["width"])
        assertEquals(4294967296L, output["seed"])
        for (key in listOf("cfg", "lora_1_strength", "unknown", "steps")) {
            assertTrue("$key keeps its numeric type", output[key] is Double)
        }
        assertTrue(input["width"] is Double)
    }

    @Test fun referenceIndicesAreNormalizedWithoutChangingTopology() {
        val graph = mapOf("1" to node("CLIPTextEncode", mapOf("text" to listOf("8", 0.0))))
        val normalized = WorkflowGraph.sanitizeNumbers(graph) as Map<*, *>
        val inputs = (normalized["1"] as Map<*, *>)["inputs"] as Map<*, *>
        assertEquals(listOf("8", 0), inputs["text"])
        assertEquals(emptyList<EditableNode>(), WorkflowGraph.parseWorkflowNodes(
            normalized as Map<String, Any>, emptyMap(), emptyMap(), emptyMap()))
    }

    @Test fun coercionRetainsExpectedScalarTypes() {
        assertEquals(3, WorkflowGraph.coerceToExpectedType(1, 3.8))
        assertEquals(3L, WorkflowGraph.coerceToExpectedType(1L, 3.8))
        assertEquals(3f, WorkflowGraph.coerceToExpectedType(1f, 3))
        assertEquals(3.0, WorkflowGraph.coerceToExpectedType(1.0, 3))
        assertEquals(true, WorkflowGraph.coerceToExpectedType(false, " yes "))
        assertEquals(false, WorkflowGraph.coerceToExpectedType(true, 0))
        assertEquals("42", WorkflowGraph.coerceToExpectedType("", 42))
        assertNull(WorkflowGraph.coerceToExpectedType(1, "not a number"))
        assertNull(WorkflowGraph.coerceToExpectedType("", null))
    }

    @Test fun overridesCopyGraphAndRemoveOnlyConnectedInputOverrides() {
        val graph = mapOf("1" to node("Sampler", mapOf("steps" to 20, "model" to listOf("9", 0))))
        val values = mutableMapOf<String, Any>("1:steps" to 31.0, "1:model" to "bad", "invalid" to "keep")
        val (output, removed) = WorkflowGraph.applyParamOverridesToWorkflow(graph, values)
        val inputs = (output["1"] as Map<*, *>)["inputs"] as Map<*, *>
        assertEquals(31, inputs["steps"])
        assertEquals(listOf("9", 0), inputs["model"])
        assertEquals(listOf("1:model"), removed)
        assertFalse(values.containsKey("1:model"))
        assertTrue(values.containsKey("invalid"))
        assertEquals(20, ((graph["1"] as Map<*, *>)["inputs"] as Map<*, *>)["steps"])
    }

    @Test fun emptyOverridesReturnOriginalGraph() {
        val graph = mapOf("1" to node("Text", mapOf("text" to "hello")))
        val (output, removed) = WorkflowGraph.applyParamOverridesToWorkflow(graph, mutableMapOf())
        assertSame(graph, output)
        assertTrue(removed.isEmpty())
    }

    @Test fun parserPreservesVisibilityLabelsAndUnknownComboValues() {
        val graph = mapOf("1" to node("Selector", mapOf("choice" to "custom", "enabled" to true)))
        val info = mapOf("Selector" to mapOf("input" to mapOf("required" to mapOf(
            "choice" to listOf(listOf("a", "b", "a"))))))
        val parsed = WorkflowGraph.parseWorkflowNodes(graph, info,
            mapOf("1:choice" to true), mapOf("1:choice" to "我的选项"))
        val text = parsed.filterIsInstance<EditableNode.TextInput>().single()
        assertEquals("我的选项", text.title)
        assertTrue(text.isVisible)
        assertEquals(listOf("custom", "a", "b"), text.options)
        assertFalse(parsed.filterIsInstance<EditableNode.BooleanInput>().single().isVisible)
    }

    @Test fun imageVideoAndAudioInputsRetainTheirUploadKeys() {
        val graph = linkedMapOf(
            "1" to node("LoadImage", mapOf("image" to "a.png", "subfolder" to "uploads")),
            "2" to node("LoadVideo", mapOf("video" to "b.mp4")),
            "3" to node("LoadAudio", mapOf("audio" to "c.wav")))
        val images = WorkflowGraph.parseWorkflowNodes(graph, emptyMap(), emptyMap(), emptyMap())
            .filterIsInstance<EditableNode.ImageInput>()
        assertEquals(listOf("image", "video", "audio"), images.map { it.key })
        assertEquals("uploads", images.first().subfolder)
        assertEquals(listOf("a.png", "b.mp4", "c.wav"), images.map { it.filename })
        assertTrue(images.first().isVisible)
    }

    @Test fun easyLoraStackSimpleModeHidesUnusedAndAdvancedSlots() {
        val graph = mapOf("1" to node("easy loraStack", mapOf(
            "num_loras" to 1, "mode" to "simple", "lora_1_strength" to 0.75,
            "lora_1_model_strength" to 0.9, "lora_1_clip_strength" to 0.5,
            "lora_2_strength" to 1.0)))
        val fields = WorkflowGraph.parseWorkflowNodes(graph, emptyMap(), emptyMap(), emptyMap())
            .map { WorkflowGraph.compoundKeyOf(it) }
        assertTrue(fields.contains("1:lora_1_strength"))
        assertFalse(fields.contains("1:lora_1_model_strength"))
        assertFalse(fields.contains("1:lora_1_clip_strength"))
        assertFalse(fields.contains("1:lora_2_strength"))
    }

    @Test fun easyLoraStackAdvancedModeKeepsWeightRanges() {
        val graph = mapOf("1" to node("easy loraStack", mapOf(
            "num_loras" to 1, "mode" to "advanced", "lora_1_strength" to 1.0,
            "lora_1_model_strength" to 0.9, "lora_1_clip_strength" to 0.5)))
        val numbers = WorkflowGraph.parseWorkflowNodes(graph, emptyMap(), emptyMap(), emptyMap())
            .filterIsInstance<EditableNode.NumberInput>().associateBy { it.key }
        assertFalse(numbers.containsKey("lora_1_strength"))
        assertEquals(0.0, numbers.getValue("lora_1_model_strength").min)
        assertEquals(5.0, numbers.getValue("lora_1_model_strength").max)
        assertTrue(numbers.containsKey("lora_1_clip_strength"))
    }

    @Test fun titlesUseTrimmedMetadataThenClassThenNodeId() {
        val graph = linkedMapOf<String, Any>(
            "1" to node("Text", emptyMap(), "  标题  "),
            "2" to node("Fallback", emptyMap(), " "),
            "3" to mapOf("inputs" to emptyMap<String, Any>()),
            "4" to "not a node")
        assertEquals(mapOf("1" to "标题", "2" to "Fallback", "3" to "3"),
            WorkflowGraph.buildWorkflowNodeTitles(graph))
    }

    @Test fun summaryUsesVisibleTextEncodersOnly() {
        val nodes = listOf(
            EditableNode.TextInput("1", "", "CLIPTextEncode", "one", key = "text"),
            EditableNode.TextInput("2", "", "CLIPTextEncode", "hidden", key = "text", isVisible = false),
            EditableNode.TextInput("3", "", "Other", "two", key = "text_g"),
            EditableNode.TextInput("4", "", "Other", "ignored", key = "filename"))
        assertEquals("one\ntwo", WorkflowGraph.generatePromptSummary(nodes))
    }

    @Test fun seedRandomizationCopiesInputsAndIsDeterministicallyTestable() {
        val graph = mapOf("1" to node("Sampler", mapOf<String, Any>(
            "seed" to 5L, "noise_seed" to 6L, "steps" to 20)))
        var next = 100
        val output = WorkflowGraph.randomizeSeedsForSend(graph) { next++ }
        val inputs = (output["1"] as Map<*, *>)["inputs"] as Map<*, *>
        assertEquals(100, inputs["seed"])
        assertEquals(101, inputs["noise_seed"])
        assertEquals(20, inputs["steps"])
        val original = (graph["1"] as Map<*, *>)["inputs"] as Map<*, *>
        assertEquals(5L, original["seed"])
        assertEquals(6L, original["noise_seed"])
    }
}
