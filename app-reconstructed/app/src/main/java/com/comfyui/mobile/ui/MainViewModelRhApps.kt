package com.comfyui.mobile.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.comfyui.mobile.backend.BackendKind
import com.comfyui.mobile.backend.GenerationRequest
import com.comfyui.mobile.backend.RhFieldValue
import com.comfyui.mobile.backend.RunningHubBackend
import com.comfyui.mobile.backend.TaskError
import com.comfyui.mobile.backend.TaskOutput
import com.comfyui.mobile.backend.TaskState
import com.comfyui.mobile.data.TaskRecord
import com.comfyui.mobile.model.AiApp
import com.comfyui.mobile.model.EditableNode
import com.comfyui.mobile.model.LogType
import com.comfyui.mobile.model.MediaOutput
import com.comfyui.mobile.network.RhApiException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

/** One editable field of a RunningHub AI app template (from /api/webapp/apiCallDemo nodeInfoList). */
data class RhTemplateField(
    val nodeId: String,
    val nodeName: String,
    val fieldName: String,
    val fieldType: String,
    val fieldValue: Any?,
    val description: String?,
    val options: List<String>?,
    val min: Double?,
    val max: Double?
    )

/**
 * RunningHub AI apps on the official API: template parsing, editable nodes, submit via
 * [RunningHubBackend], in-app polling with backoff, output download into the gallery, TaskStore.
 */
object MainViewModelRhApps {

    private const val MAX_TASK_MS = 3 * 60 * 60_000L

    private val pollingLocalIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Records tracked through /task/openapi (AI apps and official-API cloud workflows). */
    fun isTaskApiOrigin(origin: String?): Boolean = origin == "ai_app" || origin == "official_workflow"

    // ---------------- template ----------------

