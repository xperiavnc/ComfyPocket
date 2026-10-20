package com.comfyui.mobile.agent.workspace

import com.comfyui.mobile.agent.AgentPromptBinding
import com.comfyui.mobile.agent.AgentPromptOutput
import com.comfyui.mobile.agent.PromptBindingMode
import com.comfyui.mobile.model.AgentChatMessage
import com.comfyui.mobile.model.MediaOutput
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.security.MessageDigest

internal fun workspaceHash(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun workspaceId(vararg parts: String): String =
    workspaceHash(parts.joinToString("\u0000").toByteArray(Charsets.UTF_8))

data class AgentLegacySnapshot(
    val scope: String,
    val messages: List<AgentChatMessage>,
    val media: List<MediaOutput>,
    val promptOutput: AgentPromptOutput,
    val runPromptIds: List<String>,
    val workflowRef: String?,
    val workflowStateJson: String,
    val mode: String,
    val llmProfileId: String?,
    val fingerprint: String,
    val hasUnresolvedConfiguration: Boolean = false
) {
    val hasHistory: Boolean get() = messages.isNotEmpty() || media.isNotEmpty() ||
        runPromptIds.isNotEmpty() || promptOutput.positive.isNotBlank() ||
        !promptOutput.negative.isNullOrBlank() || workflowRef != null || hasUnresolvedConfiguration
}

/** Reads an immutable prefs.all snapshot. Never writes preferences or invents a server identity. */
object AgentLegacyReader {
    private val bases = listOf(
        "agent_messages", "agent_latest_media_list", "agent_prompt_output",
        "agent_last_prompt", "agent_run_prompt_ids", "agent_wf_source", "agent_wf_id", "agent_wf_label"
    )

    fun read(values: Map<String, *>, gson: Gson = Gson(), onlyScope: String? = null): List<AgentLegacySnapshot> {
        val scopes = sortedSetOf<String>()
        for (key in values.keys) for (base in bases) {
            if (key.startsWith("${base}__")) {
                val scope = key.removePrefix("${base}__")
                if (onlyScope != null && onlyScope != scope) continue
                require(scope == "local" || scope.matches(Regex("cloud@[a-zA-Z0-9._-]+"))) { "Invalid legacy scope" }
                scopes += scope
            }
        }
        if (bases.any { values.containsKey(it) } || values.containsKey("agent_wf_id_local") ||
            values.containsKey("agent_wf_id_cloud")) scopes += "local"
        return scopes.filter { onlyScope == null || it == onlyScope }.map { scope ->
            val used = sortedMapOf<String, Any?>()
            fun value(key: String): Any? = values[key].also {
                if (it != null) used[key] = if (it is Set<*>) it.map { v -> v.toString() }.sorted() else it
            }
            fun string(key: String): String? {
                val raw = value(key) ?: return null
                require(raw is String) { "Invalid legacy value type: $key" }
                return raw
            }
            fun scoped(base: String): String? = string("${base}__$scope")
                ?: if (scope == "local") string(base) else null
            fun parsed(raw: String?, objectOnly: Boolean = false): JsonElement? =
                raw?.takeIf { it.isNotBlank() }?.let {
                    JsonParser.parseString(it).also { item ->
                        require(if (objectOnly) item.isJsonObject else item.isJsonArray) { "Invalid legacy JSON shape" }
                    }
                }
            val messages = parsed(scoped("agent_messages"))?.let {
                gson.fromJson<List<AgentChatMessage>>(it, object : TypeToken<List<AgentChatMessage>>() {}.type)
            }.orEmpty().map {
                require(it.id.isNotBlank() && it.role.isNotBlank()) { "Invalid legacy message identity" }
                it.copy(isThinking = false)
            }
            require(messages.map { it.id }.distinct().size == messages.size) { "Duplicate legacy message identity" }
            val media = parsed(scoped("agent_latest_media_list"))?.let {
                gson.fromJson<List<MediaOutput>>(it, object : TypeToken<List<MediaOutput>>() {}.type)
            }.orEmpty()
            val prompt = parsed(scoped("agent_prompt_output"), true)?.let {
                val p = it.asJsonObject
                fun text(name: String): String? = p[name]?.takeUnless { v -> v.isJsonNull }?.let { v ->
                    require(v.isJsonPrimitive && v.asJsonPrimitive.isString) { "Invalid legacy prompt field" }
                    v.asString
                }
                AgentPromptOutput(text("positive").orEmpty(), text("negative"), text("negativeStatus"))
            } ?: AgentPromptOutput(scoped("agent_last_prompt").orEmpty())
            val idsValue = value("agent_run_prompt_ids__$scope")
                ?: if (scope == "local") value("agent_run_prompt_ids") else null
            require(idsValue == null || idsValue is Set<*>) { "Invalid legacy task IDs" }
            val ids = idsValue?.map {
                require(it is String && it.isNotBlank()) { "Invalid legacy task ID" }; it
            }?.sorted().orEmpty()
            val source = scoped("agent_wf_source") ?: if (scope == "local")
                string("agent_wf_source_local") else null
            val id = scoped("agent_wf_id") ?: if (scope == "local")
                string("agent_wf_id_local") else null
            val label = scoped("agent_wf_label").orEmpty()
            val portableId = if (source == "saved") id?.replace('\\', '/')?.substringAfterLast('/') else id
            val workflow = if (!source.isNullOrBlank() && !portableId.isNullOrBlank()) "$source:$portableId" else null
            val legacyCloudSource = if (scope == "local") string("agent_wf_source_cloud") else null
            val legacyCloudId = if (scope == "local") string("agent_wf_id_cloud") else null
            val unresolvedCloud = if (!legacyCloudSource.isNullOrBlank() && !legacyCloudId.isNullOrBlank())
                mapOf("source" to legacyCloudSource, "id" to
                    if (legacyCloudSource == "saved") legacyCloudId.replace('\\', '/').substringAfterLast('/') else legacyCloudId,
                    "origin" to "unknown")
                else null
            val suffix = "${source.orEmpty()}_${id.orEmpty()}"
            fun workflowValue(base: String): String? = string("${base}_${scope}_$suffix")
                ?: if (scope == "local") string("${base}_$suffix") else null
            val parameters = parsed(workflowValue("agent_wf_params"), true)
            fun binding(negative: Boolean): JsonElement {
                val raw = string("agent_prompt_binding_${if (negative) "negative" else "positive"}__${scope}_$suffix")
                if (raw != null) return parsed(raw, true)!!
                val legacyNode = if (!negative) workflowValue("agent_target_node") else null
                return gson.toJsonTree(if (legacyNode.isNullOrBlank()) AgentPromptBinding()
                    else AgentPromptBinding(PromptBindingMode.EXPLICIT, legacyNode))
            }
            val workflowState = gson.toJson(mapOf(
                "source" to source, "id" to portableId, "label" to label,
                "parameters" to (parameters ?: JsonParser.parseString("{}")),
                "positiveBinding" to binding(false), "negativeBinding" to binding(true),
                "artistNodeId" to workflowValue("agent_artist_node").orEmpty(),
                "auxNodeKeys" to parsed(workflowValue("agent_aux_nodes")),
                "unresolvedLegacyCloudWorkflow" to unresolvedCloud
            ))
            val mode = string("agent_interaction_mode")?.takeIf { it == "chat" } ?: "draw"
            val profile = string("current_llm_profile_id")?.takeIf { it.isNotBlank() }
            AgentLegacySnapshot(scope, messages, media, prompt, ids, workflow, workflowState,
                mode, profile, workspaceHash(gson.toJson(used).toByteArray(Charsets.UTF_8)),
                hasUnresolvedConfiguration = unresolvedCloud != null)
        }
    }
}
