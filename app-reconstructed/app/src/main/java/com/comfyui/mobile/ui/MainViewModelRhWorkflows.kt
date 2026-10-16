package com.comfyui.mobile.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.comfyui.mobile.GenerationLiveUpdate
import com.comfyui.mobile.KeepAliveService
import com.comfyui.mobile.backend.BackendKind
import com.comfyui.mobile.backend.GenerationRequest
import com.comfyui.mobile.backend.RhFieldValue
import com.comfyui.mobile.backend.RunningHubBackend
import com.comfyui.mobile.backend.TaskError
import com.comfyui.mobile.backend.TaskState
import com.comfyui.mobile.data.TaskRecord
import com.comfyui.mobile.model.LogType
import com.comfyui.mobile.model.RunningHubSite
import com.comfyui.mobile.network.RhApiException
import com.comfyui.mobile.network.RhError
import com.comfyui.mobile.network.SecretRedactor
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** RunningHub metadata of one saved cloud workflow (SharedPreferences, keyed by the workflow file name). */
data class RhWorkflowMeta(
    /** RunningHub workflowId; "" = explicitly cleared by the user (no file-name recovery). */
    val workflowId: String? = null,
    /** workflowId recovered from the file name (not stored explicitly). */
    val recovered: Boolean = false,
    /** RunningHubSite.id the workflow belongs to (null = not marked yet); filled on read, not stored in the JSON. */
    val site: String? = null
    )

/**
 * RunningHub cloud WORKFLOWS: the only way they run is the official task API (/task/openapi/create) with the
 * workflow's RunningHub workflowId plus the full edited API graph. Tasks are persisted in the TaskStore right
 * after create and tracked by polling (shared with AI apps), so they survive the app being swiped away.
 * Without a workflowId nothing is submitted (RunningHub rejects graph-only creates).
 * Nothing here is used for LOCAL ComfyUI servers.
 */
object MainViewModelRhWorkflows {

    const val ORIGIN = "official_workflow"
    /** workflowKey prefix of agent-triggered cloud runs: outputs go to the agent result list. */
    const val AGENT_KEY_PREFIX = "__agent_rh__:"
    const val MISSING_ID_MESSAGE = "请先填写 RunningHub 工作流 ID"
    const val SITE_MISMATCH_TOAST = "请确认服务站"

    /** Legacy (site-less) meta key; read as a fallback and removed on the first site-prefixed write. */
    private const val META_PREFIX = "rh_wf_meta:"
    /** Site of a saved cloud workflow file: "rh_wf_site:<fileName>" = "cn" / "ai" (absent = not marked). */
    private const val SITE_PREFIX = "rh_wf_site:"
    /** Site of each official upload name ("api/..." -> "cn" / "ai"); upload names are only valid on their site. */
    private const val UPLOAD_SITES_KEY = "rh_upload_sites_v1"
    private const val MAX_UPLOAD_SITES = 500
    /** Obsolete pref of the removed proxy upload path (proxy file name -> local file); deleted on start. */
    private const val OBSOLETE_UPLOAD_SOURCES_KEY = "rh_local_upload_sources_v1"
    private const val MAX_BUSY_RETRIES = 60

    private val ID_IN_NAME = Regex("""(?<!\d)(\d{15,25})(?!\d)""")
    private val ID_IN_INPUT = Regex("""\d{10,25}""")
    private val INPUT_KEYS = setOf("image", "video", "audio", "file", "upload")
    private val LOADER_CLASS = Regex("(?i)load\\s*_?(image|video|audio)|vhs_load|loadimagemask")
    /** PARAMS_INVALID / WORKFLOW_NOT_EXISTS / workflow not saved: most likely a wrong workflowId. */
    private val ID_REJECT_CODES = setOf(301, 380, 810)
    private val TRANSIENT_CODES = setOf(1003, 1005, 1010, 1011)

    // ---------------- workflowId metadata ----------------

    fun cloudWorkflowFile(vm: MainViewModel, key: String): File =
        File(File(File(vm.getApplication<Application>().filesDir, "saved_workflows"), "cloud"), key)

    fun isCloudWorkflowKey(vm: MainViewModel, key: String?): Boolean {
        if (key.isNullOrBlank() || key.contains('/') || key.contains('\\')) return false
        return runCatching { cloudWorkflowFile(vm, key).isFile }.getOrDefault(false)
    }

    private fun metaKey(site: String, key: String) = "rh_wf_meta@$site:$key"

