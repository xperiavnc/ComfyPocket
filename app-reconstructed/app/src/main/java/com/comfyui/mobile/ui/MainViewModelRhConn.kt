package com.comfyui.mobile.ui

import androidx.lifecycle.viewModelScope
import com.comfyui.mobile.model.LogType
import com.comfyui.mobile.model.RunningHubSite
import com.comfyui.mobile.model.ServerProfile
import com.comfyui.mobile.network.RhApiException
import com.comfyui.mobile.network.RhError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Real connection state of the current RunningHub profile (API key verified by the account check). */
enum class RhConnState { IDLE, VALIDATING, AUTHORIZED, UNAUTHORIZED, NETWORK_ERROR }

/**
 * RunningHub connection gate:
 * - "connected" means the API key was validated with the official account check;
 * - a key rejected by RunningHub (801/802/806/811/332/1002, HTTP 401/403) marks the profile unauthorized:
 *   no further calls are made until the key is changed/re-saved or the user retries;
 * - the dashboard label is derived from this state, not hardcoded.
 */
object MainViewModelRhConn {

    private const val NETWORK_RETRY_MIN_MS = 15_000L

    /** Profile signature: a changed key (or site) is a new signature and is validated again. */
    fun sigOf(p: ServerProfile): String =
        p.effectiveId + "|" + p.effectiveRhSite.id + "|" + p.effectiveRhApiKey.hashCode().toString(16)

    fun isValidated(vm: MainViewModel, p: ServerProfile): Boolean = vm.rhValidatedSig == sigOf(p)

    fun isUnauthorized(vm: MainViewModel, p: ServerProfile): Boolean = vm.rhUnauthorizedSig == sigOf(p)

    /** Called when switching servers: forget the previous profile's validation / queue data. */
    fun reset(vm: MainViewModel) {
        vm.rhValidateJob?.cancel()
        vm.rhValidateJob = null
        vm.rhValidatedSig = null
        vm.rhLastValidateAttemptAt = 0L
        vm._rhConnState.value = RhConnState.IDLE
        vm._rhConnMessage.value = null
        vm._rhQueueStatus.value = null
    }

