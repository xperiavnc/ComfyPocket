package com.comfyui.mobile.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.comfyui.mobile.backend.BackendKind
import com.comfyui.mobile.model.AiApp
import com.comfyui.mobile.model.AiAppSource
import com.comfyui.mobile.model.LogType
import com.comfyui.mobile.network.RhWebAppDemoData
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

object MainViewModelAiApps {

    fun isCloudMode(vm: MainViewModel): Boolean = MainViewModelWorkflow.isCloudMode(vm)

    /**
     * Storage root for AI apps of one backend kind: ai_apps/local or ai_apps/cloud.
     * [kind] null = the current mode. Imports pass their SOURCE kind explicitly.
     */
    fun aiAppsDir(vm: MainViewModel, kind: BackendKind? = null): File {
        val base = File(vm.getApplication<Application>().filesDir, "ai_apps")
        if (!base.exists()) base.mkdirs()

        // 迁移旧版根目录数据到 local
        val localDir = File(base, "local")
        val legacyIndex = File(base, "apps.json")
        if (!localDir.exists() && legacyIndex.exists()) {
            localDir.mkdirs()
            try {
                val localWf = File(localDir, "workflows").apply { mkdirs() }
                val localCov = File(localDir, "covers").apply { mkdirs() }
                File(base, "workflows").listFiles()?.forEach { f ->
                    if (f.isFile) f.copyTo(File(localWf, f.name), overwrite = true)
                }
                File(base, "covers").listFiles()?.forEach { f ->
                    if (f.isFile) f.copyTo(File(localCov, f.name), overwrite = true)
                }
                legacyIndex.copyTo(File(localDir, "apps.json"), overwrite = true)
            } catch (_: Exception) {}
        }

        val effective = kind ?: if (isCloudMode(vm)) BackendKind.RUNNINGHUB else BackendKind.LOCAL_COMFY
        val sub = if (effective == BackendKind.RUNNINGHUB) "cloud" else "local"
        val target = File(base, sub)
        if (!target.exists()) target.mkdirs()
        return target
    }

    fun aiAppsWorkflowDir(vm: MainViewModel, kind: BackendKind? = null): File = File(aiAppsDir(vm, kind), "workflows").apply { if (!exists()) mkdirs() }

    fun aiAppsCoverDir(vm: MainViewModel, kind: BackendKind? = null): File = File(aiAppsDir(vm, kind), "covers").apply { if (!exists()) mkdirs() }

    fun aiAppsIndexFile(vm: MainViewModel, kind: BackendKind? = null): File = File(aiAppsDir(vm, kind), "apps.json")