    fun getMeta(vm: MainViewModel, key: String): RhWorkflowMeta {
        val site = siteOf(vm, key)
        val raw = site?.let { vm.prefs.getString(metaKey(it, key), null) } ?: vm.prefs.getString(META_PREFIX + key, null)
        val stored = raw?.let { runCatching { vm.gson.fromJson(it, RhWorkflowMeta::class.java) }.getOrNull() }
        val storedId = stored?.workflowId
        if (stored != null && storedId != null) {
            return if (storedId.isBlank()) RhWorkflowMeta(workflowId = null, site = site) else RhWorkflowMeta(workflowId = storedId, site = site)
        }
        // Older imports: "云端工作流_<id>.json" style names carry the RunningHub id.
        val fromName = ID_IN_NAME.find(key.substringBeforeLast('.'))?.groupValues?.getOrNull(1)
        return if (fromName != null) RhWorkflowMeta(workflowId = fromName, recovered = true, site = site) else RhWorkflowMeta(site = site)
    }

    /** Writes the site-prefixed meta (marks the file with the current site if unmarked) and drops the legacy key. */
    private fun saveMeta(vm: MainViewModel, key: String, meta: RhWorkflowMeta) {
        val site = siteOf(vm, key) ?: currentSiteId(vm)?.also { setSite(vm, key, it) }
        val json = vm.gson.toJson(meta.copy(recovered = false, site = null))
        val editor = vm.prefs.edit()
        if (site != null) {
            editor.putString(metaKey(site, key), json).remove(META_PREFIX + key)
        } else {
            editor.putString(META_PREFIX + key, json)
        }
        editor.apply()
        if (key == vm.currentWorkflowKey) refreshCurrentMeta(vm)
    }

    /** Import from RunningHub (/workflow/{id}, /post/{id} or a plain id): remember the id (and site) for the saved file. */
    fun rememberImportedId(vm: MainViewModel, key: String, idOrLink: String, siteId: String? = null) {
        val targetSite = siteId ?: currentSiteId(vm)
        targetSite?.let { setSite(vm, key, it) }
        val clean = ID_IN_INPUT.find(idOrLink)?.value ?: return
        saveMeta(vm, key, RhWorkflowMeta(workflowId = clean))
    }

    // ---------------- site (中国站 / 国际站) isolation ----------------

    /** Site id of the current profile, or null when the current profile is not RunningHub. */
    fun currentSiteId(vm: MainViewModel): String? =
        vm.currentServerProfile()?.takeIf { it.isRunningHub }?.effectiveRhSite?.id

    fun siteName(siteId: String?): String =
        if (siteId.isNullOrBlank()) "" else RunningHubSite.fromId(siteId).displayName

    fun siteOf(vm: MainViewModel, key: String): String? =
        vm.prefs.getString(SITE_PREFIX + key, null)?.takeIf { it.isNotBlank() }

    private fun setSite(vm: MainViewModel, key: String, siteId: String) {
        val editor = vm.prefs.edit().putString(SITE_PREFIX + key, siteId)
        // Migrate a legacy (site-less) meta to the site-prefixed key.
        val legacy = vm.prefs.getString(META_PREFIX + key, null)
        if (legacy != null && vm.prefs.getString(metaKey(siteId, key), null) == null) {
            editor.putString(metaKey(siteId, key), legacy)
        }
        editor.remove(META_PREFIX + key).apply()
        if (key == vm.currentWorkflowKey) refreshCurrentMeta(vm)
    }

    /** Marks a file just written into the cloud workflow folder with the current site (no-op otherwise). */
    fun markSavedFile(vm: MainViewModel, file: File, siteId: String? = currentSiteId(vm)) {
        if (siteId == null) return
        val inCloudDir = runCatching { file.parentFile?.canonicalPath == cloudWorkflowFile(vm, "x").parentFile?.canonicalPath }.getOrDefault(false)
        if (inCloudDir) setSite(vm, file.name, siteId)
    }

    /** Cloud list filter: files of the current site plus unmarked (older) ones. */
    fun isVisibleOnCurrentSite(vm: MainViewModel, key: String): Boolean {
        val cur = currentSiteId(vm) ?: return true
        val s = siteOf(vm, key)
        return s == null || s == cur
    }

    /** Refusal when [key] is stamped to a different site; unmarked old data is allowed. */
    fun siteBlockMessage(vm: MainViewModel, key: String?): String? {
        if (key.isNullOrBlank() || !isCloudWorkflowKey(vm, key)) return null
        val cur = currentSiteId(vm) ?: return null
        val s = siteOf(vm, key) ?: return null
        return if (s != cur) SITE_MISMATCH_TOAST else null
    }

    // ---- upload names are site-specific ----
    private var uploadSites: LinkedHashMap<String, String>? = null

    private fun uploadSiteMap(vm: MainViewModel): LinkedHashMap<String, String> {
        uploadSites?.let { return it }
        val type = object : TypeToken<LinkedHashMap<String, String>>() {}.type
        val loaded: LinkedHashMap<String, String> = vm.prefs.getString(UPLOAD_SITES_KEY, null)?.let { raw ->
            runCatching { vm.gson.fromJson<LinkedHashMap<String, String>>(raw, type) }.getOrNull()
        } ?: LinkedHashMap()
        uploadSites = loaded
        return loaded
    }

