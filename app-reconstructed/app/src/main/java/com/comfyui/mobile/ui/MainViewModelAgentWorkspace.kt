package com.comfyui.mobile.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.comfyui.mobile.agent.AgentPromptBinding
import com.comfyui.mobile.agent.AgentPromptOutput
import com.comfyui.mobile.agent.workspace.*
import com.comfyui.mobile.model.AgentAttachment
import com.comfyui.mobile.model.AgentChatMessage
import com.comfyui.mobile.model.LogType
import com.comfyui.mobile.model.MediaOutput
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AgentStorageMode { LOADING, ROOM, LEGACY, BLOCKED, RESTORING }
data class AgentStorageStatus(val mode: AgentStorageMode = AgentStorageMode.LOADING,
    val message: String = "正在读取会话", val conversationId: String? = null) {
    val writable get() = mode == AgentStorageMode.ROOM || mode == AgentStorageMode.LEGACY
}

/**
 * The single production write boundary. Commands capture a conversation before enqueueing.
 * Its IO worker drains accepted writes on ViewModel close; it never waits for the main thread.
 */
internal class MainViewModelAgentWorkspace(private val vm: MainViewModel) {
    private val app = vm.getApplication<Application>()
    private val directory = File(app.filesDir, "agent_workspace")
    val importsDirectory = File(directory, "imports")
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val closed = CompletableDeferred<Unit>()
    private val ticket = AtomicLong()
    private val mutableStatus = MutableStateFlow(AgentStorageStatus())
    val status = mutableStatus.asStateFlow()
    private var db: AgentWorkspaceDatabase? = null
    private val repository: AgentWorkspaceRepository
        get() {
            val database = db ?: AgentWorkspaceDatabase.open(app).also { db = it }
            return AgentWorkspaceRepository(database, directory, File(app.filesDir, "agent_attachments"), vm.gson)
        }
    private data class Loaded(
        val row: AgentConversation, val state: AgentConversationState,
        val messages: List<AgentChatMessage>, val media: List<MediaOutput>, val before: Long,
        val links: List<AgentExecutionLink>, val epoch: Long
    )
    @Volatile private var active: AgentConversation? = null
    @Volatile private var roomActivated = false
    @Volatile private var currentState: AgentConversationState? = null
    @Volatile private var firstSequence = Long.MAX_VALUE
    @Volatile private var savedMessages = emptyList<AgentChatMessage>()
    private val links = ConcurrentHashMap<String, AgentExecutionLink>()
    private val epochs = AtomicLong()
    val callbackEpoch get() = epochs.get()
    private val pendingMedia = ConcurrentHashMap<String, List<MediaOutput>>()
    @Volatile private var lastFailure: Throwable? = null
    private val failedWrites = ArrayDeque<suspend () -> Unit>()
    private val draftRevision = AtomicLong()
    private val persistedConversations = ConcurrentHashMap.newKeySet<String>()
    private val checkpoints = ConcurrentHashMap<String, Long>()
    val hasEarlier = MutableStateFlow(false)
    val draft = MutableStateFlow(AgentInputDraft())
    val ready get() = status.value.writable
    val usesRoom get() = roomActivated && status.value.mode != AgentStorageMode.LEGACY
    val conversationId get() = active?.id
    val activeScope get() = active?.scope

    init {
        worker.launch {
            try { for (command in commands) command() }
            finally { db?.close(); closed.complete(Unit); worker.cancel() }
        }
    }

    private suspend fun <T> serial(block: suspend () -> T): T {
        val result = CompletableDeferred<T>()
        commands.send {
            try { result.complete(block()) } catch (e: Throwable) { result.completeExceptionally(e) }
        }
        return result.await()
    }

    private fun write(block: suspend () -> Unit) {
        if (status.value.mode == AgentStorageMode.RESTORING) return
        val epoch = epochs.get()
        check(commands.trySend {
            if (epoch != epochs.get()) return@trySend
            if (failedWrites.isNotEmpty()) {
                failedWrites.addLast(block)
                return@trySend
            }
            try { block() } catch (e: Throwable) {
                failedWrites.addLast(block)
                lastFailure = e
                mutableStatus.value = AgentStorageStatus(AgentStorageMode.BLOCKED, "会话保存失败，点击重试")
                vm.addLog("Agent 会话保存失败：${e.javaClass.simpleName}", LogType.ERROR)
            }
        }.isSuccess)
    }

