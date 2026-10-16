package com.comfyui.mobile.backend

import com.comfyui.mobile.data.ComfyRepository
import com.comfyui.mobile.model.ServerProfile

/**
 * Holds exactly one active [GenerationBackend], chosen by the current server profile's type.
 * Switching to a different profile (or changing its site / key / type) disposes the previous
 * backend so no connection, in-flight call or credential leaks across modes.
 */
class BackendProvider(private val repository: ComfyRepository) {

    private var active: GenerationBackend? = null
    private var activeSignature: String? = null

    private fun signatureOf(profile: ServerProfile): String {
        val kind = BackendKind.of(profile)
        return if (kind == BackendKind.RUNNINGHUB) {
            "${profile.effectiveId}|$kind|${profile.effectiveRhSite.name}|${profile.effectiveRhGpuTier.name}|${profile.effectiveRhApiKey.hashCode()}"
        } else {
            "${profile.effectiveId}|$kind"
        }
    }

    /** Returns the backend for [profile], creating it (and disposing the old one) when needed. */
    @Synchronized
    fun activate(profile: ServerProfile?): GenerationBackend? {
        if (profile == null) {
            disposeActive()
            return null
        }
        val sig = signatureOf(profile)
        if (sig == activeSignature && active != null) return active
        disposeActive()
        active = when (BackendKind.of(profile)) {
            BackendKind.RUNNINGHUB -> RunningHubBackend(profile)
            BackendKind.LOCAL_COMFY -> LocalComfyBackend(repository, profile.effectiveId)
        }
        activeSignature = sig
        return active
    }

    @Synchronized
    fun current(): GenerationBackend? = active

    /** The active backend only if it is RunningHub AND belongs to [serverId] (when given). */
    @Synchronized
    fun runningHub(serverId: String? = null): RunningHubBackend? {
        val rh = active as? RunningHubBackend ?: return null
        if (serverId != null && rh.serverId != serverId) return null
        return rh
    }

    @Synchronized
    fun disposeActive() {
        runCatching { active?.dispose() }
        active = null
        activeSignature = null
    }
}