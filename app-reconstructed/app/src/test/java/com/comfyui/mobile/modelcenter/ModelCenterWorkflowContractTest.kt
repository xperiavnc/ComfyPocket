package com.comfyui.mobile.modelcenter

import com.comfyui.mobile.workflow.WorkflowGraph
import com.comfyui.mobile.model.EditableNode
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.*
import org.junit.Test

class ModelCenterWorkflowContractTest {
    @Test fun baselineIsApiGraphWithTwoLoraTypesAndIndependentPromptBindings() {
        val raw = javaClass.getResource("/model-center-api-graph.json")!!.readText()
        val type = object : TypeToken<Map<String, Any>>() {}.type
        val graph: Map<String, Any> = Gson().fromJson(raw, type)
        assertFalse(graph.containsKey("nodes")) // not browser/LiteGraph workflow JSON
        val nodes = WorkflowGraph.parseWorkflowNodes(graph, emptyMap(), emptyMap(), emptyMap())
        assertEquals(2, nodes.filterIsInstance<EditableNode.TextInput>().count { it.key == "text" })
        val style = graph["style"] as Map<*, *>
        val modelOnly = graph["modelOnly"] as Map<*, *>
        assertEquals("LoraLoader", style["class_type"])
        assertEquals("LoraLoaderModelOnly", modelOnly["class_type"])
        assertFalse((modelOnly["inputs"] as Map<*, *>).containsKey("clip"))
        val patched = WorkflowGraph.applyParamOverridesToWorkflow(graph,
            mutableMapOf("style:strength_model" to 0.8, "positive:text" to "new prompt")).first
        assertEquals((graph["sampler"] as Map<*, *>)["inputs"], (patched["sampler"] as Map<*, *>)["inputs"])
        assertEquals((graph["negative"] as Map<*, *>)["inputs"], (patched["negative"] as Map<*, *>)["inputs"])
        assertEquals((style["inputs"] as Map<*, *>)["model"],
            (((patched["style"] as Map<*, *>)["inputs"]) as Map<*, *>)["model"])
    }
}