    fun parseTemplate(vm: MainViewModel, json: String?): List<RhTemplateField> {
        if (json.isNullOrBlank()) return emptyList()
        val type = object : TypeToken<List<Map<String, Any?>>>() {}.type
        val raw: List<Map<String, Any?>> = try {
            vm.gson.fromJson<List<Map<String, Any?>>>(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        return raw.mapNotNull { fieldFromMap(vm, it) }
    }

    fun fieldFromMap(vm: MainViewModel, item: Map<String, Any?>): RhTemplateField? {
        val nodeId = item["nodeId"]?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val fieldName = item["fieldName"]?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val nodeName = item["nodeName"]?.toString()?.trim().orEmpty().ifBlank { "Node" }
        val fieldData = parseFieldData(vm, item["fieldData"])
        val declared = item["fieldType"]?.toString()?.trim()?.uppercase().orEmpty()
        val type = declared.ifBlank {
            when {
                nodeName.contains("LoadImage", true) && fieldName == "image" -> "IMAGE"
                nodeName.contains("LoadVideo", true) -> "VIDEO"
                nodeName.contains("LoadAudio", true) -> "AUDIO"
                else -> "STRING"
            }
        }
        var options: List<String>? = null
        var min: Double? = null
        var max: Double? = null
        val list = fieldData as? List<*>
        if (type == "LIST" && list != null) {
            options = list.mapNotNull { el ->
                when (el) {
                    is Map<*, *> -> (el["name"] ?: el["index"])?.toString()?.takeIf { it.isNotBlank() }
                    is String -> el.takeIf { it.isNotBlank() }
                    is Number -> el.toString()
                    else -> null
                }
            }.distinct().ifEmpty { null }
        }
        if (list != null) {
            val spec = list.firstOrNull { it is Map<*, *> && (it.containsKey("min") || it.containsKey("max")) } as? Map<*, *>
            min = (spec?.get("min") as? Number)?.toDouble() ?: spec?.get("min")?.toString()?.toDoubleOrNull()
            max = (spec?.get("max") as? Number)?.toDouble() ?: spec?.get("max")?.toString()?.toDoubleOrNull()
        }
        val cur = item["fieldValue"]
        if (options != null && cur != null && cur.toString().isNotBlank() && cur.toString() !in options) {
            options = listOf(cur.toString()) + options
        }
        val desc = (item["description"] ?: item["descriptionEn"])?.toString()?.trim()?.takeIf { it.isNotBlank() && it != "null" }
        return RhTemplateField(nodeId, nodeName, fieldName, type, cur, desc, options, min, max)
    }

    private fun parseFieldData(vm: MainViewModel, raw: Any?): Any? {
        if (raw is String) {
            val t = raw.trim()
            if (t.startsWith("[") || t.startsWith("{")) {
                return try { vm.gson.fromJson(t, Any::class.java) } catch (_: Exception) { raw }
            }
        }
        return raw
    }

    private fun isNumberType(t: String) = t == "INT" || t == "FLOAT" || t == "NUMBER" || t == "INTEGER"
    private fun isBoolType(t: String) = t == "BOOLEAN" || t == "BOOL" || t == "SWITCH"
    private fun isMediaType(t: String) = t == "IMAGE" || t == "VIDEO" || t == "AUDIO"

    /** Initial value stored in the app's synthetic graph (typed so edits/overrides keep working). */
    fun initialGraphValue(f: RhTemplateField): Any {
        val v = f.fieldValue
        return when {
            isNumberType(f.fieldType) -> (v as? Number) ?: v?.toString()?.trim()?.toDoubleOrNull()?.let { d ->
                if (d % 1.0 == 0.0 && f.fieldType != "FLOAT") d.toLong() else d
            } ?: (v?.toString() ?: "")
            isBoolType(f.fieldType) -> (v as? Boolean) ?: v?.toString()?.trim()?.lowercase()?.let { it == "true" || it == "1" } ?: false
            else -> v?.toString() ?: ""
        }
    }

    /** RunningHub fieldValue is always sent as a string. */
    fun fieldValueString(v: Any?): String = when (v) {
        null -> ""
        is Double -> if (v.isFinite() && v % 1.0 == 0.0) v.toLong().toString() else v.toString()
        is Float -> if (v.isFinite() && v % 1.0f == 0.0f) v.toLong().toString() else v.toString()
        is Number -> v.toString()
        is Boolean -> v.toString()
        else -> v.toString()
    }

    /** Editable nodes built from the template (fieldType aware) with current graph values. */
    fun buildEditableNodes(
        workflow: Map<String, Any>,
        template: List<RhTemplateField>,
        visibility: Map<String, Boolean>,
        labels: Map<String, String>
    ): List<EditableNode> = template.map { f ->
        val key = "${f.nodeId}:${f.fieldName}"
        val inputs = (workflow[f.nodeId] as? Map<*, *>)?.get("inputs") as? Map<*, *>
        val value = inputs?.get(f.fieldName) ?: f.fieldValue
        val title = labels[key]?.takeIf { it.isNotBlank() } ?: f.description ?: "${f.nodeName} · ${f.fieldName}"
        val visible = visibility[key] ?: true
        when {
            isMediaType(f.fieldType) -> EditableNode.ImageInput(
                nodeId = f.nodeId,
                title = title,
                classType = when (f.fieldType) { "VIDEO" -> "LoadVideo"; "AUDIO" -> "LoadAudio"; else -> "LoadImage" },
                filename = value?.toString().orEmpty(),
                key = f.fieldName,
                subfolder = "",
                type = "rh",
                isVisible = visible
    )
            isBoolType(f.fieldType) -> EditableNode.BooleanInput(
                nodeId = f.nodeId, title = title, classType = f.nodeName,
                value = (value as? Boolean) ?: value?.toString()?.trim()?.lowercase().let { it == "true" || it == "1" },
                key = f.fieldName, isVisible = visible
    )
            isNumberType(f.fieldType) && (value is Number || value?.toString()?.trim()?.toDoubleOrNull() != null) -> EditableNode.NumberInput(
                nodeId = f.nodeId, title = title, classType = f.nodeName,
                value = (value as? Number) ?: value.toString().trim().toDouble(),
                key = f.fieldName, min = f.min, max = f.max, isVisible = visible
    )
            else -> EditableNode.TextInput(
                nodeId = f.nodeId, title = title, classType = f.nodeName,
                text = value?.toString().orEmpty(),
                options = f.options,
                key = f.fieldName,
                isVisible = visible
    )
        }
    }

    /**
     * Legacy (pre-2a) RunningHub AI app import: a synthetic graph without any node links, which can
     * never run through the proxy. Such apps must be re-imported.
     */
    fun isLegacySyntheticGraph(workflow: Map<String, Any>): Boolean {
        if (workflow.isEmpty()) return false
        for ((_, n) in workflow) {
            val node = n as? Map<*, *> ?: return false
            val inputs = node["inputs"] as? Map<*, *> ?: return false
            if (inputs.values.any { it is List<*> }) return false
        }
        return true
    }

    // ---------------- run ----------------

    /** [workflow] = the AI app screen session's graph (edited values); never the Control screen's workflow. */
    fun runRhWebApp(vm: MainViewModel, app: AiApp, batchCount: Int, workflow: Map<String, Any>) {
        val server = vm.currentServerProfile()
        if (server?.isRunningHub != true) {
            vm.showToast("请先切换到云端服务器")
            return
        }
        val webappId = app.rhWebappId?.trim().orEmpty()
        if (webappId.isBlank() || app.rhTemplateJson.isNullOrBlank()) {
            vm.showToast("请重新导入此应用")
            return
        }
        if (vm._rhAppRunning.value) {
            vm.showToast("已有任务在跑，请先停止")
            return
        }
        val backend = vm.currentRunningHubBackend()
        if (backend == null || !backend.hasKey) {
            vm.showToast("未配置 API Key")
            return
        }
        MainViewModelAiApps.siteBlockMessage(app, backend.site.id)?.let {
            vm.showToast(MainViewModelRhWorkflows.SITE_MISMATCH_TOAST)
            return
        }
        if (vm.aiAppSession?.workflowKey != app.workflowKey) {
            vm.showToast("请先打开应用")
            return
        }
        val template = parseTemplate(vm, app.rhTemplateJson)
        if (template.isEmpty()) {
            vm.showToast("请重新导入")
            return
        }
        // Exactly the template's (nodeId, fieldName) pairs, with the edited values.
        val fields = template.map { f ->
            val inputs = (workflow[f.nodeId] as? Map<*, *>)?.get("inputs") as? Map<*, *>
            RhFieldValue(f.nodeId, f.fieldName, fieldValueString(inputs?.get(f.fieldName) ?: f.fieldValue))
        }
        val foreign = MainViewModelRhWorkflows.foreignUploads(vm, fields.map { it.fieldValue }, backend.site.id)
        if (foreign.isNotEmpty()) {
            vm.showToast(MainViewModelRhWorkflows.SITE_MISMATCH_TOAST)
            return
        }
        val n = batchCount.coerceIn(1, 8)
        com.comfyui.mobile.GenerationLiveUpdate.noteUserStartedRun()
        vm._rhAppRunning.value = true
        vm.resetLatestGenerationFor(app.workflowKey)
        val jobKey = "batch_" + UUID.randomUUID().toString()
        val job = vm.viewModelScope.launch {
            try {
                for (i in 1..n) {
                    currentCoroutineContext().ensureActive()
                    val title = if (n > 1) "${app.title} (${i}/$n)" else app.title
                    val submitted = submitAndAwait(vm, backend, app, webappId, fields, title)
                    if (!submitted) break
                }
            } finally {
                vm.rhAppPollJobs.remove(jobKey)
                vm._rhAppRunning.value = false
                vm._rhAppStatus.value = null
            }
        }
        vm.rhAppPollJobs[jobKey] = job
    }

    /** Returns false when the submit itself failed (stop the batch). */
    private suspend fun submitAndAwait(
        vm: MainViewModel,
        backend: RunningHubBackend,
        app: AiApp,
        webappId: String,
        fields: List<RhFieldValue>,
        title: String
    ): Boolean {
        val localId = "rhapp_" + UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        vm.taskStore.upsert(
            TaskRecord(
                localId = localId,
                serverId = backend.serverId,
                backend = BackendKind.RUNNINGHUB.name,
                state = TaskState.SUBMITTING.name,
                title = title,
                workflowKey = app.workflowKey,
                appId = app.id,
                webappId = webappId,
                gpuTier = backend.gpuTierLabel,
                instanceType = backend.defaultInstanceType,
                createdAt = now,
                origin = "ai_app",
                rhSite = backend.site.id,
                rhKeySig = com.comfyui.mobile.data.SecretStore.keySig(vm.currentServerProfile()?.effectiveRhApiKey)
    )
        )
        vm._rhAppStatus.value = "提交中…"
        val res = backend.submit(
            GenerationRequest.RhWebApp(
                webappId = webappId,
                nodeInfoList = fields,
                instanceType = backend.defaultInstanceType,
                title = title,
                workflowKey = app.workflowKey
    )
        )
        val err = res.exceptionOrNull()
        if (err != null) {
            if (err is CancellationException) {
                vm.taskStore.update(localId) { it.copy(state = TaskState.CANCELLED.name, finishedAt = System.currentTimeMillis()) }
                throw err
            }
            val rhErr = err as? RhApiException
            val msg = com.comfyui.mobile.network.SecretRedactor.redact(err.message ?: "提交失败")
            vm.taskStore.update(localId) {
                it.copy(
                    state = TaskState.FAILED.name,
                    error = TaskError(message = msg, code = rhErr?.code, retryable = rhErr?.retryable == true),
                    finishedAt = System.currentTimeMillis()
    )
            }
            vm.addLog("RunningHub AI 应用提交失败: $msg", LogType.ERROR)
            vm.showToast("提交失败: $msg")
            return false
        }
        val snap = res.getOrThrow()
        vm.taskStore.update(localId) { it.copy(remoteId = snap.remoteId, state = snap.state.name) }
        vm.addLog("已提交 RunningHub AI 应用任务「$title」(taskId: ${snap.remoteId})", LogType.SUCCESS)
        snap.message?.let { tips -> vm.addLog("RunningHub 提示: ${tips.take(300)}", LogType.INFO) }
        pollUntilTerminal(vm, backend, localId, snap.remoteId, app.workflowKey)
        return true
    }

    /** In-app polling of /task/openapi/outputs with backoff until a terminal state. */
    suspend fun pollUntilTerminal(
        vm: MainViewModel,
        backend: RunningHubBackend,
        localId: String,
        remoteId: String,
        workflowKey: String?
    ) {
        if (!pollingLocalIds.add(localId)) return
        try {
            val started = vm.taskStore.find(localId)?.createdAt?.takeIf { it > 0 } ?: System.currentTimeMillis()
            var interval = 3_000L
            var failures = 0
            var lastState: TaskState? = null
            while (true) {
                delay(interval)
                currentCoroutineContext().ensureActive()
                if (backend.client.disposed) return // server switched: record stays in-flight, resumed later
                if (!workflowKey.isNullOrBlank() && vm.detachedWorkflowKeys.contains(workflowKey)) return
                val existing = vm.taskStore.find(localId) ?: return // record cleared by the user: stop tracking (not cancelled on RH)
                if (existing.taskState.isTerminal || existing.taskState == TaskState.PAUSED) return
                val elapsed = System.currentTimeMillis() - started
                val r = backend.poll(remoteId)
                val e = r.exceptionOrNull()
                if (e != null) {
                    if (e is CancellationException) throw e
                    val authErr = e as? RhApiException
                    if (authErr != null && (com.comfyui.mobile.network.RhError.isAuthFailure(authErr.code) || authErr.httpCode == 401 || authErr.httpCode == 403)) {
                        // Key rejected: keep the task in-flight (resumed once the key is fixed) and stop calling.
                        MainViewModelRhConn.onRhCallError(vm, backend.serverId, authErr)
                        vm._rhAppStatus.value = "API Key 无效，已暂停跟踪（修复 Key 后自动继续）"
                        return
                    }
                    failures++
                    val rhErr = e as? RhApiException
                    val fatal = rhErr != null && rhErr.code != null && !rhErr.retryable && failures >= 3
                    if (fatal || elapsed > MAX_TASK_MS) {
                        val msg = com.comfyui.mobile.network.SecretRedactor.redact(e.message ?: "查询失败")
                        val st = if (fatal) TaskState.FAILED else TaskState.LOST
                        vm.taskStore.update(localId) {
                            it.copy(state = st.name, error = TaskError(message = msg, code = rhErr?.code), finishedAt = System.currentTimeMillis())
                        }
                        vm.addLog("RunningHub 任务 $remoteId ${st.label}: $msg", LogType.ERROR)
                        vm.showToast("云端任务${st.label}: $msg")
                        return
                    }
                    interval = (3_000L shl failures.coerceAtMost(4)).coerceAtMost(30_000L)
                    vm._rhAppStatus.value = "查询状态失败，${interval / 1000} 秒后重试…"
                    continue
                }
                failures = 0
                val s = r.getOrThrow()
                val recNow = vm.taskStore.find(localId)
                if (recNow == null || recNow.taskState.isTerminal) return
                when (s.state) {
                    TaskState.QUEUED, TaskState.RUNNING, TaskState.SUBMITTING -> {
                        if (s.state != lastState) {
                            vm.taskStore.update(localId) { it.copy(state = s.state.name) }
                            lastState = s.state
                        }
                        vm._rhAppStatus.value = "${s.state.label} · ${elapsed / 1000} 秒"
                        if (elapsed > MAX_TASK_MS) {
                            vm.taskStore.update(localId) {
                                it.copy(state = TaskState.LOST.name, error = TaskError(message = "超过 3 小时未完成"), finishedAt = System.currentTimeMillis())
                            }
                            vm.showToast("云端任务长时间未完成，已标记为状态未知")
                            return
                        }
                        interval = if (elapsed < 60_000L) 3_000L else 5_000L
                    }
                    TaskState.SUCCESS -> {
                        vm._rhAppStatus.value = "下载结果…"
                        val outputs = downloadOutputs(vm, backend, remoteId, s.outputs)
                        val saved = outputs.count { !it.localUri.isNullOrBlank() }
                        vm.taskStore.update(localId) {
                            it.copy(
                                state = TaskState.SUCCESS.name,
                                outputs = outputs,
                                cost = s.cost,
                                error = if (saved < outputs.size) TaskError(message = "${outputs.size - saved} 个结果下载失败（可稍后在网页端获取）") else null,
                                finishedAt = System.currentTimeMillis()
    )
                        }
                        publishOutputs(vm, workflowKey, remoteId, outputs, backend.site.id)
                        val costText = s.cost?.coins?.let { String.format(Locale.US, "，消耗 %.2f RH 币", it) }.orEmpty()
                        val kindText = if (vm.taskStore.find(localId)?.origin == "official_workflow") "云端工作流" else "云端应用"
                        vm.addLog("RunningHub $kindText 完成 (taskId: $remoteId)，已保存 $saved/${outputs.size} 个结果$costText", LogType.SUCCESS)
                        vm.showToast("${kindText}完成：已保存 $saved 个结果$costText")
                        vm.fetchRunningHubAccountStatus()
                        return
                    }
                    TaskState.PAUSED -> return
                    TaskState.FAILED, TaskState.CANCELLED, TaskState.LOST -> {
                        vm.taskStore.update(localId) {
                            it.copy(state = s.state.name, error = s.error, finishedAt = System.currentTimeMillis())
                        }
                        val msg = s.error?.message ?: s.state.label
                        vm.addLog("RunningHub 任务 $remoteId ${s.state.label}: $msg", LogType.ERROR)
                        vm.showToast("云端任务${s.state.label}: $msg")
                        return
                    }
                }
            }
        } finally {
            pollingLocalIds.remove(localId)
        }
    }

    private fun extForFileType(fileType: String?): String {
        val t = fileType?.lowercase()?.trim().orEmpty()
        return when {
            t.isBlank() -> "png"
            t.length in 2..5 && t.all { it.isLetterOrDigit() } -> t
            t.contains("video") -> "mp4"
            t.contains("audio") -> "mp3"
            t.contains("text") -> "txt"
            else -> "png"
        }
    }

    /** Downloads each output fileUrl immediately and stores it in the system album (fallback: app folder). */
    private suspend fun downloadOutputs(
        vm: MainViewModel,
        backend: RunningHubBackend,
        remoteId: String,
        outputs: List<TaskOutput>
    ): List<TaskOutput> = withContext(Dispatchers.IO) {
        val ctx = vm.getApplication<Application>()
        outputs.mapIndexed { idx, out ->
            val url = out.url
            if (url.isNullOrBlank()) return@mapIndexed out
            val fromUrl = Uri.parse(url).lastPathSegment?.substringAfterLast('/')?.takeIf { it.contains('.') }
            val name = MainViewModelGallery.cleanFileName(
                "RH_" + (fromUrl ?: "${remoteId}_${idx + 1}.${extForFileType(out.fileType)}"),
                MainViewModelGallery.outputMediaType(fromUrl ?: "x.${extForFileType(out.fileType)}")
            )
            val mediaType = MainViewModelGallery.outputMediaType(name)
            val tmp = File(ctx.cacheDir, "rh_dl_${System.currentTimeMillis()}_$idx")
            try {
                if (!backend.client.download(url, tmp)) return@mapIndexed out.copy(filename = name)
                val media = MediaOutput(
                    filename = name,
                    subfolder = "",
                    type = "local",
                    mediaType = mediaType,
                    promptId = remoteId,
                    localUri = Uri.fromFile(tmp).toString()
    )
                val saved = MainViewModelGallery.saveMediaToMediaStore(ctx, media, vm.repository)
                val localUri = if (saved != null) {
                    saved.toString()
                } else {
                    val dir = File(MainViewModelGallery.defaultSavedDir(ctx), "RunningHub").apply { mkdirs() }
                    val dest = File(dir, name)
                    tmp.copyTo(dest, overwrite = true)
                    Uri.fromFile(dest).toString()
                }
                out.copy(localUri = localUri, filename = name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                vm.addLog("下载云端结果失败: ${com.comfyui.mobile.network.SecretRedactor.redact(e.message)}", LogType.WARNING)
                out.copy(filename = name)
            } finally {
                runCatching { tmp.delete() }
            }
        }
    }

    private suspend fun publishOutputs(vm: MainViewModel, workflowKey: String?, remoteId: String, outputs: List<TaskOutput>, siteId: String?) {
        val media = outputs.mapNotNull { o ->
            val uri = o.localUri?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = o.filename ?: "RH_$remoteId"
            MediaOutput(
                filename = name,
                subfolder = "",
                type = "local",
                mediaType = MainViewModelGallery.outputMediaType(name),
                promptId = remoteId,
                localUri = uri
    )
        }
        if (media.isEmpty()) return
        if (!workflowKey.isNullOrBlank() && vm.detachedWorkflowKeys.contains(workflowKey)) return
        withContext(Dispatchers.Main) {
            vm.publishRhAppOutputs(workflowKey, media, siteId)
            val now = System.currentTimeMillis()
            vm.mergeCloudMediaIntoSession(media.mapIndexed { i, m -> m.copy(timestamp = now - i) })
            MainViewModelGallery.refreshLocalMedia(vm, vm.getApplication())
        }
    }

    /** A record belongs to [backend]'s site (records without a site = the profile's site, see TaskRecord.rhSite). */
    private fun onSite(rec: TaskRecord, backend: RunningHubBackend): Boolean =
        rec.rhSite.isNullOrBlank() || rec.rhSite == backend.site.id

    /** Resume polling of this profile's in-flight AI-app tasks (after a server switch or app restart). */
    fun resumeInFlight(vm: MainViewModel) {
        val backend = vm.currentRunningHubBackend() ?: return
        vm.viewModelScope.launch {
            try {
                vm.taskStore.ensureLoaded()
                // Downloaded cloud results of this profile -> in-app gallery list (survives restarts).
                showCloudResultsInGallery(vm, backend.serverId)
                // Only tasks submitted to this profile's CURRENT site (a task is never polled on another site).
                val all = vm.taskStore.inFlight(BackendKind.RUNNINGHUB, backend.serverId).filter { onSite(it, backend) }
                // Unfinished records of the removed proxy mode cannot be tracked any more: mark them lost.
                val oldProxy = all.filter { !isTaskApiOrigin(it.origin) }
                for (rec in oldProxy) markOldProxyLost(vm, rec)
                if (oldProxy.isNotEmpty()) vm.addLog("${oldProxy.size} 个旧版云端任务已无法追踪，已标记为状态未知", LogType.WARNING)
                val currentSig = com.comfyui.mobile.data.SecretStore.keySig(vm.currentServerProfile()?.effectiveRhApiKey)
                val pending = all.filter { rec ->
                    isTaskApiOrigin(rec.origin) &&
                        (rec.rhKeySig.isNullOrBlank() || rec.rhKeySig == currentSig)
                }
                val otherKey = all.count { isTaskApiOrigin(it.origin) && !it.rhKeySig.isNullOrBlank() && it.rhKeySig != currentSig }
                if (otherKey > 0) {
                    vm.addLog("$otherKey 个任务由其他 API Key 提交，继续暂停跟踪", LogType.WARNING)
                }
                for (rec in pending) {
                    val remote = rec.remoteId
                    if (remote.isNullOrBlank()) {
                        vm.taskStore.update(rec.localId) {
                            it.copy(state = TaskState.LOST.name, error = TaskError(message = "提交时应用被关闭，任务状态未知"), finishedAt = System.currentTimeMillis())
                        }
                        continue
                    }
                    if (pollingLocalIds.contains(rec.localId)) continue
                    val key = "resume_" + rec.localId
                    if (vm.rhAppPollJobs.containsKey(key)) continue
                    if (rec.taskState == TaskState.PAUSED) {
                        vm.taskStore.update(rec.localId) { it.copy(state = TaskState.RUNNING.name, error = null) }
                    }
                    val job = vm.viewModelScope.launch {
                        try {
                            pollUntilTerminal(vm, backend, rec.localId, remote, rec.workflowKey)
                        } finally {
                            vm.rhAppPollJobs.remove(key)
                        }
                    }
                    vm.rhAppPollJobs[key] = job
                    vm.addLog("继续跟踪云端任务「${rec.title ?: remote}」", LogType.INFO)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                vm.addLog("恢复云端任务失败: ${e.message}", LogType.WARNING)
            }
        }
    }

    /**
     * User deleted the saved cloud workflow [workflowKey]: cancel in-flight RH tasks of that key and
     * mark them cancelled. Completed / failed records stay in the task list.
     */
    fun detachDeletedWorkflow(vm: MainViewModel, workflowKey: String) {
        if (workflowKey.isBlank()) return
        vm.detachedWorkflowKeys.add(workflowKey)
        val matching = vm.taskStore.tasks.value.filter { it.workflowKey == workflowKey && !it.taskState.isTerminal }
        val localIds = matching.map { it.localId }.toHashSet()
        val jobs = vm.rhAppPollJobs.entries.toList()
        for ((k, job) in jobs) {
            val drop = localIds.any { k.endsWith(it) } ||
                (matching.isNotEmpty() && k.startsWith("batch_"))
            if (drop) {
                job.cancel()
                vm.rhAppPollJobs.remove(k)
            }
        }
        if (matching.isNotEmpty()) {
            vm._rhAppRunning.value = false
            vm._rhAppStatus.value = null
        }
        val backend = vm.backendProvider.runningHub(vm.currentServerProfile()?.effectiveId)
        vm.viewModelScope.launch {
            try {
                if (backend != null) {
                    for (rec in matching) {
                        val remote = rec.remoteId
                        if (!remote.isNullOrBlank()) {
                            runCatching { backend.cancel(remote) }
                        }
                    }
                }
                val n = vm.taskStore.cancelInFlightByWorkflowKey(workflowKey)
                if (n > 0) vm.addLog("已取消该工作流 $n 个进行中的任务（已完成的记录保留）", LogType.INFO)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                vm.addLog("取消已删工作流的进行中任务失败: ${e.message}", LogType.WARNING)
            }
        }
    }

    /** Cancels the running AI-app batch and asks RunningHub to cancel in-flight tasks of this profile. */
    fun cancelRunning(vm: MainViewModel) {
        // Drop local tracking immediately so the keep-alive watcher cannot restart the FGS.
        vm.rhAppPollJobs.values.forEach { it.cancel() }
        vm.rhAppPollJobs.clear()
        vm._rhAppRunning.value = false
        vm._rhAppStatus.value = null
        com.comfyui.mobile.GenerationLiveUpdate.cloudStatusText = null
        val backend = vm.backendProvider.runningHub(vm.currentServerProfile()?.effectiveId)
        vm.viewModelScope.launch {
            try {
                val pending = if (backend != null) {
                    vm.taskStore.inFlight(BackendKind.RUNNINGHUB, backend.serverId)
                        .filter { isTaskApiOrigin(it.origin) && onSite(it, backend) }
                } else {
                    emptyList()
                }
                for (rec in pending) {
                    vm.taskStore.update(rec.localId) {
                        it.copy(
                            state = TaskState.CANCELLED.name,
                            finishedAt = System.currentTimeMillis()
    )
                    }
                    val remote = rec.remoteId
                    if (!remote.isNullOrBlank() && backend != null) {
                        val ok = backend.cancel(remote).isSuccess
                        if (!ok) {
                            vm.taskStore.update(rec.localId) {
                                it.copy(error = TaskError(message = "已停止跟踪（RunningHub 取消请求失败，任务可能仍在云端运行）"))
                            }
                        }
                    }
                }
                if (pending.isNotEmpty()) vm.showToast("已取消云端任务")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                vm.addLog("取消云端任务失败: ${e.message}", LogType.WARNING)
            }
        }
    }

    // ---------------- old proxy-mode records, cloud results in the gallery, manual refresh ----------------

    /** Reason shown on unfinished records of the removed proxy mode (no way to query them any more). */
    const val OLD_PROXY_LOST_REASON = "代理模式任务已无法追踪"

    private suspend fun markOldProxyLost(vm: MainViewModel, rec: TaskRecord) {
        vm.taskStore.update(rec.localId) {
            it.copy(
                state = TaskState.LOST.name,
                error = TaskError(message = OLD_PROXY_LOST_REASON),
                finishedAt = it.finishedAt ?: System.currentTimeMillis()
    )
        }
    }

    /** Manual refresh re-downloads missing outputs only for tasks finished within this window. */
    private const val REDOWNLOAD_WINDOW_MS = 7L * 24 * 60 * 60_000L

    private val refreshingCloudTasks = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun isAuthError(e: Throwable?): Boolean {
        val ae = e as? RhApiException ?: return false
        return com.comfyui.mobile.network.RhError.isAuthFailure(ae.code) || ae.httpCode == 401 || ae.httpCode == 403
    }

    /** True if a downloaded copy still exists (MediaStore content Uri or app-folder file). */
    private fun localUriAlive(ctx: android.content.Context, uri: String?): Boolean {
        if (uri.isNullOrBlank()) return false
        return try {
            val u = Uri.parse(uri)
            if (u.scheme == "file") {
                val f = File(u.path ?: return false)
                f.exists() && f.length() > 0L
            } else {
                ctx.contentResolver.openFileDescriptor(u, "r")?.use { true } ?: false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun sameFile(a: TaskOutput, b: TaskOutput): Boolean =
        a.filename == b.filename && (a.subfolder ?: "") == (b.subfolder ?: "") && (a.type ?: "") == (b.type ?: "")

    /**
     * Downloads outputs of OLD proxy-mode records through /view (read-only) into the system album (Pictures/ComfyUI etc., the folder the
     * in-app local gallery scans). Isolation: /view always goes to the ACTIVE server, so nothing is
     * fetched once the user switched away from [serverId].
     */
    private suspend fun downloadProxyMedia(vm: MainViewModel, serverId: String, media: List<MediaOutput>): List<TaskOutput> =
        withContext(Dispatchers.IO) {
            val ctx = vm.getApplication<Application>()
            media.map { m ->
                val active = vm.currentServerProfile()
                val uri = if (active?.isRunningHub == true && active.effectiveId == serverId) {
                    try {
                        MainViewModelGallery.saveMediaToMediaStore(ctx, m, vm.repository)?.toString()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                } else null
                TaskOutput(
                    fileType = m.mediaType.name.lowercase(Locale.US),
                    localUri = uri,
                    filename = m.filename,
                    subfolder = m.subfolder,
                    type = m.type
    )
            }
        }

    /** Merges [outs] into the record (keeps earlier local copies), optionally marking it SUCCESS. */
    private suspend fun applyOutputs(
        vm: MainViewModel,
        localId: String,
        outs: List<TaskOutput>,
        cost: com.comfyui.mobile.backend.TaskCost?,
        markSuccess: Boolean
    ): List<TaskOutput> {
        var merged = outs
        val now = System.currentTimeMillis()
        vm.taskStore.update(localId) { cur ->
            val prev = cur.outputs.orEmpty()
            merged = outs.map { o ->
                if (!o.localUri.isNullOrBlank()) o
                else prev.firstOrNull { p -> sameFile(p, o) && !p.localUri.isNullOrBlank() } ?: o
            }
            val failed = merged.count { it.localUri.isNullOrBlank() }
            cur.copy(
                state = if (markSuccess) TaskState.SUCCESS.name else cur.state,
                outputs = merged,
                cost = cost ?: cur.cost,
                error = when {
                    failed > 0 -> TaskError(message = "$failed 个结果下载失败（可在任务记录页点右上角刷新重试）")
                    markSuccess -> null
                    else -> cur.error
                },
                finishedAt = if (markSuccess) (cur.finishedAt ?: now) else cur.finishedAt
    )
        }
        return merged
    }

    private fun startTracking(vm: MainViewModel, backend: RunningHubBackend, rec: TaskRecord) {
        val remote = rec.remoteId ?: return
        if (!isTaskApiOrigin(rec.origin)) return
        val key = "resume_" + rec.localId
        if (vm.rhAppPollJobs.containsKey(key)) return
        val job = vm.viewModelScope.launch {
            try {
                pollUntilTerminal(vm, backend, rec.localId, remote, rec.workflowKey)
            } finally {
                vm.rhAppPollJobs.remove(key)
            }
        }
        vm.rhAppPollJobs[key] = job
    }

    /** One status check of an AI-app task. Returns true when no further polling is wanted. */
    private suspend fun checkAppOnce(vm: MainViewModel, backend: RunningHubBackend, rec: TaskRecord): Boolean {
        val pid = rec.remoteId ?: return true
        val r = backend.poll(pid)
        val e = r.exceptionOrNull()
        if (e != null) {
            if (e is CancellationException) throw e
            if (isAuthError(e)) MainViewModelRhConn.onRhCallError(vm, backend.serverId, e)
            return true
        }
        val s = r.getOrThrow()
        return when (s.state) {
            TaskState.SUCCESS -> {
                val outs = downloadOutputs(vm, backend, pid, s.outputs)
                val merged = applyOutputs(vm, rec.localId, outs, s.cost, markSuccess = true)
                publishOutputs(vm, rec.workflowKey, pid, merged, backend.site.id)
                vm.addLog("已找回云端任务「${rec.title ?: pid}」的结果：${merged.count { !it.localUri.isNullOrBlank() }}/${merged.size} 个", LogType.SUCCESS)
                true
            }
            TaskState.FAILED, TaskState.CANCELLED -> {
                vm.taskStore.update(rec.localId) { it.copy(state = s.state.name, error = s.error, finishedAt = it.finishedAt ?: System.currentTimeMillis()) }
                true
            }
            TaskState.LOST -> true
            else -> {
                if (rec.state != s.state.name) vm.taskStore.update(rec.localId) { it.copy(state = s.state.name, error = null, finishedAt = null) }
                false
            }
        }
    }

    /** SUCCESS task whose downloaded copies are missing: fetch fresh outputs and download only the missing ones. */
    private suspend fun redownloadMissing(vm: MainViewModel, backend: RunningHubBackend, rec: TaskRecord): Boolean {
        val pid = rec.remoteId ?: return false
        val ctx = vm.getApplication<Application>()
        val prev = rec.outputs.orEmpty()
        val useTaskApi = isTaskApiOrigin(rec.origin) || prev.any { !it.url.isNullOrBlank() }
        val downloaded: List<TaskOutput> = if (useTaskApi) {
            val r = backend.poll(pid)
            val e = r.exceptionOrNull()
            if (e is CancellationException) throw e
            if (isAuthError(e)) {
                MainViewModelRhConn.onRhCallError(vm, backend.serverId, e)
                return false
            }
            val fresh = r.getOrNull()?.takeIf { it.state == TaskState.SUCCESS }?.outputs?.takeIf { it.isNotEmpty() }
                ?: prev.filter { !it.url.isNullOrBlank() }
            val keep = fresh.mapIndexed { i, _ -> prev.getOrNull(i)?.takeIf { localUriAlive(ctx, it.localUri) } }
            val toFetch = fresh.filterIndexed { i, _ -> keep[i] == null }
            if (toFetch.isEmpty()) return false
            val got = downloadOutputs(vm, backend, pid, toFetch)
            var gi = 0
            val merged = fresh.mapIndexed { i, _ -> keep[i] ?: got[gi++] }
            vm.taskStore.update(rec.localId) {
                val failed = merged.count { o -> o.localUri.isNullOrBlank() }
                it.copy(outputs = merged, error = if (failed > 0) TaskError(message = "$failed 个结果下载失败") else null)
            }
            got
        } else {
            // Old proxy-mode record: re-fetch the outputs it already knows through the read-only /view
            // (no proxy history any more; records without stored outputs cannot be recovered).
            if (vm.currentServerProfile()?.effectiveId != backend.serverId) return false
            val missing = prev.filter { p -> !p.filename.isNullOrBlank() && !localUriAlive(ctx, p.localUri) }.map { p ->
                val name = p.filename.orEmpty()
                MediaOutput(
                    filename = name,
                    subfolder = p.subfolder.orEmpty(),
                    type = p.type?.takeIf { it.isNotBlank() } ?: "output",
                    mediaType = MainViewModelGallery.outputMediaType(name),
                    timestamp = 0L,
                    promptId = pid
    )
            }
            if (missing.isEmpty()) return false
            val got = downloadProxyMedia(vm, backend.serverId, missing)
            val keepAlive = prev.filter { localUriAlive(ctx, it.localUri) }
            val merged = (keepAlive + got).distinctBy { o -> "${o.subfolder}|${o.filename}|${o.type}|${o.localUri}" }
            vm.taskStore.update(rec.localId) {
                val failed = got.count { o -> o.localUri.isNullOrBlank() }
                it.copy(outputs = merged, error = if (failed > 0) TaskError(message = "$failed 个结果下载失败") else null)
            }
            got
        }
        val ok = downloaded.filter { !it.localUri.isNullOrBlank() }
        if (ok.isEmpty()) return false
        publishOutputs(vm, rec.workflowKey, pid, ok, backend.site.id)
        return true
    }

    /**
     * 「刷新云端任务」: re-polls non-terminal / LOST tasks of the current RH profile (and resumes tracking of the
     * ones still running) and re-downloads outputs of succeeded tasks that have no local copy.
     */
    fun refreshCloudTasks(vm: MainViewModel) {
        val server = vm.currentServerProfile()
        if (server == null || !server.isRunningHub) {
            vm.showToast("当前不是云端服务器")
            return
        }
        if (MainViewModelRhConn.isUnauthorized(vm, server)) {
            vm.showToast("Key 无效")
            return
        }
        val backend = vm.currentRunningHubBackend()
        if (backend == null) {
            vm.showToast("云端尚未就绪，请稍后再试")
            return
        }
        if (!refreshingCloudTasks.compareAndSet(false, true)) {
            vm.showToast("正在刷新，请稍候…")
            return
        }
        vm._isRefreshingCloudTasks.value = true
        vm.viewModelScope.launch {
            var checked = 0
            var recovered = 0
            var redownloaded = 0
            var running = 0
            var lost = 0
            var aborted = false
            try {
                vm.taskStore.ensureLoaded()
                val ctx = vm.getApplication<Application>()
                val now = System.currentTimeMillis()
                val recs = vm.taskStore.tasks.value.filter {
                    it.backend == BackendKind.RUNNINGHUB.name && it.serverId == backend.serverId && !it.remoteId.isNullOrBlank() &&
                        onSite(it, backend)
                }
                for (rec in recs) {
                    currentCoroutineContext().ensureActive()
                    if (backend.client.disposed || vm.currentServerProfile()?.effectiveId != backend.serverId ||
                        MainViewModelRhConn.isUnauthorized(vm, server)
                    ) {
                        aborted = true
                        break
                    }
                    val pid = rec.remoteId ?: continue
                    val st = rec.taskState
                    if (!isTaskApiOrigin(rec.origin) && (!st.isTerminal || st == TaskState.LOST)) {
                        // Old proxy-mode record: cannot be queried any more.
                        if (!st.isTerminal) {
                            markOldProxyLost(vm, rec)
                            lost++
                        }
                        continue
                    }
                    if (!st.isTerminal || st == TaskState.LOST) {
                        if (pollingLocalIds.contains(rec.localId)) {
                            running++
                            continue
                        }
                        checked++
                        val done = checkAppOnce(vm, backend, rec)
                        val after = vm.taskStore.find(rec.localId)
                        if (after?.taskState == TaskState.SUCCESS) recovered++
                        if (!done && after != null && !after.taskState.isTerminal) {
                            running++
                            startTracking(vm, backend, after)
                        }
                    } else if (st == TaskState.SUCCESS) {
                        val finished = rec.finishedAt ?: rec.createdAt
                        if (now - finished > REDOWNLOAD_WINDOW_MS) continue
                        val outs = rec.outputs.orEmpty()
                        val missing = outs.isNotEmpty() &&
                            withContext(Dispatchers.IO) { outs.any { !localUriAlive(ctx, it.localUri) } }
                        if (missing && redownloadMissing(vm, backend, rec)) redownloaded++
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                vm.addLog("刷新云端任务失败: ${com.comfyui.mobile.network.SecretRedactor.redact(e.message)}", LogType.WARNING)
            } finally {
                refreshingCloudTasks.set(false)
                vm._isRefreshingCloudTasks.value = false
            }
            val summary = buildString {
                append("已检查 $checked 个未完成任务，找回 $recovered 个结果")
                if (redownloaded > 0) append("，重新下载 $redownloaded 个任务的结果")
                if (running > 0) append("，$running 个仍在运行（继续跟踪）")
                if (lost > 0) append("，$lost 个旧版任务已无法追踪")
                if (aborted) append("（已中止：服务器已切换或 Key 无效）")
            }
            vm.addLog("刷新云端任务：$summary", LogType.INFO)
            vm.showToast(summary)
            if (recovered > 0 || redownloaded > 0) vm.fetchRunningHubAccountStatus()
        }
    }

    /** RH mode gallery: show this profile's downloaded cloud results (TaskStore) in the in-app gallery list. */
    fun showCloudResultsInGallery(vm: MainViewModel, serverId: String) {
        vm.viewModelScope.launch {
            try {
                vm.taskStore.ensureLoaded()
                val ctx = vm.getApplication<Application>()
                val media = withContext(Dispatchers.IO) {
                    vm.taskStore.tasks.value
                        .filter { it.backend == BackendKind.RUNNINGHUB.name && it.serverId == serverId && it.taskState == TaskState.SUCCESS }
                        .sortedByDescending { it.finishedAt ?: it.createdAt }
                        .take(100)
                        .flatMap { rec -> rec.outputs.orEmpty().filter { localUriAlive(ctx, it.localUri) }.map { o -> toMedia(rec, o) } }
                }
                withContext(Dispatchers.Main) {
                    if (vm.currentServerProfile()?.effectiveId == serverId) vm.mergeCloudMediaIntoSession(media)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private fun toMedia(rec: TaskRecord, o: TaskOutput): MediaOutput {
        val name = o.filename ?: "RH_${rec.remoteId}"
        return MediaOutput(
            filename = name,
            subfolder = "",
            type = "local",
            mediaType = MainViewModelGallery.outputMediaType(name),
            timestamp = rec.finishedAt ?: rec.createdAt,
            promptId = rec.remoteId,
            localUri = o.localUri
    )
    }

    /** AI app detail: make sure the results of the app's latest completed task (TaskStore) are shown. */
    fun backfillLatestFromStore(vm: MainViewModel, app: AiApp) {
        val key = app.workflowKey
        vm.viewModelScope.launch {
            try {
                vm.taskStore.ensureLoaded()
                val ctx = vm.getApplication<Application>()
                val rec = vm.taskStore.tasks.value
                    .filter {
                        it.backend == BackendKind.RUNNINGHUB.name && it.taskState == TaskState.SUCCESS &&
                            (it.appId == app.id || it.workflowKey == key) &&
                            it.outputs.orEmpty().any { o -> !o.localUri.isNullOrBlank() }
                    }
                    .maxByOrNull { it.finishedAt ?: it.createdAt } ?: return@launch
                val media = withContext(Dispatchers.IO) {
                    rec.outputs.orEmpty().filter { localUriAlive(ctx, it.localUri) }.map { toMedia(rec, it) }
                }
                withContext(Dispatchers.Main) { vm.ensureLatestShown(key, media) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }
}
