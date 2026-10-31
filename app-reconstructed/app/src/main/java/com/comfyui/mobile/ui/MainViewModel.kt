package com.comfyui.mobile.ui

import com.comfyui.mobile.model.AgentChatMessage
import com.comfyui.mobile.model.CharacterLore
import com.comfyui.mobile.model.LlmProfile
import com.comfyui.mobile.data.LlmClient
import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.comfyui.mobile.GenerationLiveUpdate
import com.comfyui.mobile.KeepAliveService
import com.comfyui.mobile.data.AppModule
import com.comfyui.mobile.data.toRhView
import com.comfyui.mobile.network.ConnectionState
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import com.comfyui.mobile.model.HistoryItem
import com.comfyui.mobile.model.LogType
import com.comfyui.mobile.model.MediaOutput
import com.comfyui.mobile.model.MediaType
import com.comfyui.mobile.model.QueueDto
import com.comfyui.mobile.model.EditableNode
import com.comfyui.mobile.model.AiApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
import com.comfyui.mobile.network.NetworkMonitor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import java.io.File
import com.comfyui.mobile.model.ServerProfile
import com.comfyui.mobile.data.WorkflowPreferences
import com.comfyui.mobile.ui.state.AppCacheManager
import com.comfyui.mobile.ui.state.ConsoleLogStore
import com.comfyui.mobile.ui.state.WorkflowEditorState
import com.comfyui.mobile.workflow.WorkflowGraph

class MainViewModel(application: Application) : AndroidViewModel(application), ControlUi, AiAppUi, GalleryUi {
    companion object {
        /** LoRA / ControlNet weights: strength, strength_model, lora_1_clip_strength ... (always FLOAT, shown as slider). */
        internal fun isStrengthKey(key: String): Boolean = com.comfyui.mobile.model.isStrengthKey(key)
        const val THEME_FOLLOW_SYSTEM = 0
        const val AGENT_MODE_CHAT = "chat"
        const val AGENT_MODE_DRAW = "draw"
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
        const val GALLERY_SERVER = "server"
        const val GALLERY_LOCAL = "local"
        const val AGENT_WF_AI_APP = "ai_app"
        const val AGENT_WF_SAVED = "saved"


        fun getComboOptions(objectInfo: Map<String, Any>, classType: String, inputKey: String): List<String>? =
            com.comfyui.mobile.model.WorkflowOptionParser.getComboOptions(objectInfo, classType, inputKey)
    }

    internal val repository = AppModule.provideRepository()

    /** Persisted task store (JSON under filesDir). Cloud records screen shows its RUNNINGHUB tasks only. */
    internal val taskStore = com.comfyui.mobile.data.TaskStore.get(application)

    /** RH upload fileName ("api/xxx.png") -> local preview Uri (persistent app-storage copy), for input previews. */
    internal val rhUploadPreviews = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** Local ComfyUI upload name -> local preview copy (used when the /view URL is not reachable). */
    internal val uploadPreviewStore = UploadPreviewStore(application, rhUploadPreviews)

    /** Exactly one active generation backend, chosen by the current server profile type. */
    internal val backendProvider = com.comfyui.mobile.backend.BackendProvider(repository)
    internal val gson = Gson()
    internal val prefs = application.getSharedPreferences("comfy_prefs", android.content.Context.MODE_PRIVATE)

    // Independent state holders; existing screen interfaces remain the facade.
    private val workflowPreferences = WorkflowPreferences(
        read = { key -> prefs.getString(key, null) },
        write = { key, json, legacyKey ->
            val editor = prefs.edit().putString(key, json)
            if (legacyKey != null) editor.remove(legacyKey)
            editor.apply()
        },
        currentNamespace = { currentKindNs() },
        gson = gson
    )
    private val workflowEditor = WorkflowEditorState(workflowPreferences) { _objectInfo.value }
    private val consoleLog = ConsoleLogStore()
    private val cacheManager = AppCacheManager(application, uploadPreviewStore)
    private val modelPickerData = lazy { com.comfyui.mobile.modelcenter.ModelPickerData(application, repository) }
    
    val connectionState = repository.webSocketManager.connectionState
        .stateIn(viewModelScope, SharingStarted.Eagerly, ConnectionState.DISCONNECTED)
        
    val systemStats = repository.webSocketManager.systemStats
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
        
