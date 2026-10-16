package com.comfyui.mobile.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.comfyui.mobile.model.LogType
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

object MainViewModelWorkflow {

    fun isCloudMode(vm: MainViewModel): Boolean {
        val currentId = vm.currentServerId.value
        val server = vm.servers.value.firstOrNull { it.effectiveId == currentId || it.id == currentId }
        return server?.isRunningHub == true
    }

    fun modeSuffix(vm: MainViewModel): String = if (isCloudMode(vm)) "_cloud" else "_local"

    fun saveLastWorkflowState(vm: MainViewModel, type: String, target: String) {
        val suffix = modeSuffix(vm)
        val editor = vm.prefs.edit()
            .putString("last_workflow_type$suffix", type)
            .putString("last_workflow_target$suffix", target)
        // Also remembered per server profile (restored when switching back to that server).
        val serverId = vm.currentServerId.value
        if (serverId.isNotBlank()) {
            editor.putString("last_workflow_type@srv_$serverId", type)
                .putString("last_workflow_target@srv_$serverId", target)
        }
        editor.apply()
    }

    /**
     * Removes persisted last-workflow entries of type "app" (older versions opened AI apps in the Control
     * screen's state): the AI app screen has its own session and Control never restores an app.
     */
    private fun purgeAppEntries(vm: MainViewModel) {
        val stale = vm.prefs.all.entries
            .filter { (k, v) -> k.startsWith("last_workflow_type") && v == "app" }
            .map { it.key }
        if (stale.isEmpty()) return
        val editor = vm.prefs.edit()
        for (typeKey in stale) {
            editor.remove(typeKey)
            editor.remove("last_workflow_target" + typeKey.removePrefix("last_workflow_type"))
        }
        editor.apply()
    }

    /**
     * Unloads the current workflow / AI app (Control screen shows its empty state). Used on a local<->cloud
     * switch so the other mode's workflow never stays loaded.
     */
    fun clearCurrentWorkflow(vm: MainViewModel) {
        vm.workflowLoadGeneration.incrementAndGet()
        vm.setCurrentWorkflowKey(null)
        vm.currentWorkflow = null
        vm._editableNodes.value = emptyList()
        vm._currentWorkflowName.value = null
        vm.clearLatestGenerationList()
    }

    private fun isInside(file: File, dir: File): Boolean = try {
        file.canonicalPath.startsWith(dir.canonicalPath + File.separator)
    } catch (_: Exception) {
        false
    }

    fun savedWorkflowsDir(vm: MainViewModel): File {
        val base = File(vm.getApplication<Application>().filesDir, "saved_workflows")
        if (!base.exists()) base.mkdirs()

        // 迁移旧版根目录文件到 local
        val localDir = File(base, "local")
        if (!localDir.exists()) {
            localDir.mkdirs()
            base.listFiles()?.forEach { f ->
                if (f.isFile && f.extension.equals("json", ignoreCase = true)) {
                    try {
                        f.copyTo(File(localDir, f.name), overwrite = true)
                        f.delete()
                    } catch (_: Exception) {}
                }
            }
        }

        val sub = if (isCloudMode(vm)) "cloud" else "local"
        val target = File(base, sub)
        if (!target.exists()) target.mkdirs()
        return target
    }

    fun overwriteCurrentWorkflowToLocal(vm: MainViewModel) {
        val workflow = vm.currentWorkflow
        if (workflow == null) {
            vm.showToast("当前没有可保存的工作流")
            return
        }

        vm.viewModelScope.launch(Dispatchers.IO) {
            try {
                val currentName = vm._currentWorkflowName.value?.takeIf { it.isNotBlank() }
                val targetFilename = if (currentName != null) {
                    if (currentName.lowercase().endsWith(".json")) currentName else "$currentName.json"
                } else {
                    "workflow.json"
                }

                val json = vm.gson.toJson(workflow)
                val dir = savedWorkflowsDir(vm)
                val targetFile = File(dir, targetFilename)
                targetFile.writeText(json)
                MainViewModelRhWorkflows.markSavedFile(vm, targetFile)
                vm.showToast("已覆盖")
                vm.addLog("已覆盖: $targetFilename", LogType.SUCCESS)
                refreshSavedWorkflows(vm)
                saveLastWorkflowState(vm, "file", targetFile.absolutePath)
            } catch (e: Exception) {
                vm.showToast("覆盖保存失败")
                vm.addLog("覆盖保存失败: ${e.message}", LogType.ERROR)
            }
        }
    }