    fun requireWritable(): Boolean {
        if (ready) return true
        vm.showToast(status.value.message)
        return false
    }

    suspend fun flush() {
        serial { lastFailure?.let { throw IllegalStateException("会话保存尚未完成，请重试", it) } }
    }

    fun retry() {
        if (lastFailure == null) { reload(); return }
        vm.viewModelScope.launch {
            mutableStatus.value = AgentStorageStatus(AgentStorageMode.LOADING, "正在重试保存")
            try {
                serial {
                    while (failedWrites.isNotEmpty()) {
                        failedWrites.first().invoke()
                        failedWrites.removeFirst()
                    }
                    lastFailure = null
                }
                mutableStatus.value = AgentStorageStatus(AgentStorageMode.ROOM, "", active?.id)
            } catch (e: Exception) {
                mutableStatus.value = AgentStorageStatus(AgentStorageMode.BLOCKED, "会话保存失败，点击重试")
            }
        }
    }

    private fun legacyValues(): Map<String, *> = vm.prefs.all.mapValues { (_, v) ->
        if (v is Set<*>) v.toSet() else v
    }

    private suspend fun migrateScope(scope: String, values: Map<String, *>) {
        if (repository.migrationComplete(scope)) return
        val snapshot = AgentLegacyReader.read(values, vm.gson, onlyScope = scope).singleOrNull()
            ?: AgentLegacySnapshot(scope, emptyList(), emptyList(), AgentPromptOutput(), emptyList(),
                null, "{}", vm._agentInteractionMode.value, vm._currentLlmProfileId.value, workspaceId(scope, "empty"))
        repository.migrate(snapshot)
    }

    private suspend fun display(messages: List<AgentChatMessage>) = messages.map { msg ->
        msg.copy(
            attachments = msg.attachments?.map { attachment ->
                repository.resolveAttachment(attachment.uri)?.let { attachment.copy(uri = it.toURI().toString()) } ?: attachment
            },
            content = if (msg.isThinking && msg.content.isBlank()) "回复已中断" else msg.content,
            errorMessage = if (msg.isThinking) "上次回复已中断" else msg.errorMessage,
            isThinking = false
        )
    }

    private suspend fun load(scope: String, values: Map<String, *>): Loaded {
        migrateScope(scope, values)
        val row = repository.primaryConversation(scope)
        val stored = repository.state(row.id)
        if (stored != null) persistedConversations.add(row.id)
        val state = stored ?: AgentConversationState(row.id)
        val page = repository.messagePage(row.id)
        reconcileTasks()
        // Staging files are kept through an active request; abandoned imports are reclaimed on a later start.
        importsDirectory.listFiles().orEmpty().filter {
            it.isFile && it.lastModified() < System.currentTimeMillis() - 86_400_000L
        }.forEach { it.delete() }
        return Loaded(row, state, display(page.map { vm.gson.fromJson(it.payloadJson, AgentChatMessage::class.java) }),
            repository.media(row.id), page.firstOrNull()?.sequence ?: 0, repository.executions(), repository.currentEpoch())
    }