    /**
     * Validates the current RH profile's key (account check); on success resumes its in-flight tasks.
     * [force] = explicit user action (select / save server, "重试"): also clears an unauthorized mark.
     * No-op for local profiles.
     */
    fun validateAndConnect(vm: MainViewModel, force: Boolean = false) {
        val p = vm.currentServerProfile() ?: return
        if (!p.isRunningHub) return
        val sig = sigOf(p)
        if (p.effectiveRhApiKey.isBlank()) {
            markUnauthorized(vm, p, "未配置 API Key，请在服务器设置中填写")
            return
        }
        if (!force) {
            if (vm.rhUnauthorizedSig == sig) {
                vm._rhConnState.value = RhConnState.UNAUTHORIZED
                return
            }
            if (vm.rhValidateJob?.isActive == true) return
            val since = System.currentTimeMillis() - vm.rhLastValidateAttemptAt
            if (vm._rhConnState.value == RhConnState.NETWORK_ERROR && since < NETWORK_RETRY_MIN_MS) return
        } else {
            vm.rhUnauthorizedSig = null
        }
        vm.rhValidateJob?.cancel()
        vm.rhLastValidateAttemptAt = System.currentTimeMillis()
        vm._rhConnState.value = RhConnState.VALIDATING
        vm._rhConnMessage.value = null
        vm.rhValidateJob = vm.viewModelScope.launch {
            try {
                val backend = vm.currentRunningHubBackend() ?: return@launch
                val res = backend.client.accountStatus()
                // Ignore the answer if the user switched servers / changed the key meanwhile.
                val now = vm.currentServerProfile()
                if (now == null || !now.isRunningHub || sigOf(now) != sig) return@launch
                val err = res.exceptionOrNull()
                if (err == null) {
                    val data = res.getOrNull()
                    val cur = data?.currency?.trim()?.uppercase()
                    val siteMismatch = when (backend.site) {
                        RunningHubSite.DOMESTIC_CN -> cur == "USD"
                        RunningHubSite.OVERSEAS_AI -> cur == "CNY" || cur == "RMB"
                    }
                    if (siteMismatch) {
                        val mismatchMsg = if (backend.site == RunningHubSite.DOMESTIC_CN) "Key属于AI站，站点不匹配" else "Key属于CN站，站点不匹配"
                        markUnauthorized(vm, now, mismatchMsg)
                        return@launch
                    }
                    vm._rhAccountData.value = data
                    vm.rhValidatedSig = sig
                    vm.rhUnauthorizedSig = null
                    vm._rhConnState.value = RhConnState.AUTHORIZED
                    vm._rhConnMessage.value = null
                    // Key valid: load node definitions for editing (read-only object_info).
                    vm.ensureConnected(forceProbe = false)
                    refreshQueueStatus(vm)
                    MainViewModelRhApps.resumeInFlight(vm)
                } else {
                    handleFailure(vm, now, err)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val now = vm.currentServerProfile()
                if (now != null && now.isRunningHub && sigOf(now) == sig) handleFailure(vm, now, e)
            }
        }
    }

    fun retry(vm: MainViewModel) {
        val p = vm.currentServerProfile() ?: return
        if (!p.isRunningHub) {
            vm.ensureConnected(forceProbe = true)
            return
        }
        vm.addLog("重新验证 RunningHub API Key…", LogType.INFO)
        validateAndConnect(vm, force = true)
    }

    private fun handleFailure(vm: MainViewModel, p: ServerProfile, e: Throwable) {
        val rh = e as? RhApiException
        if (rh != null && (RhError.isAuthFailure(rh.code) || rh.httpCode == 401 || rh.httpCode == 403)) {
            markUnauthorized(vm, p, rh.message ?: "API Key 无效")
            return
        }
        val msg = com.comfyui.mobile.network.SecretRedactor.redact(e.message ?: e.javaClass.simpleName)
        vm.rhValidatedSig = null
        vm._rhConnState.value = RhConnState.NETWORK_ERROR
        vm._rhConnMessage.value = msg
        vm.addLog("RunningHub 连接失败（账户验证未通过）: $msg", LogType.WARNING)
    }

    /** Marks the profile unauthorized: no RunningHub calls until the key is changed or the user retries. */
    fun markUnauthorized(vm: MainViewModel, p: ServerProfile, message: String) {
        val sig = sigOf(p)
        val already = vm.rhUnauthorizedSig == sig && vm._rhConnState.value == RhConnState.UNAUTHORIZED
        vm.rhUnauthorizedSig = sig
        vm.rhValidatedSig = null
        vm._rhConnState.value = RhConnState.UNAUTHORIZED
        vm._rhConnMessage.value = message
        vm._rhAccountData.value = null
        vm._rhQueueStatus.value = null
        if (!already) {
            val text = "RunningHub $message（修改 Key 或点击重试后恢复）"
            vm.addLog(text, LogType.ERROR)
            vm.showToast(text)
        }
    }

    /** Any official-API call answered with a key error marks the (still current) profile unauthorized. */
    fun onRhCallError(vm: MainViewModel, serverId: String?, err: Throwable?) {
        val rh = err as? RhApiException ?: return
        if (!(RhError.isAuthFailure(rh.code) || rh.httpCode == 401 || rh.httpCode == 403)) return
        val p = vm.currentServerProfile() ?: return
        if (!p.isRunningHub || (serverId != null && p.effectiveId != serverId)) return
        markUnauthorized(vm, p, rh.message ?: "API Key 无效")
    }

    /** Successful account call outside the validation flow (e.g. balance refresh). */
    fun onAccountOk(vm: MainViewModel, p: ServerProfile) {
        val sig = sigOf(p)
        if (vm.rhValidatedSig == sig) return
        if (vm.rhValidateJob?.isActive == true) return
        vm.rhValidatedSig = sig
        vm.rhUnauthorizedSig = null
        vm._rhConnState.value = RhConnState.AUTHORIZED
        vm._rhConnMessage.value = null
        vm.ensureConnected(forceProbe = false)
    }

    /** GET /openapi/v2/queue/status for the dashboard (apiKeyType, concurrentLimit, running / queued). */
    fun refreshQueueStatus(vm: MainViewModel) {
        val p = vm.currentServerProfile() ?: return
        if (!p.isRunningHub || isUnauthorized(vm, p)) return
        vm.viewModelScope.launch {
            val backend = vm.currentRunningHubBackend() ?: return@launch
            val res = backend.client.queueStatus()
            if (vm.currentServerProfile()?.effectiveId != backend.serverId) return@launch
            res.onSuccess { vm._rhQueueStatus.value = it }
                .onFailure { e -> onRhCallError(vm, backend.serverId, e) }
        }
    }

    /**
     * Plain "已连接" / "未连接": connected = API key verified by the account check. The reason for
     * "未连接" (validating / key rejected / network) is shown separately (dashboard hint).
     */
    fun statusLabel(state: RhConnState): Pair<String, Boolean> = when (state) {
        RhConnState.AUTHORIZED -> "已连接" to true
        else -> "未连接" to false
    }
}