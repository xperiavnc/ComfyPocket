package com.comfyui.mobile.agent

import org.junit.Assert.*
import org.junit.Test

class AgentWorkflowToolsTest {
    private val graph: Map<String, Any> = mapOf(
        "1" to mapOf("class_type" to "Text", "inputs" to mapOf("text" to "old", "linked" to listOf("2", 0))),
        "2" to mapOf("class_type" to "Sampler", "inputs" to mapOf("steps" to 20, "cfg" to 7.0, "enabled" to true, "name" to "euler"))
    )
    private val fields = listOf(
        AgentFieldSpec("1", "text", "Text", AgentFieldKind.TEXT),
        AgentFieldSpec("2", "steps", "Sampler", AgentFieldKind.INTEGER, min = 1.0, max = 100.0),
        AgentFieldSpec("2", "cfg", "Sampler", AgentFieldKind.DECIMAL, min = 0.0, max = 20.0),
        AgentFieldSpec("2", "enabled", "Sampler", AgentFieldKind.BOOLEAN),
        AgentFieldSpec("2", "name", "Sampler", AgentFieldKind.TEXT, options = listOf("euler", "dpmpp"))
    )
    private fun patch(vararg changes: AgentFieldChange, specs: List<AgentFieldSpec> = fields) =
        PatchWorkflowTool().execute(AgentPatchRequest(AgentWorkflowSnapshot(graph, specs), changes.toList()))
    private fun value(graph: Map<String, Any>, node: String, key: String) =
        ((graph[node] as Map<*, *>)["inputs"] as Map<*, *>)[key]

    @Test fun inspectionExposesOnlyExplicitCapabilities() {
        val result = InspectWorkflowTool().execute(AgentWorkflowSnapshot(graph, fields)).getOrThrow()
        assertEquals(5, result.size)
        assertFalse(result.any { it.spec.key == "linked" })
        assertEquals("old", result.first().value)
    }

    @Test fun patchUsesCopyOnWriteAndReturnsExactDiff() {
        val result = patch(AgentFieldChange("1", "text", "new"), AgentFieldChange("2", "steps", 30)).getOrThrow()
        assertEquals("old", value(graph, "1", "text"))
        assertEquals("new", value(result.workflow, "1", "text"))
        assertEquals(30, value(result.workflow, "2", "steps"))
        assertEquals(AgentFieldDiff("1", "text", "old", "new"), result.diff.first())
        assertEquals(listOf("2", 0), value(result.workflow, "1", "linked"))
        assertEquals("Text", (result.workflow["1"] as Map<*, *>)["class_type"])
    }

    @Test fun unknownFieldAndNodeAreRejected() {
        assertTrue(patch(AgentFieldChange("1", "class_type", "Anything")).isFailure)
        assertTrue(patch(AgentFieldChange("1", "unknown", "x")).isFailure)
        assertTrue(patch(AgentFieldChange("404", "text", "x")).isFailure)
    }

    @Test fun evenWhitelistedLinksAreProtected() {
        val specs = fields + AgentFieldSpec("1", "linked", "Text", AgentFieldKind.TEXT)
        assertTrue(patch(AgentFieldChange("1", "linked", "oops"), specs = specs).isFailure)
        assertTrue(InspectWorkflowTool().execute(AgentWorkflowSnapshot(graph, specs)).isFailure)
    }

    @Test fun multiFieldFailureIsAtomic() {
        assertTrue(patch(AgentFieldChange("1", "text", "new"), AgentFieldChange("2", "steps", 999)).isFailure)
        assertEquals("old", value(graph, "1", "text"))
        assertEquals(20, value(graph, "2", "steps"))
    }

    @Test fun scalarTypesAreStrict() {
        assertTrue(patch(AgentFieldChange("2", "enabled", "false")).isFailure)
        assertTrue(patch(AgentFieldChange("2", "cfg", "8")).isFailure)
        assertTrue(patch(AgentFieldChange("1", "text", false)).isFailure)
        assertTrue(patch(AgentFieldChange("2", "steps", 20.5)).isFailure)
        assertTrue(patch(AgentFieldChange("2", "steps", Long.MAX_VALUE)).isFailure)
    }

    @Test fun rangesAndNonFiniteNumbersAreRejected() {
        for (invalid in listOf(-1.0, 21.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertTrue(patch(AgentFieldChange("2", "cfg", invalid)).isFailure)
        }
        assertTrue(patch(AgentFieldChange("2", "cfg", 0.0)).isSuccess)
        assertTrue(patch(AgentFieldChange("2", "cfg", 20.0)).isSuccess)
    }

    @Test fun comboAllowsOnlyListedOptions() {
        assertTrue(patch(AgentFieldChange("2", "name", "dpmpp")).isSuccess)
        assertTrue(patch(AgentFieldChange("2", "name", "invented")).isFailure)
    }

    @Test fun duplicatesAndEmptyChangesAreRejected() {
        assertTrue(patch().isFailure)
        assertTrue(patch(AgentFieldChange("1", "text", "one"), AgentFieldChange("1", "text", "two")).isFailure)
    }

    @Test fun staleNodeClassAndMissingInputAreRejected() {
        assertTrue(patch(AgentFieldChange("1", "text", "new"),
            specs = listOf(fields.first().copy(classType = "Other"))).isFailure)
        assertTrue(patch(AgentFieldChange("1", "missing", "new"),
            specs = listOf(fields.first().copy(key = "missing"))).isFailure)
    }
}