    fun reload() {
        if (lastFailure != null) {
            mutableStatus.value = AgentStorageStatus(AgentStorageMode.BLOCKED, "有尚未保存的会话，请先重试保存")
            return
        }
        val next = ticket.incrementAndGet()
        val scope = MainViewModelAgent.scope(vm)
        val values = legacyValues()
        mutableStatus.value = AgentStorageStatus()
        vm._agentMessages.value = emptyList()
        vm._agentLatestGenerationList.value = emptyList()
        vm._agentPendingAttachments.value = emptyList()
        draft.value = AgentInputDraft()
        vm.agentWorkflow = null
        MainViewModelAgent.parseAgentEditableNodes(vm, null)
        vm.viewModelScope.launch {
            try {
                val loaded = serial { load(scope, values) }
                if (ticket.get() != next || scope != MainViewModelAgent.scope(vm)) return@launch
                active = loaded.row
                roomActivated = true
                currentState = loaded.state
                firstSequence = loaded.before
                epochs.set(loaded.epoch)
                val socket = vm.repository.webSocketManager
                val staleSocket = socket.mediaWorkspaceEpoch != loaded.epoch && socket.connectionState.value in setOf(
                    com.comfyui.mobile.network.ConnectionState.CONNECTED,
                    com.comfyui.mobile.network.ConnectionState.CONNECTING)
                socket.mediaWorkspaceEpoch = loaded.epoch
                hasEarlier.value = loaded.before > 0
                savedMessages = loaded.messages
                links.clear()
                loaded.links.forEach { link -> link.remoteId?.let { links[link.targetKey + ":" + it] = link } }
                lastFailure = null
                apply(loaded, next)
                mutableStatus.value = AgentStorageStatus(AgentStorageMode.ROOM, "", active?.id)
                vm.agentRunPromptIds.clear()
                vm.agentRunPromptIds.addAll(loaded.links.filter { it.conversationId == loaded.row.id }
                    .mapNotNull { it.remoteId })
                MainViewModelAgent.launchWorkspaceWorkflowLoad(vm)
                if (vm.currentServerProfile()?.isRunningHub == true) MainViewModelRhApps.resumeInFlight(vm)
                else {
                    // A connection started before DB loading may have captured the pre-restore epoch.
                    if (staleSocket) socket.reconnect()
                    if (socket.isSocketLive()) vm.checkLatestHistory()
                }
                // Report orphan originals without adopting or deleting them.
                write { inventoryLegacyFiles() }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (ticket.get() != next) return@launch
                val migrated = runCatching { serial { repository.migrationComplete(scope) } }.getOrDefault(true)
                if (migrated) {
                    mutableStatus.value = AgentStorageStatus(AgentStorageMode.BLOCKED, "会话读取失败，点击重试")
                } else {
                    active = null
                    mutableStatus.value = AgentStorageStatus(AgentStorageMode.LEGACY,
                        "迁移未完成，继续使用原记录；点击重试", "legacy:$scope")
                    MainViewModelAgent.reloadLegacyAgentWorkflow(vm)
                }
                vm.addLog("Agent 工作区加载失败：${e.javaClass.simpleName}", LogType.ERROR)
            }
        }
    }

    private fun apply(loaded: Loaded, loadTicket: Long) {
        val workflow = JsonParser.parseString(loaded.state.workflowStateJson).asJsonObject
        fun text(key: String) = workflow[key]?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        vm._agentWorkflowSource.value = text("source")
        vm._agentWorkflowId.value = text("id")
        vm._agentWorkflowLabel.value = text("label")
        vm.agentWorkflowParamValues.clear()
        workflow["parameters"]?.let {
            val type = object : TypeToken<Map<String, Any>>() {}.type
            vm.agentWorkflowParamValues.putAll(
                vm.sanitizeNumbers(vm.gson.fromJson<Map<String, Any>>(it, type)) as Map<String, Any>)
        }
        vm._agentPositiveBinding.value = workflow["positiveBinding"]?.let {
            vm.gson.fromJson(it, AgentPromptBinding::class.java) } ?: AgentPromptBinding()
        vm._agentNegativeBinding.value = workflow["negativeBinding"]?.let {
            vm.gson.fromJson(it, AgentPromptBinding::class.java) } ?: AgentPromptBinding()
        vm._agentTargetNodeId.value = vm._agentPositiveBinding.value.nodeId
        vm._agentArtistNodeId.value = text("artistNodeId")
        vm._agentAuxNodeKeys.value = workflow["auxNodeKeys"]?.takeUnless { it.isJsonNull }?.asJsonArray
            ?.map { it.asString }?.toSet().orEmpty()
        vm._agentMessages.value = loaded.messages
        val prompt = vm.gson.fromJson(loaded.state.promptOutputJson, AgentPromptOutput::class.java)
        vm._agentPromptOutput.value = prompt
        vm._agentLastExpandedPrompt.value = prompt.positive
        vm._agentLatestGenerationList.value = loaded.media
        vm._agentInteractionMode.value = loaded.row.mode
        loaded.row.llmProfileId?.let { id ->
            vm._llmProfiles.value.firstOrNull { it.id == id }?.let { profile ->
                vm._currentLlmProfileId.value = id
                vm._llmBaseUrl.value = profile.baseUrl
                vm._llmApiKey.value = profile.apiKey
                vm._llmModel.value = profile.model
                vm._llmReasoningEffort.value = com.comfyui.mobile.agent.AgentReasoningEffort.fromStored(profile.reasoningEffort)
            }
        }
        val input = vm.gson.fromJson(loaded.state.draftJson, AgentInputDraft::class.java)
        draft.value = input
        val revision = draftRevision.incrementAndGet()
        val attachments = JsonParser.parseString(loaded.state.draftJson).asJsonObject["attachments"]
        val parsed = attachments?.let { vm.gson.fromJson<List<AgentAttachment>>(it,
            object : TypeToken<List<AgentAttachment>>() {}.type) }.orEmpty()
        vm._agentPendingAttachments.value = parsed
        // Resolve draft assets off-main in the same serial worker.
        if (parsed.isNotEmpty()) write {
            val display = display(listOf(AgentChatMessage("draft", "user", "", attachments = parsed))).single().attachments.orEmpty()
            vm.viewModelScope.launch(Dispatchers.Main) {
                if (active?.id == loaded.row.id && ticket.get() == loadTicket &&
                    draftRevision.get() == revision) vm._agentPendingAttachments.value = display
            }
        }
    }

