package com.comfyui.mobile.ui

import com.comfyui.mobile.model.AiApp
import com.comfyui.mobile.model.EditableNode
import com.comfyui.mobile.model.LogType

/**
 * State of the AI app opened on the AI app screen. Fully separate from the Control screen's current
 * workflow (currentWorkflow / key / name / editable nodes / params / results / last-workflow memory):
 * opening, editing, running or finishing an AI app never changes Control's state.
 */
internal class AiAppSession(
    val app: AiApp,
    val workflowKey: String,
    /** RH AI app (official API): editable nodes come from this template; null for normal apps. */
    val rhTemplate: List<RhTemplateField>?,
    /** Pre-2a RH import (synthetic graph without webappId): cannot run, must be re-imported. */
    val legacyRhImport: Boolean,
    /** Graph with the persisted param values applied (edits are applied here directly). */
    var workflow: Map<String, Any>,
    val paramValues: MutableMap<String, Any>,
    val paramLabels: MutableMap<String, String>,
    val paramVisibility: MutableMap<String, Boolean>,
    var paramOrder: MutableList<String>
    )

/** Operations of the AI app screen's session (see [AiAppSession]). Main thread. */
object MainViewModelAiAppSession {

    /** Installs a freshly loaded session (called by MainViewModelAiApps.loadAiApp on the main thread). */
    internal fun install(
        vm: MainViewModel,
        app: AiApp,
        rawWorkflow: Map<String, Any>,
        rhTemplate: List<RhTemplateField>?,
        legacyRhImport: Boolean
    ) {
        val key = app.workflowKey
        vm.workflowKeyNamespaces[key] = vm.currentKindNs()
        val values = vm.loadWorkflowAnyMap(key, "values")
        val (graph, removed) = vm.applyParamOverridesToWorkflow(rawWorkflow, values)
        if (removed.isNotEmpty()) vm.persistWorkflowMap(key, "values", values)
        val session = AiAppSession(
            app = app,
            workflowKey = key,
            rhTemplate = rhTemplate,
            legacyRhImport = legacyRhImport,
            workflow = graph,
            paramValues = values,
            paramLabels = vm.loadWorkflowStringMap(key, "labels"),
            paramVisibility = vm.loadWorkflowBooleanMap(key, "visibility"),
            paramOrder = vm.loadWorkflowStringList(key, "order")
    )
        vm.aiAppSession = session
        vm._aiAppNeedsReimport.value = legacyRhImport
        vm._aiAppNodeTitles.value = vm.buildWorkflowNodeTitles(graph)
        rebuildNodes(vm)
        vm._aiAppLatestResults.value = vm.loadLatestGenerationFor(key)
    }

    /** Clears the session (e.g. after a local<->cloud switch: the app belongs to the other mode's storage). */
    internal fun invalidate(vm: MainViewModel) {
        vm.aiAppSessionGeneration.incrementAndGet()
        vm.aiAppSession = null
        vm._aiAppEditableNodes.value = emptyList()
        vm._aiAppLatestResults.value = emptyList()
        vm._aiAppNodeTitles.value = emptyMap()
        vm._aiAppNeedsReimport.value = false
    }

    /** Clears the displayed session state right away when another app is being opened. */
    internal fun clearForOtherApp(vm: MainViewModel, appId: String) {
        if (vm.aiAppSession?.app?.id == appId) return
        vm.aiAppSession = null
        vm._aiAppEditableNodes.value = emptyList()
        vm._aiAppLatestResults.value = emptyList()
        vm._aiAppNodeTitles.value = emptyMap()
        vm._aiAppNeedsReimport.value = false
    }

    internal fun rebuildNodes(vm: MainViewModel) {
        val s = vm.aiAppSession ?: return
        val list = if (s.rhTemplate != null) {
            // RH AI app: editable nodes come from the official template (fieldType / options aware).
            MainViewModelRhApps.buildEditableNodes(s.workflow, s.rhTemplate, s.paramVisibility, s.paramLabels)
        } else {
            vm.parseWorkflowNodes(s.workflow, vm._objectInfo.value, s.paramVisibility, s.paramLabels)
        }
        vm._aiAppEditableNodes.value = applyOrder(vm, list, s.paramOrder)
    }

    private fun applyOrder(vm: MainViewModel, list: List<EditableNode>, order: List<String>): List<EditableNode> {
        if (order.isEmpty()) return list
        val orderIndex = order.withIndex().associate { it.value to it.index }
        return list.sortedWith(compareBy { orderIndex[vm.compoundKeyOf(it)] ?: Int.MAX_VALUE })
    }