    /** Remembers on which site an official upload name ("api/...") was created. */
    fun recordUploadSite(vm: MainViewModel, fileName: String, siteId: String) = synchronized(this) {
        val map = uploadSiteMap(vm)
        map.remove(fileName)
        map[fileName] = siteId
        while (map.size > MAX_UPLOAD_SITES) map.remove(map.keys.first())
        vm.prefs.edit().putString(UPLOAD_SITES_KEY, vm.gson.toJson(map)).apply()
    }

    /** Upload names among [values] that were uploaded on a site other than [siteId] (unknown ones are allowed). */
    fun foreignUploads(vm: MainViewModel, values: Collection<Any?>, siteId: String): List<Pair<String, String>> = synchronized(this) {
        val map = uploadSiteMap(vm)
        values.mapNotNull { v ->
            val s = (v as? String)?.trim()?.takeIf { it.startsWith("api/") } ?: return@mapNotNull null
            val site = map[s] ?: return@mapNotNull null
            if (site != siteId) s to site else null
        }.distinct()
    }

    private fun graphStringValues(graph: Map<String, Any>): List<Any?> =
        graph.values.flatMap { n -> ((n as? Map<*, *>)?.get("inputs") as? Map<*, *>)?.values.orEmpty() }

    /** Site / upload checks before submitting [graph] of [key]; shows the refusal and returns false when blocked. */
    private fun checkSiteBeforeRun(vm: MainViewModel, key: String?, graph: Map<String, Any>?, siteId: String): Boolean {
        siteBlockMessage(vm, key)?.let {
            vm.showToast(SITE_MISMATCH_TOAST)
            return false
        }
        if (graph != null) {
            val foreign = foreignUploads(vm, graphStringValues(graph), siteId)
            if (foreign.isNotEmpty()) {
                vm.showToast(SITE_MISMATCH_TOAST)
                return false
            }
        }
        return true
    }

    // ---------------- delete a saved cloud workflow (local data only; nothing is called on RunningHub) ----------------

    /**
     * Deletes the saved cloud workflow [file] and its app-side data: workflowId meta (all site variants + legacy),
     * its site mark, per-workflow prefs (param values / labels / visibility / order / latest results of the cloud
     * namespaces) and last-workflow memories pointing to it; unloads it if it is the current workflow.
     * In-flight tasks of this workflow are cancelled on RunningHub and marked cancelled locally.
     * Completed / failed task records are kept. Downloaded album files are left.
     */
    fun deleteCloudWorkflow(vm: MainViewModel, file: File): Boolean {
        val key = file.name
        val sameFile = runCatching { cloudWorkflowFile(vm, key).canonicalPath == file.canonicalPath }.getOrDefault(false)
        if (!isCloudWorkflowKey(vm, key) || !sameFile) return false
        if (key == vm.currentWorkflowKey) MainViewModelWorkflow.clearCurrentWorkflow(vm)
        if (!file.delete() && file.exists()) return false
        val localTwin = File(File(File(vm.getApplication<Application>().filesDir, "saved_workflows"), "local"), key).exists()
        val path = file.absolutePath
        val editor = vm.prefs.edit()
        editor.remove(META_PREFIX + key).remove(SITE_PREFIX + key)
        RunningHubSite.entries.forEach { editor.remove(metaKey(it.id, key)) }
        for ((k, v) in vm.prefs.all) {
            when {
                k.startsWith("workflow@cloud") && k.substringAfter(':').startsWith("$key:") -> editor.remove(k)
                // Pre-namespace shared key: only when no local workflow of the same name could still use it.
                !localTwin && k.startsWith("workflow:$key:") -> editor.remove(k)
                k.startsWith("last_workflow_target") && v == path -> {
                    editor.remove(k)
                    editor.remove("last_workflow_type" + k.removePrefix("last_workflow_target"))
                }
            }
        }
        editor.apply()
        vm.forgetWorkflowCaches(key)
        MainViewModelRhApps.detachDeletedWorkflow(vm, key)
        MainViewModelWorkflow.refreshSavedWorkflows(vm)
        vm.addLog("已删除云端工作流「${key.removeSuffix(".json")}」（本机数据与该工作流的任务记录已清除）", LogType.INFO)
        return true
    }

