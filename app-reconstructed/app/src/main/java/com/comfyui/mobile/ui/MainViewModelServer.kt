package com.comfyui.mobile.ui

import androidx.lifecycle.viewModelScope
import com.comfyui.mobile.GenerationLiveUpdate
import com.comfyui.mobile.KeepAliveService
import com.comfyui.mobile.data.SecretStore
import com.comfyui.mobile.model.LogType
import com.comfyui.mobile.model.RunningHubGpuTier
import com.comfyui.mobile.model.RunningHubSite
import com.comfyui.mobile.model.ServerProfile
import com.comfyui.mobile.model.ServerType
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.launch
import java.util.UUID

object MainViewModelServer {

    fun loadServersMigrating(vm: MainViewModel): List<ServerProfile> {
        val raw = vm.prefs.getString("servers_json", null)
        if (!raw.isNullOrBlank()) {
            return try {
                val type = object : TypeToken<List<ServerProfile>>() {}.type
                val parsed = (vm.gson.fromJson<List<ServerProfile>>(raw, type) ?: emptyList())
                    .filter { it.isRunningHub || it.effectiveHost.isNotBlank() }
                hydrateAndMigrateKeys(vm, parsed)
            } catch (_: Exception) {
                emptyList()
            }
        }
        val host = vm.prefs.getString("ip", "").orEmpty()
        if (host.isBlank()) return emptyList()
        val scheme = vm.prefs.getString("scheme", "http") ?: "http"
        val port = vm.prefs.getString("port", "8188") ?: "8188"
        val id = UUID.randomUUID().toString()
        val migrated = listOf(
            ServerProfile(
                id = id,
                name = host,
                scheme = scheme,
                host = host,
                port = port
    )
        )
        vm.prefs.edit()
            .putString("servers_json", vm.gson.toJson(migrated))
            .putString("current_server_id", id)
            .apply()
        return migrated
    }

    /**
     * In-memory list keeps plaintext keys for API calls. Prefs JSON is stored with rhApiKey blank;
     * ciphertext lives in [SecretStore] (no-backup dir + AndroidKeystore).
     */
    fun persistServers(vm: MainViewModel, list: List<ServerProfile>) {
        val store = SecretStore.get(vm.getApplication())
        for (s in list) {
            if (s.isRunningHub && s.effectiveId.isNotBlank()) store.putRhKey(s.effectiveId, s.effectiveRhApiKey)
        }
        val persisted = list.map { if (it.isRunningHub) it.copy(rhApiKey = "") else it }
        vm.prefs.edit().putString("servers_json", vm.gson.toJson(persisted)).apply()
        vm._servers.value = list
    }

    private fun hydrateAndMigrateKeys(vm: MainViewModel, parsed: List<ServerProfile>): List<ServerProfile> {
        val store = SecretStore.get(vm.getApplication())
        var migrated = false
        val hydrated = parsed.map { s ->
            if (!s.isRunningHub || s.effectiveId.isBlank()) return@map s
            val fromStore = store.getRhKey(s.effectiveId)
            when {
                !fromStore.isNullOrBlank() -> s.copy(rhApiKey = fromStore)
                s.effectiveRhApiKey.isNotBlank() -> {
                    store.putRhKey(s.effectiveId, s.effectiveRhApiKey)
                    migrated = true
                    s
                }
                else -> s
            }
        }
        if (migrated) {
            val stripped = hydrated.map { if (it.isRunningHub) it.copy(rhApiKey = "") else it }
            vm.prefs.edit().putString("servers_json", vm.gson.toJson(stripped)).apply()
        }
        return hydrated
    }

    fun normalizeEndpoint(rawIp: String, rawPort: String, preferredScheme: String? = null): Triple<String, String, String>? {
        val trimmedIp = rawIp.trim()
        val scheme = when {
            trimmedIp.startsWith("https://", ignoreCase = true) -> "https"
            trimmedIp.startsWith("http://", ignoreCase = true) -> "http"
            !preferredScheme.isNullOrBlank() -> preferredScheme.lowercase().trim()
            else -> "http"
        }
        var host = when {
            trimmedIp.startsWith("https://", ignoreCase = true) -> trimmedIp.substring(8)
            trimmedIp.startsWith("http://", ignoreCase = true) -> trimmedIp.substring(7)
            else -> trimmedIp
        }.trim().trimEnd('/')

        var p = rawPort.trim()

        if (host.startsWith("[") && host.contains("]")) {
            val closingIndex = host.indexOf(']')
            val ipv6 = host.substring(0, closingIndex + 1)
            val remainder = host.substring(closingIndex + 1)
            val afterSlash = if (remainder.contains("/")) "/" + remainder.substringAfter("/") else ""
            val portCandidate = remainder.substringBefore("/").removePrefix(":")
            if (p.isBlank() && portCandidate.isNotBlank()) p = portCandidate
            host = ipv6 + afterSlash
        } else if (host.contains(":") && !host.contains("::")) {
            val beforeSlash = host.substringBefore("/")
            val afterSlash = if (host.contains("/")) "/" + host.substringAfter("/") else ""
            val hostPort = beforeSlash.split(":", limit = 2)
            if (hostPort.size == 2) {
                val hostPart = hostPort[0]
                val portPart = hostPort[1]
                host = hostPart + afterSlash
                if (p.isBlank()) p = portPart
            }
        }

        if (p.isBlank()) p = if (scheme == "https") "443" else "8188"
        val portNum = p.toIntOrNull()?.coerceIn(1, 65535)?.toString() ?: (if (scheme == "https") "443" else "8188")
        if (host.isBlank()) return null
        return Triple(scheme, host, portNum)
    }