    fun updateNodeValue(vm: MainViewModel, nodeId: String, key: String, value: Any) {
        val s = vm.aiAppSession ?: return
        val current = s.workflow.toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val node = (current[nodeId] as? Map<String, Any>)?.toMutableMap() ?: return
        @Suppress("UNCHECKED_CAST")
        val inputs = (node["inputs"] as? Map<String, Any>)?.toMutableMap() ?: return
        val expected = inputs[key]
        // Never overwrite a link input (upstream node connection) with a literal value.
        if (expected is List<*> && expected.size == 2 && expected[0] is String && expected[1] is Number) {
            vm.addLog("尝试更新连线端口 $nodeId:$key，已拦截以保护工作流连线", LogType.WARNING)
            return
        }
        val coerced = vm.coerceToExpectedType(expected, value) ?: value
        inputs[key] = coerced
        node["inputs"] = inputs
        current[nodeId] = node
        s.workflow = current
        s.paramValues["$nodeId:$key"] = coerced
        vm.persistWorkflowMap(s.workflowKey, "values", s.paramValues)
        rebuildNodes(vm)
    }

    fun toggleVisibility(vm: MainViewModel, nodeId: String, key: String, visible: Boolean) {
        val s = vm.aiAppSession ?: return
        s.paramVisibility["$nodeId:$key"] = visible
        vm.persistWorkflowMap(s.workflowKey, "visibility", s.paramVisibility)
        rebuildNodes(vm)
    }

    fun setCustomName(vm: MainViewModel, nodeId: String, key: String, name: String) {
        val s = vm.aiAppSession ?: return
        val mapKey = "$nodeId:$key"
        val cleaned = name.trim()
        if (cleaned.isBlank()) s.paramLabels.remove(mapKey) else s.paramLabels[mapKey] = cleaned
        vm.persistWorkflowMap(s.workflowKey, "labels", s.paramLabels)
        rebuildNodes(vm)
    }

    fun updateParamOrder(vm: MainViewModel, newOrderKeys: List<String>) {
        val s = vm.aiAppSession ?: return
        val currentList = vm._aiAppEditableNodes.value
        val nodeMap = currentList.associateBy { vm.compoundKeyOf(it) }
        val newList = ArrayList<EditableNode>()
        val seen = HashSet<String>()
        for (k in newOrderKeys) {
            val node = nodeMap[k] ?: continue
            newList.add(node)
            seen.add(k)
        }
        for (node in currentList) {
            if (vm.compoundKeyOf(node) !in seen) newList.add(node)
        }
        vm._aiAppEditableNodes.value = newList
        s.paramOrder = newList.map { vm.compoundKeyOf(it) }.toMutableList()
        if (s.paramOrder.isNotEmpty()) vm.persistWorkflowStringList(s.workflowKey, "order", s.paramOrder)
    }

    fun appendPositivePrompt(vm: MainViewModel, textToAppend: String) {
        val clean = textToAppend.trim().removePrefix(",").trim()
        if (clean.isBlank()) return
        val texts = vm._aiAppEditableNodes.value.filterIsInstance<EditableNode.TextInput>()
        val textNode = texts.firstOrNull { it.classType == "CLIPTextEncode" && (it.key == "text" || it.key == "text_g") }
            ?: texts.firstOrNull { it.key == "prompt" || it.key == "text" }
            ?: return
        val currentText = textNode.text.trim()
        val newText = if (currentText.isBlank()) clean else "$currentText, $clean"
        updateNodeValue(vm, textNode.nodeId, textNode.key, newText)
        vm.showToast("已自动追加触发词: $clean")
    }

    /** Runs the session's app (RH template app -> official task API; otherwise the current backend). */
    fun run(vm: MainViewModel, batchCount: Int) {
        val s = vm.aiAppSession
        if (s == null) {
            vm.showToast("请先打开该应用再运行")
            return
        }
        // Latest stored app (its site may have been assigned after the session was opened).
        val app = vm._aiApps.value.firstOrNull { it.id == s.app.id } ?: s.app
        if (s.rhTemplate != null) {
            MainViewModelRhApps.runRhWebApp(vm, app, batchCount, s.workflow)
            return
        }
        val cloud = vm.currentServerProfile()?.isRunningHub == true
        if (s.legacyRhImport && cloud) {
            vm.showToast("请重新导入此应用")
            return
        }
        if (cloud) {
            val siteId = MainViewModelRhWorkflows.currentSiteId(vm)
            val blocked = siteId?.let { MainViewModelAiApps.siteBlockMessage(app, it) }
            if (blocked != null) {
                vm.showToast(MainViewModelRhWorkflows.SITE_MISMATCH_TOAST)
                return
            }
            @Suppress("UNCHECKED_CAST")
            val wf = vm.sanitizeNumbers(s.workflow) as? Map<String, Any> ?: return
            MainViewModelRhWorkflows.runCurrent(vm, wf, s.workflowKey, s.app.title, batchCount)
            return
        }
        vm.runLocalWorkflow(batchCount, s)
    }
}