    /**
     * Manual entry for the current cloud workflow: plain id or a RunningHub link; blank clears it.
     * Returns an error text, or null on success.
     */
    fun setWorkflowIdForCurrent(vm: MainViewModel, input: String): String? {
        val key = vm.currentWorkflowKey
        if (!isCloudWorkflowKey(vm, key) || key == null) return "请先保存为云端工作流，再填写 ID"
        val cur = currentSiteId(vm)
        val site = siteOf(vm, key)
        if (cur != null && site != null && site != cur) {
            return SITE_MISMATCH_TOAST
        }
        val t = input.trim()
        if (t.isEmpty()) {
            saveMeta(vm, key, RhWorkflowMeta(workflowId = ""))
            vm.addLog("已清除此工作流的 RunningHub 工作流 ID", LogType.INFO)
            return null
        }
        val inputSite = RunningHubSite.fromUrl(t)
        val targetSite = site ?: cur
        if (inputSite != null && targetSite != null && inputSite.id != targetSite) {
            return "站点不匹配，该链接属于${inputSite.displayName}"
        }
        val clean = ID_IN_INPUT.find(t)?.value ?: return "无法识别工作流 ID（请输入纯数字 ID 或 RunningHub 工作流链接）"
        saveMeta(vm, key, RhWorkflowMeta(workflowId = clean))
        vm.addLog("已设置此工作流的 RunningHub 工作流 ID: $clean", LogType.SUCCESS)
        return null
    }

    fun refreshCurrentMeta(vm: MainViewModel) {
        val key = vm.currentWorkflowKey
        vm._currentRhWorkflowMeta.value = try {
            if (key != null && isCloudWorkflowKey(vm, key)) getMeta(vm, key) else null
        } catch (_: Exception) {
            null
        }
    }

    /** workflowId of a saved cloud workflow, or null (not a saved cloud file / not set). */
    private fun workflowIdFor(vm: MainViewModel, key: String?): String? =
        key?.takeIf { isCloudWorkflowKey(vm, it) }?.let { getMeta(vm, it).workflowId }?.takeIf { it.isNotBlank() }

    /** Refused run without workflowId: message, and the ID dialog on the Control screen when it can be set there. */
    private fun onMissingId(vm: MainViewModel, key: String?) {
        vm.addLog("$MISSING_ID_MESSAGE（未提交任务）", LogType.WARNING)
        if (key != null && isCloudWorkflowKey(vm, key) && key == vm.currentWorkflowKey) {
            vm.showToast(MISSING_ID_MESSAGE)
            vm.requestRhWorkflowId()
        } else {
            vm.showToast("$MISSING_ID_MESSAGE（请先保存为云端工作流，再在「运行」下方填写）")
        }
    }

    // ---------------- inputs / seeds ----------------