    /**
     * [previous] = the active profile BEFORE this call (needed when the active profile was edited in place:
     * the list already holds the new values). Default: looked up from the current list.
     */
    fun applyServer(
        vm: MainViewModel,
        server: ServerProfile,
        startService: Boolean = true,
        previous: ServerProfile? = vm._servers.value.firstOrNull { it.effectiveId == vm._currentServerId.value }
    ) {
        val oldServer = previous?.takeIf { it.effectiveId == vm._currentServerId.value }
        val serverChanged = oldServer == null || oldServer.effectiveId != server.effectiveId
        // RunningHub site isolation: another site, or an in-place edit of the active profile's site / API key,
        // is a full switch (like local <-> cloud): stale object_info, account / balance, the loaded workflow and
        // the AI app session are all dropped.
        val siteOrKeyChanged = oldServer != null && oldServer.isRunningHub && server.isRunningHub &&
            (oldServer.effectiveRhSite != server.effectiveRhSite ||
                (!serverChanged && oldServer.effectiveRhApiKey != server.effectiveRhApiKey))
        val modeChanged = oldServer == null || oldServer.isRunningHub != server.isRunningHub || siteOrKeyChanged

        if (siteOrKeyChanged || (oldServer?.isRunningHub == true && (serverChanged || modeChanged))) {
            GenerationLiveUpdate.dismissQuietly(vm.getApplication())
        }

        // Drop the previous server's account / in-flight UI state before switching (no cross-server leak).
        if (serverChanged || modeChanged) vm.onServerSwitching(modeChanged)
        if (siteOrKeyChanged) {
            val siteChanged = oldServer?.effectiveRhSite != server.effectiveRhSite
            vm.addLog(
                if (siteChanged) "已切换站点，已清空上一站点的工作流、应用会话与账户信息"
                else "API Key 已更换，已清空之前的工作流、应用会话与账户信息",
                LogType.INFO
    )
            val sid = oldServer?.effectiveId ?: server.effectiveId
            if (!siteChanged && oldServer != null) {
                val oldSig = SecretStore.keySig(oldServer.effectiveRhApiKey)
                val newSig = SecretStore.keySig(server.effectiveRhApiKey)
                vm.viewModelScope.launch {
                    if (!oldSig.isNullOrBlank()) {
                        runCatching { vm.taskStore.stampKeySigIfMissing(sid, oldServer.effectiveRhSite.id, oldSig) }
                    }
                    val n = vm.taskStore.pauseTracking(
                        serverId = sid,
                        siteId = oldServer.effectiveRhSite.id,
                        reason = "API Key 已更换，暂停跟踪（换回原 Key 后可继续；新 Key 无法查询原账户任务）",
                        onlyIfKeySigNot = newSig
    )
                    if (n > 0) vm.addLog("$n 个云端任务已暂停跟踪（API Key 已更换）", LogType.WARNING)
                }
            }
        }

        val editor = vm.prefs.edit().putString("current_server_id", server.effectiveId)
        if (!server.isRunningHub) {
            // Legacy scheme/ip/port prefs only ever describe a LOCAL server (never the RH site address).
            editor.putString("scheme", server.effectiveScheme)
                .putString("ip", server.effectiveHost)
                .putString("port", server.effectivePort)
            vm._savedScheme.value = server.effectiveScheme
            vm._savedIp.value = server.effectiveHost
            vm._savedPort.value = server.effectivePort
        }
        editor.apply()
        vm._currentServerId.value = server.effectiveId
        if (!server.isRunningHub) {
            vm.repository.webSocketManager.mediaSourceKey =
                com.comfyui.mobile.agent.workspace.AgentExecutionTarget("LOCAL_COMFY", server.effectiveId).key
        }
        // Exactly one active backend; switching disposes the previous one's connections / in-flight calls.
        vm.backendProvider.activate(server)
        MainViewModelRhConn.reset(vm)
        if (server.isRunningHub) {
            // Cloud: no ComfyUI WebSocket and no ComfyUI run/queue/history REST at all; runs go through the
            // official task API. The base URL is kept only for read-only object_info / view.
            // Selecting / saving a server is an explicit action: it also clears an "unauthorized" mark.
            vm.repository.setCloudActive(true)
            if (com.comfyui.mobile.network.SecretRedactor.isValidBaseUrl(server.baseUrl)) {
                vm.repository.setBaseUrlOnly(server.baseUrl)
            }
            MainViewModelRhConn.validateAndConnect(vm, force = true)
        } else {
            vm.repository.setCloudActive(false)
            vm.repository.updateConnection(server.baseUrl, force = true)
        }

        if (modeChanged) {
            // Never keep the other mode's workflow / AI app loaded: unload, then restore this mode's
            // (server's) remembered one, or show the empty state.
            MainViewModelWorkflow.clearCurrentWorkflow(vm)
            MainViewModelWorkflow.refreshSavedWorkflows(vm)
            MainViewModelAiApps.refreshAiApps(vm)
            MainViewModelWorkflow.restoreLastWorkflow(vm, clearIfNone = true)
            MainViewModelAgent.reloadAgentWorkflowForMode(vm)
        } else if (serverChanged) {
            // Same mode, other server: restore that server's remembered workflow if there is one.
            MainViewModelWorkflow.restoreLastWorkflow(vm, serverOnly = true)
        }
    }

