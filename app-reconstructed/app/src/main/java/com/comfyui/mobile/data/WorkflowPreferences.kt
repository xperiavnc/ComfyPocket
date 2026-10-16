package com.comfyui.mobile.data

import com.comfyui.mobile.model.MediaOutput
import com.comfyui.mobile.workflow.WorkflowGraph
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns workflow preference keys, legacy fallbacks and JSON conversion.
 *
 * Storage is a small read/write port, not a ViewModel or Android Context, so namespace
 * isolation and migration can be tested on the JVM. A write and its legacy-key removal
 * must be committed together by the storage adapter.
 */
internal class WorkflowPreferences(
    private val read: (String) -> String?,
    private val write: (key: String, json: String, removeLegacyKey: String?) -> Unit,
    private val currentNamespace: () -> String,
    private val gson: Gson = Gson()
) {
    val namespaces = ConcurrentHashMap<String, String>()

    private fun namespace(workflowKey: String): String =
        namespaces[workflowKey] ?: currentNamespace()

    private fun readJson(workflowKey: String, suffix: String): String? {
        val ns = namespace(workflowKey)
        read("workflow@$ns:$workflowKey:$suffix")?.let { return it }
        if (ns.startsWith("cloud@")) {
            read("workflow@cloud:$workflowKey:$suffix")?.let { return it }
        }
        return read("workflow:$workflowKey:$suffix")
    }

    private fun writeJson(workflowKey: String, suffix: String, json: String) {
        val ns = namespace(workflowKey)
        val legacyKey = if (ns.startsWith("cloud@")) "workflow@cloud:$workflowKey:$suffix" else null
        write("workflow@$ns:$workflowKey:$suffix", json, legacyKey)
    }

    fun <T> persistMap(workflowKey: String, suffix: String, map: Map<String, T>) =
        writeJson(workflowKey, suffix, gson.toJson(map))

    fun persistStringList(workflowKey: String, suffix: String, list: List<String>) =
        writeJson(workflowKey, suffix, gson.toJson(list))

    fun persistMediaList(workflowKey: String, suffix: String, list: List<MediaOutput>) =
        writeJson(workflowKey, suffix, gson.toJson(list))

    fun loadStringList(workflowKey: String, suffix: String): MutableList<String> {
        val raw = readJson(workflowKey, suffix) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            val parsed: List<String> = gson.fromJson(raw, type) ?: emptyList()
            parsed.toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun loadMediaList(workflowKey: String, suffix: String): List<MediaOutput> {
        val raw = readJson(workflowKey, suffix) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<MediaOutput>>() {}.type
            gson.fromJson<List<MediaOutput>>(raw, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun loadAnyMap(workflowKey: String, suffix: String): MutableMap<String, Any> {
        val raw = readJson(workflowKey, suffix) ?: return mutableMapOf()
        return try {
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val parsed: Map<String, Any> = gson.fromJson(raw, type) ?: emptyMap()
            val sanitized = WorkflowGraph.sanitizeNumbers(parsed) as? Map<String, Any> ?: emptyMap()
            sanitized.toMutableMap()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    fun loadStringMap(workflowKey: String, suffix: String): MutableMap<String, String> {
        val raw = readJson(workflowKey, suffix) ?: return mutableMapOf()
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            val parsed: Map<String, String> = gson.fromJson(raw, type) ?: emptyMap()
            parsed.toMutableMap()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    fun loadBooleanMap(workflowKey: String, suffix: String): MutableMap<String, Boolean> {
        val raw = readJson(workflowKey, suffix) ?: return mutableMapOf()
        return try {
            val type = object : TypeToken<Map<String, Boolean>>() {}.type
            val parsed: Map<String, Boolean> = gson.fromJson(raw, type) ?: emptyMap()
            parsed.toMutableMap()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }
}
