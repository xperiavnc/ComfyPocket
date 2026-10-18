package com.comfyui.mobile.ui

import com.comfyui.mobile.agent.AgentPromptTargets
import com.comfyui.mobile.agent.AgentPromptBindings
import com.comfyui.mobile.agent.PromptBindingMode
import com.google.gson.reflect.TypeToken
import java.io.File
import kotlinx.coroutines.CancellationException

/** Read a detached template snapshot. Generation must not load, mutate or submit the active graph. */
internal object AgentWorkflowPromptContext {
    data class References(
        val negativePrompt: String? = null,
        val modelFacts: List<Map<String, String>> = emptyList()
    )

    fun negative(vm: MainViewModel, expectedVersion: Long): String? =
        capture(vm, expectedVersion).negativePrompt

    @Suppress("UNCHECKED_CAST")
    fun capture(vm: MainViewModel, expectedVersion: Long): References {
        if (vm.agentSession.contextVersion() != expectedVersion) return References()
        return try {
            val source = vm._agentWorkflowSource.value
            val id = vm._agentWorkflowId.value
            val (file, fallback) = when (source) {
                MainViewModel.AGENT_WF_AI_APP -> {
                    val app = vm._aiApps.value.firstOrNull { it.id == id }
                        ?: vm.loadAiAppsFromDisk().firstOrNull { it.id == id } ?: return References()
                    File(vm.aiAppsWorkflowDir(), app.workflowFileName) to app.workflowKey
                }
                MainViewModel.AGENT_WF_SAVED -> {
                    val saved = vm.getLocalSavedWorkflows().firstOrNull { it.name == id } ?: return References()
                    saved to saved.name
                }
                else -> return References()
            }
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val raw: Map<String, Any> = vm.gson.fromJson(file.readText(), type)
            val graph = vm.sanitizeNumbers(raw) as Map<String, Any>
            val params = MainViewModelAgent.loadAgentWorkflowParams(vm, source, id, fallback).toMutableMap()
            val snapshot = vm.applyParamOverridesToWorkflow(graph, params).first
            if (vm.agentSession.contextVersion() != expectedVersion) References() else {
                // Literal configured resource names only. No architecture/capability inference.
                val modelKeys = setOf("ckpt_name", "unet_name", "diffusion_model", "lora_name",
                    "clip_name", "clip_name1", "clip_name2", "clip_name3")
                val modelFacts = snapshot.flatMap { (nodeId, rawNode) ->
                    val node = rawNode as? Map<*, *> ?: return@flatMap emptyList()
                    val classType = node["class_type"] as? String ?: return@flatMap emptyList()
                    val inputs = node["inputs"] as? Map<*, *> ?: return@flatMap emptyList()
                    inputs.mapNotNull { (key, value) ->
                        if (key !in modelKeys || value !is String || value.isBlank()) null else
                            mapOf("node" to nodeId, "class_type" to classType.take(120),
                                "field" to key.toString(), "value" to value.take(240))
                    }
                }.take(16)
                val positive = MainViewModelAgent.loadPromptBinding(vm, false, source, id).let {
                    it.explicitTarget(snapshot) ?: AgentPromptTargets.positive(snapshot).firstOrNull()
                }
                val binding = MainViewModelAgent.loadPromptBinding(vm, true, source, id)
                val targets = if (binding.mode == PromptBindingMode.KEEP) {
                    AgentPromptTargets.negative(snapshot, positive)
                } else AgentPromptBindings.negativeTargets(snapshot, binding, positive)
                References(AgentPromptBindings.text(snapshot, targets)?.take(6000), modelFacts)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            References() // Normal prepare reports template errors after a valid model response.
        }
    }
}