    fun selectServer(vm: MainViewModel, id: String) {
        val server = vm._servers.value.firstOrNull { it.effectiveId == id || it.id == id } ?: return
        applyServer(vm, server)
        vm.addLog("已切换服务器：${server.displayName}", LogType.INFO)
    }

    fun upsertServer(
        vm: MainViewModel,
        id: String?,
        name: String,
        ip: String,
        port: String,
        scheme: String = "http",
        selectAfter: Boolean = true
    ): Boolean {
        val existingAny = if (!id.isNullOrBlank()) vm._servers.value.firstOrNull { it.effectiveId == id || it.id == id } else null
        // Local/cloud isolation: never turn an existing RunningHub profile into a local one.
        // Save the local settings as a new profile instead and keep the RH profile untouched.
        val convertingRh = existingAny?.isRunningHub == true
        @Suppress("NAME_SHADOWING")
        val id = if (convertingRh) null else id
        val existingServer = if (convertingRh) null else existingAny
        if (convertingRh) {
            vm.showToast("云端配置不会被改为本地，已另存为新的本地服务器")
        }
        val effectiveScheme = scheme.ifBlank { existingServer?.effectiveScheme ?: "http" }
        val normalized = normalizeEndpoint(ip, port, effectiveScheme)
        if (normalized == null) {
            vm.showToast("请输入正确的 IP 或服务器地址")
            return false
        }
        val (finalScheme, host, p) = normalized
        val customName = name.trim().takeIf { it.isNotBlank() && it != host }.orEmpty()
        val list = vm._servers.value.toMutableList()
        val server = if (id.isNullOrBlank()) {
            ServerProfile(
                id = UUID.randomUUID().toString(),
                name = customName,
                scheme = finalScheme,
                host = host,
                port = p,
                serverType = ServerType.LOCAL_COMFYUI
    ).also { list.add(it) }
        } else {
            val idx = list.indexOfFirst { it.effectiveId == id || it.id == id }
            if (idx < 0) return false
            ServerProfile(
                id = id,
                name = customName,
                scheme = finalScheme,
                host = host,
                port = p,
                serverType = ServerType.LOCAL_COMFYUI
    ).also { list[idx] = it }
        }
        persistServers(vm, list)
        if (selectAfter || vm._currentServerId.value == server.effectiveId || vm._currentServerId.value.isBlank()) {
            applyServer(vm, server)
        }
        return true
    }