    /**
     * Input files of LoadImage/LoadVideo/LoadAudio-style nodes must be RunningHub names: files picked in the app
     * are uploaded via /task/openapi/upload when picked ("api/..."), unchanged values of the imported workflow
     * are server-side names. Anything else is only reported (submitted as is).
     */
    private suspend fun checkInputs(vm: MainViewModel, graph: Map<String, Any>, sourceKey: String?) {
        val original: Map<String, Any>? = if (sourceKey != null && isCloudWorkflowKey(vm, sourceKey)) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val type = object : TypeToken<Map<String, Any>>() {}.type
                    vm.gson.fromJson<Map<String, Any>>(cloudWorkflowFile(vm, sourceKey).readText(), type)
                }.getOrNull()
            }
        } else null
        for ((nodeId, nodeAny) in graph) {
            val node = nodeAny as? Map<*, *> ?: continue
            val cls = node["class_type"] as? String ?: continue
            if (!LOADER_CLASS.containsMatchIn(cls)) continue
            val inputs = node["inputs"] as? Map<*, *> ?: continue
            for ((kAny, vAny) in inputs) {
                val k = kAny?.toString() ?: continue
                if (k.lowercase() !in INPUT_KEYS) continue
                val v = (vAny as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                if (v.startsWith("api/")) continue
                val origInputs = (original?.get(nodeId) as? Map<*, *>)?.get("inputs") as? Map<*, *>
                if ((origInputs?.get(k) as? String)?.trim() == v) continue
                vm.addLog("输入 #$nodeId.$k「$v」不是在本 App 中上传到 RunningHub 的文件，按原值提交（如运行失败请重新选择该文件）", LogType.WARNING)
            }
        }
    }

    /** seed / noise_seed values of the graph, pinned via nodeInfoList (the API otherwise re-randomizes seeds). */
    fun seedFields(graph: Map<String, Any>): List<RhFieldValue> {
        val out = mutableListOf<RhFieldValue>()
        for ((nodeId, nodeAny) in graph) {
            val inputs = (nodeAny as? Map<*, *>)?.get("inputs") as? Map<*, *> ?: continue
            for (k in listOf("seed", "noise_seed")) {
                val v = inputs[k] as? Number ?: continue
                out.add(RhFieldValue(nodeId, k, v.toLong().toString()))
            }
        }
        return out
    }

    // ---------------- submit ----------------

    private sealed class SubmitOutcome {
        data class Submitted(val localId: String, val taskId: String) : SubmitOutcome()
        data class Failed(val message: String, val code: Int?) : SubmitOutcome()
    }

    private fun isWorkflowIdRejection(e: Throwable): Boolean {
        val rh = e as? RhApiException
        val code = rh?.code
        if (code != null && code in ID_REJECT_CODES) return true
        val text = (rh?.rawMsg ?: e.message).orEmpty()
        return text.contains("workflowId", ignoreCase = true) ||
            text.contains("workflow_id", ignoreCase = true) ||
            (text.contains("工作流") && (text.contains("ID", ignoreCase = true) || text.contains("不存在")))
    }

    /** Creates one task; the record is written (SUBMITTING) before the call and updated with the taskId right after. */
    private suspend fun submitOne(
        vm: MainViewModel,
        backend: RunningHubBackend,
        graph: Map<String, Any>,
        workflowId: String,
        workflowKey: String?,
        title: String
    ): SubmitOutcome {
        val localId = "rhwf_" + UUID.randomUUID().toString()
        vm.taskStore.upsert(
            TaskRecord(
                localId = localId,
                serverId = backend.serverId,
                backend = BackendKind.RUNNINGHUB.name,
                state = TaskState.SUBMITTING.name,
                title = title,
                workflowKey = workflowKey,
                gpuTier = backend.gpuTierLabel,
                instanceType = backend.defaultInstanceType,
                createdAt = System.currentTimeMillis(),
                origin = ORIGIN,
                rhSite = backend.site.id,
                rhKeySig = com.comfyui.mobile.data.SecretStore.keySig(vm.currentServerProfile()?.effectiveRhApiKey)
    )
        )
        val seeds = seedFields(graph)
        if (seeds.isNotEmpty()) {
            vm.addLog("「$title」种子: " + seeds.joinToString("，") { "#${it.nodeId}.${it.fieldName}=${it.fieldValue}" }, LogType.INFO)
        }
        val json = withContext(Dispatchers.Default) { vm.gson.toJson(graph) }
        var sendSeeds = seeds.isNotEmpty()
        var busyRetries = 0
        var transientRetries = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            vm._rhAppStatus.value = "提交中…"
            val res = backend.submit(
                GenerationRequest.RhWorkflow(
                    workflowId = workflowId,
                    workflowJson = json,
                    nodeInfoList = if (sendSeeds) seeds else emptyList(),
                    instanceType = backend.defaultInstanceType,
                    title = title,
                    workflowKey = workflowKey
    )
            )
            val err = res.exceptionOrNull()
            if (err == null) {
                val snap = res.getOrThrow()
                vm.taskStore.update(localId) { it.copy(remoteId = snap.remoteId, state = snap.state.name) }
                vm.addLog("已提交 RunningHub 云端工作流「$title」(taskId: ${snap.remoteId}，${backend.gpuTierLabel})", LogType.SUCCESS)
                snap.message?.let { tips -> vm.addLog("RunningHub 提示: ${tips.take(300)}", LogType.INFO) }
                vm._rhAppStatus.value = snap.state.label
                return SubmitOutcome.Submitted(localId, snap.remoteId)
            }
            if (err is CancellationException) {
                withContext(NonCancellable) {
                    vm.taskStore.update(localId) { it.copy(state = TaskState.CANCELLED.name, finishedAt = System.currentTimeMillis()) }
                }
                throw err
            }
            val rh = err as? RhApiException
            val code = rh?.code
            val msg = SecretRedactor.redact(err.message ?: "提交失败")
            if ((code == 421 || code == 415) && busyRetries < MAX_BUSY_RETRIES) {
                busyRetries++
                val waitS = if (code == 421) 15 else 30
                val what = if (code == 421) "并发任务数已满" else "实例繁忙"
                if (busyRetries == 1) vm.addLog("「$title」${RhError.message(code, null)}，等待后自动重试", LogType.WARNING)
                vm._rhAppStatus.value = "$what，${waitS} 秒后重试（第 $busyRetries 次）"
                delay(waitS * 1000L)
                continue
            }
            if (code == 803 && sendSeeds) {
                sendSeeds = false
                vm.addLog("RunningHub 不接受种子固定字段（803），改为不固定种子重试", LogType.WARNING)
                continue
            }
            if (code != null && code in TRANSIENT_CODES && transientRetries < 3) {
                transientRetries++
                vm._rhAppStatus.value = "RunningHub 繁忙，5 秒后重试…"
                delay(5_000L)
                continue
            }
            MainViewModelRhConn.onRhCallError(vm, backend.serverId, err)
            val display = when {
                code == 416 -> "RunningHub 余额不足（416）"
                isWorkflowIdRejection(err) -> "$msg（请检查 RunningHub 工作流 ID $workflowId 是否正确）"
                else -> msg
            }
            vm.taskStore.update(localId) {
                it.copy(
                    state = TaskState.FAILED.name,
                    error = TaskError(message = display, code = code, retryable = rh?.retryable == true),
                    finishedAt = System.currentTimeMillis()
    )
            }
            vm.addLog("RunningHub 云端工作流提交失败: $display", LogType.ERROR)
            return SubmitOutcome.Failed(display, code)
        }
    }

    private suspend fun resolveConcurrency(vm: MainViewModel, backend: RunningHubBackend): Int {
        val known = vm._rhQueueStatus.value?.concurrentLimit
        val limit = known ?: backend.client.queueStatus().getOrNull()?.also { qs ->
            if (vm.currentServerProfile()?.effectiveId == backend.serverId) vm._rhQueueStatus.value = qs
        }?.concurrentLimit
        return (limit ?: 1).coerceIn(1, 8)
    }

    private suspend fun runBatch(
        vm: MainViewModel,
        backend: RunningHubBackend,
        baseWorkflow: Map<String, Any>,
        workflowKey: String?,
        title: String,
        workflowId: String,
        n: Int
    ) = coroutineScope {
        checkInputs(vm, baseWorkflow, workflowKey)
        val limit = if (n > 1) resolveConcurrency(vm, backend) else 1
        if (n > 1) vm.addLog("云端批量 $n 个任务（并发上限 $limit，每个任务单独记录）", LogType.INFO)
        val sem = Semaphore(limit)
        for (i in 1..n) {
            currentCoroutineContext().ensureActive()
            sem.acquire()
            val graph = vm.randomizeSeedsForSend(baseWorkflow)
            val itemTitle = if (n > 1) "$title (${i}/$n)" else title
            when (val r = submitOne(vm, backend, graph, workflowId, workflowKey, itemTitle)) {
                is SubmitOutcome.Submitted -> launch {
                    try {
                        MainViewModelRhApps.pollUntilTerminal(vm, backend, r.localId, r.taskId, workflowKey)
                    } finally {
                        sem.release()
                    }
                }
                is SubmitOutcome.Failed -> {
                    sem.release()
                    vm.showToast(if (r.code == 416) "RunningHub 余额不足，已停止批量任务" else "提交失败: ${r.message}")
                    if (i < n) vm.addLog("已停止剩余 ${n - i} 个批量任务", LogType.WARNING)
                    return@coroutineScope
                }
            }
        }
    }

    /** Control screen "运行" for a cloud workflow (official task API only). */
    fun runCurrent(vm: MainViewModel, baseWorkflow: Map<String, Any>, workflowKey: String?, title: String, batchCount: Int) {
        val server = vm.currentServerProfile() ?: return
        if (vm._rhAppRunning.value) {
            vm.showToast("已有云端任务在进行，请等待完成或先点「停止」")
            return
        }
        val backend = vm.currentRunningHubBackend()
        if (backend == null || !backend.hasKey) {
            vm.showToast("当前 RunningHub 服务器未配置 API Key")
            return
        }
        if (MainViewModelRhConn.isUnauthorized(vm, server)) {
            vm.showToast("RunningHub API Key 无效，请在服务器设置中检查")
            return
        }
        if (!checkSiteBeforeRun(vm, workflowKey, baseWorkflow, backend.site.id)) return
        val workflowId = workflowIdFor(vm, workflowKey)
        if (workflowId == null) {
            onMissingId(vm, workflowKey)
            return
        }
        val n = batchCount.coerceIn(1, 8)
        com.comfyui.mobile.GenerationLiveUpdate.noteUserStartedRun()
        vm._rhAppRunning.value = true
        if (!workflowKey.isNullOrBlank()) vm.resetLatestGenerationFor(workflowKey)
        val jobKey = "batch_" + UUID.randomUUID().toString()
        val job = vm.viewModelScope.launch {
            try {
                runBatch(vm, backend, baseWorkflow, workflowKey, title, workflowId, n)
            } finally {
                vm.rhAppPollJobs.remove(jobKey)
                vm._rhAppRunning.value = false
                vm._rhAppStatus.value = null
            }
        }
        vm.rhAppPollJobs[jobKey] = job
    }

    /** "批量发送工作流文件" in cloud mode: every file needs its workflowId; each file is its own persisted task. */
    fun runFiles(vm: MainViewModel, files: List<File>) {
        if (files.isEmpty()) return
        val backend = vm.currentRunningHubBackend()
        if (backend == null || !backend.hasKey) {
            vm.showToast("当前 RunningHub 服务器未配置 API Key")
            return
        }
        if (vm._rhAppRunning.value) {
            vm.showToast("已有云端任务在进行，请等待完成或先点「停止」")
            return
        }
        // Pre-check: nothing is submitted unless every file has a RunningHub workflowId.
        val items = files.map { file ->
            val key = file.name.takeIf {
                isCloudWorkflowKey(vm, it) && cloudWorkflowFile(vm, it).absolutePath == file.absolutePath
            }
            Triple(file, key, workflowIdFor(vm, key))
        }
        // Site isolation: every file must belong to the current profile's site.
        val siteBlocked = items.mapNotNull { (_, key, _) -> siteBlockMessage(vm, key) }
        if (siteBlocked.isNotEmpty()) {
            vm.showToast(SITE_MISMATCH_TOAST)
            return
        }
        val missing = items.filter { it.third == null }.map { it.first.name.removeSuffix(".json") }
        if (missing.isNotEmpty()) {
            val names = missing.take(3).joinToString("、") + if (missing.size > 3) " 等 ${missing.size} 个" else ""
            vm.addLog("$MISSING_ID_MESSAGE：$names（未提交任何任务）", LogType.WARNING)
            vm.showToast("$MISSING_ID_MESSAGE：$names（打开工作流后在「运行」下方填写）")
            return
        }
        com.comfyui.mobile.GenerationLiveUpdate.noteUserStartedRun()
        vm._rhAppRunning.value = true
        val jobKey = "batch_" + UUID.randomUUID().toString()
        val job = vm.viewModelScope.launch {
            try {
                vm.showToast("开始批量提交 ${files.size} 个云端工作流…")
                val limit = if (files.size > 1) resolveConcurrency(vm, backend) else 1
                val sem = Semaphore(limit)
                coroutineScope {
                    for ((index, item) in items.withIndex()) {
                        val (file, key, workflowId) = item
                        if (workflowId == null) continue
                        currentCoroutineContext().ensureActive()
                        val graph: Map<String, Any> = try {
                            val type = object : TypeToken<Map<String, Any>>() {}.type
                            val raw: Map<String, Any> = withContext(Dispatchers.IO) { vm.gson.fromJson(file.readText(), type) }
                            @Suppress("UNCHECKED_CAST")
                            vm.sanitizeNumbers(raw) as Map<String, Any>
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            vm.addLog("批量发送出错 (${index + 1}/${files.size}): ${file.name} - ${e.message}", LogType.ERROR)
                            continue
                        }
                        val foreign = foreignUploads(vm, graphStringValues(graph), backend.site.id)
                        if (foreign.isNotEmpty()) {
                            vm.showToast(SITE_MISMATCH_TOAST)
                            continue
                        }
                        sem.acquire()
                        checkInputs(vm, graph, key)
                        when (val r = submitOne(vm, backend, graph, workflowId, key, file.name.removeSuffix(".json"))) {
                            is SubmitOutcome.Submitted -> launch {
                                try {
                                    MainViewModelRhApps.pollUntilTerminal(vm, backend, r.localId, r.taskId, key)
                                } finally {
                                    sem.release()
                                }
                            }
                            is SubmitOutcome.Failed -> {
                                sem.release()
                                if (r.code == 416) {
                                    vm.showToast("RunningHub 余额不足，已停止批量任务")
                                    break
                                }
                            }
                        }
                    }
                }
            } finally {
                vm.rhAppPollJobs.remove(jobKey)
                vm._rhAppRunning.value = false
                vm._rhAppStatus.value = null
            }
        }
        vm.rhAppPollJobs[jobKey] = job
    }

    private fun trackInBackground(vm: MainViewModel, backend: RunningHubBackend, localId: String, taskId: String, workflowKey: String?) {
        val key = "resume_$localId"
        if (vm.rhAppPollJobs.containsKey(key)) return
        val job = vm.viewModelScope.launch {
            try {
                MainViewModelRhApps.pollUntilTerminal(vm, backend, localId, taskId, workflowKey)
            } finally {
                vm.rhAppPollJobs.remove(key)
            }
        }
        vm.rhAppPollJobs[key] = job
    }

    /**
     * Agent-triggered run in cloud mode (official task API only). Fails with [MISSING_ID_MESSAGE] when the
     * agent workflow has no RunningHub workflowId. Tasks are persisted and tracked in the background; outputs
     * go to the agent result list. Success value = last taskId.
     */
    suspend fun submitForAgent(
        vm: MainViewModel,
        baseWorkflow: Map<String, Any>,
        label: String,
        sourceKey: String?,
        batchCount: Int
    ): Result<String> {
        val server = vm.currentServerProfile() ?: return Result.failure(IllegalStateException("未选择服务器"))
        val backend = vm.currentRunningHubBackend()
        if (backend == null || !backend.hasKey) return Result.failure(IllegalStateException("当前 RunningHub 服务器未配置 API Key"))
        if (MainViewModelRhConn.isUnauthorized(vm, server)) return Result.failure(IllegalStateException("RunningHub API Key 无效"))
        val cloudKey = sourceKey?.takeIf { isCloudWorkflowKey(vm, it) }
        siteBlockMessage(vm, cloudKey)?.let {
            return Result.failure(IllegalStateException(SITE_MISMATCH_TOAST))
        }
        foreignUploads(vm, graphStringValues(baseWorkflow), backend.site.id).takeIf { it.isNotEmpty() }?.let {
            return Result.failure(IllegalStateException(SITE_MISMATCH_TOAST))
        }
        val workflowId = workflowIdFor(vm, cloudKey)
        if (workflowId == null) {
            vm.addLog("Agent 云端工作流未运行：$MISSING_ID_MESSAGE", LogType.WARNING)
            return Result.failure(IllegalStateException(MISSING_ID_MESSAGE))
        }
        val cleanTitle = label.trim()
            .removePrefix("Agent · ")
            .removePrefix("Agent ·")
            .removePrefix("Agent: ")
            .removePrefix("Agent:")
            .removeSuffix(".json")
            .removeSuffix(".JSON")
            .ifBlank { "工作流" }
        val key = AGENT_KEY_PREFIX + cleanTitle
        checkInputs(vm, baseWorkflow, cloudKey)
        GenerationLiveUpdate.noteUserStartedRun()
        var last: String? = null
        repeat(batchCount.coerceIn(1, 8)) {
            val graph = vm.randomizeSeedsForSend(baseWorkflow)
            when (val r = submitOne(vm, backend, graph, workflowId, key, cleanTitle)) {
                is SubmitOutcome.Submitted -> {
                    last = r.taskId
                    trackInBackground(vm, backend, r.localId, r.taskId, key)
                }
                is SubmitOutcome.Failed -> {
                    val done = last
                    return if (done == null) Result.failure(IllegalStateException(r.message)) else Result.success(done)
                }
            }
        }
        return Result.success(last ?: "")
    }

    // ---------------- background keep-alive ----------------

    /**
     * While any official task of the current profile is non-terminal (or being submitted), keep the
     * foreground KeepAliveService running (polling continues in the background) and show the cloud state
     * in the Live Update notification. KeepAliveService.start already handles
     * ForegroundServiceStartNotAllowedException (background start) gracefully.
     */
    fun startBusyWatcher(vm: MainViewModel) {
        vm.prefs.edit().remove(OBSOLETE_UPLOAD_SOURCES_KEY).apply()
        vm.viewModelScope.launch {
            combine(vm.taskStore.tasks, vm._currentServerId, vm._rhAppRunning, vm._rhConnState, vm._servers) { tasks, sid, running, st, servers ->
                val site = servers.firstOrNull { it.effectiveId == sid }?.takeIf { it.isRunningHub }?.effectiveRhSite?.id
                running || (st != RhConnState.UNAUTHORIZED && tasks.any {
                    it.backend == BackendKind.RUNNINGHUB.name &&
                        it.serverId == sid &&
                        (it.rhSite.isNullOrBlank() || it.rhSite == site) &&
                        MainViewModelRhApps.isTaskApiOrigin(it.origin) &&
                        it.taskState.isActive &&
                        !it.remoteId.isNullOrBlank()
                })
            }.distinctUntilChanged().collect { busy -> vm._rhOfficialBusy.value = busy }
        }
        vm.viewModelScope.launch {
            combine(vm._rhOfficialBusy, vm.repository.isGenerating, vm._rhAppStatus) { busy, gen, status ->
                Triple(busy, gen, status)
            }.collect { (busy, gen, status) ->
                val app = vm.getApplication<Application>()
                if (GenerationLiveUpdate.isUserStopRequested()) {
                    if (gen) vm.repository.setIsGenerating(false)
                    if (!busy) GenerationLiveUpdate.noteUserStartedRun()
                    return@collect
                }
                if (busy) {
                    GenerationLiveUpdate.cloudStatusText = status?.takeIf { it.isNotBlank() } ?: "云端任务进行中"
                    if (!gen) vm.repository.setIsGenerating(true)
                    if (!KeepAliveService.isServiceRunning.get()) {
                        KeepAliveService.start(app)
                    } else {
                        runCatching { GenerationLiveUpdate.update(app, 0f, 0, isExecuting = false, isGenerating = true) }
                    }
                } else {
                    val sid = vm._currentServerId.value
                    val leftover = vm.taskStore.tasks.value.any {
                        it.backend == BackendKind.RUNNINGHUB.name &&
                            it.serverId == sid &&
                            MainViewModelRhApps.isTaskApiOrigin(it.origin) &&
                            !it.taskState.isTerminal
                    }
                    if (leftover) {
                        GenerationLiveUpdate.dismissQuietly(app)
                        if (!vm.isRunningNow) vm.repository.setIsGenerating(false)
                        return@collect
                    }
                    if (GenerationLiveUpdate.cloudStatusText != null) {
                        GenerationLiveUpdate.cloudStatusText = null
                        if (!vm.isRunningNow) vm.repository.setIsGenerating(false)
                    }
                    GenerationLiveUpdate.noteUserStartedRun()
                }
            }
        }
    }
}