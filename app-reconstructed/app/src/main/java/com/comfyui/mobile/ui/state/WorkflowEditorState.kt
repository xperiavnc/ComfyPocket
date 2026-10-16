package com.comfyui.mobile.ui.state

import com.comfyui.mobile.data.WorkflowPreferences
import com.comfyui.mobile.model.EditableNode
import com.comfyui.mobile.workflow.WorkflowGraph
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Control-screen workflow editing state. It owns parameter overrides, visibility,
 * labels and ordering; neither Agent nor AI App sessions share these mutable maps.
 * Calls are serialized by the host exactly as before; no extra scope or jobs are created.
 */
internal class WorkflowEditorState(
    private val preferences: WorkflowPreferences,
    private val objectInfo: () -> Map<String, Any>
) {
    enum class UpdateResult { UPDATED, MISSING, LINKED_INPUT }

    var workflow: Map<String, Any>? = null
    var currentKey: String? = null
        private set
    val editableNodes = MutableStateFlow<List<EditableNode>>(emptyList())
    val nodeTitles = MutableStateFlow<Map<String, String>>(emptyMap())

    private var values: MutableMap<String, Any> = mutableMapOf()
    private var labels: MutableMap<String, String> = mutableMapOf()
    var paramVisibility: MutableMap<String, Boolean> = mutableMapOf()
        private set
    private var order: MutableList<String> = mutableListOf()
    private val nodeVisibility = mutableMapOf<String, Boolean>()

    fun selectKey(key: String?, namespace: String, onKeySelected: () -> Unit = {}) {
        currentKey = key
        // Existing host metadata is refreshed immediately after selecting the key.
        onKeySelected()
        nodeVisibility.clear()
        values = mutableMapOf()
        labels = mutableMapOf()
        paramVisibility = mutableMapOf()
        order = mutableListOf()
        if (key.isNullOrBlank()) return
        preferences.namespaces[key] = namespace
        values = preferences.loadAnyMap(key, "values")
        labels = preferences.loadStringMap(key, "labels")
        paramVisibility = preferences.loadBooleanMap(key, "visibility")
        order = preferences.loadStringList(key, "order")
        nodeVisibility.putAll(paramVisibility)
    }

    fun toggleVisibility(nodeId: String, key: String, visible: Boolean) {
        val compound = "$nodeId:$key"
        nodeVisibility[compound] = visible
        currentKey?.takeIf { it.isNotBlank() }?.let {
            paramVisibility[compound] = visible
            preferences.persistMap(it, "visibility", paramVisibility)
        }
        workflow?.let(::rebuild)
    }

    fun setCustomName(nodeId: String, key: String, name: String) {
        val workflowKey = currentKey?.takeIf { it.isNotBlank() } ?: return
        val compound = "$nodeId:$key"
        val cleaned = name.trim()
        if (cleaned.isBlank()) labels.remove(compound) else labels[compound] = cleaned
        preferences.persistMap(workflowKey, "labels", labels)
        workflow?.let(::rebuild)
    }

    fun moveOrder(fromCompound: String, toCompound: String) {
        val current = editableNodes.value.toMutableList()
        val from = current.indexOfFirst { WorkflowGraph.compoundKeyOf(it) == fromCompound }
        val to = current.indexOfFirst { WorkflowGraph.compoundKeyOf(it) == toCompound }
        if (from == -1 || to == -1 || from == to) return
        current.add(to, current.removeAt(from))
        editableNodes.value = current
        order = current.map { WorkflowGraph.compoundKeyOf(it) }.toMutableList()
    }

    fun updateOrder(newOrderKeys: List<String>) {
        val current = editableNodes.value
        val nodeMap = current.associateBy { WorkflowGraph.compoundKeyOf(it) }
        val next = ArrayList<EditableNode>()
        val seen = HashSet<String>()
        for (key in newOrderKeys) {
            val node = nodeMap[key]
            if (node != null) {
                next.add(node)
                seen.add(key)
            }
        }
        for (node in current) {
            if (WorkflowGraph.compoundKeyOf(node) !in seen) next.add(node)
        }
        editableNodes.value = next
        order = next.map { WorkflowGraph.compoundKeyOf(it) }.toMutableList()
        saveOrder()
    }

    fun saveOrder() {
        val key = currentKey
        if (!key.isNullOrBlank() && order.isNotEmpty()) {
            preferences.persistStringList(key, "order", order)
        }
    }

    fun applyOverrides(graph: Map<String, Any>): Map<String, Any> {
        val (result, removed) = WorkflowGraph.applyParamOverridesToWorkflow(graph, values)
        val key = currentKey
        if (removed.isNotEmpty() && !key.isNullOrBlank()) {
            preferences.persistMap(key, "values", values)
        }
        return result
    }

    fun rebuild(graph: Map<String, Any>?) {
        if (graph == null) {
            editableNodes.value = emptyList()
            nodeTitles.value = emptyMap()
            return
        }
        nodeTitles.value = WorkflowGraph.buildWorkflowNodeTitles(graph)
        val parsed = WorkflowGraph.parseWorkflowNodes(graph, objectInfo(), nodeVisibility, labels)
        val indexes = order.withIndex().associate { it.value to it.index }
        editableNodes.value = if (order.isEmpty()) parsed else
            parsed.sortedWith(compareBy { indexes[WorkflowGraph.compoundKeyOf(it)] ?: Int.MAX_VALUE })
    }

    @Suppress("UNCHECKED_CAST")
    fun updateValue(nodeId: String, key: String, value: Any): UpdateResult {
        val current = workflow?.toMutableMap() ?: return UpdateResult.MISSING
        val node = (current[nodeId] as? Map<String, Any>)?.toMutableMap() ?: return UpdateResult.MISSING
        val inputs = (node["inputs"] as? Map<String, Any>)?.toMutableMap() ?: return UpdateResult.MISSING
        val expected = inputs[key]
        if (expected is List<*> && expected.size == 2 && expected[0] is String && expected[1] is Number) {
            return UpdateResult.LINKED_INPUT
        }
        val coerced = WorkflowGraph.coerceToExpectedType(expected, value) ?: value
        inputs[key] = coerced
        node["inputs"] = inputs
        current[nodeId] = node
        workflow = current
        rebuild(current)
        currentKey?.takeIf { it.isNotBlank() }?.let {
            values["$nodeId:$key"] = coerced
            preferences.persistMap(it, "values", values)
        }
        return UpdateResult.UPDATED
    }

    fun appendPositivePrompt(text: String): String? {
        val clean = text.trim().removePrefix(",").trim()
        if (clean.isBlank()) return null
        val texts = editableNodes.value.filterIsInstance<EditableNode.TextInput>()
        val node = texts.firstOrNull {
            it.classType == "CLIPTextEncode" && (it.key == "text" || it.key == "text_g")
        } ?: texts.firstOrNull { it.key == "prompt" || it.key == "text" } ?: return null
        val old = node.text.trim()
        updateValue(node.nodeId, node.key, if (old.isBlank()) clean else "$old, $clean")
        return clean
    }
}