    fun cachedMessages() = savedMessages
    fun workflowField(name: String, source: String, id: String): com.google.gson.JsonElement? {
        if (!usesRoom) return null
        val json = currentState?.workflowStateJson?.let { JsonParser.parseString(it).asJsonObject } ?: return null
        if (json["source"]?.asString != source || json["id"]?.asString != id) return null
        return json[name]?.takeUnless { it.isJsonNull }
    }

    private suspend fun ensure(row: AgentConversation) {
        // Always keep the latest metadata instead of overwriting it from an older queued message.
        if (repository.state(row.id) == null) repository.saveConversation(row)
        persistedConversations.add(row.id)
    }

    fun saveMessage(message: AgentChatMessage, checkpoint: Boolean = false) {
        val row = active ?: return
        savedMessages = vm._agentMessages.value.toList()
        write { ensure(row); repository.saveMessage(row.id, message, checkpoint) }
    }

    fun persistMessage(message: AgentChatMessage, checkpointOnly: Boolean) {
        if (checkpointOnly) {
            val now = android.os.SystemClock.elapsedRealtime()
            val previous = checkpoints[message.id]
            if (previous != null && now - previous < 1_000L) return
            checkpoints[message.id] = now
            saveMessage(message.copy(isThinking = true), checkpoint = true)
        } else {
            checkpoints.remove(message.id)
            saveMessage(message)
        }
    }

    fun saveConfiguration() {
        if (!usesRoom) return
        val row = active?.copy(mode = vm._agentInteractionMode.value, llmProfileId = vm._currentLlmProfileId.value,
            workflowRef = "${vm._agentWorkflowSource.value}:${vm._agentWorkflowId.value}") ?: return
        active = row
        val workflow = vm.gson.toJson(mapOf(
            "source" to vm._agentWorkflowSource.value, "id" to vm._agentWorkflowId.value,
            "label" to vm._agentWorkflowLabel.value, "parameters" to vm.agentWorkflowParamValues.toMap(),
            "positiveBinding" to vm._agentPositiveBinding.value, "negativeBinding" to vm._agentNegativeBinding.value,
            "artistNodeId" to vm._agentArtistNodeId.value, "auxNodeKeys" to vm._agentAuxNodeKeys.value))
        val state = (currentState ?: AgentConversationState(row.id)).copy(workflowStateJson = workflow,
            promptOutputJson = vm.gson.toJson(vm._agentPromptOutput.value))
        currentState = state
        write {
            repository.saveConversation(row)
            persistedConversations.add(row.id)
            val persisted = repository.state(row.id)!!
            repository.updateState(persisted.copy(workflowStateJson = state.workflowStateJson,
                promptOutputJson = state.promptOutputJson))
        }
    }

    fun saveDraft(value: AgentInputDraft = draft.value) {
        if (status.value.mode == AgentStorageMode.RESTORING) return
        draft.value = value
        if (!usesRoom) return
        val row = active ?: return
        draftRevision.incrementAndGet()
        val attachments = vm._agentPendingAttachments.value.toList()
        if (value.text.isEmpty() && attachments.isEmpty() && savedMessages.isEmpty() &&
            currentState?.workflowStateJson == "{}") return
        write { ensure(row); repository.saveDraft(row.id, vm.gson.toJson(value), attachments) }
    }

