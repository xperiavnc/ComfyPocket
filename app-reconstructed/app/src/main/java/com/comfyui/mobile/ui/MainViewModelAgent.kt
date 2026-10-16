package com.comfyui.mobile.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.comfyui.mobile.data.LlmClient
import com.comfyui.mobile.data.LlmErrorMapper
import com.comfyui.mobile.data.LlmException
import com.comfyui.mobile.data.SecretStore
import com.comfyui.mobile.model.AgentChatMessage
import com.comfyui.mobile.model.CharacterLore
import com.comfyui.mobile.model.EditableNode
import com.comfyui.mobile.model.LlmProfile
import com.comfyui.mobile.model.LogType
import com.comfyui.mobile.model.MediaOutput
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class AgentLlmResult(
    val tip: String,
    val prompt: String,
    val thinking: String,
    val shouldDraw: Boolean = false,
    /** Set when no usable prompt could be parsed; workflow is left untouched. */
    val parseError: String? = null
    )

object MainViewModelAgent {

    private var agentJob: kotlinx.coroutines.Job? = null
    private var activeAssistantId: String? = null
    @Volatile private var stopRequested = false

    /** Stop button: abort the in-flight LLM request (and the draw job), keep any partial reply. */
    fun stopAgentGeneration(vm: MainViewModel) {
        if (!vm._agentBusy.value) return
        stopRequested = true
        vm.llmClient.cancelActive()
        agentJob?.cancel()
        activeAssistantId?.let { id ->
            updateAgentMessage(vm, id, persist = true) {
                val partial = it.content.takeIf { c -> c.isNotBlank() && c != "✨深度求索中..." }
                it.copy(content = partial ?: "已停止", isThinking = false, errorMessage = "已停止")
            }
        }
        vm._agentBusy.value = false
    }


    fun setAgentInteractionMode(vm: MainViewModel, mode: String) {
        val next = when (mode) {
            MainViewModel.AGENT_MODE_CHAT -> MainViewModel.AGENT_MODE_CHAT
            else -> MainViewModel.AGENT_MODE_DRAW
        }
        vm._agentInteractionMode.value = next
        vm.prefs.edit().putString("agent_interaction_mode", next).apply()
    }

