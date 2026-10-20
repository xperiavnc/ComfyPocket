package com.comfyui.mobile.agent

import android.content.SharedPreferences
import com.google.gson.Gson

enum class PromptBindingMode { AUTO, EXPLICIT, KEEP }

data class AgentPromptBinding(
    val mode: PromptBindingMode = PromptBindingMode.AUTO,
    val nodeId: String = "",
    val key: String = ""
) {
    val label: String get() = when (mode) {
        PromptBindingMode.AUTO -> "自动探测"
        PromptBindingMode.KEEP -> "保留工作流原值"
        PromptBindingMode.EXPLICIT -> "#$nodeId · ${key.ifEmpty { "兼容旧节点配置" }}"
    }

    fun explicitTarget(graph: Map<String, Any>): Pair<String, String>? {
        if (mode != PromptBindingMode.EXPLICIT) return null
        val inputs = (graph[nodeId] as? Map<*, *>)?.get("inputs") as? Map<*, *>
        val field = key.ifEmpty {
            listOf("text", "text_g", "text_l", "string", "value").firstOrNull { inputs?.get(it) is String }.orEmpty()
        }
        require(field.isNotEmpty() && inputs?.get(field) is String) {
            "提示词绑定 #$nodeId:${key.ifEmpty { "text" }} 已失效，请重新设置；未改写连线"
        }
        return nodeId to field
    }
}

class AgentPromptBindingStore(private val prefs: SharedPreferences, private val gson: Gson = Gson()) {
    private data class StoredBinding(val mode: PromptBindingMode?, val nodeId: String?, val key: String?)
    private fun storage(scope: String, source: String, id: String, negative: Boolean) =
        "agent_prompt_binding_${if (negative) "negative" else "positive"}__${scope}_${source}_${id}"

    fun load(scope: String, source: String, id: String, negative: Boolean): AgentPromptBinding {
        val value = prefs.getString(storage(scope, source, id, negative), null)
        if (value != null) {
            val parsed = runCatching { gson.fromJson(value, StoredBinding::class.java) }.getOrNull()
            if (parsed?.mode != null && parsed.nodeId != null && parsed.key != null) {
                return AgentPromptBinding(parsed.mode, parsed.nodeId, parsed.key)
            }
        }
        if (!negative) {
            val old = prefs.getString("agent_target_node_${scope}_${source}_${id}", null)
                ?: prefs.getString("agent_target_node_${source}_${id}", "").orEmpty()
            if (old.isNotBlank()) return AgentPromptBinding(PromptBindingMode.EXPLICIT, old)
        }
        return AgentPromptBinding()
    }

    fun save(scope: String, source: String, id: String, negative: Boolean, binding: AgentPromptBinding) {
        require(negative || binding.mode != PromptBindingMode.KEEP) { "正向提示词需要写入节点" }
        require(binding.mode != PromptBindingMode.EXPLICIT || (binding.nodeId.isNotBlank() && binding.key.isNotBlank())) {
            "请选择具体文本字段"
        }
        prefs.edit().putString(storage(scope, source, id, negative), gson.toJson(binding)).apply()
    }
}

data class AgentPromptOutput(
    val positive: String = "",
    val negative: String? = null,
    val negativeStatus: String? = null,
    val positiveAliases: List<AgentPromptAlias>? = null,
    val negativeAliases: List<AgentPromptAlias>? = null
)

/** Resolve both directions against the same snapshot; explicit invalid bindings fail visibly. */
object AgentPromptBindings {
    fun negativeTargets(
        graph: Map<String, Any>,
        binding: AgentPromptBinding,
        positive: Pair<String, String>?
    ): List<Pair<String, String>> {
        val targets = when (binding.mode) {
            PromptBindingMode.KEEP -> emptyList()
            PromptBindingMode.EXPLICIT -> listOfNotNull(binding.explicitTarget(graph))
            PromptBindingMode.AUTO -> AgentPromptTargets.negative(graph, positive)
        }
        require(positive == null || positive !in targets) { "正反向提示词绑定了同一字段，请分别设置" }
        return targets
    }

    fun text(graph: Map<String, Any>, targets: List<Pair<String, String>>): String? =
        targets.takeIf { it.isNotEmpty() }?.mapNotNull { (id, key) ->
            ((graph[id] as? Map<*, *>)?.get("inputs") as? Map<*, *>)?.get(key) as? String
        }?.distinct()?.joinToString("\n")
}