    fun captureAttachmentOwner(): AgentAttachmentOwner? {
        if (!requireWritable()) return null
        return AgentAttachmentOwner(active?.id, MainViewModelAgent.scope(vm), epochs.get(), ticket.get(),
            mayCreate = active?.id?.let { it !in persistedConversations } == true)
    }

    suspend fun addAttachments(owner: AgentAttachmentOwner, added: List<AgentAttachment>) {
        if (owner.epoch != epochs.get()) { discardImports(added); return }
        if (status.value.mode == AgentStorageMode.LEGACY) {
            withContext(Dispatchers.Main) {
                if (owner.scope == MainViewModelAgent.scope(vm) && owner.ticket == ticket.get())
                    vm._agentPendingAttachments.value += added
            }
            return
        }
        val id = owner.conversationId ?: return
        // The active origin can still be a transient empty conversation.
        val row = active?.takeIf { it.id == id && it.scope == owner.scope }
        val result = serial {
            if (owner.epoch != epochs.get()) return@serial null
            if (repository.conversation(id) == null) {
                if (!owner.mayCreate || row == null || owner.ticket != ticket.get()) return@serial null
                ensure(row)
            }
            val state = repository.state(id) ?: return@serial null
            val json = JsonParser.parseString(state.draftJson).asJsonObject
            val old = json["attachments"]?.let { vm.gson.fromJson<List<AgentAttachment>>(it,
                object : TypeToken<List<AgentAttachment>>() {}.type) }.orEmpty()
            val merged = (old + added).distinctBy { it.id }.let { all ->
                var images = 0
                all.filter { !it.isImage || ++images <= 4 }
            }
            repository.saveDraft(id, state.draftJson, merged)
            display(listOf(AgentChatMessage("draft", "user", "", attachments = merged))).single().attachments
        } ?: run { discardImports(added); return }
        withContext(Dispatchers.Main) {
            if (active?.id == id && owner.epoch == epochs.get() && owner.ticket == ticket.get()) {
                draftRevision.incrementAndGet()
                // Only append the imported items; edits/removals made while reading the file stay intact.
                val addedIds = added.mapTo(hashSetOf()) { it.id }
                vm._agentPendingAttachments.value = (vm._agentPendingAttachments.value +
                    result.orEmpty().filter { it.id in addedIds }).distinctBy { it.id }
                saveDraft()
            }
        }
    }

    private fun discardImports(attachments: List<AgentAttachment>) {
        attachments.forEach { attachment ->
            runCatching {
                val uri = java.net.URI(attachment.uri)
                if (uri.scheme == "file") File(uri).canonicalFile.takeIf {
                    it.path.startsWith(importsDirectory.canonicalPath + File.separator)
                }?.delete()
            }
        }
    }

    fun clearChat() {
        val row = active ?: return
        savedMessages = emptyList()
        draftRevision.incrementAndGet()
        draft.value = AgentInputDraft()
        hasEarlier.value = false
        currentState = currentState?.copy(promptOutputJson = "{}", draftJson = "{}")
        write { if (repository.state(row.id) != null) repository.clearChat(row.id) }
    }

    fun clearMedia() { active?.let { row -> write { repository.clearMedia(row.id) } } }

    fun loadEarlier() {
        val row = active ?: return
        val before = firstSequence
        if (!usesRoom || !hasEarlier.value) return
        hasEarlier.value = false
        vm.viewModelScope.launch {
            val page = serial { repository.messagePage(row.id, before).let { rows ->
                rows to display(rows.map { vm.gson.fromJson(it.payloadJson, AgentChatMessage::class.java) })
            } }
            if (active?.id != row.id) return@launch
            firstSequence = page.first.firstOrNull()?.sequence ?: 0
            vm._agentMessages.value = (page.second + vm._agentMessages.value).distinctBy { it.id }
            savedMessages = vm._agentMessages.value
            hasEarlier.value = firstSequence > 0
        }
    }

    fun currentTarget(): AgentExecutionTarget {
        val server = vm.currentServerProfile()
        return if (server?.isRunningHub == true) AgentExecutionTarget("RUNNINGHUB", server.effectiveId, server.effectiveRhSite.id)
        else AgentExecutionTarget("LOCAL_COMFY", server?.effectiveId
            ?: "endpoint-" + workspaceId(vm.repository.baseUrl.value.trimEnd('/')))
    }

