package com.comfyui.mobile.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import com.comfyui.mobile.BuildConfig
import com.comfyui.mobile.backend.BackendKind
import com.comfyui.mobile.data.SecretStore
import com.comfyui.mobile.data.TaskStore
import com.comfyui.mobile.model.BackupExportOptions
import com.comfyui.mobile.model.BackupRestoreOptions
import com.comfyui.mobile.model.ConfigBackupDto
import com.comfyui.mobile.model.GalleryCategoryDto
import com.comfyui.mobile.model.LogType
import com.comfyui.mobile.model.ParsedBackup
import com.comfyui.mobile.model.ServerProfile
import com.comfyui.mobile.model.WorkflowBackupItem
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object MainViewModelBackup {

    /** User-facing backup extension (not .zip / .json). */
    const val BACKUP_EXTENSION = ".cui.bak"

    /** MIME for save/share dialogs. */
    const val BACKUP_MIME = "application/x-comfyui-backup"

    /**
     * Container format:
     *   magic ASCII "COMFYUIBAK" (10) + version byte 0x01
     *   + gzip( internal zip: manifest.json + saved_workflows/ + ai_apps/ + tasks/ )
     * Legacy .json / plain .zip are NOT imported.
     */
    private val MAGIC = "COMFYUIBAK".toByteArray(Charsets.US_ASCII)
    private const val CONTAINER_VERSION: Byte = 1

    private const val MANIFEST = "manifest.json"
    private const val WF_ROOT = "saved_workflows"
    private const val AI_ROOT = "ai_apps"
    private const val TASK_PATH = "tasks/task_store.json"
    private const val WORKSPACE_ROOT = "agent_workspace"

    private val AGENT_PREF_PREFIXES = listOf(
        "agent_target_node_",
        "agent_artist_node_",
        "agent_aux_nodes_",
        "agent_wf_params_"
    )

    private val RH_PREF_PREFIXES = listOf(
        "rh_wf_site:",
        "rh_wf_meta:",
        "rh_wf_meta@"
    )

    // ------------------------------------------------------------------ export

    fun exportConfigBackup(
        vm: MainViewModel,
        options: BackupExportOptions = BackupExportOptions()
    ): ConfigBackupDto {
        val secretStore = SecretStore.get(vm.getApplication())

        val exportedServers = vm._servers.value.map { s ->
            if (s.isRunningHub) {
                if (options.includeKeys) {
                    val key = s.effectiveRhApiKey.ifBlank { secretStore.getRhKey(s.effectiveId).orEmpty() }
                    s.copy(rhApiKey = key)
                } else {
                    s.copy(rhApiKey = "")
                }
            } else {
                s
            }
        }

        val exportedLlmProfiles = vm._llmProfiles.value.map { p ->
            if (options.includeKeys) {
                val key = p.apiKey.ifBlank { secretStore.getLlmKey(p.id).orEmpty() }
                p.copy(apiKey = key)
            } else {
                p.copy(apiKey = "")
            }
        }

        val wfInventory = if (options.includeWorkflows) {
            listWorkflowFiles(vm).map { (rel, _) ->
                WorkflowBackupItem(
                    fileName = File(rel).name,
                    content = null,
                    relativePath = rel
    )
            }
        } else {
            null
        }

        return ConfigBackupDto(
            version = 3,
            exportTime = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            servers = exportedServers,
            currentServerId = vm._currentServerId.value,
            savedIp = vm._savedIp.value,
            savedPort = vm._savedPort.value,
            savedScheme = vm._savedScheme.value,
            llmProfiles = exportedLlmProfiles,
            currentLlmProfileId = vm._currentLlmProfileId.value,
            agentSystemPrompt = vm._agentSystemPrompt.value,
            agentDrawPrompt = vm._agentDrawPrompt.value,
            characterLores = vm._characterLores.value,
            favoriteArtists = vm._favoriteArtists.value,
            agentWorkflowSource = vm._agentWorkflowSource.value,
            agentWorkflowId = vm._agentWorkflowId.value,
            agentWorkflowLabel = vm._agentWorkflowLabel.value,
            agentInteractionMode = vm._agentInteractionMode.value,
            agentPrefBag = collectPrefBag(vm, AGENT_PREF_PREFIXES),
            themeMode = vm._themeMode.value,
            themePaletteId = vm._themePaletteId.value,
            isAutoSave = vm._isAutoSave.value,
            galleryCategories = vm._galleryCategories.value.map { GalleryCategoryDto(it.id, it.name) },
            profileNickname = vm._userNickname.value,
            profileBio = vm._userBio.value,
            profileQq = vm._userQq.value,
            includeWorkflows = options.includeWorkflows,
            workflows = wfInventory,
            includeKeys = options.includeKeys,
            includeAiApps = options.includeAiApps,
            includeTaskHistory = options.includeTaskHistory,
            rhWorkflowPrefBag = collectPrefBag(vm, RH_PREF_PREFIXES),
            rhUploadSitesJson = vm.prefs.getString("rh_upload_sites_v1", null),
            workspaceVersion = if (options.includeAgentWorkspace) 1 else null
    )
    }



    suspend fun exportBackupToFile(vm: MainViewModel, options: BackupExportOptions = BackupExportOptions()): File {
        val dir = vm.getApplication<Application>().cacheDir
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val file = File(dir, "ComfyUI_Backup_${sdf.format(Date())}$BACKUP_EXTENSION")
        writeBackupContainer(vm, file, options)
        vm.addLog("已导出备份: ${file.name}", LogType.INFO)
        return file
    }


    suspend fun writeBackupToUri(
        vm: MainViewModel,
        context: Context,
        uri: Uri,
        options: BackupExportOptions = BackupExportOptions()
    ): Result<Unit> = runCatching {
        val tmp = File(vm.getApplication<Application>().cacheDir, "backup_write_${System.currentTimeMillis()}$BACKUP_EXTENSION")
        try {
            writeBackupContainer(vm, tmp, options)
            context.contentResolver.openOutputStream(uri)?.use { os ->
                FileInputStream(tmp).use { it.copyTo(os) }
                os.flush()
            } ?: throw IllegalStateException("无法打开文件写入流")
            vm.addLog("备份已成功保存到文件", LogType.SUCCESS)
        } finally {
            tmp.delete()
        }
    }


    /** Writes a `.cui.bak` file: magic + version + gzip(internal zip). */
    private suspend fun writeBackupContainer(vm: MainViewModel, outFile: File, options: BackupExportOptions) {
        val zipBytes = buildInternalZipBytes(vm, options)
        val gz = ByteArrayOutputStream(zipBytes.size / 2 + 64)
        GZIPOutputStream(gz).use { it.write(zipBytes) }
        FileOutputStream(outFile).use { fos ->
            fos.write(MAGIC)
            fos.write(byteArrayOf(CONTAINER_VERSION))
            fos.write(gz.toByteArray())
        }
    }

    /** Internal zip payload only — never exposed as a user .zip file. */
    private suspend fun buildInternalZipBytes(vm: MainViewModel, options: BackupExportOptions): ByteArray {
        val dto = withContext(Dispatchers.Main) { exportConfigBackup(vm, options) }
        val workspace = if (options.includeAgentWorkspace) File(vm.getApplication<Application>().cacheDir,
            "workspace_export_" + java.util.UUID.randomUUID()).also { vm.agentWorkspace.export(it) } else null
        val baos = ByteArrayOutputStream()
        try { ZipOutputStream(BufferedOutputStream(baos)).use { zos ->
            putTextEntry(zos, MANIFEST, vm.gson.toJson(dto))
            workspace?.walkTopDown()?.filter { it.isFile }?.forEach {
                putFileEntry(zos, "$WORKSPACE_ROOT/" + it.relativeTo(workspace).invariantSeparatorsPath, it)
            }

            if (options.includeWorkflows) {
                for ((rel, file) in listWorkflowFiles(vm)) {
                    putFileEntry(zos, "$WF_ROOT/$rel", file)
                }
            }

            if (options.includeAiApps) {
                for ((rel, file) in listAiAppFiles(vm)) {
                    putFileEntry(zos, "$AI_ROOT/$rel", file)
                }
            }

            if (options.includeTaskHistory) {
                val taskFile = taskStoreFile(vm)
                if (taskFile.isFile && taskFile.length() > 0L) {
                    putFileEntry(zos, TASK_PATH, taskFile)
                }
            }
        }
        } finally { workspace?.deleteRecursively() }
        return baos.toByteArray()
    }

    // ------------------------------------------------------------------ parse

    fun parseBackupJson(vm: MainViewModel, jsonStr: String): Result<ConfigBackupDto> = runCatching {
        val parsed = vm.gson.fromJson(jsonStr, ConfigBackupDto::class.java)
            ?: throw IllegalArgumentException("无法解析备份文件，内容为空")
        if (parsed.version <= 0 &&
            parsed.servers == null &&
            parsed.llmProfiles == null &&
            parsed.workflows == null
        ) {
            throw IllegalArgumentException("非合法的 ComfyUI 备份文件")
        }
        require(parsed.version in 1..3) { "备份版本尚不支持，请更新应用" }
        require(parsed.workspaceVersion == null || parsed.workspaceVersion == 1) { "工作区备份版本尚不支持，请更新应用" }
        parsed
    }

    fun parseBackupFromUri(vm: MainViewModel, context: Context, uri: Uri): Result<ParsedBackup> = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("无法读取备份文件")
        parseBackupBytes(vm, bytes)
    }

    fun parseBackupBytes(vm: MainViewModel, bytes: ByteArray): ParsedBackup {
        if (!hasBakMagic(bytes)) {
            throw IllegalArgumentException("不是 ComfyUI 备份（需要 .cui.bak）")
        }
        val ver = bytes[MAGIC.size]
        if (ver != CONTAINER_VERSION) {
            throw IllegalArgumentException("不支持的备份容器版本: $ver")
        }
        val zipBytes = gunzip(bytes.copyOfRange(MAGIC.size + 1, bytes.size))
        val staging = File(
            vm.getApplication<Application>().cacheDir,
            "backup_import_${System.currentTimeMillis()}"
    ).apply { mkdirs() }
        try {
            unzipTo(zipBytes, staging)
            val manifestFile = File(staging, MANIFEST)
            if (!manifestFile.isFile) {
                val nested = staging.listFiles()?.firstOrNull { it.isDirectory }?.let { File(it, MANIFEST) }
                if (nested != null && nested.isFile) {
                    nested.copyTo(manifestFile, overwrite = true)
                }
            }
            if (!manifestFile.isFile) {
                throw IllegalArgumentException("备份包中缺少 manifest.json")
            }
            val dto = parseBackupJson(vm, manifestFile.readText(Charsets.UTF_8)).getOrThrow()
            val workspace = File(staging, WORKSPACE_ROOT)
            require(dto.workspaceVersion == null || File(workspace, "workspace.json").isFile) { "备份缺少工作区数据" }
            val count = if (File(workspace, "workspace.json").isFile)
                runBlocking { vm.agentWorkspace.validate(workspace) } else null
            return ParsedBackup(dto = dto, stagingDir = staging, hasPackage = true, workspaceConversationCount = count)
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw e
        }
    }

    fun discardParsedBackup(parsed: ParsedBackup?) {
        parsed?.stagingDir?.deleteRecursively()
    }

    // ------------------------------------------------------------------ restore


    suspend fun restoreParsedBackup(
        vm: MainViewModel,
        parsed: ParsedBackup,
        options: BackupRestoreOptions = BackupRestoreOptions()
    ): Result<String> {
        val workspace = parsed.stagingDir?.let { File(it, WORKSPACE_ROOT) }
        var paused = false
        return try {
            if (options.restoreAgentWorkspace) {
                require(workspace != null && File(workspace, "workspace.json").isFile) { "此备份没有 Agent 工作区" }
                vm.agentWorkspace.validate(workspace)
            }
            withContext(Dispatchers.Main) {
                vm.agentRuntime.stop("正在恢复备份", invalidateContext = true)
                vm.agentWorkspace.beginRestore()
                vm.repository.webSocketManager.disconnect()
                vm.rhAppPollJobs.values.toList().forEach { it.cancel() }
                vm.rhAppPollJobs.clear()
            }
            paused = true
            vm.agentWorkspace.flush()
            vm.agentWorkspace.invalidateCallbacks()
            val restoredWorkspace = if (options.restoreAgentWorkspace)
                vm.agentWorkspace.restore(workspace!!, confirmed = true) else null
            val configuration = withContext(Dispatchers.Main) { restoreConfiguration(vm, parsed, options) }
            if (configuration.isFailure && restoredWorkspace?.previousSnapshotDirectory != null) {
                vm.agentWorkspace.restore(restoredWorkspace.previousSnapshotDirectory, confirmed = true)
            }
            configuration.map { summary -> if (restoredWorkspace == null) summary else
                "$summary；Agent 会话 "+restoredWorkspace.conversationCount+" 个，原工作区快照已保留" }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            if (paused) withContext(Dispatchers.Main) {
                vm.agentWorkspace.finishRestore()
            }
            discardParsedBackup(parsed)
        }
    }

    private fun restoreConfiguration(vm: MainViewModel, parsed: ParsedBackup, options: BackupRestoreOptions): Result<String> {
        return try {
            val backup = parsed.dto
            var restoredServersCount = 0
            var restoredLlmCount = 0
            var restoredLoresCount = 0
            var restoredWorkflowsCount = 0
            var restoredKeysCount = 0
            var restoredAiAppsCount = 0
            var restoredTasksCount = 0
            var restoredBindingsCount = 0

            val secretStore = SecretStore.get(vm.getApplication())

            if (!backup.servers.isNullOrEmpty()) {
                val currentById = vm._servers.value.associateBy { it.effectiveId }
                val merged = backup.servers.map { s ->
                    if (s.isRunningHub) {
                        val keyFromBackup = s.effectiveRhApiKey
                        val keyFromLocal = currentById[s.effectiveId]?.effectiveRhApiKey.orEmpty()
                            .ifBlank { secretStore.getRhKey(s.effectiveId).orEmpty() }
                        val finalKey = keyFromBackup.ifBlank { keyFromLocal }
                        if (finalKey.isNotBlank()) restoredKeysCount++
                        s.copy(rhApiKey = finalKey)
                    } else {
                        s
                    }
                }
                vm._servers.value = merged
                vm.persistServers(merged)
                restoredServersCount = merged.size

                val targetServerId = backup.currentServerId.takeIf { !it.isNullOrBlank() }
                    ?: vm._currentServerId.value
                val targetServer = merged.firstOrNull { it.effectiveId == targetServerId }
                    ?: merged.firstOrNull()
                if (targetServer != null) {
                    vm._currentServerId.value = targetServer.effectiveId
                    vm.prefs.edit().putString("current_server_id", targetServer.effectiveId).apply()
                    vm.applyServer(targetServer)
                }
            } else if (!backup.savedIp.isNullOrBlank()) {
                val ip = backup.savedIp
                val port = backup.savedPort ?: "8188"
                val scheme = backup.savedScheme ?: "http"
                val legacyServer = ServerProfile(
                    id = java.util.UUID.randomUUID().toString(),
                    name = ip,
                    scheme = scheme,
                    host = ip,
                    port = port
    )
                val list = listOf(legacyServer)
                vm._servers.value = list
                vm.persistServers(list)
                vm.applyServer(legacyServer)
                restoredServersCount = 1
            }

            if (!backup.llmProfiles.isNullOrEmpty()) {
                val currentById = vm._llmProfiles.value.associateBy { it.id }
                val merged = backup.llmProfiles.map { p ->
                    val keyFromBackup = p.apiKey
                    val keyFromLocal = currentById[p.id]?.apiKey.orEmpty()
                        .ifBlank { secretStore.getLlmKey(p.id).orEmpty() }
                    val finalKey = keyFromBackup.ifBlank { keyFromLocal }
                    if (finalKey.isNotBlank()) restoredKeysCount++
                    p.copy(apiKey = finalKey)
                }
                vm._llmProfiles.value = merged
                vm.persistLlmProfiles(merged)
                restoredLlmCount = merged.size

                val targetLlmId = backup.currentLlmProfileId.takeIf { !it.isNullOrBlank() }
                    ?: vm._currentLlmProfileId.value
                val activeId = merged.firstOrNull { it.id == targetLlmId }?.id
                    ?: merged.firstOrNull()?.id
                if (!activeId.isNullOrBlank()) {
                    vm._currentLlmProfileId.value = activeId
                    val profile = merged.first { it.id == activeId }
                    vm._llmBaseUrl.value = profile.baseUrl
                    vm._llmApiKey.value = profile.apiKey
                    vm._llmModel.value = profile.model
                    vm.prefs.edit().putString("current_llm_profile_id", activeId).apply()
                }
            }

            if (!backup.agentSystemPrompt.isNullOrBlank()) {
                vm.setAgentSystemPrompt(backup.agentSystemPrompt)
            }
            if (!backup.agentDrawPrompt.isNullOrBlank()) {
                vm.setAgentDrawPrompt(backup.agentDrawPrompt)
            }
            if (!backup.characterLores.isNullOrEmpty()) {
                vm._characterLores.value = backup.characterLores
                vm.persistCharacterLores(backup.characterLores)
                restoredLoresCount = backup.characterLores.size
            }
            if (!backup.favoriteArtists.isNullOrEmpty()) {
                vm._favoriteArtists.value = backup.favoriteArtists
                vm.prefs.edit().putString(
                    MainViewModelAgent.scopedPref("agent_favorite_artists", vm),
                    vm.gson.toJson(backup.favoriteArtists)
    ).apply()
            }
            if (!vm.agentWorkspaceData.isInitialized() && !backup.agentWorkflowSource.isNullOrBlank() && !backup.agentWorkflowId.isNullOrBlank()) {
                vm.setAgentDefaultWorkflow(
                    source = backup.agentWorkflowSource,
                    id = backup.agentWorkflowId,
                    label = backup.agentWorkflowLabel ?: backup.agentWorkflowId
    )
            }
            if (!vm.agentWorkspaceData.isInitialized() && !backup.agentInteractionMode.isNullOrBlank()) {
                MainViewModelAgent.setAgentInteractionMode(vm, backup.agentInteractionMode)
            }

            if (options.restoreAgentBindings && !backup.agentPrefBag.isNullOrEmpty()) {
                val editor = vm.prefs.edit()
                backup.agentPrefBag.forEach { (k, v) ->
                    if (AGENT_PREF_PREFIXES.any { k.startsWith(it) }) {
                        editor.putString(k, v)
                        restoredBindingsCount++
                    }
                }
                editor.apply()
            }

            if (!backup.rhWorkflowPrefBag.isNullOrEmpty()) {
                val editor = vm.prefs.edit()
                backup.rhWorkflowPrefBag.forEach { (k, v) ->
                    if (RH_PREF_PREFIXES.any { k.startsWith(it) }) {
                        editor.putString(k, v)
                    }
                }
                editor.apply()
            }
            if (!backup.rhUploadSitesJson.isNullOrBlank()) {
                vm.prefs.edit().putString("rh_upload_sites_v1", backup.rhUploadSitesJson).apply()
            }

            if (!backup.galleryCategories.isNullOrEmpty()) {
                val cats = backup.galleryCategories.map { GalleryCategory(it.id, it.name) }
                vm._galleryCategories.value = cats
                vm.persistGalleryCategories(cats)
            }

            val hasProfile = !backup.profileNickname.isNullOrBlank() ||
                !backup.profileBio.isNullOrBlank() ||
                !backup.profileQq.isNullOrBlank()
            if (hasProfile) {
                MainViewModelProfile.updateUserProfile(
                    vm,
                    nickname = backup.profileNickname.orEmpty(),
                    bio = backup.profileBio.orEmpty(),
                    qq = backup.profileQq.orEmpty()
    )
            }

            backup.themeMode?.let { vm.setThemeMode(it) }
            backup.themePaletteId?.let { vm.setThemePalette(it) }
            backup.isAutoSave?.let { vm.toggleAutoSave(it) }

            if (options.restoreWorkflows) {
                restoredWorkflowsCount += restoreWorkflows(vm, backup, parsed.stagingDir)
            }

            if (options.restoreAiApps && (backup.includeAiApps || parsed.stagingDir != null)) {
                restoredAiAppsCount += restoreAiApps(vm, parsed.stagingDir)
            }

            if (options.restoreTaskHistory && (backup.includeTaskHistory || parsed.stagingDir != null)) {
                restoredTasksCount += restoreTaskHistory(vm, parsed.stagingDir)
            }

            val summary = buildString {
                append("恢复完成：")
                if (restoredServersCount > 0) append("${restoredServersCount}个服务器, ")
                if (restoredLlmCount > 0) append("${restoredLlmCount}套模型, ")
                if (restoredKeysCount > 0) append("${restoredKeysCount}个密钥, ")
                if (restoredLoresCount > 0) append("${restoredLoresCount}个角色, ")
                if (restoredBindingsCount > 0) append("${restoredBindingsCount}项Agent绑定, ")
                if (restoredWorkflowsCount > 0) append("${restoredWorkflowsCount}个工作流, ")
                if (restoredAiAppsCount > 0) append("${restoredAiAppsCount}个AI应用文件, ")
                if (restoredTasksCount > 0) append("${restoredTasksCount}条云端任务/消耗")
            }.trim().trimEnd(',').trimEnd('，')

            vm.addLog("配置导入成功: $summary", LogType.SUCCESS)
            vm.showToast("导入成功")
            Result.success(summary.ifBlank { "配置已恢复" })
        } catch (e: Exception) {
            vm.addLog("配置导入失败: ${e.message}", LogType.ERROR)
            Result.failure(e)
        }
    }

    // ------------------------------------------------------------------ helpers

    fun countLocalAndCloudWorkflows(vm: MainViewModel): Int = listWorkflowFiles(vm).size

    fun countAiAppFiles(vm: MainViewModel): Int = listAiAppFiles(vm).size

    fun hasTaskHistory(vm: MainViewModel): Boolean {
        val f = taskStoreFile(vm)
        return f.isFile && f.length() > 2L
    }

    private fun taskStoreFile(vm: MainViewModel): File =
        File(File(vm.getApplication<Application>().filesDir, "tasks"), "task_store.json")

    private fun listWorkflowFiles(vm: MainViewModel): List<Pair<String, File>> {
        val base = File(vm.getApplication<Application>().filesDir, "saved_workflows")
        if (!base.exists()) return emptyList()
        val out = mutableListOf<Pair<String, File>>()
        for (sub in listOf("local", "cloud")) {
            val dir = File(base, sub)
            dir.listFiles()
                ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
                ?.forEach { out += "$sub/${it.name}" to it }
        }
        // Legacy root json (pre local/cloud split)
        base.listFiles()
            ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            ?.forEach { out += "local/${it.name}" to it }
        return out.distinctBy { it.first }
    }

    private fun listAiAppFiles(vm: MainViewModel): List<Pair<String, File>> {
        val base = File(vm.getApplication<Application>().filesDir, "ai_apps")
        if (!base.exists()) return emptyList()
        val out = mutableListOf<Pair<String, File>>()
        fun walk(dir: File, prefix: String) {
            dir.listFiles()?.forEach { f ->
                val rel = if (prefix.isEmpty()) f.name else "$prefix/${f.name}"
                if (f.isDirectory) walk(f, rel)
                else if (f.isFile) out += rel to f
            }
        }
        walk(base, "")
        return out
    }

    private fun collectPrefBag(vm: MainViewModel, prefixes: List<String>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for ((k, v) in vm.prefs.all) {
            if (v !is String) continue
            if (prefixes.any { k.startsWith(it) }) {
                out[k] = v
            }
        }
        return out
    }

    private fun restoreWorkflows(
        vm: MainViewModel,
        backup: ConfigBackupDto,
        staging: File?
    ): Int {
        var count = 0
        val base = File(vm.getApplication<Application>().filesDir, "saved_workflows").apply { mkdirs() }

        // From zip payload
        val stagedWf = staging?.let { File(it, WF_ROOT) }
        if (stagedWf != null && stagedWf.isDirectory) {
            for (sub in listOf("local", "cloud")) {
                val srcDir = File(stagedWf, sub)
                val dstDir = File(base, sub).apply { mkdirs() }
                srcDir.listFiles()?.filter { it.isFile }?.forEach { src ->
                    src.copyTo(File(dstDir, src.name), overwrite = true)
                    count++
                }
            }
        }

        // From embedded JSON items (v1 / fallback)
        backup.workflows?.forEach { item ->
            val content = item.content ?: return@forEach
            if (content.isBlank()) return@forEach
            val rel = item.relativePath?.takeIf { it.isNotBlank() }
                ?: "local/${item.fileName}"
            val safe = rel.replace('\\', '/').trimStart('/')
            if (safe.contains("..")) return@forEach
            val target = File(base, safe)
            target.parentFile?.mkdirs()
            target.writeText(content, Charsets.UTF_8)
            count++
        }

        vm.refreshSavedWorkflows()
        return count
    }

    private fun restoreAiApps(vm: MainViewModel, staging: File?): Int {
        val staged = staging?.let { File(it, AI_ROOT) } ?: return 0
        if (!staged.isDirectory) return 0
        val dest = File(vm.getApplication<Application>().filesDir, "ai_apps").apply { mkdirs() }
        var count = 0
        fun copyTree(from: File, to: File) {
            if (from.isDirectory) {
                to.mkdirs()
                from.listFiles()?.forEach { child ->
                    copyTree(child, File(to, child.name))
                }
            } else if (from.isFile) {
                to.parentFile?.mkdirs()
                from.copyTo(to, overwrite = true)
                count++
            }
        }
        // Merge: copy files; then refresh indexes from disk for both kinds
        staged.listFiles()?.forEach { child ->
            copyTree(child, File(dest, child.name))
        }
        // Refresh in-memory lists for both backends
        runCatching {
            MainViewModelAiApps.persistAiAppsToDisk(
                vm,
                MainViewModelAiApps.loadAiAppsFromDisk(vm, BackendKind.LOCAL_COMFY),
                BackendKind.LOCAL_COMFY
    )
        }
        runCatching {
            MainViewModelAiApps.persistAiAppsToDisk(
                vm,
                MainViewModelAiApps.loadAiAppsFromDisk(vm, BackendKind.RUNNINGHUB),
                BackendKind.RUNNINGHUB
    )
        }
        MainViewModelAiApps.refreshAiApps(vm)
        return count
    }

    private fun restoreTaskHistory(vm: MainViewModel, staging: File?): Int {
        val staged = staging?.let { File(it, TASK_PATH) } ?: return 0
        if (!staged.isFile) return 0
        val json = staged.readText(Charsets.UTF_8)
        return runBlocking {
            TaskStore.get(vm.getApplication()).mergeFromBackupJson(json)
        }
    }

    private fun putTextEntry(zos: ZipOutputStream, name: String, text: String) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(text.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private fun putFileEntry(zos: ZipOutputStream, name: String, file: File) {
        if (!file.isFile) return
        zos.putNextEntry(ZipEntry(name))
        FileInputStream(file).use { it.copyTo(zos) }
        zos.closeEntry()
    }

    private fun hasBakMagic(bytes: ByteArray): Boolean {
        if (bytes.size < MAGIC.size + 2) return false
        for (i in MAGIC.indices) {
            if (bytes[i] != MAGIC[i]) return false
        }
        return true
    }

    private fun gunzip(bytes: ByteArray): ByteArray {
        ByteArrayInputStream(bytes).use { bis ->
            GZIPInputStream(bis).use { gzis ->
                return gzis.readBytes()
            }
        }
    }

    private fun unzipTo(bytes: ByteArray, dest: File) {
        ZipInputStream(BufferedInputStream(bytes.inputStream())).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name.replace('\\', '/').trimStart('/')
                if (name.isBlank() || name.contains("..")) {
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }
                val out = File(dest, name)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