    fun loadAiAppsFromDisk(vm: MainViewModel, kind: BackendKind? = null): List<AiApp> {
        val file = aiAppsIndexFile(vm, kind)
        if (!file.exists()) return emptyList()
        return try {
            val raw = file.readText()
            val type = object : TypeToken<List<AiApp>>() {}.type
            vm.gson.fromJson<List<AiApp>>(raw, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun persistAiAppsToDisk(vm: MainViewModel, list: List<AiApp>, kind: BackendKind? = null) {
        val dir = aiAppsDir(vm, kind)
        if (!dir.exists()) dir.mkdirs()
        aiAppsIndexFile(vm, kind).writeText(vm.gson.toJson(list))
    }

    fun refreshAiApps(vm: MainViewModel) {
        vm._aiApps.value = loadAiAppsFromDisk(vm)
    }

    fun getAiAppCoverFile(vm: MainViewModel, app: AiApp): File? {
        val name = app.coverFileName?.trim().orEmpty()
        if (name.isBlank()) return null
        val file = File(aiAppsCoverDir(vm), name)
        return if (file.exists()) file else null
    }

    fun saveCurrentWorkflowAsAiApp(vm: MainViewModel, title: String, coverUri: Uri? = null) {
        val workflow = vm.currentWorkflow
        if (workflow == null) {
            vm.showToast("当前没有工作流")
            return
        }

        val visibilitySnapshot = vm.currentWorkflowParamVisibility.toMap()
        val siteAtSave = MainViewModelRhWorkflows.currentSiteId(vm)
        val trimmedTitle = title.trim().ifBlank { vm._currentWorkflowName.value ?: "AI应用" }
        vm.viewModelScope.launch(Dispatchers.IO) {
            try {
                val id = UUID.randomUUID().toString()
                val workflowKey = "aiapp_$id"
                val filename = "aiapp_$id.json"

                val wfDir = aiAppsWorkflowDir(vm)
                if (!wfDir.exists()) wfDir.mkdirs()
                val wfFile = File(wfDir, filename)
                wfFile.writeText(vm.gson.toJson(workflow))

                if (visibilitySnapshot.isNotEmpty()) {
                    vm.persistWorkflowMap(workflowKey, "visibility", visibilitySnapshot)
                }

                val now = System.currentTimeMillis()
                val coverFileName = if (coverUri != null) {
                    try {
                        val context = vm.getApplication<Application>()
                        val display = vm.getFileName(coverUri)?.trim().orEmpty()
                        val ext = display.substringAfterLast('.', "").takeIf { it.isNotBlank() } ?: "jpg"
                        val coverDir = aiAppsCoverDir(vm)
                        if (!coverDir.exists()) coverDir.mkdirs()
                        val outName = "cover_$id.$ext"
                        val target = File(coverDir, outName)
                        context.contentResolver.openInputStream(coverUri)?.use { input ->
                            target.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (target.exists()) outName else null
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }
                val app = AiApp(
                    id = id,
                    title = trimmedTitle,
                    workflowKey = workflowKey,
                    workflowFileName = filename,
                    coverFileName = coverFileName,
                    createdAt = now,
                    updatedAt = now,
                    // A saved workflow belongs to the mode it was edited in (cloud = RH proxy workflow app).
                    source = if (isCloudMode(vm)) AiAppSource.RUNNINGHUB else AiAppSource.LOCAL,
                    // ...and, in cloud mode, to the site of the current profile.
                    rhSite = if (isCloudMode(vm)) siteAtSave else null
    )

                val list = loadAiAppsFromDisk(vm).filterNot { it.id == id }
                val next = listOf(app) + list
                persistAiAppsToDisk(vm, next)
                vm._aiApps.value = next

                vm.showToast("已保存为应用")
                vm.addLog("保存为AI应用: $trimmedTitle", LogType.SUCCESS)
            } catch (e: Exception) {
                vm.showToast("保存失败")
                vm.addLog("保存AI应用失败: ${e.message}", LogType.ERROR)
            }
        }
    }

    fun importCloudWebApp(
        vm: MainViewModel,
        demoData: RhWebAppDemoData,
        rawId: String,
        rhSiteId: String? = null
    ) {
        val title = demoData.webappName?.trim().takeIf { !it.isNullOrBlank() } ?: "云端应用_$rawId"
        val nodes = demoData.nodeInfoList.orEmpty()

        // Keep the FULL template (fieldType / fieldData / description ...) for the official API run,
        // plus a synthetic graph holding the editable values (so overrides / visibility / order keep working).
        val templateJson = vm.gson.toJson(nodes)
        val template = nodes.mapNotNull { MainViewModelRhApps.fieldFromMap(vm, it) }
        val syntheticWorkflow = linkedMapOf<String, Any>()
        val visibilityMap = mutableMapOf<String, Boolean>()
        val labelsMap = mutableMapOf<String, String>()

        for (f in template) {
            @Suppress("UNCHECKED_CAST")
            val nodeMap = syntheticWorkflow.getOrPut(f.nodeId) {
                linkedMapOf(
                    "class_type" to f.nodeName,
                    "inputs" to linkedMapOf<String, Any>(),
                    "_meta" to linkedMapOf("title" to (f.description ?: f.nodeName))
                )
            } as MutableMap<String, Any>
            @Suppress("UNCHECKED_CAST")
            val inputs = nodeMap["inputs"] as MutableMap<String, Any>
            inputs[f.fieldName] = MainViewModelRhApps.initialGraphValue(f)
            visibilityMap["${f.nodeId}:${f.fieldName}"] = true
            if (!f.description.isNullOrBlank()) {
                labelsMap["${f.nodeId}:${f.fieldName}"] = f.description
            }
        }

        // Cover is fetched through the isolated RunningHub client (strict TLS, no auth header to the CDN).
        val rhClient = vm.currentRunningHubBackend()?.client

        vm.viewModelScope.launch(Dispatchers.IO) {
            try {
                val kind = BackendKind.RUNNINGHUB // saved by SOURCE, regardless of the current mode
                val id = UUID.randomUUID().toString()
                val workflowKey = "aiapp_$id"
                val filename = "aiapp_$id.json"

                val wfDir = aiAppsWorkflowDir(vm, kind)
                val wfFile = File(wfDir, filename)
                wfFile.writeText(vm.gson.toJson(syntheticWorkflow))

                // Per-app prefs live in the namespace of the site the app was imported from.
                rhSiteId?.let { vm.workflowKeyNamespaces[workflowKey] = vm.cloudNs(it) }
                vm.persistWorkflowMap(workflowKey, "visibility", visibilityMap)
                if (labelsMap.isNotEmpty()) {
                    vm.persistWorkflowMap(workflowKey, "labels", labelsMap)
                }

                var coverFileName: String? = null
                val coverUrl = demoData.covers?.firstOrNull()?.let { it.thumbnailUri ?: it.url }
                if (!coverUrl.isNullOrBlank() && rhClient != null) {
                    try {
                        val tmp = File(aiAppsCoverDir(vm, kind), "cover_$id.tmp")
                        if (rhClient.download(coverUrl, tmp)) {
                            // Keep the real type (GIF / video covers must not be stored as .jpg).
                            val ext = coverExtension(tmp, coverUrl)
                            val cFile = File(aiAppsCoverDir(vm, kind), "cover_$id.$ext")
                            if (tmp.renameTo(cFile)) coverFileName = cFile.name else runCatching { tmp.delete() }
                        }
                    } catch (_: Exception) {}
                }

                val now = System.currentTimeMillis()
                val app = AiApp(
                    id = id,
                    title = title,
                    workflowKey = workflowKey,
                    workflowFileName = filename,
                    coverFileName = coverFileName,
                    createdAt = now,
                    updatedAt = now,
                    source = AiAppSource.RUNNINGHUB,
                    rhWebappId = rawId.trim(),
                    rhSite = rhSiteId,
                    rhTemplateJson = templateJson
    )

                val list = loadAiAppsFromDisk(vm, kind).filterNot { it.id == id }
                val next = listOf(app) + list
                persistAiAppsToDisk(vm, next, kind)
                if (isCloudMode(vm)) vm._aiApps.value = next

                withContext(Dispatchers.Main) {
                    vm.showToast("已导入「$title」")
                    vm.addLog("同步云端应用成功: $title (ID: $rawId, ${template.size} 个参数)", LogType.SUCCESS)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    vm.showToast("导入失败: ${e.message}")
                    vm.addLog("同步云端应用异常: ${e.message}", LogType.ERROR)
                }
            }
        }
    }

    fun importCloudWorkflowAsApp(
        vm: MainViewModel,
        workflowJson: String,
        preferredTitle: String,
        workflowId: String
    ) {
        val siteId = MainViewModelRhWorkflows.currentSiteId(vm)
        vm.viewModelScope.launch(Dispatchers.IO) {
            try {
                val type = object : TypeToken<Map<String, Any>>() {}.type
                val raw: Map<String, Any> = vm.gson.fromJson(workflowJson, type)
                val sanitized = vm.sanitizeNumbers(raw) as Map<String, Any>

                val id = UUID.randomUUID().toString()
                val workflowKey = "aiapp_$id"
                val filename = "aiapp_$id.json"
                val title = preferredTitle.trim().ifBlank { "云端工作流_$workflowId" }
                val kind = BackendKind.RUNNINGHUB // saved by SOURCE, regardless of the current mode

                val wfDir = aiAppsWorkflowDir(vm, kind)
                val wfFile = File(wfDir, filename)
                wfFile.writeText(vm.gson.toJson(sanitized))

                val now = System.currentTimeMillis()
                val app = AiApp(
                    id = id,
                    title = title,
                    workflowKey = workflowKey,
                    workflowFileName = filename,
                    coverFileName = null,
                    createdAt = now,
                    updatedAt = now,
                    source = AiAppSource.RUNNINGHUB,
                    rhSite = siteId
    )

                val list = loadAiAppsFromDisk(vm, kind).filterNot { it.id == id }
                val next = listOf(app) + list
                persistAiAppsToDisk(vm, next, kind)
                if (isCloudMode(vm)) vm._aiApps.value = next

                withContext(Dispatchers.Main) {
                    vm.showToast("已导入「$title」")
                    vm.addLog("导入云端工作流应用成功: $title", LogType.SUCCESS)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    vm.showToast("导入失败: ${e.message}")
                }
            }
        }
    }

    fun updateAiAppTitle(vm: MainViewModel, appId: String, title: String) {
        val trimmed = title.trim().ifBlank { "AI应用" }
        vm.viewModelScope.launch(Dispatchers.IO) {
            try {
                val now = System.currentTimeMillis()
                val list = loadAiAppsFromDisk(vm)
                val next = list.map { app ->
                    if (app.id == appId) app.copy(title = trimmed, updatedAt = now) else app
                }
                persistAiAppsToDisk(vm, next)
                vm._aiApps.value = next
                if (vm.currentWorkflowKey == "aiapp_$appId") {
                    // (Legacy: Control no longer opens AI apps; kept harmless for an old in-memory state.)
                    vm._currentWorkflowName.value = trimmed
                }
            } catch (_: Exception) {
            }
        }
    }

    fun updateAiAppCover(vm: MainViewModel, appId: String, uri: Uri) {
        vm.viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = vm.getApplication<Application>()
                val display = vm.getFileName(uri)?.trim().orEmpty()
                val ext = display.substringAfterLast('.', "").takeIf { it.isNotBlank() } ?: "jpg"
                val coverDir = aiAppsCoverDir(vm)
                if (!coverDir.exists()) coverDir.mkdirs()

                val now = System.currentTimeMillis()
                val coverFileName = "cover_${appId}_$now.$ext"
                val target = File(coverDir, coverFileName)

                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: run {
                    vm.showToast("无法读取图片")
                    return@launch
                }

                val list = loadAiAppsFromDisk(vm)
                val oldCoverName = list.firstOrNull { it.id == appId }?.coverFileName?.trim().orEmpty().ifBlank { null }
                val next = list.map { app ->
                    if (app.id == appId) app.copy(coverFileName = coverFileName, updatedAt = now) else app
                }
                persistAiAppsToDisk(vm, next)
                vm._aiApps.value = next
                if (oldCoverName != null && oldCoverName != coverFileName) {
                    runCatching { File(coverDir, oldCoverName).delete() }
                }
            } catch (e: Exception) {
                vm.showToast("设置封面失败")
                vm.addLog("设置AI应用封面失败: ${e.message}", LogType.ERROR)
            }
        }
    }

    fun deleteAiApp(vm: MainViewModel, appId: String) {
        vm.viewModelScope.launch(Dispatchers.IO) {
            try {
                val list = loadAiAppsFromDisk(vm)
                val target = list.firstOrNull { it.id == appId }
                val next = list.filterNot { it.id == appId }
                persistAiAppsToDisk(vm, next)
                vm._aiApps.value = next

                target?.let { app ->
                    runCatching { File(aiAppsWorkflowDir(vm), app.workflowFileName).delete() }
                    app.coverFileName?.let { runCatching { File(aiAppsCoverDir(vm), it).delete() } }
                }
            } catch (_: Exception) {
            }
        }
    }

    // ---------------- site (中国站 / 国际站) isolation of cloud apps ----------------

    /** Cloud-app list filter: apps of [siteId] plus unmarked (older) ones. Local mode ([siteId] null): all. */
    fun isVisibleOnSite(app: AiApp, siteId: String?): Boolean =
        siteId == null || app.rhSite.isNullOrBlank() || app.rhSite == siteId

    /** Refusal text when a cloud [app] must not run on [siteId]; null = OK. */
    fun siteBlockMessage(app: AiApp, siteId: String): String? {
        val s = app.rhSite?.takeIf { it.isNotBlank() } ?: return null
        return if (s != siteId) MainViewModelRhWorkflows.SITE_MISMATCH_TOAST else null
    }

    fun reorderAiApps(vm: MainViewModel, orderedIds: List<String>) {
        val ids = orderedIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        vm.viewModelScope.launch(Dispatchers.IO) {
            try {
                val list = loadAiAppsFromDisk(vm)
                if (list.isEmpty()) return@launch
                val map = list.associateBy { it.id }
                val out = ArrayList<AiApp>(list.size)
                for (id in ids) {
                    val app = map[id] ?: continue
                    out.add(app)
                }
                for (app in list) {
                    if (out.none { it.id == app.id }) out.add(app)
                }
                persistAiAppsToDisk(vm, out)
                vm._aiApps.value = out
            } catch (_: Exception) {
            }
        }
    }

    /** True if [appId] exists in the CURRENT mode's AI-app storage. */
    fun aiAppExists(vm: MainViewModel, appId: String): Boolean =
        loadAiAppsFromDisk(vm).any { it.id == appId }

    /**
     * Opens [appId] in the AI app screen's OWN session (MainViewModelAiAppSession). Never touches the Control
     * screen's current workflow / key / name / params / results or the persisted last-workflow memory.
     */
    /** Cover file extension from the downloaded bytes (fallback: URL extension, then jpg). */
    private fun coverExtension(file: File, url: String): String {
        val head = ByteArray(12)
        val n = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(0)
        fun at(i: Int, c: Char) = head[i] == c.code.toByte()
        if (n >= 12) {
            if (at(0, 'G') && at(1, 'I') && at(2, 'F')) return "gif"
            if (head[0] == 0x89.toByte() && at(1, 'P') && at(2, 'N') && at(3, 'G')) return "png"
            if (at(0, 'R') && at(1, 'I') && at(2, 'F') && at(3, 'F') && at(8, 'W') && at(9, 'E') && at(10, 'B') && at(11, 'P')) return "webp"
            if (at(4, 'f') && at(5, 't') && at(6, 'y') && at(7, 'p')) return "mp4"
            if (head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() && head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte()) return "webm"
            if (head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte()) return "jpg"
        }
        val fromUrl = url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return fromUrl.takeIf { it.length in 2..5 && it.all { c -> c.isLetterOrDigit() } } ?: "jpg"
    }

    fun loadAiApp(vm: MainViewModel, appId: String, silent: Boolean = false) {
        val gen = vm.aiAppSessionGeneration.incrementAndGet()
        val kindAtStart = vm.currentKindNs()
        MainViewModelAiAppSession.clearForOtherApp(vm, appId)
        vm.viewModelScope.launch(Dispatchers.IO) {
            try {
                // Current mode's storage only (the in-memory list may still hold the other mode's apps
                // right after a local<->cloud switch).
                val app = loadAiAppsFromDisk(vm).firstOrNull { it.id == appId }
                if (app == null) {
                    if (!silent) vm.showToast("未找到应用")
                    return@launch
                }
                val file = File(aiAppsWorkflowDir(vm), app.workflowFileName)
                if (!file.exists()) {
                    if (!silent) vm.showToast("工作流文件不存在")
                    return@launch
                }
                val content = file.readText()
                val type = object : TypeToken<Map<String, Any>>() {}.type
                val raw: Map<String, Any> = vm.gson.fromJson(content, type)
                val workflow = vm.sanitizeNumbers(raw) as Map<String, Any>

                // RH AI app (official API): editable nodes come from the stored template.
                val rhTemplate = if (app.isRhWebApp) MainViewModelRhApps.parseTemplate(vm, app.rhTemplateJson) else null
                // Pre-2a RH AI app import (synthetic graph, no webappId): cannot run -> ask to re-import.
                val legacyRhImport = !app.isRhWebApp &&
                    (app.source == AiAppSource.RUNNINGHUB || app.source == null) &&
                    MainViewModelRhApps.isLegacySyntheticGraph(workflow)

                withContext(Dispatchers.Main) {
                    if (vm.aiAppSessionGeneration.get() != gen || vm.currentKindNs() != kindAtStart) return@withContext
                    MainViewModelAiAppSession.install(vm, app, workflow, rhTemplate, legacyRhImport)
                    if (legacyRhImport) {
                        vm.addLog(
                            if (isCloudMode(vm)) "AI 应用「${app.title}」为旧版导入，缺少 webappId，请重新导入此应用"
                            else "AI 应用「${app.title}」疑似在本地模式下导入的 RunningHub 云端应用；请切换到 RunningHub 服务器后重新导入",
                            LogType.WARNING
                        )
                    }
                    if (!silent) vm.showToast("已打开")
                    // RH app: if nothing is shown yet, show the latest completed task's saved outputs.
                    if (app.isRhWebApp) MainViewModelRhApps.backfillLatestFromStore(vm, app)
                }
            } catch (e: Exception) {
                vm.showToast("打开失败")
                vm.addLog("打开AI应用失败: ${e.message}", LogType.ERROR)
            }
        }
    }
}