    override val progress = repository.webSocketManager.progress
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0f)
        
    override val queueSize = repository.webSocketManager.queueSize
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private val executingNodeId = repository.webSocketManager.executingNodeId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    internal val _workflowNodeTitles = workflowEditor.nodeTitles

    val executingNodeName = combine(executingNodeId, _workflowNodeTitles) { nodeId, titles ->
        if (nodeId.isNullOrBlank()) "" else (titles[nodeId] ?: nodeId)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** Node titles of the AI app screen's own session graph (never the Control screen's workflow). */
    internal val _aiAppNodeTitles = MutableStateFlow<Map<String, String>>(emptyMap())

    override val aiAppExecutingNodeName = combine(executingNodeId, _aiAppNodeTitles) { nodeId, titles ->
        if (nodeId.isNullOrBlank()) "" else (titles[nodeId] ?: nodeId)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    internal val _agentNodeTitles = MutableStateFlow<Map<String, String>>(emptyMap())

    val agentExecutingNodeName = combine(executingNodeId, _agentNodeTitles) { nodeId, titles ->
        if (nodeId.isNullOrBlank()) "" else (titles[nodeId] ?: nodeId)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")
        
    val latestMedia = repository.webSocketManager.latestMedia
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
        
    private val _latestGenerationList = MutableStateFlow<List<MediaOutput>>(emptyList())
    override val latestGenerationList = _latestGenerationList.asStateFlow()

    val lastMessageType = repository.webSocketManager.lastMessageType
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
        
    private val _sessionMediaList = MutableStateFlow<List<MediaOutput>>(emptyList())
    override val sessionMediaList = _sessionMediaList.asStateFlow()

    override val baseUrl = repository.baseUrl

    private val _queueList = MutableStateFlow<List<QueueDto>>(emptyList())
    override val queueList = _queueList.asStateFlow()

    val consoleMessages = consoleLog.messages

    internal val _savedScheme = MutableStateFlow(prefs.getString("scheme", "http") ?: "http")
    val savedScheme = _savedScheme.asStateFlow()

    internal val _savedIp = MutableStateFlow(prefs.getString("ip", "") ?: "")
    val savedIp = _savedIp.asStateFlow()

    internal val _savedPort = MutableStateFlow(prefs.getString("port", "8188") ?: "8188")
    val savedPort = _savedPort.asStateFlow()

    internal val _servers = MutableStateFlow(MainViewModelServer.loadServersMigrating(this))
    override val servers = _servers.asStateFlow()

    internal val _currentServerId = MutableStateFlow(
        prefs.getString("current_server_id", "").orEmpty().ifBlank {
            _servers.value.firstOrNull()?.id.orEmpty()
        }
    )
    override val currentServerId = _currentServerId.asStateFlow()

    val currentServerName: String
        get() = currentServerProfile()?.displayName
            ?: _savedIp.value

    internal val _assetsSummary = MutableStateFlow<com.comfyui.mobile.model.AssetsSummaryData?>(null)
    val assetsSummary = _assetsSummary.asStateFlow()

    internal val _downloadTasks = MutableStateFlow<List<com.comfyui.mobile.model.CivitaiDownloadTaskItem>>(emptyList())
    val downloadTasks = _downloadTasks.asStateFlow()

    internal val _downloadStats = MutableStateFlow<Map<String, Any>>(emptyMap())
    val downloadStats = _downloadStats.asStateFlow()

    internal fun persistStringList(key: String, list: List<String>) {
        prefs.edit().putString(key, gson.toJson(list)).apply()
    }

    internal fun loadStringList(key: String): MutableList<String> {
        val raw = prefs.getString(key, null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            val parsed: List<String> = gson.fromJson(raw, type) ?: emptyList()
            parsed.toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun loadGalleryCategories(): List<GalleryCategory> =
        MainViewModelGallery.loadGalleryCategories(this)

    private fun loadGalleryCategoryAssignments(): Map<String, String> =
        MainViewModelGallery.loadGalleryCategoryAssignments(this)

    internal fun persistGalleryCategories(list: List<GalleryCategory>) {
        prefs.edit().putString("gallery_categories_v1", gson.toJson(list)).apply()
    }

    internal fun persistGalleryCategoryAssignments(map: Map<String, String>) {
        MainViewModelGallery.persistGalleryCategoryAssignments(this, map)
    }

    internal val _galleryCategories = MutableStateFlow(loadGalleryCategories())
    override val galleryCategories = _galleryCategories.asStateFlow()

    internal val _galleryCategoryAssignments = MutableStateFlow(loadGalleryCategoryAssignments())
    override val galleryCategoryAssignments = _galleryCategoryAssignments.asStateFlow()

    data class LocalMediaFile(
        val categoryId: String,
        val path: String,
        val name: String,
        val mediaType: MediaType,
        val modifiedAt: Long
    )

    internal val _localCategoryFiles = MutableStateFlow<Map<String, List<LocalMediaFile>>>(emptyMap())
    val localCategoryFiles = _localCategoryFiles.asStateFlow()

    override fun createGalleryCategory(name: String) =
        MainViewModelGallery.createGalleryCategory(this, name)

    override fun renameGalleryCategory(id: String, name: String) =
        MainViewModelGallery.renameGalleryCategory(this, id, name)

    override fun deleteGalleryCategory(id: String) =
        MainViewModelGallery.deleteGalleryCategory(this, id)

    fun assignMediaToGalleryCategory(keys: Set<String>, categoryId: String) =
        MainViewModelGallery.assignMediaToGalleryCategory(this, keys, categoryId)

    override fun unassignMediaFromGalleryCategory(keys: Set<String>) =
        MainViewModelGallery.unassignMediaFromGalleryCategory(this, keys)

    override fun assignMediaToGalleryCategoryAndSave(mediaList: List<MediaOutput>, categoryId: String) =
        MainViewModelGallery.assignMediaToGalleryCategoryAndSave(this, mediaList, categoryId)

    fun refreshLocalCategoryFiles() =
        MainViewModelGallery.refreshLocalCategoryFiles(this)

    override fun mediaKey(media: MediaOutput): String =
        media.localUri ?: "${media.subfolder}|${media.filename}|${media.type}"

    internal val _deletedMediaKeys = MutableStateFlow(loadStringList("deleted_media_keys").toSet())

    internal fun isDeletedKey(key: String): Boolean = _deletedMediaKeys.value.contains(key)

    internal fun addDeletedKeys(keys: Set<String>) {
        if (keys.isEmpty()) return
        val merged = (_deletedMediaKeys.value + keys).toSet()
        persistStringList("deleted_media_keys", merged.toList())
        _deletedMediaKeys.value = merged
    }

    private val _dashboardModuleOrder = MutableStateFlow(loadStringList("dashboard_module_order").toList())
    val dashboardModuleOrder = _dashboardModuleOrder.asStateFlow()

    fun updateDashboardModuleOrder(order: List<String>) {
        val sanitized = order
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        persistStringList("dashboard_module_order", sanitized)
        _dashboardModuleOrder.value = sanitized
    }

    private val _executionStatus = MutableStateFlow("IDLE") // IDLE, RUNNING, COMPLETED
    override val executionStatus = _executionStatus.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    override val isRunning = _isRunning.asStateFlow()

    private val _batchCount = MutableStateFlow(prefs.getInt("batch_count", 1).coerceIn(1, 8))
    override val batchCount = _batchCount.asStateFlow()

    override fun setBatchCount(value: Int) {
        val next = value.coerceIn(1, 8)
        _batchCount.value = next
        prefs.edit().putInt("batch_count", next).apply()
    }

    internal val _isAutoSave = MutableStateFlow(prefs.getBoolean("auto_save", false))
    val isAutoSave = _isAutoSave.asStateFlow()

    val cacheSizeText: StateFlow<String> = cacheManager.sizeText

    internal val _objectInfo = MutableStateFlow<Map<String, Any>>(emptyMap())

    internal var currentWorkflow: Map<String, Any>?
        get() = workflowEditor.workflow
        set(value) { workflowEditor.workflow = value }
    /** Bumped on every workflow/app load and on mode switches: a slower, older load never overwrites a newer state. */
    internal val workflowLoadGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    internal val currentWorkflowKey: String?
        get() = workflowEditor.currentKey

    internal val isRunningNow: Boolean get() = _isRunning.value

    // --- RunningHub cloud workflows on the official task API (see MainViewModelRhWorkflows) ---
    internal val _currentRhWorkflowMeta = MutableStateFlow<RhWorkflowMeta?>(null)
    /** workflowId metadata of the opened cloud workflow (null = no saved cloud workflow open). */
    override val currentRhWorkflowMeta = _currentRhWorkflowMeta.asStateFlow()
    private val _rhWorkflowIdRequest = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Fired when a cloud run was refused because the workflow has no RunningHub workflowId (Control opens the ID dialog). */
    override val rhWorkflowIdRequest = _rhWorkflowIdRequest.asSharedFlow()
    internal fun requestRhWorkflowId() {
        _rhWorkflowIdRequest.tryEmit(Unit)
    }

    /** Sets (or clears, when blank) the RunningHub workflowId of the opened cloud workflow; returns an error text or null. */
    override fun setCurrentRhWorkflowId(input: String): String? = MainViewModelRhWorkflows.setWorkflowIdForCurrent(this, input)

    fun currentRhSiteId(): String? = MainViewModelRhWorkflows.currentSiteId(this)
    override fun rhSiteName(siteId: String?): String = MainViewModelRhWorkflows.siteName(siteId)
    override fun rhWorkflowSiteOf(fileName: String): String? =
        MainViewModelRhWorkflows.siteOf(this, fileName)
    /** Deletes a saved cloud workflow and its app-side data (never calls RunningHub). */
    fun deleteCloudWorkflow(file: java.io.File): Boolean {
        val ok = MainViewModelRhWorkflows.deleteCloudWorkflow(this, file)
        showToast(if (ok) "已删除「${file.name.removeSuffix(".json")}」" else "删除失败")
        return ok
    }

    override fun deleteSavedWorkflow(file: java.io.File): Boolean {
        val ok = if (MainViewModelWorkflow.isCloudMode(this)) {
            MainViewModelRhWorkflows.deleteCloudWorkflow(this, file)
        } else {
            MainViewModelWorkflow.deleteLocalWorkflow(this, file)
        }
        showToast(if (ok) "已删除「${file.name.removeSuffix(".json")}」" else "删除失败")
        return ok
    }

    /** Drops in-memory caches of a deleted workflow key. */
    /** Workflow keys the user deleted; completed polls must not recreate result lists for them. */
    internal val detachedWorkflowKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    internal fun forgetWorkflowCaches(key: String) {
        detachedWorkflowKeys.add(key)
        latestGenerationCache.remove(key)
        workflowKeyNamespaces.remove(key)
    }

    // Prompt Map Persistence
    private val promptMapFile by lazy { java.io.File(application.filesDir, "prompt_map.json") }
    private val _promptMap = MutableStateFlow<MutableMap<String, String>>(mutableMapOf())
    private val activePrompts = mutableMapOf<String, String>()
    private var activeRunStartedAt: Long = 0L
    private val activeRunPromptIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val recentlyFinalizedPromptIds = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var lastFinishedPromptId: String? = null
    private var lastExternalPromptId: String? = null
    private val promptIdToWorkflowKey = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val promptIdToRunId = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val activeRunIdByWorkflowKey = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val latestGenerationCache = java.util.concurrent.ConcurrentHashMap<String, List<MediaOutput>>()

    internal val currentWorkflowParamVisibility: Map<String, Boolean>
        get() = workflowEditor.paramVisibility
    
    internal fun sanitizeNumbers(value: Any?, keyHint: String? = null): Any? =
        WorkflowGraph.sanitizeNumbers(value, keyHint)
    
    internal val _currentWorkflowName = MutableStateFlow<String?>(null)
    override val currentWorkflowName = _currentWorkflowName.asStateFlow()

    internal val _savedWorkflowFiles = MutableStateFlow<List<java.io.File>>(emptyList())
    override val savedWorkflowFiles = _savedWorkflowFiles.asStateFlow()

    internal val _aiApps = MutableStateFlow<List<AiApp>>(emptyList())
    override val aiApps = _aiApps.asStateFlow()

    private val networkMonitor = NetworkMonitor(application)
    private var foregroundHeartbeatJob: Job? = null

    init {
        loadPromptMap()
        networkMonitor.setOnNetworkRecoveredListener {
            addLog("网络状态已恢复，正在立即重连...", LogType.INFO)
            repository.webSocketManager.resetReconnectBackoff()
            ensureConnected(forceProbe = true)
            fetchSystemStats()
            fetchQueue()
        }
        networkMonitor.start()

        // Cloud profile: never open the ComfyUI WebSocket / run REST (runs use the official task API).
        repository.setCloudActive(currentServerProfile()?.isRunningHub == true)
        // Resolve only from the real current profile; never rebuild one from the legacy ip prefs
        // (for RunningHub those only hold the site host, without /proxy/{key}).
        val effectiveUrl = currentServerBaseUrlOrNull()
        if (effectiveUrl != null) {
            repository.setBaseUrlOnly(effectiveUrl)
        }
        if (currentServerProfile()?.isRunningHub != true) {
            repository.webSocketManager.mediaSourceKey =
                com.comfyui.mobile.agent.workspace.AgentExecutionTarget("LOCAL_COMFY",
                    currentServerProfile()?.effectiveId ?: "endpoint-" +
                        com.comfyui.mobile.agent.workspace.workspaceId(repository.baseUrl.value.trimEnd('/'))).key
        }
        refreshSavedWorkflows()
        refreshAiApps()
        refreshLocalCategoryFiles()
        restoreLastWorkflow()
        pruneOrphanTempFiles()

        /*
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(30000)
                ensureConnected()
            }
        }
        */

        // On-demand Foreground Service for Live Updates
        viewModelScope.launch {
            combine(
                repository.webSocketManager.progress,
                repository.webSocketManager.queueSize,
                repository.webSocketManager.executingNodeId,
                _isRunning
    ) { progress, queueSize, executingNodeId, isRunning ->
                GenerationLiveUpdate.isBusy(
                    progress = progress,
                    queueSize = queueSize,
                    isExecuting = executingNodeId != null,
                    isGenerating = isRunning
    )
            }.distinctUntilChanged().collect { isBusy ->
                if (GenerationLiveUpdate.isUserStopRequested()) {
                    repository.setIsGenerating(false)
                    return@collect
                }
                repository.setIsGenerating(isBusy)
                if (isBusy) {
                    if (!KeepAliveService.isServiceRunning.get()) {
                        KeepAliveService.start(getApplication())
                    }
                }
            }
        }

        // Execution Start Listener
        viewModelScope.launch {
            repository.externalStop.collect {
                try {
                    GenerationLiveUpdate.beginUserStop()
                    // Official cloud tasks keep the service alive; "停止" from the Live Update cancels them too.
                    if (currentServerProfile()?.isRunningHub == true) MainViewModelRhApps.cancelRunning(this@MainViewModel)
                    sendJob?.cancel()
                    sendJob = null
                    sendJobToken = null
                    isSending = false
                    activeRunStartedAt = 0L
                    activeRunPromptIds.clear()
                    promptIdToWorkflowKey.clear()
                    promptIdToRunId.clear()
                    activeRunIdByWorkflowKey.clear()
                    _isRunning.value = false
                    _executionStatus.value = "IDLE"
                    resetRunState()
                    fetchQueue()
                    repository.setIsGenerating(false)
                    KeepAliveService.stop(getApplication())
                    addLog("已从实时活动全部停止", LogType.WARNING)
                } catch (e: Exception) {
                    addLog("停止任务失败: ${e.message}", LogType.ERROR)
                    repository.setIsGenerating(false)
                    _isRunning.value = false
                    KeepAliveService.stop(getApplication())
                }
            }
        }

        viewModelScope.launch {
            repository.webSocketManager.executionStart.collect {
                if (activeRunStartedAt == 0L) {
                    activeRunStartedAt = System.currentTimeMillis()
                }
                _isRunning.value = true
                _executionStatus.value = "RUNNING"
            }
        }

        viewModelScope.launch {
            repository.webSocketManager.executionStartPromptId.collect { promptId ->
                val pid = promptId?.ifBlank { null }
                if (activeRunStartedAt == 0L) {
                    activeRunStartedAt = System.currentTimeMillis()
                }
                if (pid != null) {
                    // activeRunPromptIds.add(pid) // Optional: Track external runs too?
                    // For now, we rely on runWorkflow to add IDs we initiated. 
                    // If we want to track external, we can add them, but we might never know when to "finish" a batch of external tasks.
                    // So let's just update UI status.
                }
                val isAgentRun = !pid.isNullOrBlank() && isAgentTask(pid)
                if (isAgentRun && !pid.isNullOrBlank()) {
                    recordAgentRunPromptId(pid)
                }
                if (!isAgentRun && !_agentBusy.value && !isAgentDispatching && currentWorkflow == null) {
                    lastExternalPromptId = promptId
                    _currentWorkflowName.value = if (!promptId.isNullOrBlank()) "电脑端任务 $promptId" else "电脑端任务"
                }
                _isRunning.value = true
                _executionStatus.value = "RUNNING"
            }
        }

        viewModelScope.launch {
            repository.webSocketManager.executionFinishedPromptId.collect { promptId ->
                if (!promptId.isNullOrBlank()) {
                    val isAgentRun = isAgentTask(promptId)
                    if (isAgentRun) {
                        recordAgentRunPromptId(promptId)
                    } else if (!_agentBusy.value && !isAgentDispatching) {
                        lastExternalPromptId = promptId
                        // activeRunPromptIds removal is handled in finalizeActiveRun
                        if (currentWorkflow == null) {
                            _currentWorkflowName.value = "电脑端任务 $promptId"
                        }
                    }
                }
                // Launch finalizing in concurrent coroutine with brief delay so collector is immediately free for next event
                val target = agentWorkspace.currentTarget().key
                val epoch = agentWorkspace.callbackEpoch
                launch {
                    kotlinx.coroutines.delay(200)
                    if (target != agentWorkspace.currentTarget().key || epoch != agentWorkspace.callbackEpoch) return@launch
                    finalizeActiveRun(promptId)
                }
            }
        }

        // Auto Save Listener & Session Update
        viewModelScope.launch {
            repository.webSocketManager.mediaEvents.collect { data ->
                val now = System.currentTimeMillis()

                // Save Prompt Mapping
                // Check if this media belongs to any active prompt or the last finished one
                val pid = data.promptId
                // Note: activeRunPromptId is gone. We check map directly if possible, or assume valid.
                if (pid != null && activePrompts.containsKey(pid)) {
                    val prompt = activePrompts[pid]!!
                    val key = "${data.subfolder}|${data.filename}|${data.type}"
                    val map = _promptMap.value.toMutableMap()
                    map[key] = prompt
                    _promptMap.value = map
                    savePromptMap()
                }

                val pidForMedia = data.promptId
                if (!pidForMedia.isNullOrBlank()) {
                    val source = data.sourceKey ?: agentWorkspace.currentTarget().key
                    val workspaceOutput = agentWorkspace.record(data.copy(
                        sourceUrl = data.sourceUrl ?: repository.getImageUrl(data.filename, data.subfolder, data.type)), source)
                    if (workspaceOutput) {
                        // Repository publishes only to the owning conversation after the write commits.
                    } else if (agentWorkspace.status.value.mode == AgentStorageMode.LEGACY && isAgentTask(pidForMedia)) {
                        // Agent 专属图片流更新：仅放入 Agent 专属列表，不混入控制页工作流列表
                        val existing = _agentLatestGenerationList.value.toMutableList()
                        if (existing.none { it.filename == data.filename && it.subfolder == data.subfolder && it.type == data.type }) {
                            val next = listOf(data) + existing
                            _agentLatestGenerationList.value = next
                            persistAgentMediaList(next)
                        }
                    } else {
                        // 控制页/普通工作流专属图片流更新
                        val workflowKey = promptIdToWorkflowKey[pidForMedia]
                        val runId = promptIdToRunId[pidForMedia]
                        if (!workflowKey.isNullOrBlank() && runId != null) {
                            val activeRunId = activeRunIdByWorkflowKey[workflowKey]
                            if (activeRunId != null && activeRunId == runId) {
                                val existing = (latestGenerationCache[workflowKey] ?: emptyList()).toMutableList()
                                if (existing.none { it.filename == data.filename && it.subfolder == data.subfolder && it.type == data.type }) {
                                    val next = listOf(data) + existing
                                    setLatestGenerationForWorkflow(workflowKey, next)
                                }
                            }
                        }
                    }
                }

                // Update session list with new media
                val key = "${data.subfolder}|${data.filename}|${data.type}"
                if (isDeletedKey(key)) return@collect
                val currentList = _sessionMediaList.value.toMutableList()

                val existingIndex = currentList.indexOfFirst { "${it.subfolder}|${it.filename}|${it.type}" == key }
                if (existingIndex >= 0) {
                    val updated = currentList[existingIndex].copy(timestamp = now)
                    currentList.removeAt(existingIndex)
                    currentList.add(0, updated)
                } else {
                    currentList.add(0, data.copy(timestamp = now))
                }
                _sessionMediaList.value = currentList

                // Cloud results are downloaded by the official task tracking; auto-save is for local only.
                if (_isAutoSave.value && currentServerProfile()?.isRunningHub != true) {
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            kotlinx.coroutines.delay(1000)
                            MainViewModelGallery.saveMediaToAlbum(this@MainViewModel, getApplication(), data, silent = false)
                        } catch (e: Exception) {
                            e.printStackTrace()
                            addLog("自动保存失败: ${e.message}", LogType.ERROR)
                        }
                    }
                }
            }
        }
        
        // Queue Status Listener for Auto Refresh
        viewModelScope.launch {
            repository.webSocketManager.queueSize.collect { size ->
                if (size == 0 && (activeRunPromptIds.isNotEmpty() || _isRunning.value)) {
                    // In ComfyUI, queue_remaining == 0 only means pending queue is empty.
                    // Check if server is actually done with running tasks before finalizing.
                    launch(Dispatchers.IO) {
                        kotlinx.coroutines.delay(1200)
                        try {
                            // null = queue state unknown (error): keep waiting, never treat as empty.
                            val activeQueueIds = repository.getActiveQueuePromptIds() ?: return@launch
                            val finished = activeRunPromptIds.filter { it !in activeQueueIds }
                            for (pid in finished) {
                                finalizeActiveRun(pid)
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        }
        
        // Log Collector
        viewModelScope.launch {
            repository.webSocketManager.logEvents.collect { msg ->
                addLog(msg, LogType.INFO)
            }
        }

        // Permanent WebSocket rejection (e.g. RH 401/403): tell the user instead of reconnecting forever.
        viewModelScope.launch {
            repository.webSocketManager.connectionError.collect { err ->
                if (!err.isNullOrBlank()) {
                    addLog(err, LogType.ERROR)
                    showToast(err)
                }
            }
        }
        
        // Connection State Listener for History Sync & System Stats
        viewModelScope.launch {
            connectionState.collect { state ->
                if (state == ConnectionState.CONNECTED) {
                    kotlinx.coroutines.delay(300)
                    fetchSystemStats()
                    fetchQueue()
                    checkLatestHistory()
                    if (activeRunPromptIds.isNotEmpty() || _isRunning.value) {
                        syncActiveRunsWithServer()
                        startActiveRunWatchdog()
                    }
                    if (_objectInfo.value.isEmpty()) {
                        var info = repository.getObjectInfo()
                        if (info == null) {
                            kotlinx.coroutines.delay(1000)
                            info = repository.getObjectInfo()
                        }
                        if (info != null) {
                            _objectInfo.value = info
                            currentWorkflow?.let { parseEditableNodes(it) }
                            MainViewModelAiAppSession.rebuildNodes(this@MainViewModel)
                            agentWorkflow?.let { MainViewModelAgent.parseAgentEditableNodes(this@MainViewModel, it) }
                        }
                    }
                }
            }
        }
    }

    fun toggleAutoSave(enable: Boolean) {
        _isAutoSave.value = enable
        prefs.edit().putBoolean("auto_save", enable).apply()
    }

    internal val _gallerySource = MutableStateFlow(
        prefs.getString("gallery_source", GALLERY_SERVER) ?: GALLERY_SERVER
    )
    override val gallerySource = _gallerySource.asStateFlow()

    override fun setGallerySource(source: String) =
        MainViewModelGallery.setGallerySource(this, source)

    internal val _localSaveTreeUri = MutableStateFlow(prefs.getString("local_save_tree_uri", null))
    val localSaveTreeUri = _localSaveTreeUri.asStateFlow()

    internal val _localSaveLabel = MutableStateFlow(
        prefs.getString("local_save_label", null) ?: "应用默认目录"
    )
    val localSaveLabel = _localSaveLabel.asStateFlow()

    internal val _localMediaList = MutableStateFlow<List<MediaOutput>>(emptyList())
    override val localMediaList = _localMediaList.asStateFlow()

    fun setLocalSaveTreeUri(uriString: String?, displayName: String?) =
        MainViewModelGallery.setLocalSaveTreeUri(this, uriString, displayName)

    fun clearLocalSaveTreeUri() =
        MainViewModelGallery.clearLocalSaveTreeUri(this)

    fun defaultSavedDir(): java.io.File =
        MainViewModelGallery.defaultSavedDir(this)

    // --- Theme ---
    internal val _themeMode = MutableStateFlow(
        if (prefs.contains("theme_mode")) {
            prefs.getInt("theme_mode", THEME_FOLLOW_SYSTEM)
        } else if (prefs.getBoolean("is_dark_theme", false)) {
            THEME_DARK
        } else {
            THEME_LIGHT
        }
    )
    val themeMode = _themeMode.asStateFlow()

    internal val _isDarkTheme = MutableStateFlow(_themeMode.value == THEME_DARK)
    val isDarkTheme = _isDarkTheme.asStateFlow()

    fun setThemeMode(mode: Int) =
        MainViewModelTheme.setThemeMode(this, mode)

    fun toggleTheme(isDark: Boolean) =
        MainViewModelTheme.toggleTheme(this, isDark)

    internal val _themePaletteId = MutableStateFlow(
        com.comfyui.mobile.ui.theme.ThemePaletteIds.normalize(
            prefs.getString("theme_palette_id", null)
        )
    )
    val themePaletteId = _themePaletteId.asStateFlow()

    fun setThemePalette(paletteId: String) =
        MainViewModelTheme.setThemePalette(this, paletteId)

    internal val _bottomBarStyle = MutableStateFlow(
        com.comfyui.mobile.ui.theme.BottomBarStyle.fromId(
            prefs.getString(com.comfyui.mobile.ui.theme.BottomBarStyle.PREFERENCE_KEY, null)
        )
    )
    val bottomBarStyle = _bottomBarStyle.asStateFlow()

    fun setBottomBarStyle(style: com.comfyui.mobile.ui.theme.BottomBarStyle) =
        MainViewModelTheme.setBottomBarStyle(this, style)

    internal val _isLiquidGlassEnabled = MutableStateFlow(
        prefs.getBoolean("liquid_glass_enabled", false)
    )
    val isLiquidGlassEnabled = _isLiquidGlassEnabled.asStateFlow()

    fun toggleLiquidGlass(enable: Boolean) =
        MainViewModelTheme.toggleLiquidGlass(this, enable)

    /** Live Update chip: show numeric percent (default on). */
    internal val _liveUpdateShowPercent = MutableStateFlow(
        prefs.getBoolean("live_update_show_percent", true)
    )
    val liveUpdateShowPercent = _liveUpdateShowPercent.asStateFlow()

    fun setLiveUpdateShowPercent(enable: Boolean) =
        MainViewModelTheme.setLiveUpdateShowPercent(this, enable)

    // Software UI density scale (1.0 = system default). Applied via LocalDensity in MainActivity.
    internal val _uiScale = MutableStateFlow(
        prefs.getFloat("ui_density_scale", 1f).coerceIn(0.85f, 1.30f)
    )
    val uiScale = _uiScale.asStateFlow()

    fun setUiScale(scale: Float) =
        MainViewModelTheme.setUiScale(this, scale)

    // --- User Profile ---
    internal val _userNickname = MutableStateFlow(prefs.getString("profile_nickname", "") ?: "")
    val userNickname = _userNickname.asStateFlow()

    internal val _userBio = MutableStateFlow(prefs.getString("profile_bio", "") ?: "")
    val userBio = _userBio.asStateFlow()

    internal val _userQq = MutableStateFlow(prefs.getString("profile_qq", "") ?: "")
    val userQq = _userQq.asStateFlow()

    fun updateUserProfile(
        nickname: String,
        bio: String,
        qq: String
    ) = MainViewModelProfile.updateUserProfile(this, nickname, bio, qq)

    // --- Workflow Inputs & Upload ---
    internal val _editableNodes = workflowEditor.editableNodes
    override val editableNodes = _editableNodes.asStateFlow()

    override fun toggleNodeVisibility(nodeId: String, key: String, visible: Boolean) =
        workflowEditor.toggleVisibility(nodeId, key, visible)

    internal fun setCurrentWorkflowKey(key: String?) {
        workflowEditor.selectKey(key, currentKindNs()) {
            MainViewModelRhWorkflows.refreshCurrentMeta(this)
        }
        if (!key.isNullOrBlank()) _latestGenerationList.value = loadLatestGenerationFor(key)
    }

    /** Persisted latest results of [key] (cached; agent-run items filtered out). */
    internal fun loadLatestGenerationFor(key: String): List<MediaOutput> {
        val cached = latestGenerationCache[key]
        val loaded = cached ?: loadWorkflowMediaList(key, "latest_generation_v1").also { latestGenerationCache[key] = it }
        val filtered = loaded.filterNot { it.promptId != null && agentRunPromptIds.contains(it.promptId) }
        if (filtered.size != loaded.size) {
            latestGenerationCache[key] = filtered
            persistWorkflowMediaList(key, "latest_generation_v1", filtered)
        }
        return filtered
    }

    // --- AI app screen session: its OWN state, fully separate from the Control screen's current workflow ---
    /** The AI app opened on the AI app screen (null = none). Control's currentWorkflow* never holds an app. */
    @Volatile
    internal var aiAppSession: AiAppSession? = null
    internal val aiAppSessionGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    internal val _aiAppEditableNodes = MutableStateFlow<List<EditableNode>>(emptyList())
    override val aiAppEditableNodes = _aiAppEditableNodes.asStateFlow()
    internal val _aiAppLatestResults = MutableStateFlow<List<MediaOutput>>(emptyList())
    override val aiAppLatestResults = _aiAppLatestResults.asStateFlow()
    internal val _aiAppNeedsReimport = MutableStateFlow(false)
    override val aiAppNeedsReimport = _aiAppNeedsReimport.asStateFlow()
    internal val _rhAppStatus = MutableStateFlow<String?>(null)
    /** Human readable status of the running RH AI-app task (queued / running / downloading). */
    override val rhAppStatus = _rhAppStatus.asStateFlow()

    /** Prepends downloaded RH outputs to the workflow's result list (shown in the app detail). */
    internal fun publishRhAppOutputs(workflowKey: String?, media: List<MediaOutput>, siteId: String? = null,
        serverId: String? = null) {
        if (!workflowKey.isNullOrBlank() && detachedWorkflowKeys.contains(workflowKey)) return
        if (!serverId.isNullOrBlank() && !siteId.isNullOrBlank()) {
            val source = com.comfyui.mobile.agent.workspace.AgentExecutionTarget("RUNNINGHUB", serverId, siteId).key
            media.forEach { agentWorkspace.record(it, source) }
        }
        if (workflowKey != null && workflowKey.startsWith(MainViewModelRhWorkflows.AGENT_KEY_PREFIX)) {
            if (agentWorkspace.status.value.mode != AgentStorageMode.LEGACY) return
            // Agent-triggered official cloud run: results belong to the agent list (not the Control screen).
            val next = (media + _agentLatestGenerationList.value).distinctBy { mediaKey(it) }
            _agentLatestGenerationList.value = next
            persistAgentMediaList(next)
            return
        }
        if (workflowKey.isNullOrBlank()) {
            // No owning workflow/app: never prepend into whatever result list is on screen
            // (the gallery merge happens separately in the caller).
            return
        }
        // RH results always belong to the cloud namespace of the site the task ran on (even if published after a
        // switch to local / the other site).
        val ns = siteId?.let { cloudNs(it) }
            ?: workflowKeyNamespaces[workflowKey]?.takeIf { it.startsWith("cloud@") }
            ?: currentKindNs().takeIf { it.startsWith("cloud@") }
            ?: cloudNs(com.comfyui.mobile.model.RunningHubSite.DOMESTIC_CN.id)
        if (workflowKeyNamespaces[workflowKey] != ns) latestGenerationCache.remove(workflowKey)
        workflowKeyNamespaces[workflowKey] = ns
        if (latestGenerationCache[workflowKey] == null) {
            latestGenerationCache[workflowKey] = loadWorkflowMediaList(workflowKey, "latest_generation_v1")
        }
        val existing = latestGenerationCache[workflowKey] ?: emptyList()
        setLatestGenerationForWorkflow(workflowKey, (media + existing).take(50))
    }

    internal fun resetLatestGenerationFor(workflowKey: String) = setLatestGenerationForWorkflow(workflowKey, emptyList())

    /** UI-only reset of the displayed result list (persisted per-workflow results are kept). */
    internal fun clearLatestGenerationList() {
        _latestGenerationList.value = emptyList()
    }

    /** Makes sure [media] (latest completed task of [workflowKey]) is at the top of its result list. */
    internal fun ensureLatestShown(workflowKey: String, media: List<MediaOutput>) {
        if (media.isEmpty()) return
        val current = when (workflowKey) {
            aiAppSession?.workflowKey -> _aiAppLatestResults.value
            currentWorkflowKey -> _latestGenerationList.value
            else -> return
        }
        val keys = current.map { mediaKey(it) }.toSet()
        if (media.all { keys.contains(mediaKey(it)) }) return
        setLatestGenerationForWorkflow(workflowKey, (media + current).distinctBy { mediaKey(it) }.take(50))
    }

    /** RH: prepend downloaded cloud results (local copies) to the in-app gallery list. */
    internal fun mergeCloudMediaIntoSession(media: List<MediaOutput>) {
        // The gallery is a media (image/video/audio/text) browser: generic file outputs (zip, ...) stay out of it.
        val galleryMedia = media.filter { it.mediaType != MediaType.FILE }
        if (galleryMedia.isEmpty()) return
        _sessionMediaList.value = (galleryMedia + _sessionMediaList.value)
            .distinctBy { mediaKey(it) }
            .sortedByDescending { it.timestamp }
            .filterNot { isDeletedKey(mediaKey(it)) }
    }

    override fun cancelRhAppRun() = MainViewModelRhApps.cancelRunning(this)

    internal fun setLatestGenerationForWorkflow(workflowKey: String, list: List<MediaOutput>) {
        latestGenerationCache[workflowKey] = list
        if (currentWorkflowKey == workflowKey) {
            _latestGenerationList.value = list
        }
        if (aiAppSession?.workflowKey == workflowKey) {
            _aiAppLatestResults.value = list
        }
        persistWorkflowMediaList(workflowKey, "latest_generation_v1", list)
    }

    internal fun compoundKeyOf(node: EditableNode): String =
        WorkflowGraph.compoundKeyOf(node)

    fun moveParamOrder(fromNodeId: String, fromKey: String, toNodeId: String, toKey: String) {
        moveParamOrder("$fromNodeId:$fromKey", "$toNodeId:$toKey")
    }

    fun moveParamOrder(fromCompound: String, toCompound: String) =
        workflowEditor.moveOrder(fromCompound, toCompound)

    override fun updateParamOrder(newOrderKeys: List<String>) =
        workflowEditor.updateOrder(newOrderKeys)

    fun saveParamOrder() = workflowEditor.saveOrder()

    override fun setParamCustomName(nodeId: String, key: String, name: String) =
        workflowEditor.setCustomName(nodeId, key, name)

    internal fun applyWorkflowParamOverrides(workflow: Map<String, Any>): Map<String, Any> =
        workflowEditor.applyOverrides(workflow)

    internal fun applyParamOverridesToWorkflow(
        workflow: Map<String, Any>,
        paramValues: MutableMap<String, Any>
    ): Pair<Map<String, Any>, List<String>> =
        WorkflowGraph.applyParamOverridesToWorkflow(workflow, paramValues)

    internal fun coerceToExpectedType(expected: Any?, incoming: Any?): Any? =
        WorkflowGraph.coerceToExpectedType(expected, incoming)

    // Persistence and namespace migration are owned by WorkflowPreferences.
    internal val workflowKeyNamespaces get() = workflowPreferences.namespaces
    internal fun cloudNs(siteId: String): String = "cloud@$siteId"

    internal fun currentKindNs(): String {
        val p = currentServerProfile()
        return if (p?.isRunningHub == true) cloudNs(p.effectiveRhSite.id) else "local"
    }

    internal fun <T> persistWorkflowMap(workflowKey: String, suffix: String, map: Map<String, T>) =
        workflowPreferences.persistMap(workflowKey, suffix, map)

    internal fun persistWorkflowStringList(workflowKey: String, suffix: String, list: List<String>) =
        workflowPreferences.persistStringList(workflowKey, suffix, list)

    private fun persistWorkflowMediaList(workflowKey: String, suffix: String, list: List<MediaOutput>) =
        workflowPreferences.persistMediaList(workflowKey, suffix, list)

    internal fun loadWorkflowStringList(workflowKey: String, suffix: String): MutableList<String> =
        workflowPreferences.loadStringList(workflowKey, suffix)

    private fun loadWorkflowMediaList(workflowKey: String, suffix: String): List<MediaOutput> =
        workflowPreferences.loadMediaList(workflowKey, suffix)

    internal fun loadWorkflowAnyMap(workflowKey: String, suffix: String): MutableMap<String, Any> =
        workflowPreferences.loadAnyMap(workflowKey, suffix)

    internal fun loadWorkflowStringMap(workflowKey: String, suffix: String): MutableMap<String, String> =
        workflowPreferences.loadStringMap(workflowKey, suffix)

    internal fun loadWorkflowBooleanMap(workflowKey: String, suffix: String): MutableMap<String, Boolean> =
        workflowPreferences.loadBooleanMap(workflowKey, suffix)

    internal fun parseWorkflowNodes(
        workflow: Map<String, Any>,
        objectInfo: Map<String, Any>,
        visibility: Map<String, Boolean>,
        labels: Map<String, String>
    ): List<EditableNode> =
        WorkflowGraph.parseWorkflowNodes(workflow, objectInfo, visibility, labels)

    private fun generateSummaryFromWorkflow(workflow: Map<String, Any>): String {
        val nodes = parseWorkflowNodes(workflow, _objectInfo.value, emptyMap(), emptyMap())
        return generatePromptSummary(nodes)
    }

    internal fun parseEditableNodes(workflow: Map<String, Any>?) =
        workflowEditor.rebuild(workflow)

    internal fun buildWorkflowNodeTitles(workflow: Map<String, Any>): Map<String, String> =
        WorkflowGraph.buildWorkflowNodeTitles(workflow)

    override fun updateNodeValue(nodeId: String, key: String, value: Any) {
        if (workflowEditor.updateValue(nodeId, key, value) == WorkflowEditorState.UpdateResult.LINKED_INPUT) {
            addLog("尝试更新连线端口 $nodeId:$key，已拦截以保护工作流连线", LogType.WARNING)
        }
    }

    fun appendPositivePrompt(textToAppend: String) {
        workflowEditor.appendPositivePrompt(textToAppend)?.let {
            showToast("已自动追加触发词: $it")
        }
    }

    /**
     * Uploads a picked file to an input node. [key] = the node input to set (from the tapped upload button);
     * [forAiApp] = the node belongs to the AI app screen's session (never Control's workflow) and vice versa.
     * The real file name / extension is kept; cloud uploads get a persistent local preview copy.
     */
    override fun uploadImageToNode(uri: Uri, nodeId: String, key: String?, forAiApp: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            var tempDir: java.io.File? = null
            try {
                addLog("准备上传文件...", LogType.INFO)
                val context = getApplication<Application>()
                val mimeType = context.contentResolver.getType(uri)
                val uploadName = UploadPreviewStore.uploadFileName(getFileName(uri), mimeType)
                val dir = java.io.File(context.cacheDir, "upload_${System.currentTimeMillis()}").apply { mkdirs() }
                tempDir = dir
                val file = java.io.File(dir, uploadName)
                val inputStream = context.contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    addLog("无法读取文件", LogType.ERROR)
                    return@launch
                }
                inputStream.use { input -> java.io.FileOutputStream(file).use { output -> input.copyTo(output) } }

                fun resolveKey(): String {
                    key?.takeIf { it.isNotBlank() }?.let { return it }
                    val nodes = if (forAiApp) _aiAppEditableNodes.value else _editableNodes.value
                    val node = nodes.firstOrNull { it.nodeId == nodeId && it is EditableNode.ImageInput } as? EditableNode.ImageInput
                    return node?.key ?: "image"
                }
                fun applyValue(value: String) {
                    if (forAiApp) aiAppUpdateNodeValue(nodeId, resolveKey(), value)
                    else updateNodeValue(nodeId, resolveKey(), value)
                }

                val session = if (forAiApp) aiAppSession else null
                val rhApp = session?.app?.takeIf { session.rhTemplate != null }
                if (rhApp != null || currentServerProfile()?.isRunningHub == true) {
                    // Cloud (AI app or workflow) input: official upload API (/task/openapi/upload);
                    // data.fileName ("api/...") is the LoadImage value.
                    val backend = currentRunningHubBackend()
                    if (backend == null) {
                        showToast("请先切换到 RunningHub 服务器")
                        return@launch
                    }
                    val up = backend.client.uploadInput(file, mimeType, uploadName)
                    up.onSuccess { fileName ->
                        // Upload names are only valid on the site they were uploaded to.
                        MainViewModelRhWorkflows.recordUploadSite(this@MainViewModel, fileName, backend.site.id)
                        // Persistent preview copy (survives restarts); fallback: the picked Uri (this session only).
                        val stored = runCatching { uploadPreviewStore.put(fileName, file) }.getOrNull()
                        if (stored == null) rhUploadPreviews[fileName] = uri.toString()
                        addLog("已上传到 RunningHub: $uploadName -> $fileName", LogType.SUCCESS)
                        withContext(Dispatchers.Main) {
                            applyValue(fileName)
                            showToast("文件已上传: $uploadName")
                        }
                    }.onFailure { e ->
                        addLog("RunningHub 上传失败: ${com.comfyui.mobile.network.SecretRedactor.redact(e.message)}", LogType.ERROR)
                        showToast("上传失败: ${e.message}")
                    }
                    return@launch
                }

                val resp = repository.uploadImage(file)
                if (resp != null) {
                    val serverName = if (resp.subfolder.isNullOrBlank()) resp.name else "${resp.subfolder}/${resp.name}"
                    addLog("上传成功: $serverName", LogType.SUCCESS)
                    withContext(Dispatchers.Main) {
                        applyValue(serverName)
                        showToast("文件已更新: ${resp.name}")
                    }
                } else {
                    addLog("上传失败", LogType.ERROR)
                    showToast("上传失败")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                addLog("上传异常: ${e.message}", LogType.ERROR)
            } finally {
                tempDir?.let { d -> runCatching { d.deleteRecursively() } }
            }
        }
    }

    /** Cloud model / LoRA library of the CURRENT RunningHub profile (official resource/list through RhHttpClient). */
    override suspend fun searchCloudModels(
        resourceType: String,
        keyword: String,
        page: Int,
        pageSize: Int,
        baseModels: List<String>?
    ): Result<com.comfyui.mobile.network.RhResourcePageData> {
        val backend = currentRunningHubBackend()
            ?: return Result.failure(IllegalStateException("请先切换到 RunningHub 服务器"))
        if (!backend.hasKey) return Result.failure(IllegalStateException("当前 RunningHub 服务器未配置 API Key"))
        return backend.client.resourceList(resourceType, keyword, page, pageSize, baseModels)
    }

    /** Local model / LoRA library from ComfyUI (via ComfyUI-Mobile-Bridge). */
    override suspend fun searchLocalModels(
        resourceType: String,
        keyword: String,
        page: Int,
        pageSize: Int,
        baseModel: String?,
        refresh: Boolean
    ): Result<com.comfyui.mobile.network.RhResourcePageData> {
        val server = currentServerProfile()
            ?: return Result.failure(IllegalStateException("请先选择 ComfyUI 服务器"))
        return repository.getLocalResourceList(resourceType, keyword, page, pageSize, baseModel, refresh)
            .map { modelPickerData.value.decorate(server, it) }
    }

    override suspend fun localModelDetail(item: com.comfyui.mobile.network.RhResourceItem):
        Result<com.comfyui.mobile.network.RhResourceItem> {
        val server = currentServerProfile()
            ?: return Result.failure(IllegalStateException("请先选择 ComfyUI 服务器"))
        return modelPickerData.value.detail(server, item)
    }
    override fun localModelImageLoader() = modelPickerData.value.imageLoader()
    override fun localModelImageRequest(url: String) = modelPickerData.value.imageRequest(url)

    fun fetchAssetsSummary() {
        val server = currentServerProfile()
        if (server?.isRunningHub == true) return
        viewModelScope.launch(Dispatchers.IO) {
            val res = repository.getAssetsSummary()
            res.onSuccess {
                _assetsSummary.value = it
            }
        }
    }

    suspend fun searchCivitaiModels(
        query: String,
        type: String? = null,
        baseModel: String? = null,
        page: Int = 1,
        cursor: String? = null,
        limit: Int = 20,
        sort: String? = null
    ): Result<com.comfyui.mobile.model.CivitaiSearchPageData> {
        return repository.searchCivitai(query, type, baseModel, page, cursor, limit, sort)
    }

    suspend fun getModelFolders(type: String): Result<List<com.comfyui.mobile.model.ModelFolderItem>> {
        return repository.getModelFolders(type)
    }

    suspend fun downloadCivitaiModel(
        modelId: Long,
        modelVersionId: Long,
        modelType: String? = null,
        modelRoot: String? = null,
        subfolder: String? = null,
        fileId: Long? = null,
        downloadUrl: String? = null,
        fileType: String? = null,
        role: String? = null
    ): Result<String> {
        return repository.downloadCivitaiModel(
            modelId, modelVersionId, modelType, modelRoot, subfolder, fileId, downloadUrl, fileType, role
        )
    }

    fun fetchDownloadTasks() {
        val server = currentServerProfile()
        if (server?.isRunningHub == true) return
        viewModelScope.launch(Dispatchers.IO) {
            val res = repository.getCivitaiDownloadTasks()
            res.onSuccess { data ->
                _downloadTasks.value = data.queue ?: emptyList()
                _downloadStats.value = data.stats ?: emptyMap()
            }
        }
    }

    suspend fun civitaiTaskAction(action: String, downloadId: String): Result<Unit> {
        val res = repository.civitaiTaskAction(action, downloadId)
        fetchDownloadTasks()
        return res
    }

    // --- AI app screen session API (never touches the Control screen's workflow state) ---
    override fun openAiAppSession(appId: String) = MainViewModelAiApps.loadAiApp(this, appId)
    override fun aiAppUpdateNodeValue(nodeId: String, key: String, value: Any) = MainViewModelAiAppSession.updateNodeValue(this, nodeId, key, value)
    override fun aiAppToggleNodeVisibility(nodeId: String, key: String, visible: Boolean) = MainViewModelAiAppSession.toggleVisibility(this, nodeId, key, visible)
    override fun aiAppSetParamCustomName(nodeId: String, key: String, name: String) = MainViewModelAiAppSession.setCustomName(this, nodeId, key, name)
    override fun aiAppUpdateParamOrder(newOrderKeys: List<String>) = MainViewModelAiAppSession.updateParamOrder(this, newOrderKeys)
    fun aiAppAppendPositivePrompt(textToAppend: String) = MainViewModelAiAppSession.appendPositivePrompt(this, textToAppend)
    override fun runAiApp(batchCount: Int) = MainViewModelAiAppSession.run(this, batchCount)

    internal fun persistServers(list: List<ServerProfile>) =
        MainViewModelServer.persistServers(this, list)

    internal fun applyServer(server: ServerProfile, startService: Boolean = true) =
        MainViewModelServer.applyServer(this, server, startService)

    fun selectServer(id: String) =
        MainViewModelServer.selectServer(this, id)

    fun upsertServer(
        id: String?,
        name: String,
        ip: String,
        port: String,
        scheme: String = "http",
        selectAfter: Boolean = true
    ): Boolean =
        MainViewModelServer.upsertServer(this, id, name, ip, port, scheme, selectAfter)

    fun upsertRunningHubServer(
        id: String?,
        name: String,
        site: com.comfyui.mobile.model.RunningHubSite,
        apiKey: String,
        gpuTier: com.comfyui.mobile.model.RunningHubGpuTier,
        selectAfter: Boolean = true
    ): Boolean =
        MainViewModelServer.upsertRunningHubServer(this, id, name, site, apiKey, gpuTier, selectAfter)

    suspend fun testRunningHubConnection(
        site: com.comfyui.mobile.model.RunningHubSite,
        apiKey: String
    ): Result<com.comfyui.mobile.network.RhAccountData> =
        repository.checkRunningHubStatus(site.hostUrl, apiKey)

    /**
     * AI app template via the isolated normal-TLS client, in the documented form
     * (GET /api/webapp/apiCallDemo?apiKey=..&webappId=.. + Bearer header). URLs are redacted in all logs.
     */
    private suspend fun fetchRhWebAppDemo(
        backend: com.comfyui.mobile.backend.RunningHubBackend,
        webappId: String
    ): Result<com.comfyui.mobile.network.RhWebAppDemoData> {
        val res = backend.client.webAppDemo(webappId)
        (res.exceptionOrNull() as? com.comfyui.mobile.network.RhApiException)?.let { err ->
            MainViewModelRhConn.onRhCallError(this, backend.serverId, err)
        }
        return res
    }

    private suspend fun resolveSyncedWorkflowTitle(
        backend: com.comfyui.mobile.backend.RunningHubBackend,
        id: String,
        customName: String?,
        apiTitle: String?
    ): String {
        customName?.trim()?.ifBlank { null }?.let { return it }
        apiTitle?.trim()?.ifBlank { null }?.let { return it }
        backend.client.workflowPublicName(id)?.let { return it }
        return "云端工作流_$id"
    }

    override fun syncRunningHubResource(
        inputUrlOrId: String,
        customName: String?,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val trimmed = inputUrlOrId.trim()
        if (trimmed.isBlank()) {
            onError("请输入链接或 ID")
            return
        }

        // Only the CURRENT RunningHub profile's own key is used (never borrowed from another profile).
        val rhServer = currentServerProfile()
        if (rhServer?.isRunningHub != true) {
            onError("请先切换到云端服务器")
            return
        }
        if (rhServer.effectiveRhApiKey.isBlank()) {
            onError("未配置 API Key")
            return
        }
        val backend = currentRunningHubBackend()
        if (backend == null) {
            onError("云端未就绪")
            return
        }
        val rhSiteId = rhServer.effectiveRhSite.id

        val urlSite = com.comfyui.mobile.model.RunningHubSite.fromUrl(trimmed)
        if (urlSite != null && urlSite != rhServer.effectiveRhSite) {
            onError("站点不匹配，该链接属于${urlSite.displayName}")
            return
        }

        val isExplicitWebapp = trimmed.contains("ai-detail", ignoreCase = true) || trimmed.contains("webapp", ignoreCase = true)
        val isExplicitWorkflow = trimmed.contains("workflow", ignoreCase = true) ||
            trimmed.contains("/post/", ignoreCase = true) ||
            Regex("""[/]i[/]""", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)
        val idCandidate = Regex("""\d{10,25}""").find(trimmed)?.value ?: trimmed

        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (isExplicitWebapp) {
                    val res = fetchRhWebAppDemo(backend, idCandidate)
                    res.onSuccess { data ->
                        MainViewModelAiApps.importCloudWebApp(this@MainViewModel, data, idCandidate, rhSiteId)
                        withContext(Dispatchers.Main) {
                            onSuccess("已导入「${data.webappName ?: idCandidate}」")
                        }
                    }.onFailure { err ->
                        withContext(Dispatchers.Main) {
                            onError(err.message ?: "导入失败")
                        }
                    }
                } else if (isExplicitWorkflow) {
                    val res = backend.client.workflowJson(idCandidate)
                    res.onSuccess { wfResult ->
                        val targetTitle = resolveSyncedWorkflowTitle(backend, idCandidate, customName, wfResult.title)
                        val savedFile = MainViewModelWorkflow.saveCloudWorkflowJson(this@MainViewModel, targetTitle, wfResult.prompt, rhSiteId)
                        if (savedFile != null) {
                            MainViewModelRhWorkflows.rememberImportedId(this@MainViewModel, savedFile.name, idCandidate, rhSiteId)
                            MainViewModelWorkflow.loadLocalWorkflow(this@MainViewModel, savedFile, silent = false)
                            withContext(Dispatchers.Main) {
                                onSuccess("已同步「$targetTitle」")
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                onError("保存失败")
                            }
                        }
                    }.onFailure { err ->
                        withContext(Dispatchers.Main) {
                            onError(err.message ?: "同步失败")
                        }
                    }
                } else {
                    val wfRes = backend.client.workflowJson(idCandidate)
                    if (wfRes.isSuccess) {
                        val wfResult = wfRes.getOrThrow()
                        val targetTitle = resolveSyncedWorkflowTitle(backend, idCandidate, customName, wfResult.title)
                        val savedFile = MainViewModelWorkflow.saveCloudWorkflowJson(this@MainViewModel, targetTitle, wfResult.prompt, rhSiteId)
                        if (savedFile != null) {
                            MainViewModelRhWorkflows.rememberImportedId(this@MainViewModel, savedFile.name, idCandidate, rhSiteId)
                            MainViewModelWorkflow.loadLocalWorkflow(this@MainViewModel, savedFile, silent = false)
                            withContext(Dispatchers.Main) {
                                onSuccess("已同步「$targetTitle」")
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                onError("保存失败")
                            }
                        }
                    } else {
                        val appRes = fetchRhWebAppDemo(backend, idCandidate)
                        if (appRes.isSuccess) {
                            val data = appRes.getOrThrow()
                            MainViewModelAiApps.importCloudWebApp(this@MainViewModel, data, idCandidate, rhSiteId)
                            withContext(Dispatchers.Main) {
                                onSuccess("已导入「${data.webappName ?: idCandidate}」")
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                onError(wfRes.exceptionOrNull()?.message ?: "ID 无效")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError(e.message ?: "导入失败")
                }
            }
        }
    }

    fun deleteServer(id: String) =
        MainViewModelServer.deleteServer(this, id)

    fun connect(ip: String, port: String, name: String = "", scheme: String = "http") =
        MainViewModelServer.connect(this, ip, port, name, scheme)

    fun ensureConnected(forceProbe: Boolean = false) {
        val ip = _savedIp.value
        val port = _savedPort.value
        val currentServer = currentServerProfile()
        repository.setCloudActive(currentServer?.isRunningHub == true)
        if (currentServer?.isRunningHub == true) {
            // Cloud: "connected" = API key verified by the account check (a rejected key stays blocked until
            // it is changed or the user retries). No WebSocket; object_info is loaded for node editing.
            if (!MainViewModelRhConn.isValidated(this, currentServer)) {
                MainViewModelRhConn.validateAndConnect(this)
                return
            }
            currentServerBaseUrlOrNull()?.let { repository.setBaseUrlOnly(it) }
            loadObjectInfoIfMissing()
            return
        }
        // Only connect to the resolved current profile; if it can't be resolved, do nothing.
        val url = currentServerBaseUrlOrNull() ?: return

        // Keep repository REST API baseUrl synchronized with target server
        repository.setBaseUrlOnly(url)

        if (!GenerationLiveUpdate.isUserStopRequested() &&
            (activeRunPromptIds.isNotEmpty() || _isRunning.value)
        ) {
            startActiveRunWatchdog()
            repository.setIsGenerating(true)
            if (!KeepAliveService.isServiceRunning.get()) {
                KeepAliveService.start(getApplication())
            }
        }

        if (forceProbe) {
            repository.webSocketManager.softEnsure(url, forceIfSuspicious = true)
            return
        }

        // Soft: keep healthy sockets; only repair when dead / disconnected.
        if (repository.webSocketManager.isSocketLive()) return
        if (repository.webSocketManager.connectionState.value == ConnectionState.CONNECTING) return

        val serverLabel = currentServer?.displayName ?: "$ip:$port"
        addLog("正在连接服务器 $serverLabel...", LogType.INFO)
        repository.webSocketManager.softEnsure(url)
    }

    private var cloudObjectInfoJob: Job? = null

    /**
     * Cloud: loads the read-only object_info of the cloud base URL once per server (node-editing dropdowns).
     * Local servers load it on WebSocket connect as before.
     */
    private fun loadObjectInfoIfMissing() {
        if (_objectInfo.value.isNotEmpty() || cloudObjectInfoJob?.isActive == true) return
        val sid = _currentServerId.value
        cloudObjectInfoJob = viewModelScope.launch {
            val info = repository.getObjectInfo() ?: return@launch
            if (_currentServerId.value != sid) return@launch
            _objectInfo.value = info
            currentWorkflow?.let { parseEditableNodes(it) }
            MainViewModelAiAppSession.rebuildNodes(this@MainViewModel)
            agentWorkflow?.let { MainViewModelAgent.parseAgentEditableNodes(this@MainViewModel, it) }
        }
    }

    /** Local ComfyUI only (cloud runs never use the WebSocket). */
    private suspend fun ensureSocketLiveBeforeDispatch() {
        if (!repository.webSocketManager.isSocketLive()) {
            val url = currentServerBaseUrlOrNull() ?: return
            repository.webSocketManager.softEnsure(url, forceIfSuspicious = true)
            withTimeoutOrNull(1500L) {
                repository.webSocketManager.connectionState.first { it == ConnectionState.CONNECTED }
            }
        }
    }

    internal val _rhAccountData = MutableStateFlow<com.comfyui.mobile.network.RhAccountData?>(null)
    val rhAccountData = _rhAccountData.asStateFlow()

    // --- RunningHub connection gate (API key validated by the account check; see MainViewModelRhConn) ---
    internal val _rhConnState = MutableStateFlow(RhConnState.IDLE)
    val rhConnState = _rhConnState.asStateFlow()
    internal val _rhConnMessage = MutableStateFlow<String?>(null)
    /** Detail for the RH connection state (error text), null when fine. */
    val rhConnMessage = _rhConnMessage.asStateFlow()
    internal val _rhQueueStatus = MutableStateFlow<com.comfyui.mobile.network.RhQueueStatus?>(null)
    /** GET /openapi/v2/queue/status of the current RH key (null = not loaded / not RH). */
    val rhQueueStatus = _rhQueueStatus.asStateFlow()
    @Volatile internal var rhValidatedSig: String? = null
    @Volatile internal var rhUnauthorizedSig: String? = null
    @Volatile internal var rhLastValidateAttemptAt: Long = 0L
    internal var rhValidateJob: Job? = null

    /** Dashboard status of the current RH profile: label + fully-connected flag (derived, never hardcoded). */
    val rhStatusLabel: StateFlow<Pair<String, Boolean>> =
        _rhConnState.map { st -> MainViewModelRhConn.statusLabel(st) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, "未连接" to false)

    /** Explicit retry (re-validates the key, then reconnects). */
    fun retryRhConnection() = MainViewModelRhConn.retry(this)

    val rhTaskRecords: StateFlow<List<com.comfyui.mobile.network.RhTaskRecord>> =
        combine(taskStore.tasks, _currentServerId, _servers) { tasks, sid, servers ->
            val profile = servers.firstOrNull { it.effectiveId == sid }
            if (profile?.isRunningHub != true) emptyList()
            else {
                val site = profile.effectiveRhSite.id
                tasks.filter { it.matchesRhSite(site, sid) }
                    .map { it.toRhView() }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            try {
                // viewModelScope runs on Main.immediate: yield so the rest of the VM finishes constructing
                // (poll-job maps etc. are declared further down) before any task is resumed.
                kotlinx.coroutines.yield()
                taskStore.ensureLoaded()
                val n = taskStore.migrateLegacyRhRecords(prefs)
                if (n > 0) addLog("已迁移 $n 条旧版云端任务记录到任务库（原数据保留）", LogType.INFO)
                val pending = taskStore.tasks.value.count {
                    it.backend == com.comfyui.mobile.backend.BackendKind.RUNNINGHUB.name && !it.taskState.isTerminal
                }
                if (pending > 0) addLog("任务库中有 $pending 个未完成的云端任务，验证 Key 后继续跟踪", LogType.INFO)
                // RH profile: validate the key first; on success the in-flight official tasks of this
                // profile are resumed (unfinished old proxy-mode records are marked lost).
                MainViewModelRhConn.validateAndConnect(this@MainViewModel)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addLog("任务库加载失败: ${e.message}", LogType.WARNING)
            }
        }
    }

    /** The RunningHub backend for the current profile (activating it if needed), or null in local mode. */
    internal fun currentRunningHubBackend(): com.comfyui.mobile.backend.RunningHubBackend? {
        val server = currentServerProfile() ?: return null
        if (!server.isRunningHub) return null
        return backendProvider.activate(server) as? com.comfyui.mobile.backend.RunningHubBackend
    }

    /** The saved profile matching the current server id, or null (never synthesized from legacy prefs). */
    internal fun currentServerProfile(): ServerProfile? {
        val id = _currentServerId.value
        if (id.isBlank()) return null
        return _servers.value.firstOrNull { it.effectiveId == id }
    }

    /** Valid base URL of the current profile, or null if it can't be resolved / would be invalid. */
    internal fun currentServerBaseUrlOrNull(): String? {
        val server = currentServerProfile() ?: return null
        if (server.isRunningHub && server.effectiveRhApiKey.isBlank()) return null
        return server.baseUrl.takeIf { com.comfyui.mobile.network.SecretRedactor.isValidBaseUrl(it) }
    }

    /** Records screen: re-poll non-terminal / LOST RH tasks and re-download missing outputs of succeeded ones. */
    fun refreshCloudTasks() = MainViewModelRhApps.refreshCloudTasks(this)

    /** RunningHub AI-app poll jobs by TaskStore localId (in-app polling; service integration is Phase 2b). */
    internal val rhAppPollJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /**
     * Called by applyServer() before switching to another server profile: clears the displayed
     * account/balance, stops the old profile's cloud polling and (on a local<->cloud switch) the
     * in-flight run UI state, so nothing leaks across servers/modes. Tasks themselves stay in TaskStore
     * and RH AI-app polling resumes when their profile becomes active again.
     */
    internal fun onServerSwitching(modeChanged: Boolean) {
        MainViewModelAgent.invalidateContext(this, "服务器已切换，本轮已停止")
        _rhAccountData.value = null
        MainViewModelRhConn.reset(this)
        rhAppPollJobs.values.forEach { it.cancel() }
        rhAppPollJobs.clear()
        _rhAppRunning.value = false
        // Server-scoped caches: never show / use the previous server's queue, history or node definitions.
        _queueList.value = emptyList()
        _objectInfo.value = emptyMap()
        // The gallery list holds /view references of the previous server (relative to its base URL).
        _sessionMediaList.value = emptyList()
        if (modeChanged) {
            // The AI app screen's session belongs to the previous mode's app storage.
            MainViewModelAiAppSession.invalidate(this)
            latestGenerationCache.clear()
            if (_isRunning.value || activeRunPromptIds.isNotEmpty() || _executionStatus.value == "RUNNING") {
                resetRunState()
                repository.setIsGenerating(false)
            }
            _latestGenerationList.value = emptyList()
        }
    }

    internal val _rhAppRunning = MutableStateFlow(false)
    /** True while an RH AI-app task of the current profile is being submitted/polled. */
    override val rhAppRunning = _rhAppRunning.asStateFlow()

    internal val _rhOfficialBusy = MutableStateFlow(false)
    /** True while any official-API task (AI app / cloud workflow) of the current profile is submitting or in flight. */
    override val rhOfficialBusy = _rhOfficialBusy.asStateFlow()

    /**
     * Dashboard progress card (cloud): the current profile's in-flight cloud task (running first, then
     * queued / submitting; oldest first) and how many are in flight. Derived from the TaskStore only.
     */
    val rhActiveTask: StateFlow<Pair<com.comfyui.mobile.data.TaskRecord?, Int>> =
        combine(taskStore.tasks, _currentServerId, _servers) { tasks, sid, servers ->
            val site = servers.firstOrNull { it.effectiveId == sid }?.takeIf { it.isRunningHub }?.effectiveRhSite?.id
            val inFlight = tasks.filter {
                it.backend == com.comfyui.mobile.backend.BackendKind.RUNNINGHUB.name &&
                    it.serverId == sid &&
                    (it.rhSite.isNullOrBlank() || it.rhSite == site) &&
                    MainViewModelRhApps.isTaskApiOrigin(it.origin) &&
                    it.taskState.isActive
            }
            val first = inFlight.sortedWith(
                compareBy<com.comfyui.mobile.data.TaskRecord>(
                    { if (it.taskState == com.comfyui.mobile.backend.TaskState.RUNNING) 0 else 1 },
                    { it.createdAt }
    )
            ).firstOrNull()
            first to inFlight.size
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null to 0)

    init {
        // Keep the foreground service alive while official cloud tasks are in flight (polling in background).
        MainViewModelRhWorkflows.startBusyWatcher(this)
    }

    /**
     * 任务记录「清除」: removes records of the current RunningHub site only. Other site's rows stay.
     * In-flight tracking of the cleared site stops; nothing is cancelled on RunningHub.
     */
    fun clearRhTaskRecords() {
        val profile = currentServerProfile()
        val siteId = profile?.takeIf { it.isRunningHub }?.effectiveRhSite?.id
        val sid = profile?.effectiveId.orEmpty()
        if (siteId.isNullOrBlank()) {
            showToast("当前不是 RunningHub 服务器")
            return
        }
        val dropping = taskStore.tasks.value.filter { it.matchesRhSite(siteId, sid) }
        val dropIds = dropping.map { it.localId }.toHashSet()
        rhAppPollJobs.entries.toList().forEach { (k, job) ->
            if (dropIds.any { k.endsWith(it) } || (dropping.any { !it.taskState.isTerminal } && k.startsWith("batch_"))) {
                job.cancel()
                rhAppPollJobs.remove(k)
            }
        }
        if (dropping.any { it.taskState.isActive }) {
            _rhAppRunning.value = false
            _rhAppStatus.value = null
        }
        viewModelScope.launch {
            try {
                taskStore.ensureLoaded()
                val unfinished = dropping.count { !it.taskState.isTerminal }
                val n = taskStore.removeMatching { it.matchesRhSite(siteId, sid) }
                val extra = if (unfinished > 0) "，$unfinished 个进行中的任务已停止跟踪（未在云端取消）" else ""
                addLog("已清除 $n 条云端任务记录$extra；已下载的文件保留", LogType.INFO)
                showToast("已清除 $n 条任务记录")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addLog("清除任务记录失败: ${e.message}", LogType.WARNING)
                showToast("清除任务记录失败: ${e.message}")
            }
        }
    }

    internal val _isRefreshingCloudTasks = MutableStateFlow(false)
    /** True while 刷新云端任务 is running (records screen spinner). */
    val isRefreshingCloudTasks = _isRefreshingCloudTasks.asStateFlow()

    internal val _isRefreshingRhAccount = MutableStateFlow(false)
    val isRefreshingRhAccount = _isRefreshingRhAccount.asStateFlow()

    /** Awaits a fresh RunningHub account status (updates [rhAccountData]); null if not RH or the call failed. */
    suspend fun refreshRunningHubAccountStatusAwait(): com.comfyui.mobile.network.RhAccountData? {
        val server = currentServerProfile()
        if (server?.isRunningHub != true) return null
        // Only the current profile's own key is ever used (no fallback to another RH profile).
        if (server.effectiveRhApiKey.isBlank()) return null
        // A rejected key stays blocked (no calls) until it is changed or the user explicitly retries.
        if (MainViewModelRhConn.isUnauthorized(this, server)) return null
        val backend = currentRunningHubBackend() ?: return null
        val res = backend.client.accountStatus()
        res.onSuccess { data ->
            // Drop the answer if the user switched servers meanwhile (no cross-profile leak).
            if (_currentServerId.value == backend.serverId) {
                _rhAccountData.value = data
                MainViewModelRhConn.onAccountOk(this, server)
            }
        }.onFailure { err ->
            addLog("获取 RunningHub 账户状态失败: ${err.message}", LogType.WARNING)
            MainViewModelRhConn.onRhCallError(this, backend.serverId, err)
        }
        return res.getOrNull()
    }

    fun fetchRunningHubAccountStatus() {
        val server = currentServerProfile()
        if (server?.isRunningHub != true) return
        viewModelScope.launch {
            _isRefreshingRhAccount.value = true
            try {
                val data = refreshRunningHubAccountStatusAwait()
                if (data != null) MainViewModelRhConn.refreshQueueStatus(this@MainViewModel)
                if (data != null) {
                    val coinsText = data.displayCoins?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "--"
                    addLog("RunningHub 状态更新: 余额 $coinsText RH币, 运行中任务: ${data.taskCounts}", LogType.INFO)
                }
            } finally {
                _isRefreshingRhAccount.value = false
            }
        }
    }

    private var systemStatsJob: Job? = null
    private var systemStatsRequestBase: String? = null

    fun fetchSystemStats(refreshAssets: Boolean = true) {
        val server = currentServerProfile()
        if (server?.isRunningHub == true) {
            fetchRunningHubAccountStatus()
            return
        }
        if (refreshAssets) fetchAssetsSummary()
        val requestBase = repository.baseUrl.value
        val serverId = currentServerId.value
        if (systemStatsJob?.isActive == true && systemStatsRequestBase == requestBase) return
        systemStatsJob?.cancel()
        systemStatsRequestBase = requestBase
        systemStatsJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val stats = repository.getSystemStats()
                val mobile = kotlinx.coroutines.withTimeoutOrNull(2_000L) { repository.getMobileSystemMonitor() }
                val data = com.comfyui.mobile.data.SystemMonitorMapper.fromRest(stats, mobile) ?: return@launch
                // A slow sample from the previous server must not appear on the newly selected one.
                if (repository.baseUrl.value == requestBase && currentServerId.value == serverId && !repository.cloudActive) {
                    repository.webSocketManager.updateSystemStats(data)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    fun startForegroundHeartbeat() {
        if (foregroundHeartbeatJob?.isActive == true) return
        foregroundHeartbeatJob = viewModelScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(25_000L)
                if (!_isRunning.value && activeRunPromptIds.isEmpty()) {
                    fetchQueue()
                    fetchSystemStats()
                    if (!repository.webSocketManager.isSocketLive()) {
                        ensureConnected(forceProbe = false)
                    }
                }
            }
        }
    }

    fun stopForegroundHeartbeat() {
        foregroundHeartbeatJob?.cancel()
        foregroundHeartbeatJob = null
    }

    override fun onCleared() {
        MainViewModelAgent.invalidateContext(this, "会话已关闭")
        llmClient.cancelActive()
        if (modelPickerData.isInitialized()) modelPickerData.value.close()
        if (agentWorkspaceData.isInitialized()) agentWorkspace.close()
        super.onCleared()
        networkMonitor.stop()
        stopForegroundHeartbeat()
    }

    internal fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = getApplication<Application>().contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        result = cursor.getString(index)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }

    override fun loadWorkflow(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val content = getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader().use { it?.readText() }
                if (content != null) {
                    val type = object : TypeToken<Map<String, Any>>() {}.type
                    val rawMap: Map<String, Any> = gson.fromJson(content, type)
                    val workflowMap = sanitizeNumbers(rawMap) as Map<String, Any>
                    val name = getFileName(uri) ?: uri.lastPathSegment ?: "导入的工作流"
                    
                    // Check if it is API format or Standard Workflow format
                    if (workflowMap.containsKey("nodes") && workflowMap.containsKey("links")) {
                        showToast("警告：检测到标准工作流格式")
                        addLog("错误：检测到标准工作流格式 (Standard Format)。ComfyUI API 仅支持 API 格式。", LogType.ERROR)
                        addLog("请在 ComfyUI 设置中开启 'Dev Mode'，然后使用 'Save (API Format)' 保存。", LogType.WARNING)
                        // We still load it, but user knows it might fail
                        val saved = saveWorkflowJsonToLocal(name, content)
                        setCurrentWorkflowKey(saved?.name)
                        currentWorkflow = applyWorkflowParamOverrides(workflowMap)
                        resetRunStateForWorkflowLoad()
                        parseEditableNodes(currentWorkflow)
                        _currentWorkflowName.value = saved?.name ?: name
                    } else {
                        val saved = saveWorkflowJsonToLocal(name, content)
                        setCurrentWorkflowKey(saved?.name)
                        currentWorkflow = applyWorkflowParamOverrides(workflowMap)
                        resetRunStateForWorkflowLoad()
                        parseEditableNodes(currentWorkflow)
                        _currentWorkflowName.value = saved?.name ?: name
                        showToast("API 工作流加载成功: $name")
                        addLog("本地 API 工作流加载成功: $name", LogType.SUCCESS)
                    }
                } else {
                    showToast("无法读取文件内容")
                    addLog("无法读取文件内容", LogType.ERROR)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                showToast("加载失败: ${e.message}")
                addLog("加载失败: ${e.message}", LogType.ERROR)
            }
        }
    }

    override fun checkLatestHistory() {
        if (repository.cloudActive) return
        val source = agentWorkspace.currentTarget().key
        val sourceBase = repository.baseUrl.value
        val epoch = agentWorkspace.callbackEpoch
        viewModelScope.launch {
            try {
                addLog("正在同步历史记录...", LogType.INFO)
                val history = repository.getHistoryOrNull()
                if (source != agentWorkspace.currentTarget().key || sourceBase != repository.baseUrl.value ||
                    epoch != agentWorkspace.callbackEpoch) return@launch
                if (history == null) {
                    addLog("同步历史记录失败（网络或服务器错误），保留现有数据", LogType.WARNING)
                    return@launch
                }
                
                // Populate activePrompts from history and update promptMap with new format
                val newPromptMap = _promptMap.value.toMutableMap()
                var mapChanged = false

                for (item in history) {
                    try {
                        val summary = generateSummaryFromWorkflow(item.workflow)
                        if (summary.isNotBlank()) {
                            activePrompts[item.promptId] = summary

                            // Map images in this history item to this summary
                            val outputs = item.outputs
                            if (outputs != null) {
                                for (nodeOutput in outputs.values) {
                                    if (nodeOutput is Map<*, *>) {
                                        val images = nodeOutput["images"] as? List<*> 
                                            ?: nodeOutput["gifs"] as? List<*> 
                                            ?: emptyList<Any>()
                                        
                                        for (imgObj in images) {
                                            if (imgObj is Map<*, *>) {
                                                val filename = imgObj["filename"] as? String
                                                val subfolder = imgObj["subfolder"] as? String ?: ""
                                                val type = imgObj["type"] as? String ?: "output"
                                                
                                                if (!filename.isNullOrBlank()) {
                                                    val key = "$subfolder|$filename|$type"
                                                    if (newPromptMap[key] != summary) {
                                                        newPromptMap[key] = summary
                                                        mapChanged = true
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore parsing errors for history
                    }
                }

                if (mapChanged) {
                    _promptMap.value = newPromptMap
                    savePromptMap()
                }

                val extId = lastExternalPromptId
                if (!extId.isNullOrBlank()) {
                    val isAgentRun = isAgentTask(extId)
                    if (isAgentRun) {
                        recordAgentRunPromptId(extId)
                    } else if (currentWorkflow == null) {
                        val hit = history.firstOrNull { it.promptId == extId }
                        _currentWorkflowName.value = hit?.name ?: "电脑端任务 $extId"
                    }
                    lastExternalPromptId = null
                }
                val foundMedia = mutableListOf<MediaOutput>()
                // We will re-assign timestamps based on history order (assuming history is Oldest->Newest or Newest->Oldest).
                // If user says "Latest is at back", it implies we are putting them in reverse order.
                // We'll collect all, then verify order.
                
                for (historyItem in history) {
                    agentWorkspace.completeLocal(source, historyItem.promptId,
                        if (historyItem.interrupted) "CANCELLED" else if (historyItem.statusStr == "error") "FAILED" else "SUCCESS",
                        historyItem.errorMessage)
                    val outputs = historyItem.outputs
                    if (outputs != null) {
                        for ((_, nodeOutput) in outputs) {
                             if (nodeOutput is Map<*, *>) {
                                 // The key is usually "images" or "gifs" etc. but we iterate values
                                 for ((_, value) in nodeOutput) {
                                     if (value is List<*>) {
                                         for (item in value) {
                                             if (item is Map<*, *>) {
                                                 val filename = item["filename"] as? String
                                                 val subfolder = item["subfolder"] as? String ?: ""
                                                val type = item["type"] as? String ?: "output"
                                                
                                                if (!filename.isNullOrBlank()) {
                                                    val lower = filename.lowercase()
                                                    val mediaType = when {
                                                        lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mov") -> MediaType.VIDEO
                                                        lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".ogg") || lower.endsWith(".flac") -> MediaType.AUDIO
                                                        lower.endsWith(".txt") || lower.endsWith(".json") || lower.endsWith(".yaml") || lower.endsWith(".yml") -> MediaType.TEXT
                                                        else -> MediaType.IMAGE
                                                    }

                                                     foundMedia.add(
                                                        MediaOutput(
                                                            filename = filename,
                                                            subfolder = subfolder,
                                                            type = type,
                                                            mediaType = mediaType,
                                                            timestamp = 0L, // Will assign later
                                                            promptId = historyItem.promptId,
                                                            sourceKey = source, workspaceEpoch = epoch,
                                                            sourceUrl = repository.getImageUrl(filename, subfolder, type, sourceBase)
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                             }
                        }
                    }
                }
                
                // If history is Oldest -> Newest (typical append log), we need to reverse it to get Newest -> Oldest.
                // We will assign timestamps such that the first item (Newest) has the highest timestamp.
                val reversedMedia = foundMedia.reversed()
                val now = System.currentTimeMillis()
                
                // Deduplicate (Keep first occurrence which is latest)
                val distinctMedia = reversedMedia
                    .distinctBy { "${it.subfolder}|${it.filename}|${it.type}" }
                    .mapIndexed { index, item ->
                        item.copy(timestamp = now - (index * 100))
                    }
                    .sortedByDescending { it.timestamp }
                    .filterNot { isDeletedKey(mediaKey(it)) }
                
                if (distinctMedia.isNotEmpty()) {
                    _sessionMediaList.value = distinctMedia

                    distinctMedia.forEach { media ->
                        agentWorkspace.record(media, source)
                    }
                    val agentMedia = if (agentWorkspace.status.value.mode == AgentStorageMode.LEGACY)
                        distinctMedia.filter { it.promptId != null && agentRunPromptIds.contains(it.promptId) } else emptyList()
                    if (agentMedia.isNotEmpty()) {
                        val mergedAgent = (agentMedia + _agentLatestGenerationList.value)
                            .distinctBy { "${it.subfolder}|${it.filename}|${it.type}" }
                            .sortedByDescending { it.timestamp }
                            .filterNot { isDeletedKey(mediaKey(it)) }
                        _agentLatestGenerationList.value = mergedAgent
                        persistAgentMediaList(mergedAgent)
                    }

                    addLog("已同步 ${distinctMedia.size} 个文件", LogType.SUCCESS)
                } else {
                    addLog("历史记录中未找到媒体文件", LogType.WARNING)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                addLog("同步历史记录失败: ${e.message}", LogType.ERROR)
            }
        }
    }

    override fun deleteMedia(mediaList: List<MediaOutput>) {
        if (mediaList.isEmpty()) return

        val keys = mediaList.map { mediaKey(it) }.toSet()
        addDeletedKeys(keys)

        _sessionMediaList.value = _sessionMediaList.value.filterNot { keys.contains(mediaKey(it)) }
        val workflowKey = currentWorkflowKey
        if (!workflowKey.isNullOrBlank()) {
            val current = (latestGenerationCache[workflowKey] ?: _latestGenerationList.value)
            val next = current.filterNot { keys.contains(mediaKey(it)) }
            setLatestGenerationForWorkflow(workflowKey, next)
        }

        if (repository.cloudActive) {
            showToast("已删除")
            return
        }
        viewModelScope.launch {
            try {
                val ok = repository.deleteMediaOnServer(mediaList)
                if (ok) {
                    showToast("已发送删除请求（电脑端）")
                    addLog("已发送删除请求（电脑端）: ${mediaList.size} 个文件", LogType.WARNING)
                } else {
                    showToast("删除请求发送失败")
                    addLog("删除请求发送失败（可能未安装 DeleteFiles 节点）", LogType.ERROR)
                }
            } catch (e: Exception) {
                showToast("删除失败: ${e.message}")
                addLog("删除失败: ${e.message}", LogType.ERROR)
            }
        }
    }

    override fun fetchQueue() {
        if (repository.cloudActive) return
        viewModelScope.launch {
            // null = queue state unknown (error): keep the current list instead of showing an empty queue.
            val list = repository.getQueueOrNull() ?: return@launch
            _queueList.value = list
        }
    }
    
    override fun stopAllWorkflows() {
        GenerationLiveUpdate.beginUserStop()
        repository.setIsGenerating(false)
        _isRunning.value = false
        _executionStatus.value = "IDLE"
        if (currentServerProfile()?.isRunningHub == true) {
            // Cloud: cancel the in-flight taskId(s) via /task/openapi/cancel.
            MainViewModelRhApps.cancelRunning(this)
            addLog("停止云端任务", LogType.WARNING)
            KeepAliveService.stop(getApplication())
            viewModelScope.launch {
                kotlinx.coroutines.delay(150L)
                GenerationLiveUpdate.notifyFailedThenDismiss(getApplication())
            }
            return
        }
        viewModelScope.launch {
            sendJob?.cancel()
            sendJob = null
            sendJobToken = null
            isSending = false
            repository.interrupt()
            repository.clearQueue()
            KeepAliveService.stop(getApplication())
            kotlinx.coroutines.delay(150L)
            GenerationLiveUpdate.notifyFailedThenDismiss(getApplication())
            showToast("已发送停止所有指令")
            addLog("停止所有工作流 (中断 + 清空队列)", LogType.WARNING)
            resetRunState()
            fetchQueue() // Refresh queue
        }
    }

    private var isSending = false
    private var sendJob: Job? = null
    private var sendJobToken: Any? = null

    internal fun randomizeSeedsForSend(workflow: Map<String, Any>): Map<String, Any> =
        WorkflowGraph.randomizeSeedsForSend(workflow)

    /** Console only: the redacted raw /prompt response of the last failed submit (debugging aid). */
    private fun logQueuePromptRawBody() {
        val raw = repository.lastQueuePromptRawBody ?: return
        addLog("服务器原始响应（已脱敏）: ${raw.take(1500)}", LogType.WARNING)
    }

    /** Control screen run (the AI app screen runs its own session via [runAiApp]). */
    override fun runWorkflow(batchCount: Int) {
        // RunningHub cloud workflows: always the official task API (tasks survive app kill).
        if (currentServerProfile()?.isRunningHub == true) {
            @Suppress("UNCHECKED_CAST")
            val wf = currentWorkflow?.let { sanitizeNumbers(applyWorkflowParamOverrides(it)) as Map<String, Any> }
            if (wf == null) {
                showToast("请先加载工作流")
                return
            }
            val title = _currentWorkflowName.value?.removeSuffix(".json")?.removeSuffix(".JSON")?.ifBlank { null } ?: "云端工作流"
            MainViewModelRhWorkflows.runCurrent(this, wf, currentWorkflowKey, title, batchCount)
            return
        }
        runLocalWorkflow(batchCount)
    }

    /**
     * Local ComfyUI submit path (/prompt + WebSocket). [session] = run the AI app screen's session graph
     * (its own key / values); null = the Control screen's current workflow.
     */
    internal fun runLocalWorkflow(batchCount: Int, session: AiAppSession? = null) {
        if (isSending) return
        isSending = true

        val workflowKeyForRun = if (session != null) session.workflowKey else currentWorkflowKey
        @Suppress("UNCHECKED_CAST")
        val baseWorkflow = if (session != null) {
            sanitizeNumbers(session.workflow) as? Map<String, Any>
        } else {
            currentWorkflow?.let { sanitizeNumbers(applyWorkflowParamOverrides(it)) as Map<String, Any> }
        }
        val summaryNodes = if (session != null) _aiAppEditableNodes.value else _editableNodes.value
        if (baseWorkflow != null) {
            if (!workflowKeyForRun.isNullOrBlank()) {
                val runId = System.currentTimeMillis()
                activeRunIdByWorkflowKey[workflowKeyForRun] = runId
                setLatestGenerationForWorkflow(workflowKeyForRun, emptyList())
            }
            sendJob?.cancel()
            val token = Any()
            sendJobToken = token
            val job = viewModelScope.launch {
                try {
                    ensureSocketLiveBeforeDispatch()
                    val now = System.currentTimeMillis()
                    val summary = generatePromptSummary(summaryNodes)
                    activeRunStartedAt = now
                    
                    var successCount = 0
                    repeat(batchCount) { i ->
                        currentCoroutineContext().ensureActive()
                        val workflow = randomizeSeedsForSend(baseWorkflow)
                        val pid = repository.queuePrompt(workflow)
                        if (!pid.isNullOrBlank()) {
                            // Track all IDs in batch
                            activeRunPromptIds.add(pid)
                            activePrompts[pid] = summary
                            if (!workflowKeyForRun.isNullOrBlank()) {
                                val runId = activeRunIdByWorkflowKey[workflowKeyForRun]
                                if (runId != null) {
                                    promptIdToWorkflowKey[pid] = workflowKeyForRun
                                    promptIdToRunId[pid] = runId
                                }
                            }
                            successCount++
                            addLog("任务已发送 ($i/$batchCount) (ID: $pid)", LogType.INFO)
                        } else {
                            addLog("任务发送失败 ($i/$batchCount): ${repository.lastQueuePromptError ?: "null pid"}", LogType.ERROR)
                            logQueuePromptRawBody()
                        }
                        // Small delay to ensure server order
                        kotlinx.coroutines.delay(100)
                    }

                    if (successCount > 0) {
                        GenerationLiveUpdate.noteUserStartedRun()
                        _isRunning.value = true
                        _executionStatus.value = "RUNNING"
                        startActiveRunWatchdog()
                        showToast("已发送 $successCount 个任务")
                    } else {
                        _isRunning.value = false
                        _executionStatus.value = "IDLE"
                        val detail = repository.lastQueuePromptError?.take(180)
                        showToast(if (detail.isNullOrBlank()) "任务发送失败，请检查连接和参数" else "任务发送失败：$detail")
                    }
                    fetchQueue() // Refresh queue
                } catch (_: CancellationException) {
                    _isRunning.value = false
                    _executionStatus.value = "IDLE"
                } catch (e: Exception) {
                    addLog("本地任务运行异常: ${e.message}", LogType.ERROR)
                    _isRunning.value = false
                    _executionStatus.value = "IDLE"
                    showToast("运行出错：${e.message}")
                } finally {
                    isSending = false
                    if (sendJobToken === token) {
                        sendJob = null
                        sendJobToken = null
                    }
                }
            }
            sendJob = job
        } else {
            isSending = false
            showToast("请先加载工作流")
            addLog("尝试运行，但未加载工作流", LogType.WARNING)
        }
    }
    
    fun addLog(text: String, type: LogType = LogType.INFO) = consoleLog.add(text, type)

    fun getConsoleLogText(): String = consoleLog.render(listOf(
        "baseUrl" to com.comfyui.mobile.network.SecretRedactor.redact(repository.baseUrl.value),
        "agentWorkflow" to "${_agentWorkflowSource.value}/${_agentWorkflowId.value}/${_agentWorkflowLabel.value}",
        "llmBase" to _llmBaseUrl.value,
        "llmModel" to _llmModel.value,
        "currentWorkflowKey" to currentWorkflowKey,
        "currentWorkflowName" to _currentWorkflowName.value,
        "isRunning" to "${_isRunning.value} status=${_executionStatus.value} isSending=$isSending"
    ))

    fun exportConsoleLogFile(): File =
        consoleLog.export(getApplication<Application>().cacheDir, getConsoleLogText())

    fun clearConsoleLog() = consoleLog.clear()
    
    fun simulateTestImage() {
        val testMedia = MediaOutput(
            filename = "example.png", // This file likely doesn't exist, but we can see the loading error or use a placeholder
            subfolder = "",
            type = "output",
            mediaType = MediaType.IMAGE
        )
        viewModelScope.launch {
            repository.webSocketManager.injectTestMedia(testMedia)
        }
    }
    
    // --- Local Saved Workflows (Simple implementation using SharedPreferences or File) ---
    // For simplicity, we can save ONE "favorite" or use a local file list.
    // Let's implement a simple "Save Current" which saves to App's private storage with a timestamp name.
    
    override fun overwriteCurrentWorkflowToLocal() = MainViewModelWorkflow.overwriteCurrentWorkflowToLocal(this)
    fun saveCurrentWorkflowToLocal() = MainViewModelWorkflow.saveCurrentWorkflowToLocal(this)
    override fun refreshSavedWorkflows() = MainViewModelWorkflow.refreshSavedWorkflows(this)
    fun getLocalSavedWorkflows(): List<java.io.File> = MainViewModelWorkflow.getLocalSavedWorkflows(this)
    /** Saved workflows shown in lists: in cloud mode only the current site's and unmarked ones. */
    override fun getSiteSavedWorkflows(): List<java.io.File> = MainViewModelWorkflow.getSiteSavedWorkflows(this)
    override fun loadLocalWorkflow(file: java.io.File, silent: Boolean) = MainViewModelWorkflow.loadLocalWorkflow(this, file, silent)
    internal fun restoreLastWorkflow() = MainViewModelWorkflow.restoreLastWorkflow(this)
    internal fun saveWorkflowJsonToLocal(preferredName: String, json: String): java.io.File? = MainViewModelWorkflow.saveWorkflowJsonToLocal(this, preferredName, json)

    internal fun aiAppsDir(): File = MainViewModelAiApps.aiAppsDir(this)
    internal fun aiAppsWorkflowDir(): File = MainViewModelAiApps.aiAppsWorkflowDir(this)
    internal fun loadAiAppsFromDisk(): List<AiApp> = MainViewModelAiApps.loadAiAppsFromDisk(this)
    fun refreshAiApps() = MainViewModelAiApps.refreshAiApps(this)
    override fun getAiAppCoverFile(app: AiApp): File? = MainViewModelAiApps.getAiAppCoverFile(this, app)
    override fun saveCurrentWorkflowAsAiApp(title: String, coverUri: Uri?) = MainViewModelAiApps.saveCurrentWorkflowAsAiApp(this, title, coverUri)
    override fun updateAiAppTitle(appId: String, title: String) = MainViewModelAiApps.updateAiAppTitle(this, appId, title)
    override fun updateAiAppCover(appId: String, uri: Uri) = MainViewModelAiApps.updateAiAppCover(this, appId, uri)
    override fun deleteAiApp(appId: String) = MainViewModelAiApps.deleteAiApp(this, appId)
    fun reorderAiApps(orderedIds: List<String>) = MainViewModelAiApps.reorderAiApps(this, orderedIds)
    fun loadAiApp(appId: String) = MainViewModelAiApps.loadAiApp(this, appId)

    fun queueMultipleWorkflows(files: List<java.io.File>) {
        if (files.isEmpty()) return
        if (currentServerProfile()?.isRunningHub == true) {
            MainViewModelRhWorkflows.runFiles(this, files)
            return
        }
        if (isSending) {
             showToast("已有任务正在发送中，请稍候")
             return
        }
        
        sendJob?.cancel()
        val token = Any()
        sendJobToken = token
        val job = viewModelScope.launch {
            try {
                ensureSocketLiveBeforeDispatch()
                isSending = true
                var successCount = 0
                val total = files.size
                
                showToast("开始批量发送 $total 个工作流...")
                
                files.forEachIndexed { index, file ->
                    currentCoroutineContext().ensureActive()
                    try {
                        val content = withContext(Dispatchers.IO) { file.readText() }
                        val type = object : TypeToken<Map<String, Any>>() {}.type
                        val raw: Map<String, Any> = gson.fromJson(content, type)
                        
                        val sanitized = sanitizeNumbers(raw) as Map<String, Any>
                        val pid = repository.queuePrompt(sanitized)
                        if (!pid.isNullOrBlank()) {
                             successCount++
                             activeRunPromptIds.add(pid)
                             addLog("批量发送 (${index + 1}/$total): ${file.name} (ID: $pid)", LogType.INFO)
                        } else {
                             addLog("批量发送失败 (${index + 1}/$total): ${file.name} - ${repository.lastQueuePromptError ?: "null pid"}", LogType.ERROR)
                             logQueuePromptRawBody()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        addLog("批量发送出错 (${index + 1}/$total): ${file.name} - ${e.message}", LogType.ERROR)
                    }
                    kotlinx.coroutines.delay(200)
                }
                
                if (successCount > 0) {
                     GenerationLiveUpdate.noteUserStartedRun()
                     _isRunning.value = true
                     _executionStatus.value = "RUNNING"
                     startActiveRunWatchdog()
                     showToast("批量发送完成，成功 $successCount/$total")
                } else {
                     showToast("批量发送全部失败")
                }
                fetchQueue()
            } catch (_: CancellationException) {
                _isRunning.value = false
                _executionStatus.value = "IDLE"
            } finally {
                isSending = false
                if (sendJobToken === token) {
                    sendJob = null
                    sendJobToken = null
                }
            }
        }
        sendJob = job
    }

    private var activeRunWatchdogJob: Job? = null

    private fun startActiveRunWatchdog() {
        if (activeRunWatchdogJob?.isActive == true) return
        activeRunWatchdogJob = viewModelScope.launch(Dispatchers.IO) {
            var consecutiveAllIdleCycles = 0
            while (isActive) {
                kotlinx.coroutines.delay(2500)
                if (activeRunPromptIds.isEmpty() && !_isRunning.value) {
                    break
                }
                try {
                    // null = queue state unknown (network error / non-2xx): keep waiting.
                    val activeQueueIds = repository.getActiveQueuePromptIds() ?: continue
                    val runningOrPending = activeRunPromptIds.toList()
                    val finishedCandidateIds = runningOrPending.filter { it !in activeQueueIds }

                    for (pid in finishedCandidateIds) {
                        val hit = repository.getHistoryItem(pid)
                        if (hit != null) {
                            addLog("轮询对齐：任务已完成 (ID: $pid)", LogType.INFO)
                            finalizeActiveRun(pid)
                        }
                    }

                    if (activeQueueIds.isEmpty()) {
                        consecutiveAllIdleCycles++
                        if (consecutiveAllIdleCycles >= 2 && activeRunPromptIds.isNotEmpty()) {
                            for (pid in activeRunPromptIds.toList()) {
                                finalizeActiveRun(pid)
                            }
                            if (activeRunPromptIds.isEmpty()) {
                                resetRunState()
                            }
                        }
                    } else {
                        consecutiveAllIdleCycles = 0
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun stopActiveRunWatchdog() {
        activeRunWatchdogJob?.cancel()
        activeRunWatchdogJob = null
    }

    private fun syncActiveRunsWithServer() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val activeQueueIds = repository.getActiveQueuePromptIds() ?: return@launch
                val finished = activeRunPromptIds.filter { it !in activeQueueIds }
                for (pid in finished) {
                    val hit = repository.getHistoryItem(pid)
                    if (hit != null) {
                        finalizeActiveRun(pid)
                    }
                }
                if (activeQueueIds.isEmpty() && activeRunPromptIds.isNotEmpty()) {
                    kotlinx.coroutines.delay(1000)
                    val recheck = repository.getActiveQueuePromptIds()
                    if (recheck != null && recheck.isEmpty()) {
                        for (pid in activeRunPromptIds.toList()) {
                            finalizeActiveRun(pid)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    internal fun resetRunState() {
        stopActiveRunWatchdog()
        activeRunStartedAt = 0L
        activeRunPromptIds.clear()
        recentlyFinalizedPromptIds.clear()
        _isRunning.value = false
        _executionStatus.value = "IDLE"
    }

    internal fun resetRunStateForWorkflowLoad() {
        if (_executionStatus.value == "RUNNING" || _isRunning.value || activeRunPromptIds.isNotEmpty()) return
        resetRunState()
    }

    private suspend fun finalizeActiveRun(promptIdFromEvent: String?) {
        val source = agentWorkspace.currentTarget().key
        val epoch = agentWorkspace.callbackEpoch
        val promptId = promptIdFromEvent?.ifBlank { null }
        val now = System.currentTimeMillis()

        if (!promptId.isNullOrBlank()) {
            val last = recentlyFinalizedPromptIds[promptId]
            if (last != null && now - last < 2_500L) {
                // Same prompt already finalized by another completion signal.
                return
            }
            recentlyFinalizedPromptIds[promptId] = now
            if (recentlyFinalizedPromptIds.size > 32) {
                val cutoff = now - 60_000L
                recentlyFinalizedPromptIds.entries.removeAll { it.value < cutoff }
            }

            activeRunPromptIds.remove(promptId)
            lastFinishedPromptId = promptId
            val wsFailure = repository.webSocketManager.consumeExecutionFailure(promptId)
            val historyHit = syncRunOutputsFromHistory(promptId)
            promptIdToWorkflowKey.remove(promptId)
            promptIdToRunId.remove(promptId)

            // Outcome: WS execution_error / execution_interrupted first, then ComfyUI history status.
            val interrupted = wsFailure?.interrupted == true || (wsFailure == null && historyHit?.interrupted == true)
            val failureMessage: String? = when {
                wsFailure != null && !wsFailure.interrupted -> wsFailure.displayMessage
                wsFailure == null && !interrupted && historyHit?.statusStr.equals("error", ignoreCase = true) ->
                    historyHit?.errorMessage ?: "执行失败"
                else -> null
            }
            if (source == agentWorkspace.currentTarget().key && epoch == agentWorkspace.callbackEpoch &&
                (historyHit != null || wsFailure != null)) {
                agentWorkspace.completeLocal(source, promptId,
                    if (interrupted) "CANCELLED" else if (failureMessage != null) "FAILED" else "SUCCESS", failureMessage)
            }
            if (failureMessage != null) {
                addLog("任务执行失败 (ID: $promptId): $failureMessage", LogType.ERROR)
                showToast("任务失败：${failureMessage.take(120)}")
                GenerationLiveUpdate.markRunFailed(getApplication(), "绘图失败")
            } else if (interrupted) {
                addLog("任务已中断 (ID: $promptId)", LogType.WARNING)
                GenerationLiveUpdate.markRunFailed(getApplication(), "绘图终止")
            }
        } else {
            // Completion signal without a prompt id: only finalize prompts that ComfyUI history confirms
            // are finished; the others keep running (queue listener / watchdog handle them).
            if (!_isRunning.value && activeRunPromptIds.isEmpty()) return
            val remaining = activeRunPromptIds.toList()
            for (pid in remaining) {
                val hit = try { repository.getHistoryItem(pid) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                if (hit != null) finalizeActiveRun(pid)
            }
        }

        if (activeRunPromptIds.isEmpty()) {
            activeRunStartedAt = 0L
            _isRunning.value = false
            _executionStatus.value = "COMPLETED"
            stopActiveRunWatchdog()
        }
    }

    /** Syncs outputs of [promptId] from history; returns the history item (for status) or null if not found. */
    private suspend fun syncRunOutputsFromHistory(promptId: String): HistoryItem? {
        val source = agentWorkspace.currentTarget().key
        val sourceBase = repository.baseUrl.value
        val epoch = agentWorkspace.callbackEpoch
        fun current() = source == agentWorkspace.currentTarget().key &&
            sourceBase == repository.baseUrl.value && epoch == agentWorkspace.callbackEpoch
        try {
            var hit = repository.getHistoryItem(promptId)
            if (!current()) return null
            if (hit == null) {
                kotlinx.coroutines.delay(350)
                if (!current()) return null
                hit = repository.getHistoryItem(promptId)
            }
            if (!current()) return null
            if (hit == null) {
                hit = repository.getHistoryOrNull()?.firstOrNull { it.promptId == promptId }
            }
            if (!current()) return null
            if (hit == null) return null

            val isAgentRun = isAgentTask(promptId)
            if (isAgentRun) {
                recordAgentRunPromptId(promptId)
            } else {
                if (!hit.name.isNullOrBlank()) {
                    if (currentWorkflow == null) {
                        _currentWorkflowName.value = hit.name
                    }
                } else if (currentWorkflow == null) {
                    _currentWorkflowName.value = "电脑端任务 $promptId"
                }
            }

            val outputs = hit.outputs ?: return hit
            val foundMedia = mutableListOf<MediaOutput>()

            for ((_, nodeOutput) in outputs) {
                if (nodeOutput is Map<*, *>) {
                    for ((_, value) in nodeOutput) {
                        if (value is List<*>) {
                            for (item in value) {
                                if (item is Map<*, *>) {
                                    val filename = item["filename"] as? String
                                    val subfolder = item["subfolder"] as? String ?: ""
                                    val type = item["type"] as? String ?: "output"
                                    if (!filename.isNullOrBlank()) {
                                        val lower = filename.lowercase()
                                        val mediaType = when {
                                            lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mov") -> MediaType.VIDEO
                                            lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".ogg") || lower.endsWith(".flac") -> MediaType.AUDIO
                                            lower.endsWith(".txt") || lower.endsWith(".json") || lower.endsWith(".yaml") || lower.endsWith(".yml") -> MediaType.TEXT
                                            else -> MediaType.IMAGE
                                        }

                                        foundMedia.add(
                                            MediaOutput(
                                                filename = filename,
                                                subfolder = subfolder,
                                                type = type,
                                                mediaType = mediaType,
                                                timestamp = 0L,
                                                promptId = promptId,
                                                sourceKey = source, workspaceEpoch = epoch,
                                                sourceUrl = repository.getImageUrl(filename, subfolder, type, sourceBase)
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (foundMedia.isEmpty()) return hit

            val now = System.currentTimeMillis()
            val latestOnly = foundMedia
                .asReversed()
                .distinctBy { "${it.subfolder}|${it.filename}|${it.type}" }
                .mapIndexed { index, item -> item.copy(timestamp = now - (index * 100)) }
                .sortedByDescending { it.timestamp }
                .filterNot { isDeletedKey(mediaKey(it)) }

            if (!isAgentRun) {
                val workflowKey = promptIdToWorkflowKey[promptId] ?: currentWorkflowKey
                if (!workflowKey.isNullOrBlank()) {
                    val runId = promptIdToRunId[promptId]
                    val activeRunId = activeRunIdByWorkflowKey[workflowKey]
                    if (runId == null || activeRunId == null || runId == activeRunId) {
                        setLatestGenerationForWorkflow(workflowKey, latestOnly)
                    }
                }
            } else if (agentWorkspace.status.value.mode == AgentStorageMode.LEGACY) {
                // Agent 专属历史同步隔离：仅更新 Agent 列表
                val next = (latestOnly + _agentLatestGenerationList.value)
                    .distinctBy { "${it.subfolder}|${it.filename}|${it.type}" }
                    .filterNot { isDeletedKey(mediaKey(it)) }
                _agentLatestGenerationList.value = next
                persistAgentMediaList(next)
            }
            latestOnly.forEach { media ->
                agentWorkspace.record(media, source)
            }

            val existing = _sessionMediaList.value
            val merged = (latestOnly + existing)
                .distinctBy { "${it.subfolder}|${it.filename}|${it.type}" }
                .sortedByDescending { it.timestamp }
                .filterNot { isDeletedKey(mediaKey(it)) }
            _sessionMediaList.value = merged
            return hit
        } catch (_: Exception) {
            return null
        }
    }

    override fun showToast(msg: String) {
        com.comfyui.mobile.ui.feedback.AppToast.show(getApplication(), msg)
    }
    
    fun interrupt() {
        viewModelScope.launch {
            GenerationLiveUpdate.beginUserStop()
            sendJob?.cancel()
            sendJob = null
            sendJobToken = null
            isSending = false
            repository.setIsGenerating(false)
            stopActiveRunWatchdog()
            repository.interrupt()
            KeepAliveService.stop(getApplication())
            kotlinx.coroutines.delay(150L)
            GenerationLiveUpdate.notifyFailedThenDismiss(getApplication())
            activeRunStartedAt = 0L
            activeRunPromptIds.clear()
            promptIdToWorkflowKey.clear()
            promptIdToRunId.clear()
            activeRunIdByWorkflowKey.clear()
            _isRunning.value = false
            _executionStatus.value = "IDLE"
            fetchQueue()
        }
    }
    
    fun getImageUrl(data: String): String {
        val parts = data.split("|")
        if (parts.size >= 3) {
            return repository.getImageUrl(parts[0], parts[1], parts[2])
        }
        return ""
    }

    override fun getViewUrl(media: MediaOutput): String {
        // Downloaded cloud results (and other local copies) are shown from their local Uri.
        media.localUri?.takeIf { it.isNotBlank() }?.let { return it }
        return getImageUrl(media)
    }
    fun getImageUrl(media: MediaOutput): String {
        if (media.sourceUnresolved) return ""
        return media.sourceUrl ?: if (media.sourceKey != null) ""
            else repository.getImageUrl(media.filename, media.subfolder, media.type)
    }

    fun getImageUrl(filename: String, subfolder: String, type: String): String {
        return repository.getImageUrl(filename, subfolder, type)
    }

    override fun getViewUrl(filename: String, subfolder: String, type: String): String {
        uploadPreviewStore.ensureLoaded()
        // RH AI app inputs have no /view URL; show the local picked file when we have one.
        if (type == "rh") return rhUploadPreviews[filename].orEmpty()
        // Cloud workflow inputs uploaded via the task API ("api/...") have no view URL: show the picked local file.
        if (filename.startsWith("api/")) rhUploadPreviews[filename]?.let { return it }
        return repository.getImageUrl(filename, subfolder, type)
    }
    
    override suspend fun fetchTextContent(media: MediaOutput): String {
        return withContext(Dispatchers.IO) {
            if (media.sourceUnresolved) return@withContext "历史媒体来源待确认"
            val url = media.sourceUrl
            if (url != null) runCatching {
                repository.hostAwareCallFactory.newCall(okhttp3.Request.Builder().url(url).build()).execute().use {
                    if (it.isSuccessful) it.body?.string() else null
                }
            }.getOrNull() ?: "读取文本失败"
            else if (media.sourceKey != null) "媒体来源地址缺失"
            else repository.getTextContent(media.filename, media.subfolder, media.type) ?: "读取文本失败"
        }
    }

    override fun previewUrl(media: MediaOutput): String {
        return media.localUri ?: getImageUrl(media)
    }

    override fun refreshLocalMedia(context: android.content.Context) =
        MainViewModelGallery.refreshLocalMedia(this, context)

    override fun deleteLocalMedia(context: android.content.Context, mediaList: List<MediaOutput>) =
        MainViewModelGallery.deleteLocalMedia(this, context, mediaList)

    override fun saveMediaToAlbum(
        context: android.content.Context,
        media: MediaOutput,
        silent: Boolean,
        onComplete: ((Boolean) -> Unit)?
    ) = MainViewModelGallery.saveMediaToAlbum(this, context, media, silent, onComplete)

    override fun saveBatchMediaToAlbum(
        context: android.content.Context,
        mediaList: List<MediaOutput>,
        onComplete: (() -> Unit)?
    ) = MainViewModelGallery.saveBatchMediaToAlbum(this, context, mediaList, onComplete)

    fun downloadMedia(context: android.content.Context, media: MediaOutput): Long =
        MainViewModelGallery.downloadMedia(this, context, media)

    private fun loadPromptMap() {
        viewModelScope.launch(Dispatchers.IO) {
            if (promptMapFile.exists()) {
                try {
                    val json = promptMapFile.readText()
                    val type = object : TypeToken<MutableMap<String, String>>() {}.type
                    val map: MutableMap<String, String> = gson.fromJson(json, type) ?: mutableMapOf()
                    _promptMap.value = map
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun savePromptMap() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val json = gson.toJson(_promptMap.value)
                promptMapFile.writeText(json)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun getPromptForMedia(media: MediaOutput): String? {
        val key = "${media.subfolder}|${media.filename}|${media.type}"
        val mapValue = _promptMap.value[key]
        if (!mapValue.isNullOrBlank()) return mapValue

        val pid = media.promptId
        if (pid != null) {
            val summary = activePrompts[pid]
            if (summary != null) {
                 val newMap = _promptMap.value.toMutableMap()
                 newMap[key] = summary
                 _promptMap.value = newMap
                 savePromptMap()
                 return summary
            }
        }
        return null
    }

    private fun generatePromptSummary(nodes: List<EditableNode> = _editableNodes.value): String =
        WorkflowGraph.generatePromptSummary(nodes)
    // ===== Agent / LLM State & Delegations =====
    internal val llmClient = LlmClient(gson)
    /** Last Agent model call: context use, speed, model id (shown above the composer). */
    internal val _agentLlmStats = MutableStateFlow<com.comfyui.mobile.agent.AgentLlmStats?>(null)
    val agentLlmStats: StateFlow<com.comfyui.mobile.agent.AgentLlmStats?> = _agentLlmStats.asStateFlow()
    internal val agentSession = com.comfyui.mobile.agent.AgentSessionController(llmClient::cancelRequest)
    internal val agentWorkflowLoadVersion = java.util.concurrent.atomic.AtomicLong()
    internal val agentRuntime by lazy { MainViewModelAgentRuntime(this) }
    internal val agentWorkspaceData = lazy { MainViewModelAgentWorkspace(this) }
    internal val agentWorkspace get() = agentWorkspaceData.value
    val agentStorageStatus get() = agentWorkspace.status
    val agentInputDraft get() = agentWorkspace.draft
    val agentHasEarlierMessages get() = agentWorkspace.hasEarlier
    val agentConversations get() = agentWorkspace.conversations
    val activeAgentConversation get() = agentWorkspace.activeConversation
    val agentConversationDeleteInfo get() = agentWorkspace.deleteInfo
    val agentArchivedConversations get() = agentWorkspace.archivedConversations
    val agentProjects get() = agentWorkspace.projects
    fun newAgentConversation(title: String = "新会话", projectId: String? = null, onOpened: () -> Unit = {}) =
        agentWorkspace.newConversation(title, projectId, onOpened)
    fun createAgentProject(name: String) = agentWorkspace.createProject(name)
    fun renameAgentProject(id: String, name: String) = agentWorkspace.renameProject(id, name)
    fun setAgentProjectArchived(id: String, archived: Boolean) = agentWorkspace.setProjectArchived(id, archived)
    fun deleteAgentProject(id: String) = agentWorkspace.deleteProject(id)
    fun moveAgentConversation(id: String, projectId: String?) = agentWorkspace.moveConversation(id, projectId)
    fun setAgentConversationArchived(id: String, archived: Boolean) =
        agentWorkspace.setConversationArchived(id, archived)
    fun openAgentConversation(id: String, onOpened: () -> Unit = {}) =
        agentWorkspace.openConversation(id, onOpened)
    fun renameAgentConversation(id: String, title: String) = agentWorkspace.renameConversation(id, title)
    fun requestDeleteAgentConversation(id: String) = agentWorkspace.requestDelete(id)
    fun dismissDeleteAgentConversation() = agentWorkspace.dismissDelete()
    fun deleteAgentConversation(id: String) = agentWorkspace.deleteConversation(id)
    fun retryAgentStorage() = agentWorkspace.retry()
    fun agentReferenceWarning() = agentWorkspace.referenceWarning()
    fun useCurrentAgentConfiguration() = agentWorkspace.useCurrentConfiguration()
    fun loadEarlierAgentMessages() = agentWorkspace.loadEarlier()
    fun updateAgentDraft(draft: AgentInputDraft) = agentWorkspace.saveDraft(draft)
    val agentSessionState = agentSession.state

    internal fun loadLlmProfiles(): List<LlmProfile> =
        MainViewModelAgent.loadLlmProfiles(this)

    internal fun persistLlmProfiles(list: List<LlmProfile>) =
        MainViewModelAgent.persistLlmProfiles(this, list)

    internal val _llmProfiles = MutableStateFlow(loadLlmProfiles())
    val llmProfiles = _llmProfiles.asStateFlow()

    internal val _currentLlmProfileId = MutableStateFlow(
        prefs.getString("current_llm_profile_id", "")?.takeIf { id ->
            _llmProfiles.value.any { it.id == id }
        } ?: _llmProfiles.value.firstOrNull()?.id.orEmpty()
    )
    val currentLlmProfileId = _currentLlmProfileId.asStateFlow()

    val currentLlmProfile: StateFlow<LlmProfile?> = combine(_currentLlmProfileId, _llmProfiles) { id, list ->
        list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, _llmProfiles.value.firstOrNull())

    internal val _llmBaseUrl = MutableStateFlow(MainViewModelAgent.initialLlmValue(this, "llm_base_url") { it.baseUrl })
    val llmBaseUrl = _llmBaseUrl.asStateFlow()

    internal val _llmApiKey = MutableStateFlow(MainViewModelAgent.initialLlmValue(this, "llm_api_key") { it.apiKey })
    val llmApiKey = _llmApiKey.asStateFlow()

    internal val _llmModel = MutableStateFlow(MainViewModelAgent.initialLlmValue(this, "llm_model") { it.model })
    val llmModel = _llmModel.asStateFlow()

    internal val _llmReasoningEffort = MutableStateFlow(
        com.comfyui.mobile.agent.AgentReasoningEffort.fromStored(
            _llmProfiles.value.firstOrNull { it.id == _currentLlmProfileId.value }?.reasoningEffort
        )
    )
    val llmReasoningEffort = _llmReasoningEffort.asStateFlow()

    internal val _agentPromptSettings = MutableStateFlow(com.comfyui.mobile.agent.AgentPromptSettingsStore(prefs).load())
    val agentPromptSettings = _agentPromptSettings.asStateFlow()
    internal val localTagLibrary = com.comfyui.mobile.taglib.LocalTagLibrary(application)
    val localTagLibraryState = localTagLibrary.store.state

    init {
        viewModelScope.launch { localTagLibrary.store.refresh() }
    }

    fun installLocalTagLibrary(uri: android.net.Uri) = MainViewModelTagLibrary.install(this, uri)
    fun removeLocalTagLibrary() = MainViewModelTagLibrary.remove(this)

    fun setAgentPromptSettings(value: com.comfyui.mobile.agent.AgentPromptSettings) =
        MainViewModelAgent.setPromptSettings(this, value)

    suspend fun searchLocalTags(query: String, kind: String) = localTagLibrary.search(query, kind)

    internal val _agentRuleFiles = MutableStateFlow(com.comfyui.mobile.agent.AgentRuleFilesStore(prefs, gson).load())
    val agentRuleFiles = _agentRuleFiles.asStateFlow()
    internal val agentRules = MainViewModelAgentRules(this)
    val agentRulesImporting = agentRules.importing
    fun importAgentRules(kind: com.comfyui.mobile.agent.AgentRuleKind, uri: android.net.Uri) = agentRules.import(kind, uri)
    fun removeAgentRules(kind: com.comfyui.mobile.agent.AgentRuleKind) {
        agentRules.replace(kind, null)
        showToast("已移除${kind.title}，下一轮生效")
    }
    fun selectAgentRules(kind: com.comfyui.mobile.agent.AgentRuleKind, name: String?) {
        runCatching { agentRules.select(kind, name) }
            .onSuccess { showToast(if (name == null) "${kind.title}已停用，下一轮生效" else "已切换${kind.title}：$name，下一轮生效") }
            .onFailure { showToast(it.message ?: "切换失败") }
    }

    internal val _agentSystemPrompt = MutableStateFlow(_agentRuleFiles.value.dialogue?.content.orEmpty())
    val agentSystemPrompt = _agentSystemPrompt.asStateFlow()

    internal val _agentDrawPrompt = MutableStateFlow(_agentRuleFiles.value.drawing?.content.orEmpty())
    val agentDrawPrompt = _agentDrawPrompt.asStateFlow()

    internal val _agentWorkflowSource = MutableStateFlow(prefs.getString("agent_wf_source", "") ?: "")
    val agentWorkflowSource = _agentWorkflowSource.asStateFlow()

    internal val _agentWorkflowId = MutableStateFlow(prefs.getString("agent_wf_id", "") ?: "")
    val agentWorkflowId = _agentWorkflowId.asStateFlow()

    internal val _agentWorkflowLabel = MutableStateFlow(
        (prefs.getString("agent_wf_label", "") ?: "").removeSuffix(".json").removeSuffix(".JSON")
    )
    val agentWorkflowLabel = _agentWorkflowLabel.asStateFlow()

    internal val _agentMessages = MutableStateFlow<List<AgentChatMessage>>(emptyList())
    val agentMessages = _agentMessages.asStateFlow()

    internal val _agentBusy = agentSession.busy
    val agentBusy: StateFlow<Boolean> = _agentBusy

    /** chat = LLM only; draw = expand prompt + enqueue workflow. */
    internal val _agentInteractionMode = MutableStateFlow(
        prefs.getString("agent_interaction_mode", AGENT_MODE_DRAW)?.takeIf { it == AGENT_MODE_CHAT || it == AGENT_MODE_DRAW }
            ?: AGENT_MODE_DRAW
    )
    val agentInteractionMode = _agentInteractionMode.asStateFlow()

    fun setAgentInteractionMode(mode: String) =
        MainViewModelAgent.setAgentInteractionMode(this, mode)

    /** Attachments staged in the Agent input: up to 4 images plus text files. Draw mode uses the first image. */
    internal val _agentPendingAttachments = MutableStateFlow<List<com.comfyui.mobile.model.AgentAttachment>>(emptyList())
    val agentPendingAttachments = _agentPendingAttachments.asStateFlow()

    fun captureAgentAttachmentOwner() = agentWorkspace.captureAttachmentOwner()
    fun captureAgentConfigurationOwner() = agentWorkspace.captureConfigurationOwner()
    fun isCurrentAgentConfiguration(owner: AgentConfigurationOwner?) = agentWorkspace.ownsConfiguration(owner)
    fun addAgentImageAttachments(uris: List<android.net.Uri>, owner: AgentAttachmentOwner? = captureAgentAttachmentOwner()) =
        MainViewModelAgent.addAgentImageAttachments(this, uris, owner)
    fun addAgentFileAttachments(uris: List<android.net.Uri>, owner: AgentAttachmentOwner? = captureAgentAttachmentOwner()) =
        MainViewModelAgent.addAgentFileAttachments(this, uris, owner)
    fun removeAgentAttachment(id: String) {
        if (!agentWorkspace.requireWritable()) return
        _agentPendingAttachments.value = _agentPendingAttachments.value.filterNot { it.id == id }
        agentWorkspace.saveDraft()
    }
    fun clearAgentAttachments() {
        _agentPendingAttachments.value = emptyList()
        if (agentWorkspaceData.isInitialized()) agentWorkspace.saveDraft()
    }

    /** True while the Agent LLM call (chat or draw expand) is in flight. */
    val agentLlmBusy: StateFlow<Boolean> = _agentBusy

    /** True while an Agent image job is queued/running (local Comfy or RH Agent task). */
    val agentImageBusy: StateFlow<Boolean> = combine(_isRunning, taskStore.tasks, agentStorageStatus) { running, tasks, storage ->
        if (storage.mode == AgentStorageMode.LEGACY) running || tasks.any {
            it.workflowKey?.startsWith(MainViewModelRhWorkflows.AGENT_KEY_PREFIX) == true && it.taskState.isActive
        } else storage.mode == AgentStorageMode.ROOM && tasks.any {
            it.agentConversationId == storage.conversationId && it.agentExecutionId != null && it.taskState.isActive
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    internal val _agentLastExpandedPrompt = MutableStateFlow(
        MainViewModelAgent.loadPromptOutput(this).positive
    )
    val agentLastExpandedPrompt = _agentLastExpandedPrompt.asStateFlow()
    internal val _agentPromptOutput = MutableStateFlow(MainViewModelAgent.loadPromptOutput(this))
    val agentPromptOutput = _agentPromptOutput.asStateFlow()

    val agentRunPromptIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet<String>().also {
        it.addAll(MainViewModelAgent.loadRunPromptIds(this))
    }

    @Volatile internal var isAgentDispatching: Boolean = false

    internal fun isAgentTask(promptId: String?): Boolean {
        if (promptId.isNullOrBlank()) return false
        if (agentWorkspaceData.isInitialized() && agentWorkspace.usesRoom) {
            return agentWorkspace.isKnown(agentWorkspace.currentTarget().key, promptId)
        }
        return agentRunPromptIds.contains(promptId)
    }

    internal fun recordAgentRunPromptId(pid: String) {
        agentRunPromptIds.add(pid)
        if (agentWorkspaceData.isInitialized() && agentWorkspace.usesRoom) return
        val trimmed = if (agentRunPromptIds.size > 200) {
            agentRunPromptIds.toList().takeLast(200).toSet()
        } else {
            agentRunPromptIds.toSet()
        }
        if (agentRunPromptIds.size > 200) {
            agentRunPromptIds.clear()
            agentRunPromptIds.addAll(trimmed)
        }
        MainViewModelAgent.persistRunPromptIds(this, trimmed)
    }

    internal val _agentLatestGenerationList = MutableStateFlow<List<MediaOutput>>(MainViewModelAgent.loadAgentMediaList(this))
    val agentLatestGenerationList = _agentLatestGenerationList.asStateFlow()

    fun clearAgentLatestGenerationList() {
        if (!agentWorkspace.requireWritable()) return
        _agentLatestGenerationList.value = emptyList()
        if (agentWorkspace.usesRoom) {
            agentWorkspace.clearMedia()
            showToast("已清空 Agent 生图记录")
            return
        }
        val inFlight = agentRunPromptIds.filter { activeRunPromptIds.contains(it) }.toSet()
        agentRunPromptIds.clear()
        agentRunPromptIds.addAll(inFlight)
        prefs.edit()
            .remove(MainViewModelAgent.scopedPref("agent_latest_media_list", this))
            .apply()
        MainViewModelAgent.persistRunPromptIds(this, inFlight)
        showToast("已清空 Agent 生图记录")
    }

    internal val _favoriteArtists = MutableStateFlow<List<String>>(MainViewModelAgent.loadFavoriteArtists(this))
    val favoriteArtists = _favoriteArtists.asStateFlow()

    fun addFavoriteArtist(artist: String) =
        MainViewModelAgent.addFavoriteArtist(this, artist)

    fun removeFavoriteArtist(artist: String) =
        MainViewModelAgent.removeFavoriteArtist(this, artist)

    internal val _agentTargetNodeId = MutableStateFlow(
        MainViewModelAgent.readTargetNodeId(this, _agentWorkflowSource.value, _agentWorkflowId.value)
    )
    val agentTargetNodeId = _agentTargetNodeId.asStateFlow()
    internal val _agentPositiveBinding = MutableStateFlow(MainViewModelAgent.loadPromptBinding(this, false))
    val agentPositiveBinding = _agentPositiveBinding.asStateFlow()
    internal val _agentNegativeBinding = MutableStateFlow(MainViewModelAgent.loadPromptBinding(this, true))
    val agentNegativeBinding = _agentNegativeBinding.asStateFlow()

    fun setAgentPromptBinding(negative: Boolean, binding: com.comfyui.mobile.agent.AgentPromptBinding) =
        MainViewModelAgent.setPromptBinding(this, negative, binding)

    fun setAgentTargetNodeId(nodeId: String) =
        MainViewModelAgent.setAgentTargetNodeId(this, nodeId)

    internal val _agentArtistNodeId = MutableStateFlow(
        prefs.getString(
            "agent_artist_node_${prefs.getString("agent_wf_source", "") ?: ""}_${prefs.getString("agent_wf_id", "") ?: ""}",
            ""
        ) ?: ""
    )
    val agentArtistNodeId = _agentArtistNodeId.asStateFlow()

    fun setAgentArtistNodeId(nodeId: String) =
        MainViewModelAgent.setAgentArtistNodeId(this, nodeId)

    internal val _agentAuxNodeKeys = MutableStateFlow<Set<String>>(
        MainViewModelAgent.loadAgentAuxNodes(this, prefs.getString("agent_wf_source", "") ?: "", prefs.getString("agent_wf_id", "") ?: "")
    )
    val agentAuxNodeKeys = _agentAuxNodeKeys.asStateFlow()

    fun toggleAgentAuxNode(compoundKey: String, isChecked: Boolean) =
        MainViewModelAgent.toggleAgentAuxNode(this, compoundKey, isChecked)

    internal var agentWorkflow: Map<String, Any>? = null
    internal var agentWorkflowParamValues: MutableMap<String, Any> = mutableMapOf()
    internal val _agentEditableNodes = MutableStateFlow<List<EditableNode>>(emptyList())
    val agentEditableNodes = _agentEditableNodes.asStateFlow()
    fun updateAgentNodeValue(nodeId: String, key: String, value: Any) =
        MainViewModelAgent.updateAgentNodeValue(this, nodeId, key, value)

    internal val _characterLores = MutableStateFlow<List<CharacterLore>>(MainViewModelAgent.loadCharacterLores(this))
    val characterLores = _characterLores.asStateFlow()

    fun addOrUpdateCharacterLore(name: String, triggerWords: String, existingId: String? = null, enabled: Boolean? = null) =
        MainViewModelAgent.addOrUpdateCharacterLore(this, name, triggerWords, existingId, enabled)

    fun saveCharacterLore(lore: CharacterLore) =
        MainViewModelAgent.saveCharacterLore(this, lore)

    fun toggleCharacterLoreEnabled(id: String) =
        MainViewModelAgent.toggleCharacterLoreEnabled(this, id)

    fun setCharacterLoreAlwaysActive(id: String, alwaysActive: Boolean) =
        MainViewModelAgent.setCharacterLoreAlwaysActive(this, id, alwaysActive)

    fun removeCharacterLore(id: String) =
        MainViewModelAgent.removeCharacterLore(this, id)

    internal fun persistCharacterLores(list: List<CharacterLore>) =
        MainViewModelAgent.persistCharacterLores(this, list)

    fun preloadAgentWorkflow() =
        MainViewModelAgent.preloadAgentWorkflow(this)

    init {
        MainViewModelAgent.reloadAgentWorkflowForMode(this)
    }

    fun createNewLlmProfile(): LlmProfile =
        MainViewModelAgent.createNewLlmProfile(this)

    fun selectLlmProfile(id: String) =
        MainViewModelAgent.selectLlmProfile(this, id)

    fun addOrUpdateLlmProfile(profile: LlmProfile, selectAfterSave: Boolean = true) =
        MainViewModelAgent.addOrUpdateLlmProfile(this, profile, selectAfterSave)

    fun deleteLlmProfile(id: String) =
        MainViewModelAgent.deleteLlmProfile(this, id)

    fun setLlmBaseUrl(value: String) =
        MainViewModelAgent.setLlmBaseUrl(this, value)

    fun setLlmApiKey(value: String) =
        MainViewModelAgent.setLlmApiKey(this, value)

    fun setLlmModel(value: String) =
        MainViewModelAgent.setLlmModel(this, value)

    fun setAgentSystemPrompt(value: String) =
        MainViewModelAgent.setAgentSystemPrompt(this, value)


    fun setAgentDrawPrompt(value: String) =
        MainViewModelAgent.setAgentDrawPrompt(this, value)


    fun setAgentDefaultWorkflow(source: String, id: String, label: String) =
        MainViewModelAgent.setAgentDefaultWorkflow(this, source, id, label)

    fun resetAgentSavedWorkflow() =
        MainViewModelAgent.resetAgentSavedWorkflow(this)

    fun saveAgentWorkflowDefaults() =
        MainViewModelAgent.saveAgentWorkflowDefaults(this)

    fun showUserMessage(msg: String) =
        MainViewModelAgent.showUserMessage(this, msg)

    fun clearAgentChat() =
        MainViewModelAgent.clearAgentChat(this)

    fun sendAgentMessage(userText: String) =
        MainViewModelAgent.sendAgentMessage(this, userText)

    fun answerAgentQuestions(messageId: String, answers: List<com.comfyui.mobile.model.AgentQuestionAnswer>) =
        agentRuntime.answer(messageId, answers,
            if (_agentInteractionMode.value == AGENT_MODE_CHAT) com.comfyui.mobile.agent.AgentMode.CHAT
            else com.comfyui.mobile.agent.AgentMode.DRAW)

    fun stopAgentGeneration() = MainViewModelAgent.stopAgentGeneration(this)

    internal fun persistAgentMediaList(list: List<MediaOutput>) =
        MainViewModelAgent.persistAgentMediaList(this, list)

    /**
     * Submit current workflow once and wait for queue result.
     * Used by Agent so chat can show real success/failure.
     */
    suspend fun enqueueCurrentWorkflowAwait(batchCount: Int = 1, turnId: String = java.util.UUID.randomUUID().toString()): Result<String> {
        if (isSending) {
            return Result.failure(IllegalStateException("正在发送中，请稍候再试"))
        }
        isSending = true
        return try {
            val workflowKeyForRun = _agentWorkflowLabel.value.ifBlank { _agentWorkflowId.value }
                .removeSuffix(".json")
                .removeSuffix(".JSON")
            val baseWorkflow = agentWorkflow?.let {
                val (overridden, _) = applyParamOverridesToWorkflow(it, agentWorkflowParamValues)
                sanitizeNumbers(overridden) as Map<String, Any>
            }
            if (baseWorkflow == null) {
                addLog("Agent/提交失败: agentWorkflow 为空", LogType.ERROR)
                return Result.failure(IllegalStateException("未加载工作流"))
            }
            if (baseWorkflow.containsKey("nodes") && baseWorkflow.containsKey("links")) {
                addLog("Agent/提交失败: 工作流是 UI 格式而非 API Format", LogType.ERROR)
                return Result.failure(
                    IllegalStateException("工作流是 ComfyUI 界面格式，请用 Save (API Format) 重新导出后再设为 Agent 模板")
                )
            }
            // Heuristic: API prompt graph uses node-id keys with class_type
            val sample = baseWorkflow.entries.firstOrNull()
            val sampleNode = sample?.value as? Map<*, *>
            if (sampleNode?.containsKey("class_type") != true) {
                addLog("Agent/提交警告: 工作流可能不是 API 格式 (无 class_type)", LogType.WARNING)
            }

            addLog(
                "Agent 提交准备: key=$workflowKeyForRun nodes=${baseWorkflow.size} base=${com.comfyui.mobile.network.SecretRedactor.redact(repository.baseUrl.value)}",
                LogType.INFO
    )

            // Cloud (RunningHub): same official task API path as the Control screen.
            if (currentServerProfile()?.isRunningHub == true) {
                val sourceKey = _agentWorkflowId.value.let { id ->
                    if (id.contains("/saved_workflows/cloud/")) java.io.File(id).name else id
                }.ifBlank { null }
                val official = MainViewModelRhWorkflows.submitForAgent(this, baseWorkflow, workflowKeyForRun, sourceKey, batchCount, turnId)
                official.onSuccess { id -> addLog("Agent 云端任务已提交 taskId=$id", LogType.SUCCESS) }
                return official
            }

            sendJob?.cancel()
            val token = Any()
            sendJobToken = token

            ensureSocketLiveBeforeDispatch()
            isAgentDispatching = true
            var successCount = 0
            var lastPid: String? = null
            var lastFailDetail: String? = null
            val summary = generatePromptSummary()
            activeRunStartedAt = System.currentTimeMillis()
            val batchPids = mutableListOf<String>()

            repeat(batchCount) { i ->
                val workflow = randomizeSeedsForSend(baseWorkflow)
                val target = agentWorkspace.currentTarget()
                val link = agentWorkspace.reserve(turnId, target, gson.toJson(mapOf(
                    "workflowSource" to _agentWorkflowSource.value, "workflowId" to _agentWorkflowId.value,
                    "prompt" to _agentPromptOutput.value, "parameters" to agentWorkflowParamValues.toMap())))
                val localId = link?.id?.let { "agent_$it" }
                if (localId != null) taskStore.upsert(com.comfyui.mobile.data.TaskRecord(
                    localId, serverId = target.serverId, backend = target.backend,
                    state = com.comfyui.mobile.backend.TaskState.SUBMITTING.name, origin = "agent_local",
                    agentExecutionId = link.id, agentConversationId = link.conversationId))
                currentCoroutineContext().ensureActive()
                check(target.key == agentWorkspace.currentTarget().key) { "执行服务器已切换" }
                val pid = try {
                    repository.queuePrompt(workflow)
                } catch (e: CancellationException) {
                    agentWorkspace.unknown(link)
                    if (localId != null) withContext(kotlinx.coroutines.NonCancellable) {
                        taskStore.update(localId) { it.copy(state = "LOST",
                            error = com.comfyui.mobile.backend.TaskError(message = "提交状态待确认；未自动重发")) }
                    }
                    throw e
                } catch (e: Exception) {
                    lastFailDetail = e.message ?: e.toString()
                    addLog("Agent queuePrompt 异常: $lastFailDetail", LogType.ERROR)
                    null
                }
                if (pid.isNullOrBlank() && lastFailDetail == null) {
                    lastFailDetail = repository.lastQueuePromptError ?: "null pid（无错误详情）"
                }
                if (!pid.isNullOrBlank()) {
                    agentWorkspace.confirm(link, pid, localId)
                    withContext(kotlinx.coroutines.NonCancellable) {
                        if (localId != null) taskStore.update(localId) { it.copy(remoteId = pid,
                            state = com.comfyui.mobile.backend.TaskState.QUEUED.name) }
                    }
                    recordAgentRunPromptId(pid)
                    batchPids.add(pid)
                    activeRunPromptIds.add(pid)
                    activePrompts[pid] = summary
                    successCount++
                    lastPid = pid
                    addLog("Agent 任务已发送 ($i/$batchCount) id=$pid", LogType.SUCCESS)
                } else {
                    agentWorkspace.unknown(link)
                    if (localId != null) withContext(kotlinx.coroutines.NonCancellable) {
                        taskStore.update(localId) { it.copy(state = com.comfyui.mobile.backend.TaskState.LOST.name,
                            error = com.comfyui.mobile.backend.TaskError(message = "提交状态待确认；未自动重发")) }
                    }
                    addLog("Agent 任务发送失败 ($i/$batchCount) detail=${lastFailDetail ?: "null pid"}", LogType.ERROR)
                    logQueuePromptRawBody()
                }
                kotlinx.coroutines.delay(100)
            }

            // 纠正派发竞态期间可能被误标为电脑端任务的残留状态
            val lastExt = lastExternalPromptId
            if (lastExt != null && (batchPids.contains(lastExt) || isAgentTask(lastExt))) {
                lastExternalPromptId = null
            }
            if (currentWorkflow == null && _currentWorkflowName.value?.startsWith("电脑端任务") == true) {
                _currentWorkflowName.value = null
            }

            if (successCount > 0) {
                GenerationLiveUpdate.noteUserStartedRun()
                _isRunning.value = true
                _executionStatus.value = "RUNNING"
                startActiveRunWatchdog()
                fetchQueue()
                Result.success(lastPid ?: "")
            } else {
                _isRunning.value = false
                _executionStatus.value = "IDLE"
                Result.failure(
                    IllegalStateException(
                        lastFailDetail ?: "任务发送失败，请检查服务器连接与工作流（详见导出日志）"
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            addLog("Agent enqueue 异常: ${e.message}", LogType.ERROR)
            Result.failure(e)
        } finally {
            isAgentDispatching = false
            isSending = false
            sendJob = null
            sendJobToken = null
        }
    }

    // ===== 配置与数据导入导出 (Delegated to MainViewModelBackup) =====
    suspend fun exportBackupToFile(options: com.comfyui.mobile.model.BackupExportOptions): File =
        MainViewModelBackup.exportBackupToFile(this, options)

    suspend fun writeBackupToUri(context: android.content.Context, uri: Uri, options: com.comfyui.mobile.model.BackupExportOptions): Result<Unit> =
        MainViewModelBackup.writeBackupToUri(this, context, uri, options)

    fun parseBackupFromUri(context: android.content.Context, uri: Uri): Result<com.comfyui.mobile.model.ParsedBackup> =
        MainViewModelBackup.parseBackupFromUri(this, context, uri)

    fun discardParsedBackup(parsed: com.comfyui.mobile.model.ParsedBackup?) =
        MainViewModelBackup.discardParsedBackup(parsed)

    suspend fun restoreParsedBackup(
        parsed: com.comfyui.mobile.model.ParsedBackup,
        options: com.comfyui.mobile.model.BackupRestoreOptions = com.comfyui.mobile.model.BackupRestoreOptions()
    ): Result<String> = MainViewModelBackup.restoreParsedBackup(this, parsed, options)

    fun countBackupWorkflows(): Int = MainViewModelBackup.countLocalAndCloudWorkflows(this)
    fun countBackupAiAppFiles(): Int = MainViewModelBackup.countAiAppFiles(this)
    fun hasBackupTaskHistory(): Boolean = MainViewModelBackup.hasTaskHistory(this)

    // ===== 存储与缓存管理 =====
    fun refreshCacheSize() {
        viewModelScope.launch(Dispatchers.IO) { cacheManager.updateCacheSize() }
    }

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    fun clearAppCache(includeUploadPreviews: Boolean = false, onComplete: (() -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            cacheManager.clear(includeUploadPreviews)
            withContext(Dispatchers.Main) { onComplete?.invoke() }
        }
    }

    fun pruneOrphanTempFiles() {
        viewModelScope.launch(Dispatchers.IO) { cacheManager.pruneOrphans() }
    }
}