    fun upsertRunningHubServer(
        vm: MainViewModel,
        id: String?,
        name: String,
        site: RunningHubSite,
        apiKey: String,
        gpuTier: RunningHubGpuTier,
        selectAfter: Boolean = true
    ): Boolean {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            vm.showToast("请输入 RunningHub API Key")
            return false
        }
        val customName = name.trim().takeIf { it.isNotBlank() && !ServerProfile.isDefaultRhName(it) }.orEmpty()
        // Local/cloud isolation: never turn an existing local profile into a RunningHub one.
        val existingAny = if (!id.isNullOrBlank()) vm._servers.value.firstOrNull { it.effectiveId == id || it.id == id } else null
        val convertingLocal = existingAny != null && !existingAny.isRunningHub
        @Suppress("NAME_SHADOWING")
        val id = if (convertingLocal) null else id
        if (convertingLocal) {
            vm.showToast("本地配置不会被改为云端，已另存为新的 RunningHub 配置")
        }
        val list = vm._servers.value.toMutableList()
        // Active profile before the edit (the list is replaced below) and the edited profile's old site.
        val previousActive = vm._servers.value.firstOrNull { it.effectiveId == vm._currentServerId.value }
        val oldOfEdited = if (!id.isNullOrBlank()) list.firstOrNull { it.effectiveId == id || it.id == id } else null
        val server = if (id.isNullOrBlank()) {
            ServerProfile(
                id = UUID.randomUUID().toString(),
                name = customName,
                scheme = "https",
                host = site.hostUrl,
                port = "443",
                serverType = ServerType.RUNNINGHUB,
                rhSite = site,
                rhApiKey = cleanKey,
                rhGpuTier = gpuTier
    ).also { list.add(it) }
        } else {
            val idx = list.indexOfFirst { it.effectiveId == id || it.id == id }
            if (idx < 0) return false
            ServerProfile(
                id = id,
                name = customName,
                scheme = "https",
                host = site.hostUrl,
                port = "443",
                serverType = ServerType.RUNNINGHUB,
                rhSite = site,
                rhApiKey = cleanKey,
                rhGpuTier = gpuTier
    ).also { list[idx] = it }
        }
        // Site edited in place: records of this profile without a site keep the OLD site, so its in-flight
        // tasks are never polled on the new site (they resume only on their own site).
        val activate = selectAfter || vm._currentServerId.value == server.effectiveId || vm._currentServerId.value.isBlank()
        val siteChanged = oldOfEdited != null && oldOfEdited.isRunningHub && oldOfEdited.effectiveRhSite != site
        if (siteChanged) {
            // Stamp first, THEN save / activate, so resuming never sees an unstamped old-site record.
            val oldSite = oldOfEdited!!.effectiveRhSite
            val serverId = oldOfEdited.effectiveId
            vm.viewModelScope.launch {
                runCatching { vm.taskStore.stampSiteIfMissing(serverId, oldSite.id) }
                val oldSig = SecretStore.keySig(oldOfEdited.effectiveRhApiKey)
                if (!oldSig.isNullOrBlank()) {
                    runCatching { vm.taskStore.stampKeySigIfMissing(serverId, oldSite.id, oldSig) }
                }
                val paused = vm.taskStore.pauseTracking(
                    serverId = serverId,
                    siteId = oldSite.id,
                    reason = "已切换站点，暂停跟踪（改回后自动继续）"
    )
                persistServers(vm, list)
                if (activate) applyServer(vm, server, previous = previousActive)
                if (paused > 0) {
                    vm.addLog("$paused 个云端任务已暂停跟踪（切换站点）", LogType.WARNING)
                }
            }
            return true
        }
        persistServers(vm, list)
        if (activate) applyServer(vm, server, previous = previousActive)
        return true
    }

    fun deleteServer(vm: MainViewModel, id: String) {
        SecretStore.get(vm.getApplication()).removeRhKey(id)
        val list = vm._servers.value.filterNot { it.effectiveId == id || it.id == id }
        persistServers(vm, list)
        if (vm._currentServerId.value == id) {
            val next = list.firstOrNull()
            if (next != null) {
                applyServer(vm, next)
            } else {
                vm.prefs.edit()
                    .remove("current_server_id")
                    .putString("ip", "")
                    .apply()
                vm.onServerSwitching(modeChanged = true)
                vm._currentServerId.value = ""
                vm._savedIp.value = ""
                vm.backendProvider.disposeActive()
                vm.repository.disconnect()
                KeepAliveService.stop(vm.getApplication())
                vm.resetRunState()
            }
        }
    }

    fun connect(vm: MainViewModel, ip: String, port: String, name: String = "", scheme: String = "http") {
        val currentId = vm._currentServerId.value.ifBlank { null }
        val current = currentId?.let { cid -> vm._servers.value.firstOrNull { it.effectiveId == cid } }
        upsertServer(
            vm = vm,
            // Never overwrite (and retype) the current profile when it is a RunningHub one.
            id = if (current?.isRunningHub == true) null else currentId,
            name = name,
            ip = ip,
            port = port,
            scheme = scheme,
            selectAfter = true
    )
    }
}