    /** Upload staged image into the first Agent ImageInput node. Returns false if upload required but failed. */
    suspend fun uploadAgentPendingImageIfAny(vm: MainViewModel): Boolean {
        val uriStr = vm._agentPendingAttachments.value.firstOrNull { it.isImage }?.uri ?: return true
        val uri = runCatching { android.net.Uri.parse(uriStr) }.getOrNull() ?: return false
        val imageNode = vm._agentEditableNodes.value
            .filterIsInstance<EditableNode.ImageInput>()
            .firstOrNull()
        if (imageNode == null) {
            vm.addLog("Agent 附图：工作流无 Image 节点，已忽略上传", LogType.WARNING)
            return true
        }
        return try {
            val context = vm.getApplication<Application>()
            val mimeType = context.contentResolver.getType(uri)
            val uploadName = com.comfyui.mobile.ui.UploadPreviewStore.uploadFileName(
                vm.getFileName(uri), mimeType
            )
            val dir = File(context.cacheDir, "agent_upload_${System.currentTimeMillis()}").apply { mkdirs() }
            val file = File(dir, uploadName)
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                vm.addLog("Agent 附图：无法读取文件", LogType.ERROR)
                return false
            }
            inputStream.use { input -> java.io.FileOutputStream(file).use { output -> input.copyTo(output) } }

            val value: String? = if (vm.currentServerProfile()?.isRunningHub == true) {
                val backend = vm.currentRunningHubBackend()
                if (backend == null) {
                    vm.showToast("请先切换到 RunningHub 服务器")
                    null
                } else {
                    backend.client.uploadInput(file, mimeType, uploadName).getOrElse { e ->
                        vm.addLog("Agent RH 附图失败: ${com.comfyui.mobile.network.SecretRedactor.redact(e.message)}", LogType.ERROR)
                        vm.showToast("附图上传失败")
                        return false
                    }.also { fileName ->
                        MainViewModelRhWorkflows.recordUploadSite(vm, fileName, backend.site.id)
                        runCatching { vm.uploadPreviewStore.put(fileName, file) }
                    }
                }
            } else {
                val resp = vm.repository.uploadImage(file)
                if (resp == null) {
                    vm.showToast("附图上传失败")
                    null
                } else if (resp.subfolder.isNullOrBlank()) resp.name else "${resp.subfolder}/${resp.name}"
            }
            runCatching { dir.deleteRecursively() }
            if (value == null) return false
            withContext(Dispatchers.Main) {
                updateAgentNodeValue(vm, imageNode.nodeId, imageNode.key, value)
            }
            vm.addLog("Agent 附图已写入 ${imageNode.nodeId}:${imageNode.key} -> $value", LogType.SUCCESS)
            true
        } catch (e: Exception) {
            vm.addLog("Agent 附图异常: ${e.message}", LogType.ERROR)
            false
        }
    }


    /** local | cloud@cn | cloud@ai — Agent session isolation (LLM / 人设 / 分镜共用). */
    fun scope(vm: MainViewModel): String = vm.currentKindNs()

    fun scopedPref(base: String, vm: MainViewModel): String = "${base}__${scope(vm)}"

    private fun readScopedString(vm: MainViewModel, base: String, vararg fallbacks: String?): String? {
        vm.prefs.getString(scopedPref(base, vm), null)?.let { return it }
        for (f in fallbacks) {
            if (!f.isNullOrBlank()) vm.prefs.getString(f, null)?.let { return it }
        }
        return null
    }


    fun loadLlmProfiles(vm: MainViewModel): List<LlmProfile> {
        val raw = vm.prefs.getString("llm_profiles_v1", null)
        if (!raw.isNullOrBlank()) {
            try {
                val type = object : TypeToken<List<LlmProfile>>() {}.type
                val list: List<LlmProfile>? = vm.gson.fromJson(raw, type)
                if (!list.isNullOrEmpty()) return hydrateAndMigrateLlmKeys(vm, list)
            } catch (_: Exception) {}
        }
        val oldUrl = vm.prefs.getString("llm_base_url", "") ?: ""
        val oldKey = vm.prefs.getString("llm_api_key", "") ?: ""
        val oldModel = vm.prefs.getString("llm_model", "") ?: ""
        val list = mutableListOf<LlmProfile>()
        list.add(
            LlmProfile(
                name = if (oldModel.isNotBlank()) oldModel else "默认配置",
                baseUrl = oldUrl.ifBlank { "https://api.deepseek.com/v1" },
                apiKey = oldKey,
                model = oldModel.ifBlank { "deepseek-v4.1-flash" }
            )
        )
        persistLlmProfiles(vm, list)
        return list
    }

    fun persistLlmProfiles(vm: MainViewModel, list: List<LlmProfile>) {
        val store = SecretStore.get(vm.getApplication())
        for (p in list) {
            if (p.id.isNotBlank()) store.putLlmKey(p.id, p.apiKey)
        }
        val persisted = list.map { it.copy(apiKey = "") }
        vm.prefs.edit()
            .putString("llm_profiles_v1", vm.gson.toJson(persisted))
            .remove("llm_api_key")
            .apply()
    }

    private fun hydrateAndMigrateLlmKeys(vm: MainViewModel, parsed: List<LlmProfile>): List<LlmProfile> {
        val store = SecretStore.get(vm.getApplication())
        var migrated = false
        val hydrated = parsed.map { p ->
            val fromStore = store.getLlmKey(p.id)
            when {
                !fromStore.isNullOrBlank() -> p.copy(apiKey = fromStore)
                p.apiKey.isNotBlank() -> {
                    store.putLlmKey(p.id, p.apiKey)
                    migrated = true
                    p
                }
                else -> p
            }
        }
        if (migrated) {
            val stripped = hydrated.map { it.copy(apiKey = "") }
            vm.prefs.edit()
                .putString("llm_profiles_v1", vm.gson.toJson(stripped))
                .remove("llm_api_key")
                .apply()
        }
        return hydrated
    }

    fun initialLlmValue(vm: MainViewModel, key: String, selector: (LlmProfile) -> String): String {
        val saved = vm.prefs.getString(key, "") ?: ""
        if (saved.isNotBlank()) return saved
        val active = vm._llmProfiles.value.firstOrNull { it.id == vm._currentLlmProfileId.value }
            ?: vm._llmProfiles.value.firstOrNull()
        return active?.let(selector).orEmpty()
    }

    private fun ensurePromptSplitMigration(vm: MainViewModel) {
        val migrated = vm.prefs.getBoolean("agent_prompt_v2_migrated", false)
        if (migrated) return

        val hasDrawPrompt = vm.prefs.contains("agent_draw_prompt")
        if (!hasDrawPrompt) {
            val oldSys = vm.prefs.getString("agent_system_prompt", null)
            if (!oldSys.isNullOrBlank() && oldSys.contains("动态插图分镜导演")) {
                vm.prefs.edit()
                    .putString("agent_draw_prompt", oldSys)
                    .putString("agent_system_prompt", MainViewModel.DEFAULT_AGENT_SYSTEM_PROMPT)
                    .putBoolean("agent_prompt_v2_migrated", true)
                    .apply()
                return
            }
        }
        vm.prefs.edit().putBoolean("agent_prompt_v2_migrated", true).apply()
    }

    fun loadAgentSystemPrompt(vm: MainViewModel): String {
        ensurePromptSplitMigration(vm)
        val saved = vm.prefs.getString("agent_system_prompt", null)?.takeIf { it.isNotBlank() }
        return saved ?: MainViewModel.DEFAULT_AGENT_SYSTEM_PROMPT
    }

    fun loadAgentDrawPrompt(vm: MainViewModel): String {
        ensurePromptSplitMigration(vm)
        val saved = vm.prefs.getString("agent_draw_prompt", null)?.takeIf { it.isNotBlank() }
        return saved ?: MainViewModel.DEFAULT_AGENT_DRAW_PROMPT
    }

    fun sanitizeThinkingContent(raw: String): String {
        var text = raw.trim()
        val thinkBlockRegexes = listOf(
            Regex("""(?is)<think\b[^>]*>.*?</think>"""),
            Regex("""(?is)<thought\b[^>]*>.*?</thought>"""),
            Regex("""(?is)<reasoning\b[^>]*>.*?</reasoning>"""),
            Regex("""(?is)<reflection\b[^>]*>.*?</reflection>"""),
            Regex("""(?is)\[(?:think|thinking|thought|reasoning)\].*?\[/(?:think|thinking|thought|reasoning)\]""")
    )
        for (r in thinkBlockRegexes) {
            text = text.replace(r, "").trim()
        }
        text = text.replace(Regex("""(?is)^.*?</(?:think|thought|reasoning|reflection)>\s*"""), "")
            .replace(Regex("""(?is)<(?:think|thought|reasoning|reflection)\b[^>]*>.*$"""), "")
            .trim()
        return text
    }

    fun loadAgentMediaList(vm: MainViewModel): List<MediaOutput> {
        val raw = readScopedString(vm, "agent_latest_media_list") ?: return emptyList()
        return try {
            val type = object : TypeToken<List<MediaOutput>>() {}.type
            vm.gson.fromJson<List<MediaOutput>>(raw, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun persistAgentMediaList(vm: MainViewModel, list: List<MediaOutput>) {
        vm.prefs.edit().putString(scopedPref("agent_latest_media_list", vm), vm.gson.toJson(list)).apply()
    }

    fun loadFavoriteArtists(vm: MainViewModel): List<String> {
        val raw = readScopedString(vm, "agent_favorite_artists", "agent_favorite_artists")
            ?: return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            // Empty list [] must stay empty — do not re-seed defaults.
            vm.gson.fromJson<List<String>>(raw, type)?.filter { it.isNotBlank() }?.distinct() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addFavoriteArtist(vm: MainViewModel, artist: String) {
        val clean = artist.trim().trim('"', '\'').trim()
        if (clean.isBlank()) return
        val current = vm._favoriteArtists.value.toMutableList()
        if (!current.contains(clean)) {
            current.add(0, clean)
            vm._favoriteArtists.value = current
            vm.prefs.edit().putString(scopedPref("agent_favorite_artists", vm), vm.gson.toJson(current)).apply()
            vm.showToast("已加入候选画师: $clean")
        } else {
            vm.showToast("画师已在候选列表中")
        }
    }

    fun removeFavoriteArtist(vm: MainViewModel, artist: String) {
        val clean = artist.trim()
        val current = vm._favoriteArtists.value.toMutableList()
        if (current.remove(clean)) {
            vm._favoriteArtists.value = current
            vm.prefs.edit().putString(scopedPref("agent_favorite_artists", vm), vm.gson.toJson(current)).apply()
            vm.showToast("已移除画师: $clean")
        }
    }

    fun getAgentTargetNodePrefKey(vm: MainViewModel, source: String = vm._agentWorkflowSource.value, id: String = vm._agentWorkflowId.value): String {
        return "agent_target_node_${scope(vm)}_${source}_${id}"
    }

    fun setAgentTargetNodeId(vm: MainViewModel, nodeId: String) {
        val trimmed = nodeId.trim()
        vm._agentTargetNodeId.value = trimmed
        val key = getAgentTargetNodePrefKey(vm)
        if (key.isNotBlank()) {
            if (trimmed.isBlank()) {
                vm.prefs.edit().remove(key).apply()
            } else {
                vm.prefs.edit().putString(key, trimmed).apply()
            }
        }
        vm.addLog("Agent 提示词节点已设置为: ${if (trimmed.isBlank()) "自动" else "#$trimmed"}", LogType.INFO)
    }

    fun readTargetNodeId(vm: MainViewModel, source: String, id: String): String {
        val scoped = getAgentTargetNodePrefKey(vm, source, id)
        return vm.prefs.getString(scoped, null)
            ?: vm.prefs.getString("agent_target_node_${source}_${id}", "")
            ?: ""
    }

    fun getAgentArtistNodePrefKey(vm: MainViewModel, source: String = vm._agentWorkflowSource.value, id: String = vm._agentWorkflowId.value): String {
        return "agent_artist_node_${scope(vm)}_${source}_${id}"
    }

    fun setAgentArtistNodeId(vm: MainViewModel, nodeId: String) {
        val trimmed = nodeId.trim()
        vm._agentArtistNodeId.value = trimmed
        val key = getAgentArtistNodePrefKey(vm)
        if (key.isNotBlank()) {
            if (trimmed.isBlank()) {
                vm.prefs.edit().remove(key).apply()
            } else {
                vm.prefs.edit().putString(key, trimmed).apply()
            }
        }
        vm.addLog("Agent 画师节点已设置为: ${if (trimmed.isBlank()) "未指定" else "#$trimmed"}", LogType.INFO)
    }

    fun readArtistNodeId(vm: MainViewModel, source: String, id: String): String {
        val scoped = getAgentArtistNodePrefKey(vm, source, id)
        return vm.prefs.getString(scoped, null)
            ?: vm.prefs.getString("agent_artist_node_${source}_${id}", "")
            ?: ""
    }

    fun getAgentAuxNodesPrefKey(vm: MainViewModel, source: String, id: String): String {
        return "agent_aux_nodes_${scope(vm)}_${source}_${id}"
    }

    fun loadAgentAuxNodes(vm: MainViewModel, source: String, id: String): Set<String> {
        val raw = vm.prefs.getString(getAgentAuxNodesPrefKey(vm, source, id), null)
            ?: vm.prefs.getString("agent_aux_nodes_${source}_${id}", null)
            ?: return emptySet()
        return try {
            val type = object : TypeToken<Set<String>>() {}.type
            vm.gson.fromJson<Set<String>>(raw, type) ?: emptySet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun toggleAgentAuxNode(vm: MainViewModel, compoundKey: String, isChecked: Boolean) {
        val current = vm._agentAuxNodeKeys.value.toMutableSet()
        if (isChecked) current.add(compoundKey) else current.remove(compoundKey)
        vm._agentAuxNodeKeys.value = current
        val key = getAgentAuxNodesPrefKey(vm, vm._agentWorkflowSource.value, vm._agentWorkflowId.value)
        if (key.isNotBlank()) {
            vm.prefs.edit().putString(key, vm.gson.toJson(current)).apply()
        }
    }

    fun parseAgentEditableNodes(vm: MainViewModel, workflow: Map<String, Any>?) {
        if (workflow == null) {
            vm._agentEditableNodes.value = emptyList()
            vm._agentNodeTitles.value = emptyMap()
            return
        }
        vm._agentNodeTitles.value = vm.buildWorkflowNodeTitles(workflow)
        val list = vm.parseWorkflowNodes(
            workflow,
            vm._objectInfo.value,
            emptyMap(),
            emptyMap()
        )
        vm._agentEditableNodes.value = list
    }

    fun updateAgentNodeValue(vm: MainViewModel, nodeId: String, key: String, value: Any) {
        val current = vm.agentWorkflow?.toMutableMap() ?: return
        val node = (current[nodeId] as? Map<String, Any>)?.toMutableMap() ?: return
        val inputs = (node["inputs"] as? Map<String, Any>)?.toMutableMap() ?: return

        val expected = inputs[key]
        if (expected is List<*> && expected.size == 2 && expected[0] is String && expected[1] is Number) {
            vm.addLog("Agent: 尝试更新连线端口 $nodeId:$key，已拦截以保护连线", LogType.WARNING)
            return
        }

        val coerced = vm.coerceToExpectedType(expected, value) ?: value
        inputs[key] = coerced
        node["inputs"] = inputs
        current[nodeId] = node

        vm.agentWorkflow = current
        parseAgentEditableNodes(vm, current)

        val source = vm._agentWorkflowSource.value
        val id = vm._agentWorkflowId.value
        if (source.isNotBlank() && id.isNotBlank()) {
            vm.agentWorkflowParamValues["$nodeId:$key"] = coerced
            persistAgentWorkflowParams(vm, source, id)
        }
    }

    fun getAgentWorkflowParamStorageKey(vm: MainViewModel, source: String, id: String): String =
        "agent_wf_params_${scope(vm)}_${source}_${id}"

    fun persistAgentWorkflowParams(vm: MainViewModel, source: String = vm._agentWorkflowSource.value, id: String = vm._agentWorkflowId.value) {
        if (source.isBlank() || id.isBlank()) return
        val key = getAgentWorkflowParamStorageKey(vm, source, id)
        vm.prefs.edit().putString(key, vm.gson.toJson(vm.agentWorkflowParamValues)).apply()
    }

        fun loadAgentWorkflowParams(vm: MainViewModel, source: String, id: String, fallbackWorkflowKey: String? = null): MutableMap<String, Any> {
        if (source.isBlank() || id.isBlank()) return mutableMapOf()
        val raw = vm.prefs.getString(getAgentWorkflowParamStorageKey(vm, source, id), null)
            ?: vm.prefs.getString("agent_wf_params_${source}_${id}", null)
        if (raw != null) {
            return try {
                val type = object : TypeToken<Map<String, Any>>() {}.type
                val parsed: Map<String, Any> = vm.gson.fromJson(raw, type) ?: emptyMap()
                val sanitized = vm.sanitizeNumbers(parsed) as? Map<String, Any> ?: emptyMap()
                sanitized.toMutableMap()
            } catch (_: Exception) {
                mutableMapOf()
            }
        }
        if (!fallbackWorkflowKey.isNullOrBlank()) {
            return vm.loadWorkflowAnyMap(fallbackWorkflowKey, "values")
        }
        return mutableMapOf()
    }

    private fun clearAgentWorkflowParams(vm: MainViewModel, source: String, id: String) {
        if (source.isBlank() || id.isBlank()) return
        vm.prefs.edit()
            .remove(getAgentWorkflowParamStorageKey(vm, source, id))
            .remove("agent_wf_params_${source}_${id}")
            .apply()
    }

    /**
     * Manual Agent reload: drop this template's agent_wf_params overrides and re-read the
     * local saved JSON (same file Control overwrite-save writes). Does not run on Control save.
     * Only [MainViewModel.AGENT_WF_SAVED] is supported.
     */
    /**
     * Agent「重置」: write the current Agent graph (with live aux params) into the local
     * saved JSON (same path Control overwrite uses), then clear agent_wf_params and
     * re-read that file so the aux UI matches what was just saved — not the old file.
     */
    fun resetAgentSavedWorkflow(vm: MainViewModel) {
        val source = vm._agentWorkflowSource.value
        val id = vm._agentWorkflowId.value
        if (source.isBlank() || id.isBlank()) {
            vm.showToast("请先选择工作流")
            return
        }
        if (source != MainViewModel.AGENT_WF_SAVED) {
            vm.showToast("仅本地存档工作流可重置")
            return
        }
        val workflow = vm.agentWorkflow
        if (workflow == null) {
            vm.showToast("当前没有可保存的工作流")
            return
        }

        val withJson = if (id.lowercase().endsWith(".json")) id else "$id.json"

        vm.viewModelScope.launch(Dispatchers.IO) {
            try {
                val paramsCopy = vm.agentWorkflowParamValues.toMutableMap()
                val cleanBase = vm.sanitizeNumbers(workflow) as Map<String, Any>
                val (toSave, _) = vm.applyParamOverridesToWorkflow(cleanBase, paramsCopy)

                val dir = MainViewModelWorkflow.savedWorkflowsDir(vm)
                val existing = vm.getLocalSavedWorkflows().firstOrNull {
                    it.name.equals(id, ignoreCase = true) ||
                        it.name.equals(withJson, ignoreCase = true)
                }
                val targetFile = existing ?: File(dir, withJson)
                targetFile.parentFile?.mkdirs()
                targetFile.writeText(vm.gson.toJson(toSave))
                MainViewModelRhWorkflows.markSavedFile(vm, targetFile)
                MainViewModelWorkflow.refreshSavedWorkflows(vm)

                // Clear overrides only after a successful write, then reload from that file.
                withContext(Dispatchers.Main) {
                    clearAgentWorkflowParams(vm, source, id)
                    if (!withJson.equals(id, ignoreCase = true)) {
                        clearAgentWorkflowParams(vm, source, withJson)
                    }
                    vm.agentWorkflowParamValues.clear()
                }

                val content = targetFile.readText()
                val type = object : TypeToken<Map<String, Any>>() {}.type
                val raw: Map<String, Any> = vm.gson.fromJson(content, type)
                val cleanWf = vm.sanitizeNumbers(raw) as Map<String, Any>
                withContext(Dispatchers.Main) {
                    vm.agentWorkflowParamValues.clear()
                    vm.agentWorkflow = cleanWf
                    parseAgentEditableNodes(vm, cleanWf)
                    vm.showToast("已重置并保存")
                    vm.addLog("Agent 已重置并保存: ${targetFile.absolutePath}", LogType.SUCCESS)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    vm.showToast("重置失败: ${e.message}")
                    vm.addLog("Agent 重置失败: ${e.message}", LogType.ERROR)
                }
            }
        }
    }

    fun loadCharacterLores(vm: MainViewModel): List<CharacterLore> {
        val raw = readScopedString(vm, "agent_character_lores", "agent_character_lores") ?: return emptyList()
        return try {
            val type = object : TypeToken<List<CharacterLore>>() {}.type
            vm.gson.fromJson<List<CharacterLore>>(raw, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun persistCharacterLores(vm: MainViewModel, list: List<CharacterLore>) {
        vm.prefs.edit().putString(scopedPref("agent_character_lores", vm), vm.gson.toJson(list)).apply()
    }

    fun loadAgentMessages(vm: MainViewModel): List<AgentChatMessage> {
        val raw = readScopedString(vm, "agent_messages") ?: return emptyList()
        return try {
            val type = object : TypeToken<List<AgentChatMessage>>() {}.type
            vm.gson.fromJson<List<AgentChatMessage>>(raw, type)
                ?.map { it.copy(isThinking = false) }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun persistAgentMessages(vm: MainViewModel) {
        val toSave = vm._agentMessages.value.mapNotNull { msg ->
            if (msg.role == "user") {
                msg.takeIf { it.content.isNotBlank() || !it.attachments.isNullOrEmpty() }
            } else {
                val placeholder = msg.isThinking &&
                    msg.thinkingContent.isNullOrBlank() &&
                    (msg.content.isBlank() || msg.content.startsWith("✨深度求索") || msg.content.startsWith("正在思考"))
                if (placeholder) null
                else msg.copy(isThinking = false)
            }
        }
        vm.prefs.edit().putString(scopedPref("agent_messages", vm), vm.gson.toJson(toSave)).apply()
    }

    fun loadRunPromptIds(vm: MainViewModel): Set<String> {
        return vm.prefs.getStringSet(scopedPref("agent_run_prompt_ids", vm), null)
            ?: vm.prefs.getStringSet("agent_run_prompt_ids", emptySet())
            ?: emptySet()
    }

    fun persistRunPromptIds(vm: MainViewModel, ids: Set<String>) {
        vm.prefs.edit().putStringSet(scopedPref("agent_run_prompt_ids", vm), ids).apply()
    }

    fun toggleCharacterLoreEnabled(vm: MainViewModel, id: String) {
        val list = vm._characterLores.value.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx != -1) {
            val item = list[idx]
            val newEnabled = !item.isEnabled
            list[idx] = item.copy(enabled = newEnabled)
            persistCharacterLores(vm, list)
            vm._characterLores.value = list
            vm.showToast(if (newEnabled) "已启用角色: ${item.name}" else "已停用角色: ${item.name}")
        }
    }

    fun addOrUpdateCharacterLore(
        vm: MainViewModel,
        name: String,
        triggerWords: String,
        existingId: String? = null,
        enabled: Boolean? = null
    ) {
        val cleanName = name.trim()
        val cleanTriggers = triggerWords.trim()
        if (cleanName.isBlank()) return
        val list = vm._characterLores.value.toMutableList()
        if (existingId != null) {
            val idx = list.indexOfFirst { it.id == existingId }
            if (idx != -1) {
                val old = list[idx]
                val targetEnabled = enabled ?: old.isEnabled
                list[idx] = CharacterLore(id = existingId, name = cleanName, triggerWords = cleanTriggers, enabled = targetEnabled)
                vm.showToast("已更新角色设定: $cleanName")
            } else {
                list.add(0, CharacterLore(id = existingId, name = cleanName, triggerWords = cleanTriggers, enabled = enabled ?: true))
                vm.showToast("已添加角色设定: $cleanName")
            }
        } else {
            val existingIdx = list.indexOfFirst { it.name.equals(cleanName, ignoreCase = true) }
            if (existingIdx != -1) {
                val matched = list[existingIdx]
                val targetEnabled = enabled ?: matched.isEnabled
                list[existingIdx] = matched.copy(name = cleanName, triggerWords = cleanTriggers, enabled = targetEnabled)
                vm.showToast("已更新角色设定: $cleanName")
            } else {
                val newId = UUID.randomUUID().toString()
                list.add(0, CharacterLore(id = newId, name = cleanName, triggerWords = cleanTriggers, enabled = enabled ?: true))
                vm.showToast("已添加角色设定: $cleanName")
            }
        }
        persistCharacterLores(vm, list)
        vm._characterLores.value = list
    }

    fun removeCharacterLore(vm: MainViewModel, id: String) {
        val current = vm._characterLores.value.toMutableList()
        val item = current.firstOrNull { it.id == id }
        if (item != null && current.remove(item)) {
            persistCharacterLores(vm, current)
            vm._characterLores.value = current
            vm.showToast("已删除角色设定: ${item.name}")
        }
    }

    fun preloadAgentWorkflow(vm: MainViewModel) {
        // Re-entering Agent must not reload session from disk; that used to wipe assistant
        // replies (thinking ticks persisted a user-only list).
        if (vm.agentWorkflow != null || vm._agentMessages.value.isNotEmpty()) {
            if (vm.agentWorkflow == null &&
                vm._agentWorkflowSource.value.isNotBlank() &&
                vm._agentWorkflowId.value.isNotBlank()
            ) {
                vm.viewModelScope.launch(Dispatchers.IO) {
                    ensureAgentWorkflowLoaded(vm, silent = true)
                }
            }
            return
        }
        reloadAgentWorkflowForMode(vm)
    }

    fun reloadAgentWorkflowForMode(vm: MainViewModel) {
        try {
            val s = scope(vm)
            val isLocal = s == "local"
            val source = readScopedString(
                vm,
                "agent_wf_source",
                if (isLocal) "agent_wf_source_local" else "agent_wf_source_cloud",
                if (isLocal) "agent_wf_source" else null
    ).orEmpty()
            val id = readScopedString(
                vm,
                "agent_wf_id",
                if (isLocal) "agent_wf_id_local" else "agent_wf_id_cloud",
                if (isLocal) "agent_wf_id" else null
    ).orEmpty()
            val rawLabel = readScopedString(
                vm,
                "agent_wf_label",
                if (isLocal) "agent_wf_label_local" else "agent_wf_label_cloud",
                if (isLocal) "agent_wf_label" else null
    ).orEmpty()
            val label = rawLabel.removeSuffix(".json").removeSuffix(".JSON")
            vm._agentWorkflowSource.value = source
            vm._agentWorkflowId.value = id
            vm._agentWorkflowLabel.value = label
            vm._agentTargetNodeId.value = readTargetNodeId(vm, source, id)
            vm._agentArtistNodeId.value = readArtistNodeId(vm, source, id)
            vm._agentAuxNodeKeys.value = loadAgentAuxNodes(vm, source, id)
            vm._agentMessages.value = loadAgentMessages(vm)
            vm._agentLastExpandedPrompt.value = sanitizeThinkingContent(
                readScopedString(vm, "agent_last_prompt", "agent_last_prompt").orEmpty()
            )
            vm._agentLatestGenerationList.value = loadAgentMediaList(vm)
            vm.agentRunPromptIds.clear()
            vm.agentRunPromptIds.addAll(loadRunPromptIds(vm))
            vm._favoriteArtists.value = loadFavoriteArtists(vm)
            vm._characterLores.value = loadCharacterLores(vm)
            vm.viewModelScope.launch(Dispatchers.IO) {
                if (source.isNotBlank() && id.isNotBlank()) {
                    ensureAgentWorkflowLoaded(vm, silent = true)
                } else {
                    withContext(Dispatchers.Main) {
                        vm.agentWorkflow = null
                        parseAgentEditableNodes(vm, null)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainViewModelAgent", "reloadAgentWorkflowForMode failed", e)
        }
    }

    fun createNewLlmProfile(vm: MainViewModel): LlmProfile {
        val id = UUID.randomUUID().toString()
        val newProfile = LlmProfile(
            id = id,
            name = "新配置",
            baseUrl = "https://api.deepseek.com/v1",
            apiKey = "",
            model = "deepseek-v4.1-flash"
        )
        val list = vm._llmProfiles.value + newProfile
        persistLlmProfiles(vm, list)
        vm._llmProfiles.value = list
        selectLlmProfile(vm, id)
        return newProfile
    }

    fun selectLlmProfile(vm: MainViewModel, id: String) {
        val target = vm._llmProfiles.value.firstOrNull { it.id == id } ?: return
        vm._currentLlmProfileId.value = id
        vm.prefs.edit().putString("current_llm_profile_id", id).apply()
        vm._llmBaseUrl.value = target.baseUrl
        vm._llmApiKey.value = target.apiKey
        vm._llmModel.value = target.model
        vm.prefs.edit()
            .putString("llm_base_url", target.baseUrl)
            .remove("llm_api_key")
            .putString("llm_model", target.model)
            .apply()
    }

    fun addOrUpdateLlmProfile(vm: MainViewModel, profile: LlmProfile, selectAfterSave: Boolean = true) {
        val list = vm._llmProfiles.value.toMutableList()
        val index = list.indexOfFirst { it.id == profile.id }
        if (index != -1) {
            list[index] = profile
        } else {
            list.add(profile)
        }
        persistLlmProfiles(vm, list)
        vm._llmProfiles.value = list
        if (selectAfterSave || vm._currentLlmProfileId.value == profile.id) {
            selectLlmProfile(vm, profile.id)
        }
    }

    fun deleteLlmProfile(vm: MainViewModel, id: String) {
        val list = vm._llmProfiles.value.filterNot { it.id == id }
        if (list.isEmpty()) {
            vm.showToast("至少需要保留一套模型配置")
            return
        }
        SecretStore.get(vm.getApplication()).removeLlmKey(id)
        persistLlmProfiles(vm, list)
        vm._llmProfiles.value = list
        if (vm._currentLlmProfileId.value == id) {
            selectLlmProfile(vm, list.first().id)
        }
    }

    private fun syncCurrentProfileField(vm: MainViewModel, updater: (LlmProfile) -> LlmProfile) {
        val currId = vm._currentLlmProfileId.value
        val list = vm._llmProfiles.value.map {
            if (it.id == currId) updater(it) else it
        }
        persistLlmProfiles(vm, list)
        vm._llmProfiles.value = list
    }

    fun setLlmBaseUrl(vm: MainViewModel, value: String) {
        vm._llmBaseUrl.value = value
        vm.prefs.edit().putString("llm_base_url", value).apply()
        syncCurrentProfileField(vm) { it.copy(baseUrl = value) }
    }

    fun setLlmApiKey(vm: MainViewModel, value: String) {
        vm._llmApiKey.value = value
        vm.prefs.edit().remove("llm_api_key").apply()
        syncCurrentProfileField(vm) { it.copy(apiKey = value) }
    }

    fun setLlmModel(vm: MainViewModel, value: String) {
        vm._llmModel.value = value
        vm.prefs.edit().putString("llm_model", value).apply()
        syncCurrentProfileField(vm) { it.copy(model = value) }
    }

    fun setAgentSystemPrompt(vm: MainViewModel, value: String) {
        val v = value.trim().ifBlank { MainViewModel.DEFAULT_AGENT_SYSTEM_PROMPT }
        vm._agentSystemPrompt.value = v
        vm.prefs.edit().putString("agent_system_prompt", v).apply()
    }

    fun resetAgentSystemPrompt(vm: MainViewModel) {
        setAgentSystemPrompt(vm, MainViewModel.DEFAULT_AGENT_SYSTEM_PROMPT)
    }

    fun setAgentDrawPrompt(vm: MainViewModel, value: String) {
        val v = value.trim().ifBlank { MainViewModel.DEFAULT_AGENT_DRAW_PROMPT }
        vm._agentDrawPrompt.value = v
        vm.prefs.edit().putString("agent_draw_prompt", v).apply()
    }

    fun resetAgentDrawPrompt(vm: MainViewModel) {
        setAgentDrawPrompt(vm, MainViewModel.DEFAULT_AGENT_DRAW_PROMPT)
    }

    fun setAgentDefaultWorkflow(vm: MainViewModel, source: String, id: String, label: String) {
        val cleanLabel = label.removeSuffix(".json").removeSuffix(".JSON").ifBlank { label }
        vm._agentWorkflowSource.value = source
        vm._agentWorkflowId.value = id
        vm._agentWorkflowLabel.value = cleanLabel
        vm._agentTargetNodeId.value = readTargetNodeId(vm, source, id)
        vm._agentArtistNodeId.value = readArtistNodeId(vm, source, id)
        vm._agentAuxNodeKeys.value = loadAgentAuxNodes(vm, source, id)
        vm.prefs.edit()
            .putString(scopedPref("agent_wf_source", vm), source)
            .putString(scopedPref("agent_wf_id", vm), id)
            .putString(scopedPref("agent_wf_label", vm), cleanLabel)
            .apply()
        vm.showToast("已设置 Agent 模板: $cleanLabel")
        vm.viewModelScope.launch(Dispatchers.IO) {
            ensureAgentWorkflowLoaded(vm)
        }
    }

    fun showUserMessage(vm: MainViewModel, msg: String) {
        vm.showToast(msg)
    }

    fun clearAgentChat(vm: MainViewModel) {
        vm._agentMessages.value = emptyList()
        vm._agentLastExpandedPrompt.value = ""
        vm.clearAgentAttachments()
        vm.prefs.edit()
            .remove(scopedPref("agent_last_prompt", vm))
            .remove(scopedPref("agent_messages", vm))
            .apply()
        runCatching {
            File(vm.getApplication<Application>().filesDir, "agent_attachments").listFiles()?.forEach { it.delete() }
        }
    }

    fun appendAgentMessage(vm: MainViewModel, msg: AgentChatMessage) {
        vm._agentMessages.value = vm._agentMessages.value + msg
        persistAgentMessages(vm)
    }

    fun updateAgentMessage(
        vm: MainViewModel,
        id: String,
        persist: Boolean? = null,
        block: (AgentChatMessage) -> AgentChatMessage
    ) {
        var shouldPersist = false
        vm._agentMessages.value = vm._agentMessages.value.map {
            if (it.id != id) it
            else {
                val next = block(it)
                shouldPersist = persist ?: !next.isThinking
                next
            }
        }
        if (shouldPersist) persistAgentMessages(vm)
    }


    fun findPrimaryPositivePromptTarget(vm: MainViewModel): Pair<String, String>? {
        val workflow = vm.agentWorkflow ?: return null
        val customNodeId = vm._agentTargetNodeId.value.trim()

        if (customNodeId.isNotBlank()) {
            val matchedEditable = vm._agentEditableNodes.value
                .filterIsInstance<EditableNode.TextInput>()
                .firstOrNull { it.nodeId == customNodeId }
            if (matchedEditable != null) {
                return matchedEditable.nodeId to matchedEditable.key
            }

            val nodeData = workflow[customNodeId] as? Map<*, *>
            if (nodeData != null) {
                val inputs = nodeData["inputs"] as? Map<*, *>
                if (inputs != null) {
                    val preferredKeys = listOf("text", "text_g", "text_l", "string", "value")
                    val targetKey = preferredKeys.firstOrNull { k ->
                        val v = inputs[k]
                        v is String || (v != null && v !is List<*>)
                    } ?: inputs.entries.firstOrNull {
                        val v = it.value
                        v is String || (v != null && v !is List<*>)
                    }?.key?.toString()

                    if (targetKey != null) {
                        return customNodeId to targetKey
                    }
                }
            }
            vm.addLog("Agent: 用户指定节点 #$customNodeId 未找到文本输入槽，回退为自动推断", LogType.WARNING)
        }

        data class Cand(val nodeId: String, val key: String, val title: String, val text: String, val score: Int)
        val cands = mutableListOf<Cand>()
        for ((nodeId, nodeData) in workflow) {
            if (nodeData !is Map<*, *>) continue
            val classType = nodeData["class_type"] as? String ?: continue
            if (classType != "CLIPTextEncode" && classType != "CLIPTextEncodeSDXL" &&
                !classType.contains("TextEncode", ignoreCase = true) &&
                classType != "PrimitiveNode"
            ) continue
            val meta = nodeData["_meta"] as? Map<*, *>
            val title = (meta?.get("title") as? String).orEmpty()
            val inputs = nodeData["inputs"] as? Map<*, *> ?: continue

            val keysToCheck = listOf("text", "text_g", "text_l", "string", "value")
            for (key in keysToCheck) {
                if (!inputs.containsKey(key)) continue
                val value = inputs[key]
                if (value is List<*> && value.size == 2 && value[0] is String && value[1] is Number) {
                    continue
                }
                val text = (value as? String).orEmpty()

                var score = 0
                val t = title.lowercase()
                val negativeHints = listOf("negative", "neg", "反向", "负面", "过滤")
                if (negativeHints.any { t.contains(it) }) continue

                if (listOf("描述", "主体", "内容", "人物", "场景", "desc", "subject", "content", "prompt").any { t.contains(it) }) {
                    score += 100
                }
                if (listOf("artist", "画师", "style", "风格", "lora").any { t.contains(it) }) {
                    score -= 50
                }
                if (listOf("quality", "质量", "起手式", "masterpiece").any { t.contains(it) }) {
                    score -= 50
                }

                cands.add(Cand(nodeId, key, title, text, score))
            }
        }

        if (cands.isEmpty()) return null

        val sorted = cands.sortedWith(
            compareByDescending<Cand> { it.score }
                .thenByDescending { it.text.length }
        )
        val pick = sorted.first()
        return pick.nodeId to pick.key
    }

    suspend fun ensureAgentWorkflowLoaded(vm: MainViewModel, silent: Boolean = false): Boolean {
        val source = vm._agentWorkflowSource.value
        val id = vm._agentWorkflowId.value
        if (source.isBlank() || id.isBlank()) {
            return false
        }
        return try {
            when (source) {
                MainViewModel.AGENT_WF_AI_APP -> {
                    val app = vm._aiApps.value.firstOrNull { it.id == id }
                        ?: vm.loadAiAppsFromDisk().firstOrNull { it.id == id }
                    if (app == null) {
                        if (!silent) vm.showToast("未找到 AI 应用")
                        return false
                    }
                    val file = File(vm.aiAppsWorkflowDir(), app.workflowFileName)
                    if (!file.exists()) {
                        if (!silent) vm.showToast("工作流文件不存在")
                        return false
                    }
                    val content = file.readText()
                    val type = object : TypeToken<Map<String, Any>>() {}.type
                    val raw: Map<String, Any> = vm.gson.fromJson(content, type)
                    val workflow = vm.sanitizeNumbers(raw) as Map<String, Any>
                    withContext(Dispatchers.Main) {
                        val paramVals = loadAgentWorkflowParams(vm, source, id, fallbackWorkflowKey = app.workflowKey)
                        vm.agentWorkflowParamValues.clear()
                        vm.agentWorkflowParamValues.putAll(paramVals)
                        val (overridden, toRemove) = vm.applyParamOverridesToWorkflow(workflow, vm.agentWorkflowParamValues)
                        if (toRemove.isNotEmpty()) {
                            persistAgentWorkflowParams(vm, source, id)
                        }
                        vm.agentWorkflow = overridden
                        parseAgentEditableNodes(vm, vm.agentWorkflow)
                    }
                    true
                }
                MainViewModel.AGENT_WF_SAVED -> {
                    val file = vm.getLocalSavedWorkflows().firstOrNull { it.name == id }
                    if (file == null || !file.exists()) {
                        if (!silent) vm.showToast("未找到本地工作流")
                        return false
                    }
                    val content = file.readText()
                    val type = object : TypeToken<Map<String, Any>>() {}.type
                    val raw: Map<String, Any> = vm.gson.fromJson(content, type)
                    withContext(Dispatchers.Main) {
                        val paramVals = loadAgentWorkflowParams(vm, source, id, fallbackWorkflowKey = file.name)
                        vm.agentWorkflowParamValues.clear()
                        vm.agentWorkflowParamValues.putAll(paramVals)
                        val cleanWf = vm.sanitizeNumbers(raw) as Map<String, Any>
                        val (overridden, toRemove) = vm.applyParamOverridesToWorkflow(cleanWf, vm.agentWorkflowParamValues)
                        if (toRemove.isNotEmpty()) {
                            persistAgentWorkflowParams(vm, source, id)
                        }
                        vm.agentWorkflow = overridden
                        parseAgentEditableNodes(vm, vm.agentWorkflow)
                    }
                    true
                }
                else -> {
                    if (!silent) vm.showToast("未知的工作流来源")
                    false
                }
            }
        } catch (e: Exception) {
            if (!silent) vm.showToast("加载模板失败: ${e.message}")
            vm.addLog("Agent 加载模板失败: ${e.message}", LogType.ERROR)
            false
        }
    }

    fun extractThinkingContent(raw: String): String {
        val thinkRegex = Regex("""(?is)<think\b[^>]*>(.*?)(?:</think>|$)""")
        val match = thinkRegex.find(raw)
        if (match != null) {
            val content = match.groupValues[1].trim()
            if (content.isNotBlank()) return content
        }
        val thoughtRegex = Regex("""(?is)<thought\b[^>]*>(.*?)(?:</thought>|$)""")
        val thoughtMatch = thoughtRegex.find(raw)
        if (thoughtMatch != null) {
            val content = thoughtMatch.groupValues[1].trim()
            if (content.isNotBlank()) return content
        }
        val reasoningRegex = Regex("""(?is)<reasoning\b[^>]*>(.*?)(?:</reasoning>|$)""")
        val reasoningMatch = reasoningRegex.find(raw)
        if (reasoningMatch != null) {
            val content = reasoningMatch.groupValues[1].trim()
            if (content.isNotBlank()) return content
        }
        return ""
    }

    suspend fun expandPromptWithLlm(
        vm: MainViewModel,
        userText: String,
        priorHistory: List<AgentChatMessage> = emptyList(),
        imageDataUrls: List<String> = emptyList(),
        onProgress: ((reasoningChunk: String, contentChunk: String, fullReasoning: String, fullContent: String) -> Unit)? = null
    ): Result<AgentLlmResult> {
        val persona = vm._agentSystemPrompt.value.ifBlank { MainViewModel.DEFAULT_AGENT_SYSTEM_PROMPT }
        val drawRules = vm._agentDrawPrompt.value.ifBlank { MainViewModel.DEFAULT_AGENT_DRAW_PROMPT }

        val validHistory = priorHistory.filter { hist ->
            if (hist.role == "user") {
                hist.content.isNotBlank()
            } else if (hist.role == "assistant") {
                !hist.isThinking &&
                !hist.content.startsWith("扩写失败") &&
                !hist.content.startsWith("出错了") &&
                !hist.content.startsWith("请先在右上角") &&
                !hist.content.startsWith("【")
            } else {
                false
            }
        }

        val allLores = vm._characterLores.value
        // 只有处于“已启用”状态的角色才参与匹配；未启用（关闭）的角色彻底不生效，绝不读取
        // 仅当用户当前输入中明确提及了该角色名时才触发注入，不再强制常驻，告别被动绑定
        val matchedLores = allLores.filter { lore ->
            val cleanName = lore.name.trim()
            lore.isEnabled && cleanName.isNotBlank() && userText.contains(cleanName, ignoreCase = true)
        }

        val systemPrompt = buildString {
            appendLine("【一、你的身份与系统人设】")
            appendLine(persona.trim())
            appendLine()

            appendLine("【二、画图分镜规则】")
            appendLine(drawRules.trim())
            appendLine()

            appendLine("【特别连续性法则 - 追问改图必须严格继承】")
            appendLine("- 当用户在多轮对话中提出修改要求（例如更换服装、调整姿势、替换背景、微调表情等）时，必须保持上一轮已确定的角色核心特征（如发色、瞳色、专属饰品、体型）严格一致！")
            appendLine("- 追问改图时，必须在上一轮的 prompt tags 基础上做针对性替换或增删；但若用户明确提出更换角色、不画该角色或绘制全新主体，应以最新指令为准并剥离旧角色特征。")
            appendLine()

            if (matchedLores.isNotEmpty()) {
                appendLine("【三、已匹配的角色视觉设定档案（深度学习与情境融合准则）】")
                appendLine("当前剧情涉及以下角色设定，请深入理解其角色特征，按如下认知规则进行智能学习、融合与取舍：")
                matchedLores.forEach { lore ->
                    appendLine("- 角色「${lore.name}」视觉档案：${lore.triggerWords.trim()}")
                }
                appendLine("1. 固有特征（绝对不可变）：如发色、瞳色、专属发饰/标志、LoRA 唤醒词，无论换装或更换场景，必须永久保留！")
                appendLine("2. 情境特征（智能替换与取舍）：若角色档案包含默认服装、配剑或站姿，当用户提出特定情境要求（例如换成睡衣、泳装、躺在床上睡觉等）时，必须智能剥离冲突的旧服装与旧道具，替换为符合当前剧情的新衣物与新姿态，严禁机械死板叠加（例如严禁让比基尼与厚重铠甲共存）！")
                appendLine()
            }

            appendLine("【四、是否立即出图（should_draw）——必填布尔，勿省略】")
            appendLine("true（立刻渲染）——只要用户是在「要一张图 / 改一张图」，就 true，例如：")
            appendLine("  · 描述场景、角色、服装、姿势、风格并希望看到画面（即使没写「画」「生成」）")
            appendLine("  · 改图 / 修图 / 再画 / 出图 / 画一张 / 生成 / 加点 / 去掉 / 立绘 / 壁纸")
            appendLine("  · 换装 / 换个服装 / 换衣服 / 换发型 / 换背景 / 换姿势 / 「把…改成…」「换成…」")
            appendLine("  · 追问改上一张图、对附图提出修改")
            appendLine("false——仅当明确不要渲染，或纯文字闲聊/问答：")
            appendLine("  · 只要提示词 / 先别出图 / 先别画 / 不要生成 / 解释 tags 含义")
            appendLine("  · 你好、这是什么模型、怎么用、纯闲聊且无画面描述")
            appendLine("原则：创意出图场景默认 true；拿不准时选 true。只有明确只要文字或非视觉闲聊才 false。")
            appendLine()
            appendLine("【五、输出格式规范】")
            appendLine("只输出一个 JSON 对象：不要 markdown 代码块（```）、不要任何解释或前后缀文字，三个字段都要有：")
            appendLine("{")
            appendLine("""  "reply": "用搭档口吻的一句中文回复（严禁客服套话如'正在提交出图'）",""")
            appendLine("""  "prompt": "纯英文 Danbooru tags，逗号分隔，无中文废话",""")
            appendLine("""  "should_draw": true""")
            appendLine("}")
            appendLine("示例A（改服饰→true）：{\"reply\":\"好，换成校服风。\",\"prompt\":\"1girl, school uniform, ...\",\"should_draw\":true}")
            appendLine("示例B（描述场景→true）：{\"reply\":\"雨中撑伞，来了。\",\"prompt\":\"1girl, umbrella, rain, ...\",\"should_draw\":true}")
            appendLine("示例C（只要提示词→false）：{\"reply\":\"这版 tags 先给你。\",\"prompt\":\"1girl, ...\",\"should_draw\":false}")
        }

        val messages = mutableListOf<com.comfyui.mobile.network.ChatMessageDto>()
        messages.add(com.comfyui.mobile.network.ChatMessageDto("system", systemPrompt))

        val recentHistory = validHistory.takeLast(4)
        val lastAssistantIdx = recentHistory.indexOfLast { it.role == "assistant" }
        for ((idx, hist) in recentHistory.withIndex()) {
            if (hist.role == "user") {
                messages.add(com.comfyui.mobile.network.ChatMessageDto("user", hist.content))
            } else if (hist.role == "assistant") {
                val promptForHistory = if (idx == lastAssistantIdx) hist.expandedPrompt.orEmpty() else ""
                val assistantJson = buildString {
                    append("{")
                    append(""""reply": ${vm.gson.toJson(hist.content)}""")
                    if (promptForHistory.isNotBlank()) {
                        append(""", "prompt": ${vm.gson.toJson(promptForHistory)}""")
                    }
                    append("}")
                }
                messages.add(com.comfyui.mobile.network.ChatMessageDto("assistant", assistantJson))
            }
        }

        messages.add(com.comfyui.mobile.network.ChatMessageDto("user", userText.trim()))

        val totalChars = messages.sumOf { (it.content ?: "").length }
        vm.addLog(
            "Agent Prompt 准备就绪: 总字符数=${totalChars}, 激活角色=${if (matchedLores.isEmpty()) "无" else matchedLores.map { it.name }.joinToString()}",
            LogType.INFO
        )

        val result = if (onProgress != null) {
            vm.llmClient.chatCompletionsStream(
                baseUrl = vm._llmBaseUrl.value,
                apiKey = vm._llmApiKey.value,
                model = vm._llmModel.value,
                messages = messages,
                images = imageDataUrls,
                onProgress = onProgress
            )
        } else {
            vm.llmClient.chatCompletions(
                baseUrl = vm._llmBaseUrl.value,
                apiKey = vm._llmApiKey.value,
                model = vm._llmModel.value,
                messages = messages,
                images = imageDataUrls
    )
        }
        return result.map { output ->
            parseAgentLlmReply(vm, output.content, output.reasoning, userText = userText)
        }
    }

    private fun unescapeJsonString(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val next = s[i + 1]) {
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append('\u000c'); i += 2 }
                    'u' -> {
                        if (i + 5 < s.length) {
                            val hex = s.substring(i + 2, i + 6)
                            val code = hex.toIntOrNull(16)
                            if (code != null) {
                                sb.append(code.toChar())
                                i += 6
                            } else {
                                sb.append("\\u")
                                i += 2
                            }
                        } else {
                            sb.append("\\u")
                            i += 2
                        }
                    }
                    else -> { sb.append(next); i += 2 }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    fun extractStreamingReply(raw: String): String? {
        if (raw.isBlank()) return null
        var clean = raw.trim()
        if (clean.startsWith("```")) {
            clean = clean.replace(Regex("""(?is)^```[a-zA-Z0-9_-]*\s*"""), "")
        }
        val keyMatch = Regex("""(?i)"(?:reply|tip|message)"\s*:\s*"""").find(clean)
        if (keyMatch != null) {
            val start = keyMatch.range.last + 1
            var end = -1
            var i = start
            while (i < clean.length) {
                val c = clean[i]
                if (c == '\\') {
                    i += 2
                    continue
                }
                if (c == '"') {
                    end = i
                    break
                }
                i++
            }
            val replyContent = if (end != -1) {
                clean.substring(start, end)
            } else {
                clean.substring(start)
            }
            val unescaped = unescapeJsonString(replyContent).trim()
            if (unescaped.isNotEmpty()) return unescaped
        }

        val tipPrefix = Regex("""(?im)(?:^|[\r\n]+)\s*[\*#\-_\[\(]*(?:tip|reply|回复|提示)[\*#\-_\]\)]*\s*[:：]?\s*""").find(clean)
        if (tipPrefix != null) {
            val afterPrefix = clean.substring(tipPrefix.range.last + 1)
            val promptPrefix = Regex("""(?im)(?:^|[\r\n]+)\s*[\*#\-_\[\(]*(?:prompt|tags|正面提示词|提示词)[\*#\-_\]\)]*\s*[:：]?\s*""").find(afterPrefix)
            val replyPart = if (promptPrefix != null) {
                afterPrefix.substring(0, promptPrefix.range.first)
            } else {
                afterPrefix
            }
            val trimmed = replyPart.trim()
            if (trimmed.isNotEmpty()) return trimmed
        }

        if (!clean.contains('{') && !clean.startsWith("[")) {
            val promptPrefix = Regex("""(?im)(?:^|[\r\n]+)\s*[\*#\-_\[\(]*(?:prompt|tags|正面提示词|提示词)[\*#\-_\]\)]*\s*[:：]?\s*""").find(clean)
            val replyPart = if (promptPrefix != null) {
                clean.substring(0, promptPrefix.range.first)
            } else {
                clean
            }
            val lines = replyPart.lines()
            val chineseLines = lines.filter { line -> line.any { c -> c in '\u4e00'..'\u9fa5' } }
            if (chineseLines.isNotEmpty()) {
                return chineseLines.joinToString("\n").trim()
            }
        }

        return null
    }

    private fun resolveShouldDraw(
        llmFlag: Boolean?,
        userText: String,
        promptNonEmpty: Boolean
    ): Pair<Boolean, String> =
        com.comfyui.mobile.data.AgentDrawIntent.resolveShouldDraw(
            llmFlag = llmFlag,
            userText = userText,
            promptNonEmpty = promptNonEmpty,
            preferDrawWhenAmbiguous = true
        )

    fun parseAgentLlmReply(
        vm: MainViewModel,
        raw: String,
        apiReasoning: String? = null,
        userText: String = ""
    ): AgentLlmResult {
        val thinking = (apiReasoning?.takeIf { it.isNotBlank() } ?: extractThinkingContent(raw)).trim()
        val parsed = com.comfyui.mobile.data.AgentReplyParser.parse(sanitizeThinkingContent(raw))
        val lastPrompt = vm._agentLastExpandedPrompt.value.trim().ifBlank {
            vm._agentMessages.value.asReversed().firstOrNull {
                it.role == "assistant" && !it.expandedPrompt.isNullOrBlank()
            }?.expandedPrompt.orEmpty()
        }
        val recovery = com.comfyui.mobile.data.AgentDrawIntent.recoverPromptIfNeeded(
            parsedPrompt = parsed.prompt,
            userText = userText,
            lastPrompt = lastPrompt
        )
        val prompt = recovery.prompt
        // Recovered edit turns must draw; otherwise use normal Draw-mode bias.
        val llmFlagForDraw = when {
            recovery.recoveredFromLast -> true
            else -> parsed.shouldDraw
        }
        val (draw, reason) = resolveShouldDraw(
            llmFlagForDraw,
            userText,
            promptNonEmpty = prompt.isNotEmpty()
        )
        vm.addLog(
            "Agent should_draw decision: $draw (reason=$reason, llm=${parsed.shouldDraw}, recover=${recovery.reason}, user=${userText.take(40)})",
            LogType.INFO
    )
        if (parsed.error != null) {
            vm.addLog("Agent 提示词解析: ${parsed.error}（原始 ${raw.length} 字）", LogType.WARNING)
        }
        if (recovery.recoveredFromLast) {
            vm.addLog("Agent prompt 空，已沿用上一轮 tags (${recovery.reason})", LogType.WARNING)
        }
        return AgentLlmResult(
            tip = parsed.reply,
            prompt = prompt,
            thinking = thinking,
            shouldDraw = draw,
            parseError = if (prompt.isNotEmpty()) null else parsed.error
    )
    }

    fun sanitizeAgentReply(reply: String): String {
        var text = sanitizeThinkingContent(reply).trim()
        val phrasesToRemove = listOf(
            "已为您提交生图", "已为您提交改图", "已为您提交出图", "已为您出图", "已为您生成",
            "正在为您提交生图", "正在为您提交改图", "正在为您提交出图", "正在为您出图", "正在为您生成",
            "已提交生图", "已提交改图", "已提交出图", "已提交生成",
            "正在提交生图", "正在提交改图", "正在提交出图", "正在提交生成",
            "这就为您提交生图", "这就为您提交改图", "这就为您出图", "这就为您生成",
            "正在生成中", "请稍等片刻", "请稍等", "请稍候"
        )
        for (p in phrasesToRemove) {
            text = text.replace(p, "").trim()
        }
        text = text.replace(Regex("""[，,、：:—\-·\s]+$"""), "").trim()
        return text.ifBlank { "好嘞" }
    }

    private const val MAX_IMAGES = 4
    private const val MAX_TEXT_FILE_BYTES = 100 * 1024
    /** Cap on images re-sent from history so long chats don't explode the request. */
    private const val MAX_CONTEXT_IMAGES = 8
    private const val MAX_CONTEXT_MESSAGES = 20

    /** Downscale to max 1024px long edge, JPEG q85. */
    private fun compressImage(vm: MainViewModel, uri: android.net.Uri, maxEdge: Int = 1024): ByteArray? = runCatching {
        val context = vm.getApplication<Application>()
        val src = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
        val bmp = android.graphics.ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val longEdge = maxOf(w, h)
            if (longEdge > maxEdge) {
                val s = maxEdge.toFloat() / longEdge
                decoder.setTargetSize((w * s).toInt().coerceAtLeast(1), (h * s).toInt().coerceAtLeast(1))
            }
        }
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
        bmp.recycle()
        out.toByteArray()
    }.onFailure { vm.addLog("Agent 附图压缩失败: ${it.message}", LogType.ERROR) }.getOrNull()

    /** Image attachments are stored as already-compressed local JPEGs; encode for OpenAI vision. */
    private fun imageDataUrl(att: com.comfyui.mobile.model.AgentAttachment): String? = runCatching {
        val path = android.net.Uri.parse(att.uri).path ?: return null
        "data:image/jpeg;base64," + android.util.Base64.encodeToString(File(path).readBytes(), android.util.Base64.NO_WRAP)
    }.getOrNull()

    fun addAgentImageAttachments(vm: MainViewModel, uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        vm.viewModelScope.launch(Dispatchers.IO) {
            val dir = File(vm.getApplication<Application>().filesDir, "agent_attachments").apply { mkdirs() }
            for (uri in uris) {
                if (vm._agentPendingAttachments.value.count { it.isImage } >= MAX_IMAGES) {
                    vm.showToast("最多附 $MAX_IMAGES 张图片")
                    break
                }
                val bytes = compressImage(vm, uri) ?: continue
                val file = File(dir, "img_${UUID.randomUUID()}.jpg").apply { writeBytes(bytes) }
                val att = com.comfyui.mobile.model.AgentAttachment(
                    id = UUID.randomUUID().toString(),
                    kind = "image",
                    uri = android.net.Uri.fromFile(file).toString(),
                    name = runCatching { vm.getFileName(uri) }.getOrNull() ?: file.name,
                    sizeBytes = bytes.size.toLong()
    )
                vm._agentPendingAttachments.value = vm._agentPendingAttachments.value + att
            }
        }
    }

    fun addAgentFileAttachments(vm: MainViewModel, uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        vm.viewModelScope.launch(Dispatchers.IO) {
            val context = vm.getApplication<Application>()
            val resolver = context.contentResolver
            val dir = File(context.filesDir, "agent_attachments").apply { mkdirs() }
            for (uri in uris) {
                val name = runCatching { vm.getFileName(uri) }.getOrNull() ?: "file.txt"
                val bytes = runCatching {
                    resolver.openInputStream(uri)?.use { it.readNBytes(MAX_TEXT_FILE_BYTES + 1) }
                }.getOrNull()
                if (bytes == null) { vm.showToast("无法读取 $name"); continue }
                if (bytes.size > MAX_TEXT_FILE_BYTES) { vm.showToast("$name 超过 100KB，未添加"); continue }
                if (bytes.any { it == 0.toByte() }) { vm.showToast("$name 不是文本文件"); continue }
                val text = String(bytes, Charsets.UTF_8)
                val safe = name.replace(Regex("""[^\w.\-]+"""), "_").ifBlank { "file.txt" }
                val file = File(dir, "txt_${UUID.randomUUID()}_$safe").apply { writeBytes(bytes) }
                val att = com.comfyui.mobile.model.AgentAttachment(
                    id = UUID.randomUUID().toString(),
                    kind = "file",
                    uri = android.net.Uri.fromFile(file).toString(),
                    name = name,
                    sizeBytes = bytes.size.toLong(),
                    textContent = text
    )
                vm._agentPendingAttachments.value = vm._agentPendingAttachments.value + att
            }
        }
    }

    /** User text plus inlined text-file attachments. */
    private fun chatText(msg: AgentChatMessage): String = buildString {
        msg.attachments.orEmpty().filter { !it.isImage && it.textContent != null }.forEach { f ->
            append("【附件：").append(f.name).append("】\n```\n").append(f.textContent).append("\n```\n\n")
        }
        append(msg.content)
    }.trim()

    /** Plain ChatGPT-style chat: no draw rules, no JSON format, no workflow. */
    fun sendAgentChatMessage(vm: MainViewModel, userText: String) {
        val text = userText.trim()
        val attachments = vm._agentPendingAttachments.value
        if (text.isEmpty() && attachments.isEmpty()) return
        if (vm._agentBusy.value) return

        val prior = vm._agentMessages.value.toList()
        val userMsg = AgentChatMessage(
            id = "u-${System.currentTimeMillis()}",
            role = "user",
            content = text,
            attachments = attachments.ifEmpty { null }
    )
        appendAgentMessage(vm, userMsg)
        vm.clearAgentAttachments()
        val assistantMsgId = "a-${System.currentTimeMillis()}"
        appendAgentMessage(vm, AgentChatMessage(id = assistantMsgId, role = "assistant", content = "", isThinking = true))

        stopRequested = false
        activeAssistantId = assistantMsgId
        agentJob = vm.viewModelScope.launch(Dispatchers.IO) {
            vm._agentBusy.value = true
            try {
                if (vm._llmBaseUrl.value.isBlank() || vm._llmApiKey.value.isBlank() || vm._llmModel.value.isBlank()) {
                    updateAgentMessage(vm, assistantMsgId, persist = true) {
                        it.copy(content = "请先在右上角 Agent 设置里填写 API Base URL、Key 和模型名。", isThinking = false)
                    }
                    return@launch
                }
                val history = (prior + userMsg)
                    .filter { (it.role == "user" || it.role == "assistant") && !it.isThinking && it.errorMessage == null }
                    .takeLast(MAX_CONTEXT_MESSAGES)

                val persona = vm._agentSystemPrompt.value.ifBlank { MainViewModel.DEFAULT_AGENT_SYSTEM_PROMPT }
                val messages = mutableListOf<com.comfyui.mobile.network.ChatMessageDto>()
                messages.add(com.comfyui.mobile.network.ChatMessageDto("system", persona.trim()))
                history.forEach {
                    messages.add(
                        com.comfyui.mobile.network.ChatMessageDto(
                            it.role,
                            if (it.role == "user") chatText(it) else it.content
                        )
                    )
                }
                // Newest images first until the budget runs out, so follow-ups about older pictures still work.
                // +1 because messages[0] is the system persona prompt.
                val messageImages = mutableMapOf<Int, List<String>>()
                var budget = MAX_CONTEXT_IMAGES
                for (i in history.indices.reversed()) {
                    if (budget <= 0) break
                    val imgs = history[i].attachments.orEmpty().filter { it.isImage }.take(budget).mapNotNull { imageDataUrl(it) }
                    if (imgs.isNotEmpty()) {
                        messageImages[i + 1] = imgs
                        budget -= imgs.size
                    }
                }

                var lastUi = 0L
                val result = vm.llmClient.chatCompletionsStream(
                    baseUrl = vm._llmBaseUrl.value,
                    apiKey = vm._llmApiKey.value,
                    model = vm._llmModel.value,
                    messages = messages,
                    messageImages = messageImages,
                    onProgress = { _, _, fullReasoning, fullContent ->
                        val now = System.currentTimeMillis()
                        if (now - lastUi >= 40L) {
                            lastUi = now
                            vm.viewModelScope.launch(Dispatchers.Main) {
                                updateAgentMessage(vm, assistantMsgId, persist = false) {
                                    it.copy(
                                        content = fullContent,
                                        thinkingContent = fullReasoning.takeIf { r -> r.isNotBlank() },
                                        isThinking = fullContent.isBlank()
    )
                                }
                            }
                        }
                    }
    )
                result.fold(
                    onSuccess = { out ->
                        updateAgentMessage(vm, assistantMsgId, persist = true) {
                            it.copy(content = out.content, thinkingContent = out.reasoning, isThinking = false)
                        }
                    },
                    onFailure = { e ->
                        if (stopRequested) return@launch
                        val llmException = if (e is LlmException) e else LlmErrorMapper.map(e, endpoint = LlmClient.resolveEndpoint(vm._llmBaseUrl.value))
                        vm.addLog("Agent Chat 失败 [${llmException.errorType.title}]: ${llmException.userMessage}", LogType.ERROR)
                        updateAgentMessage(vm, assistantMsgId, persist = true) {
                            it.copy(content = llmException.toDisplayString(), errorMessage = "请求失败", isThinking = false)
                        }
                    }
    )
            } finally {
                vm._agentBusy.value = false
            }
        }
    }

    fun sendAgentMessage(vm: MainViewModel, userText: String) {
        if (vm._agentInteractionMode.value == MainViewModel.AGENT_MODE_CHAT) {
            sendAgentChatMessage(vm, userText)
            return
        }
        val text = userText.trim()
        val pendingAttachments = vm._agentPendingAttachments.value
        if (text.isEmpty() && pendingAttachments.isEmpty()) return
        if (vm._agentBusy.value) return

        // New draw turn replaces any previous ready-to-draw prompt.

        val priorHistory = vm._agentMessages.value.toList()
        appendAgentMessage(
            vm,
            AgentChatMessage(
                id = "u-${System.currentTimeMillis()}",
                role = "user",
                content = text,
                attachments = pendingAttachments.ifEmpty { null }
    )
        )

        val assistantMsgId = "a-${System.currentTimeMillis()}"
        appendAgentMessage(
            vm,
            AgentChatMessage(
                id = assistantMsgId,
                role = "assistant",
                content = "✨深度求索中...",
                isThinking = true
    )
        )

        stopRequested = false
        activeAssistantId = assistantMsgId
        agentJob = vm.viewModelScope.launch(Dispatchers.IO) {
            vm._agentBusy.value = true
            try {
                vm.addLog("Agent 收到: ${text.take(80)}", LogType.INFO)
                if (vm._llmBaseUrl.value.isBlank() || vm._llmApiKey.value.isBlank() || vm._llmModel.value.isBlank()) {
                    updateAgentMessage(vm, assistantMsgId) {
                        it.copy(
                            content = "请先在右上角 Agent 设置里填写 API Base URL、Key 和模型名。",
                            isThinking = false
                        )
                    }
                    return@launch
                }

                var lastStreamUiUpdate = 0L
                val imageDataUrls = pendingAttachments.filter { it.isImage }.mapNotNull { imageDataUrl(it) }
                val llmOut = expandPromptWithLlm(
                    vm = vm,
                    userText = text,
                    priorHistory = priorHistory,
                    imageDataUrls = imageDataUrls,
                    onProgress = { _, contentChunk, fullReasoning, fullContent ->
                        val now = System.currentTimeMillis()
                        // 节流避免超高频刷新 UI，但有新增内容 chunk 或间隔超过 40ms 时及时刷新
                        if (now - lastStreamUiUpdate >= 40L || contentChunk.isNotEmpty()) {
                            lastStreamUiUpdate = now
                            val partialReply = extractStreamingReply(fullContent)
                            val hasContent = !partialReply.isNullOrBlank()
                            vm.viewModelScope.launch(Dispatchers.Main) {
                                updateAgentMessage(vm, assistantMsgId, persist = false) {
                                    it.copy(
                                        content = partialReply?.takeIf { p -> p.isNotBlank() } ?: "✨深度求索中...",
                                        thinkingContent = fullReasoning.takeIf { it.isNotBlank() },
                                        isThinking = !hasContent
    )
                                }
                            }
                        }
                    }
                ).getOrElse { e ->
                    val llmException = if (e is LlmException) e else LlmErrorMapper.map(e, endpoint = LlmClient.resolveEndpoint(vm._llmBaseUrl.value))
                    vm.addLog("Agent LLM 扩写失败 [${llmException.errorType.title}]: ${llmException.userMessage} (endpoint=${llmException.endpoint ?: "未知"})", LogType.ERROR)
                    updateAgentMessage(vm, assistantMsgId, persist = true) {
                        it.copy(
                            content = "扩写失败\n\n" + llmException.toDisplayString(),
                            isThinking = false
                        )
                    }
                    return@launch
                }
                val tip = llmOut.tip.trim()
                val expanded = llmOut.prompt.trim()
                val thinking = llmOut.thinking.trim()
                val cleanTip = sanitizeAgentReply(tip)

                if (expanded.isBlank()) {
                    // No tags from model and no recoverable last prompt — cannot queue.
                    updateAgentMessage(vm, assistantMsgId, persist = true) {
                        it.copy(
                            content = cleanTip.ifBlank { "模型没有返回可用提示词" },
                            thinkingContent = thinking.takeIf { it.isNotBlank() },
                            errorMessage = "未解析到有效提示词，且没有上一轮可继承的 tags，未出图" +
                                (llmOut.parseError?.let { e -> "（$e）" } ?: ""),
                            isThinking = false
                        )
                    }
                    vm.clearAgentAttachments()
                    return@launch
                }
                vm.addLog("Agent 扩写完成 tip=${tip.take(40)} len=${expanded.length} thinkLen=${thinking.length} mode=${vm._agentInteractionMode.value}", LogType.INFO)

                // 固化流式输出结果并落盘持久化
                updateAgentMessage(vm, assistantMsgId, persist = true) {
                    it.copy(
                        content = cleanTip,
                        thinkingContent = thinking.takeIf { it.isNotBlank() },
                        expandedPrompt = expanded,
                        isThinking = false
                    )
                }

                if (!ensureAgentWorkflowLoaded(vm, silent = true)) {
                    updateAgentMessage(vm, assistantMsgId, persist = true) {
                        it.copy(
                            content = cleanTip,
                            errorMessage = "无法加载默认工作流，请到设置里重新选择。",
                            isThinking = false
                        )
                    }
                    return@launch
                }

                // Optional pending image -> ImageInput, then auto-draw when should_draw.
                if (!uploadAgentPendingImageIfAny(vm)) {
                    updateAgentMessage(vm, assistantMsgId, persist = true) {
                        it.copy(
                            content = cleanTip,
                            errorMessage = "附图上传失败，已取消出图",
                            isThinking = false
                        )
                    }
                    return@launch
                }
                vm.clearAgentAttachments()

                val target = withContext(Dispatchers.Main) {
                    findPrimaryPositivePromptTarget(vm)
                }
                if (target == null) {
                    vm.addLog("Agent 找不到可写入的提示词节点", LogType.ERROR)
                    updateAgentMessage(vm, assistantMsgId, persist = true) {
                        it.copy(
                            content = cleanTip,
                            errorMessage = "模板里找不到可写入的提示词节点，请在工作流设置手动选择注入节点。",
                            isThinking = false
                        )
                    }
                    return@launch
                }
                vm.addLog("Agent 写入节点 ${target.first}:${target.second}", LogType.INFO)
                withContext(Dispatchers.Main) {
                    updateAgentNodeValue(vm, target.first, target.second, expanded)
                }

                vm._agentLastExpandedPrompt.value = expanded
                vm.prefs.edit().putString(scopedPref("agent_last_prompt", vm), expanded).apply()

                if (llmOut.shouldDraw) {
                    val enqueueResult = withContext(Dispatchers.Main) {
                        vm.enqueueCurrentWorkflowAwait(1)
                    }
                    enqueueResult.fold(
                        onSuccess = {
                            updateAgentMessage(vm, assistantMsgId, persist = true) {
                                it.copy(content = cleanTip, errorMessage = null)
                            }
                            vm.addLog("Agent 自动出图 (should_draw=true)", LogType.INFO)
                        },
                        onFailure = { e ->
                            updateAgentMessage(vm, assistantMsgId, persist = true) {
                                it.copy(
                                    content = cleanTip,
                                    errorMessage = "提交出图失败：${e.message ?: e.toString()}"
    )
                            }
                        }
    )
                } else {
                    vm.addLog("Agent 跳过出图 (should_draw=false)，仅写入提示词", LogType.INFO)
                }
            } catch (e: Exception) {
                if (stopRequested) return@launch
                val llmException = if (e is LlmException) e else LlmErrorMapper.map(e, endpoint = LlmClient.resolveEndpoint(vm._llmBaseUrl.value))
                val existing = vm._agentMessages.value.firstOrNull { it.id == assistantMsgId }
                val hasReply = existing != null && existing.content.isNotBlank() && existing.content != "✨深度求索中..."
                updateAgentMessage(vm, assistantMsgId, persist = true) {
                    if (hasReply) {
                        it.copy(
                            errorMessage = "出错了：" + (e.message ?: e.toString()),
                            isThinking = false
                        )
                    } else {
                        it.copy(
                            content = "出错了\n\n" + llmException.toDisplayString(),
                            isThinking = false
                        )
                    }
                }
                vm.addLog("Agent 异常 [${llmException.errorType.title}]: ${e.message ?: e.toString()}", LogType.ERROR)
            } finally {
                vm._agentBusy.value = false
            }
        }
    }
}
