package com.comfyui.mobile.ui

import com.comfyui.mobile.agent.AgentPromptTargets
import com.google.gson.reflect.TypeToken
import java.io.File
import kotlinx.coroutines.CancellationException

/** Read a detached template snapshot. Generation must not load, mutate or submit the active graph. */
internal object AgentWorkflowPromptContext {
    @Suppress("UNCHECKED_CAST")
    fun negative(vm: MainViewModel, expectedVersion: Long): String? {
        if (vm.agentSession.contextVersion() != expectedVersion) return null
        return try {
            val source = vm._agentWorkflowSource.value
            val id = vm._agentWorkflowId.value
            val (file, fallback) = when (source) {
                MainViewModel.AGENT_WF_AI_APP -> {
                    val app = vm._aiApps.value.firstOrNull { it.id == id }
                        ?: vm.loadAiAppsFromDisk().firstOrNull { it.id == id } ?: return null
                    File(vm.aiAppsWorkflowDir(), app.workflowFileName) to app.workflowKey
                }
                MainViewModel.AGENT_WF_SAVED -> {
                    val saved = vm.getLocalSavedWorkflows().firstOrNull { it.name == id } ?: return null
                    saved to saved.name
                }
                else -> return null
            }
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val raw: Map<String, Any> = vm.gson.fromJson(file.readText(), type)
            val graph = vm.sanitizeNumbers(raw) as Map<String, Any>
            val params = MainViewModelAgent.loadAgentWorkflowParams(vm, source, id, fallback).toMutableMap()
            val snapshot = vm.applyParamOverridesToWorkflow(graph, params).first
            if (vm.agentSession.contextVersion() != expectedVersion) null else AgentPromptTargets.negativeContext(snapshot)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null // The normal prepare step reports template errors after a valid model response.
        }
    }
}