    fun saveCurrentWorkflowToLocal(vm: MainViewModel) {
        val workflow = vm.currentWorkflow
        if (workflow == null) {
            vm.showToast("当前没有工作流")
            return
        }

        vm.viewModelScope.launch(Dispatchers.IO) {
            try {
                val filename = "workflow_${System.currentTimeMillis()}.json"
                val json = vm.gson.toJson(workflow)
                val dir = savedWorkflowsDir(vm)
                File(dir, filename).writeText(json)
                MainViewModelRhWorkflows.markSavedFile(vm, File(dir, filename))
                vm.showToast("保存成功: $filename")
                vm.addLog("保存工作流到本地: $filename", LogType.SUCCESS)
                refreshSavedWorkflows(vm)
            } catch (e: Exception) {
                vm.showToast("保存失败")
                vm.addLog("保存失败: ${e.message}", LogType.ERROR)
            }
        }
    }

    fun refreshSavedWorkflows(vm: MainViewModel) {
        vm._savedWorkflowFiles.value = getSiteSavedWorkflows(vm)
    }

    /** Saved workflows of the current mode; in cloud mode only the current site's and unmarked ones. */
    fun getSiteSavedWorkflows(vm: MainViewModel): List<File> {
        val all = getLocalSavedWorkflows(vm)
        if (!isCloudMode(vm)) return all
        return all.filter { MainViewModelRhWorkflows.isVisibleOnCurrentSite(vm, it.name) }
    }

    fun sanitizeWorkflowFileName(raw: String): String {
        val trimmed = raw.trim().ifBlank { "workflow_${System.currentTimeMillis()}" }
        val cleaned = trimmed.replace(Regex("""[\\/:*?"<>|]"""), "_")
        val withExt = if (cleaned.lowercase().endsWith(".json")) cleaned else "$cleaned.json"
        return withExt.ifBlank { "workflow_${System.currentTimeMillis()}.json" }
    }

    fun saveWorkflowJsonToLocal(vm: MainViewModel, preferredName: String, json: String): File? {
        return try {
            val dir = savedWorkflowsDir(vm)
            val baseName = sanitizeWorkflowFileName(preferredName)
            var target = File(dir, baseName)
            if (target.exists()) {
                val dot = baseName.lastIndexOf('.')
                val stem = if (dot > 0) baseName.substring(0, dot) else baseName
                val ext = if (dot > 0) baseName.substring(dot) else ".json"
                var i = 2
                while (target.exists()) {
                    target = File(dir, "$stem ($i)$ext")
                    i += 1
                }
            }

            target.writeText(json)
            MainViewModelRhWorkflows.markSavedFile(vm, target)
            refreshSavedWorkflows(vm)
            target
        } catch (_: Exception) {
            null
        }
    }

    /** Saves a workflow synced from RunningHub into the cloud folder, marked with [siteId] (the source site). */
    fun saveCloudWorkflowJson(
        vm: MainViewModel,
        preferredName: String,
        json: String,
        siteId: String? = MainViewModelRhWorkflows.currentSiteId(vm)
    ): File? {
        return try {
            val base = File(vm.getApplication<Application>().filesDir, "saved_workflows")
            val cloudDir = File(base, "cloud").apply { if (!exists()) mkdirs() }
            val baseName = sanitizeWorkflowFileName(preferredName)
            var target = File(cloudDir, baseName)
            if (target.exists()) {
                val dot = baseName.lastIndexOf('.')
                val stem = if (dot > 0) baseName.substring(0, dot) else baseName
                val ext = if (dot > 0) baseName.substring(dot) else ".json"
                var i = 2
                while (target.exists()) {
                    target = File(cloudDir, "$stem ($i)$ext")
                    i += 1
                }
            }
            target.writeText(json)
            MainViewModelRhWorkflows.markSavedFile(vm, target, siteId)
            refreshSavedWorkflows(vm)
            target
        } catch (_: Exception) {
            null
        }
    }

    fun deleteLocalWorkflow(vm: MainViewModel, file: File): Boolean {
        val dir = File(File(vm.getApplication<Application>().filesDir, "saved_workflows"), "local")
        if (!isInside(file, dir)) return false
        val key = file.name
        if (key == vm.currentWorkflowKey) clearCurrentWorkflow(vm)
        if (!file.delete() && file.exists()) return false
        val path = file.absolutePath
        val cloudTwin = File(File(File(vm.getApplication<Application>().filesDir, "saved_workflows"), "cloud"), key).exists()
        val editor = vm.prefs.edit()
        for ((k, v) in vm.prefs.all) {
            when {
                k.startsWith("workflow@local:$key:") -> editor.remove(k)
                !cloudTwin && k.startsWith("workflow:$key:") -> editor.remove(k)
                k.startsWith("last_workflow_target") && v == path -> {
                    editor.remove(k)
                    editor.remove("last_workflow_type" + k.removePrefix("last_workflow_target"))
                }
            }
        }
        editor.apply()
        vm.forgetWorkflowCaches(key)
        refreshSavedWorkflows(vm)
        vm.addLog("已删除本地工作流「${key.removeSuffix(".json")}」", LogType.INFO)
        return true
    }