    fun isKnown(source: String, remote: String) = links.containsKey("$source:$remote")

    suspend fun reserve(turnId: String, target: AgentExecutionTarget, snapshot: String,
        taskLocalId: String? = null): AgentExecutionLink? {
        if (!usesRoom) return null
        val row = requireNotNull(active)
        return serial {
            check(lastFailure == null && row.id == active?.id && status.value.mode == AgentStorageMode.ROOM)
            ensure(row)
            if (row.executionTargetRef != null && row.executionTargetRef != target.key)
                error("会话记录的服务器与当前连接不同，请先确认使用当前配置")
            if (row.executionTargetRef == null) {
                val updated = row.copy(executionTargetRef = target.key)
                repository.saveConversation(updated)
                active = updated
            }
            repository.reserveExecution(row.id, turnId, target, snapshot, taskLocalId)
        }
    }

    suspend fun confirm(link: AgentExecutionLink?, remote: String, localId: String? = null) {
        if (link == null) return
        withContext(NonCancellable) {
            serial {
                check(repository.confirmExecution(link, remote, localId))
                repository.executionForRemote(AgentExecutionTarget(link.backend, link.serverId!!, link.siteId), remote)?.let {
                    val key = "${it.targetKey}:$remote"
                    links[key] = it
                    pendingMedia.remove(key).orEmpty().forEach { media -> record(media, it.targetKey) }
                }
            }
        }
    }

    suspend fun unknown(link: AgentExecutionLink?) {
        if (link != null) withContext(NonCancellable) { serial { repository.markSubmissionUnknown(link) } }
    }

    suspend fun rejected(link: AgentExecutionLink?) {
        if (link != null) withContext(NonCancellable) { serial { repository.markRejected(link) } }
    }

    fun referenceWarning(): String? {
        if (!usesRoom) return null
        val row = active ?: return null
        if (row.llmProfileId != null && vm._llmProfiles.value.none { it.id == row.llmProfileId })
            return "此会话的 AI 配置已缺失，请选择配置或确认使用当前配置"
        if (row.executionTargetRef != null && row.executionTargetRef != currentTarget().key)
            return "此会话的服务器与当前连接不同，请确认使用当前配置"
        return null
    }

    fun useCurrentConfiguration() {
        if (!requireWritable()) return
        vm.agentRuntime.stop("已切换配置", invalidateContext = true)
        active = active?.copy(executionTargetRef = currentTarget().key, llmProfileId = vm._currentLlmProfileId.value)
        saveConfiguration()
        reload()
    }

    private suspend fun reconcileTasks() {
        vm.taskStore.ensureLoaded()
        for (link in repository.executions()) {
            if (link.backend !in setOf("LOCAL_COMFY", "RUNNINGHUB")) continue
            val localId = link.taskLocalId ?: link.id
            val old = vm.taskStore.find(localId)
                ?: vm.taskStore.tasks.value.firstOrNull { it.agentExecutionId == link.id }
            if (old != null) {
                if (old.agentExecutionId != link.id || old.agentConversationId != link.conversationId)
                    vm.taskStore.update(old.localId) { it.copy(agentExecutionId = link.id,
                        agentConversationId = link.conversationId) }
            } else {
                val snapshot = JsonParser.parseString(link.snapshotJson).asJsonObject
                val keySig = snapshot["accountKeySig"]?.takeUnless { it.isJsonNull }?.asString
                vm.taskStore.upsert(com.comfyui.mobile.data.TaskRecord(localId, serverId = link.serverId,
                    backend = link.backend, remoteId = link.remoteId,
                    state = if (link.remoteId == null) "LOST"
                        else if (link.backend == "RUNNINGHUB" && keySig == null) "PAUSED" else "QUEUED",
                    title = "Agent", origin = if (link.backend == "LOCAL_COMFY") "agent_local" else "official_workflow",
                    rhSite = link.siteId, rhKeySig = keySig, agentExecutionId = link.id, agentConversationId = link.conversationId,
                    createdAt = link.createdAt, updatedAt = link.updatedAt))
            }
        }
    }

