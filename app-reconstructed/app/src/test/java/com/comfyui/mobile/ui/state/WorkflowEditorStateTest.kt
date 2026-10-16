package com.comfyui.mobile.ui.state

import com.comfyui.mobile.data.WorkflowPreferences
import com.comfyui.mobile.model.EditableNode
import com.comfyui.mobile.workflow.WorkflowGraph
import org.junit.Assert.*
import org.junit.Test

class WorkflowEditorStateTest {
    private val disk = mutableMapOf<String, String>()
    private val preferences = WorkflowPreferences(
        read = { disk[it] },
        write = { key, json, legacy -> disk[key] = json; if (legacy != null) disk.remove(legacy) },
        currentNamespace = { "local" }
    )
    private val editor = WorkflowEditorState(preferences) { emptyMap() }
    private fun graph(): Map<String, Any> = linkedMapOf(
        "1" to mapOf("class_type" to "CLIPTextEncode",
            "inputs" to mapOf("text" to "cat"), "_meta" to mapOf("title" to "Prompt")),
        "2" to mapOf("class_type" to "Sampler", "inputs" to mapOf("steps" to 20, "model" to listOf("9", 0))))

    private fun open(key: String = "workflow", namespace: String = "local") {
        editor.selectKey(key, namespace)
        editor.workflow = editor.applyOverrides(graph())
        editor.rebuild(editor.workflow)
    }

    private fun fields() = editor.editableNodes.value.map { WorkflowGraph.compoundKeyOf(it) }
    private fun inputs(node: String): Map<*, *> = (editor.workflow!![node] as Map<*, *>)["inputs"] as Map<*, *>

    @Test fun editingIsTypedPersistedAndDoesNotMutateOriginalGraph() {
        val original = graph()
        editor.selectKey("workflow", "local")
        editor.workflow = original
        assertEquals(WorkflowEditorState.UpdateResult.UPDATED, editor.updateValue("2", "steps", 31.8))
        assertEquals(31, inputs("2")["steps"])
        assertEquals(20, ((original["2"] as Map<*, *>)["inputs"] as Map<*, *>)["steps"])
        open()
        assertEquals(31, inputs("2")["steps"])
    }

    @Test fun connectedPortsAreRejectedWithoutPersistingOrRewritingGraph() {
        open()
        val before = editor.workflow
        assertEquals(WorkflowEditorState.UpdateResult.LINKED_INPUT, editor.updateValue("2", "model", "bad"))
        assertSame(before, editor.workflow)
        assertEquals(listOf("9", 0), inputs("2")["model"])
        assertTrue(disk.isEmpty())
    }

    @Test fun missingWorkflowOrNodeIsANoOp() {
        assertEquals(WorkflowEditorState.UpdateResult.MISSING, editor.updateValue("1", "text", "x"))
        open()
        assertEquals(WorkflowEditorState.UpdateResult.MISSING, editor.updateValue("missing", "text", "x"))
        assertTrue(disk.isEmpty())
    }

    @Test fun selectingAnotherWorkflowClearsPreviousLabelsVisibilityAndValues() {
        open("first")
        editor.updateValue("1", "text", "dog")
        editor.setCustomName("1", "text", "别名")
        editor.toggleVisibility("1", "text", false)
        open("second")
        val node = editor.editableNodes.value.filterIsInstance<EditableNode.TextInput>().first()
        assertEquals("cat", node.text)
        assertEquals("Prompt", node.title)
        assertTrue(node.isVisible)
        open("first")
        val restored = editor.editableNodes.value.filterIsInstance<EditableNode.TextInput>().first()
        assertEquals("dog", restored.text)
        assertEquals("别名", restored.title)
        assertFalse(restored.isVisible)
    }

    @Test fun sameWorkflowNameDoesNotCarryEditsAcrossNamespaces() {
        open(namespace = "local")
        editor.updateValue("1", "text", "local cat")
        open(namespace = "cloud@cn")
        assertEquals("cat", inputs("1")["text"])
        editor.updateValue("1", "text", "cloud cat")
        open(namespace = "local")
        assertEquals("local cat", inputs("1")["text"])
    }

    @Test fun draggingOrderIsNotPersistedUntilExplicitSave() {
        open()
        editor.moveOrder("2:steps", "1:text")
        assertEquals(listOf("2:steps", "1:text"), fields())
        assertFalse(disk.containsKey("workflow@local:workflow:order"))
        editor.saveOrder()
        open()
        assertEquals(listOf("2:steps", "1:text"), fields())
    }

    @Test fun partialOrderAppendsMissingNodesAndIgnoresUnknownKeys() {
        open()
        editor.updateOrder(listOf("missing", "2:steps"))
        assertEquals(listOf("2:steps", "1:text"), fields())
        open()
        assertEquals(listOf("2:steps", "1:text"), fields())
    }

    @Test fun blankCustomNameRestoresOriginalTitle() {
        open()
        editor.setCustomName("1", "text", "  New  ")
        assertEquals("New", editor.editableNodes.value.first().title)
        editor.setCustomName("1", "text", " ")
        assertEquals("Prompt", editor.editableNodes.value.first().title)
    }

    @Test fun promptAppendUsesTextEncoderAndPreservesCommaFormatting() {
        open()
        assertEquals("dog", editor.appendPositivePrompt(" , dog "))
        assertEquals("cat, dog", inputs("1")["text"])
        assertNull(editor.appendPositivePrompt(" , "))
    }

    @Test fun resetAndRebuildClearNodesWithoutSharingStateWithAnotherEditor() {
        open()
        val other = WorkflowEditorState(preferences) { emptyMap() }
        other.workflow = graph()
        other.rebuild(other.workflow)
        editor.updateValue("1", "text", "changed")
        assertEquals("cat", (other.editableNodes.value.first() as EditableNode.TextInput).text)
        var observedKey: String? = "not called"
        editor.selectKey(null, "local") { observedKey = editor.currentKey }
        assertNull(observedKey)
        editor.rebuild(null)
        assertTrue(editor.editableNodes.value.isEmpty())
        assertTrue(editor.nodeTitles.value.isEmpty())
        assertEquals(2, other.editableNodes.value.size)
    }

    @Test fun staleOverridesForNewlyConnectedInputsAreRemovedFromPersistence() {
        preferences.persistMap("workflow", "values", mapOf("2:model" to "stale", "2:steps" to 30))
        open()
        assertEquals(listOf("9", 0), inputs("2")["model"])
        assertFalse(preferences.loadAnyMap("workflow", "values").containsKey("2:model"))
        assertEquals(30, inputs("2")["steps"])
    }
}