    fun getLocalSavedWorkflows(vm: MainViewModel): List<File> {
        val dir = savedWorkflowsDir(vm)
        return dir.listFiles()
            ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun loadLocalWorkflow(vm: MainViewModel, file: File, silent: Boolean = false) {
        // Site isolation: a cloud workflow of the other site is never opened.
        if (isCloudMode(vm) && MainViewModelRhWorkflows.isCloudWorkflowKey(vm, file.name) &&
            !MainViewModelRhWorkflows.isVisibleOnCurrentSite(vm, file.name)
        ) {
            val s = MainViewModelRhWorkflows.siteOf(vm, file.name)
            if (!silent) vm.showToast(MainViewModelRhWorkflows.SITE_MISMATCH_TOAST)
            return
        }
        val gen = vm.workflowLoadGeneration.incrementAndGet()
        val kindAtStart = vm.currentKindNs()
        vm.viewModelScope.launch(Dispatchers.IO) {
            try {
                val content = file.readText()
                val type = object : TypeToken<Map<String, Any>>() {}.type
                val raw: Map<String, Any> = vm.gson.fromJson(content, type)
                // A newer load or a local<->cloud switch happened meanwhile: drop this (stale) result.
                if (vm.workflowLoadGeneration.get() != gen || vm.currentKindNs() != kindAtStart) return@launch
                vm.setCurrentWorkflowKey(file.name)
                vm.currentWorkflow = vm.applyWorkflowParamOverrides(vm.sanitizeNumbers(raw) as Map<String, Any>)
                vm.resetRunStateForWorkflowLoad()
                vm.parseEditableNodes(vm.currentWorkflow)
                vm._currentWorkflowName.value = file.name
                saveLastWorkflowState(vm, "file", file.absolutePath)
                if (!silent) {
                    val modeLabel = if (isCloudMode(vm)) "云端" else "本地"
                    vm.showToast("${modeLabel}工作流加载成功")
                    vm.addLog("加载${modeLabel}存档: ${file.name}", LogType.SUCCESS)
                }
            } catch (e: Exception) {
                if (!silent) vm.showToast("加载失败")
            }
        }
    }

    /**
     * Restores the remembered workflow / AI app of the current server (then of the current mode).
     * [serverOnly] = only a server-specific memory counts (same-mode server switch keeps the current one otherwise).
     * [clearIfNone] = show the empty state when nothing valid is remembered for this mode.
     * Only files inside the CURRENT mode's workflow folder are restored (never the other mode's).
     */
    fun restoreLastWorkflow(vm: MainViewModel, serverOnly: Boolean = false, clearIfNone: Boolean = false) {
        purgeAppEntries(vm)
        val suffix = modeSuffix(vm)
        val serverId = vm.currentServerId.value
        var lastType = serverId.takeIf { it.isNotBlank() }?.let { vm.prefs.getString("last_workflow_type@srv_$it", null) }
        var lastTarget = serverId.takeIf { it.isNotBlank() }?.let { vm.prefs.getString("last_workflow_target@srv_$it", null) }
        if (lastType.isNullOrBlank() && !serverOnly) {
            lastType = vm.prefs.getString("last_workflow_type$suffix", null)
            lastTarget = vm.prefs.getString("last_workflow_target$suffix", null)
            if (lastType.isNullOrBlank() && suffix == "_local") {
                lastType = vm.prefs.getString("last_workflow_type", null)
                lastTarget = vm.prefs.getString("last_workflow_target", null)
            }
        }

        var restored = false
        if (lastType == "file" && !lastTarget.isNullOrBlank()) {
            val dir = savedWorkflowsDir(vm)
            val file = File(lastTarget)
            val effectiveFile = when {
                file.exists() && isInside(file, dir) -> file
                File(dir, file.name).exists() -> File(dir, file.name)
                else -> null
            }?.takeIf { !isCloudMode(vm) || MainViewModelRhWorkflows.isVisibleOnCurrentSite(vm, it.name) }
            if (effectiveFile != null) {
                loadLocalWorkflow(vm, effectiveFile, silent = true)
                restored = true
            }
        }
        // AI apps are never restored into the Control screen (they live in the AI app screen's own session).
        if (!restored && clearIfNone) clearCurrentWorkflow(vm)
    }
}