    fun completeLocal(source: String, remote: String, state: String, message: String? = null) {
        val link = links["$source:$remote"] ?: return
        write {
            val localId = link.taskLocalId ?: link.id
            vm.taskStore.update(localId) { it.copy(state = state,
                error = message?.let { text -> com.comfyui.mobile.backend.TaskError(message = text) },
                finishedAt = System.currentTimeMillis()) }
        }
    }

    /** Source is passed by the event producer, never inferred from the displayed conversation. */
    fun record(media: MediaOutput, sourceKey: String): Boolean {
        if (status.value.mode == AgentStorageMode.RESTORING ||
            media.workspaceEpoch?.let { it != epochs.get() } == true) return false
        val remote = media.promptId ?: return false
        val identity = "$sourceKey:$remote"
        val link = links[identity] ?: run {
            if (pendingMedia.size > 128) pendingMedia.clear()
            pendingMedia.compute(identity) { _, old -> (old.orEmpty() + media).takeLast(64) }
            return false
        }
        val stamped = media.copy(sourceKey = sourceKey)
        val epoch = epochs.get()
        write {
            if (repository.recordOutput(link, stamped)) {
                val updated = repository.media(link.conversationId!!)
                vm.viewModelScope.launch(Dispatchers.Main) {
                    if (epoch == epochs.get() && active?.id == link.conversationId &&
                        MainViewModelAgent.scope(vm) == link.scope) vm._agentLatestGenerationList.value = updated
                }
            }
        }
        return true
    }

    suspend fun export(directory: File) = serial {
        check(status.value.mode == AgentStorageMode.ROOM) { "请等待会话读取完成或重试迁移后再导出" }
        lastFailure?.let { throw it }
        val values = legacyValues()
        AgentLegacyReader.read(values, vm.gson).forEach { migrateScope(it.scope, values) }
        repository.exportTo(directory)
    }

    suspend fun validate(source: File) = serial { repository.validateBackup(source) }

    fun beginRestore() {
        ticket.incrementAndGet()
        mutableStatus.value = AgentStorageStatus(AgentStorageMode.RESTORING, "正在恢复会话")
        links.clear()
        pendingMedia.clear()
    }

    suspend fun invalidateCallbacks() = serial {
        lastFailure?.let { throw it }
        epochs.set(repository.advanceEpoch())
    }

    suspend fun restore(source: File, confirmed: Boolean) = serial {
        val result = repository.restoreFrom(source, confirmed)
        // Replacement advances the DB epoch again; publish it before finishRestore reconnects.
        epochs.set(repository.currentEpoch())
        result
    }

    fun finishRestore() {
        vm.repository.webSocketManager.mediaWorkspaceEpoch = epochs.get()
        vm.repository.webSocketManager.mediaSourceKey = currentTarget().key
        if (lastFailure != null) {
            mutableStatus.value = AgentStorageStatus(AgentStorageMode.BLOCKED, "有尚未保存的会话，请先重试保存")
            return
        }
        reload()
        if (vm.currentServerProfile()?.isRunningHub != true) {
            vm.repository.updateConnection(vm.repository.baseUrl.value, force = true)
        }
    }

    fun close() { commands.close() }
    suspend fun awaitClosed() { closed.await() }

    private suspend fun inventoryLegacyFiles() {
        val known = runCatching { AgentLegacyReader.read(legacyValues(), vm.gson) }.getOrDefault(emptyList()).flatMap { it.messages }
            .flatMap { it.attachments.orEmpty() }.map { it.uri }.toSet()
        val originals = File(app.filesDir, "agent_attachments").listFiles().orEmpty().filter { it.isFile }
        val inventory = originals.filter { it.toURI().toString() !in known &&
            android.net.Uri.fromFile(it).toString() !in known }.map { mapOf("name" to it.name, "size" to it.length()) }
        directory.mkdirs()
        File(directory, "unassigned-legacy-files.json").writeText(vm.gson.toJson(inventory))
    }
}

data class AgentInputDraft(
    val text: String = "", val selectionStart: Int = 0, val selectionEnd: Int = 0,
    val scrollMessageId: String? = null, val scrollOffset: Int = 0
)

data class AgentAttachmentOwner(
    val conversationId: String?, val scope: String, val epoch: Long, val ticket: Long,
    val mayCreate: Boolean = false
)
