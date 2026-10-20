package com.comfyui.mobile.agent

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AgentPhase {
    PREPARING, GENERATING, APPLYING, SUBMITTING, WAITING_USER, COMPLETED, STOPPED, FAILED
}

/** Contains identities and lifecycle only, never keys, prompts or server credentials. */
data class AgentTurn(
    val id: String,
    val assistantId: String,
    val context: String,
    val phase: AgentPhase = AgentPhase.PREPARING,
    val failure: String? = null
)

data class AgentSessionState(val turn: AgentTurn? = null, val busy: Boolean = false)

/**
 * One controller per ViewModel. Reservation is synchronous, before launching work.
 * Every callback/finalizer must match the reserved identity; an old job cannot end a new turn.
 */
class AgentSessionController(private val cancelRequest: (String) -> Unit = {}) {
    private val lock = Any()
    private val mutableState = MutableStateFlow(AgentSessionState())
    private val mutableBusy = MutableStateFlow(false)
    val state = mutableState.asStateFlow()
    val busy = mutableBusy.asStateFlow()
    private var job: Job? = null
    private var contextVersion = 0L

    fun contextVersion(): Long = synchronized(lock) { contextVersion }

    fun begin(context: String, assistantId: String = "a-${UUID.randomUUID()}"): AgentTurn? =
        synchronized(lock) {
            if (mutableState.value.busy) return@synchronized null
            val turn = AgentTurn(UUID.randomUUID().toString(), assistantId, context)
            job = null
            mutableState.value = AgentSessionState(turn, true)
            mutableBusy.value = true
            turn
        }

    fun isCurrent(turn: AgentTurn): Boolean = synchronized(lock) {
        mutableState.value.busy && mutableState.value.turn?.id == turn.id
    }

    fun requireCurrent(turn: AgentTurn) {
        if (!isCurrent(turn)) throw CancellationException("Agent turn superseded")
    }

    fun attach(turn: AgentTurn, nextJob: Job) = synchronized(lock) {
        if (isCurrent(turn)) job = nextJob else nextJob.cancel()
    }

    fun advance(turn: AgentTurn, phase: AgentPhase) = synchronized(lock) {
        if (isCurrent(turn)) {
            mutableState.value = mutableState.value.copy(turn = turn.copy(phase = phase))
        }
    }

    fun complete(turn: AgentTurn) = finish(turn, AgentPhase.COMPLETED)
    fun waitForUser(turn: AgentTurn) = finish(turn, AgentPhase.WAITING_USER)
    fun fail(turn: AgentTurn, reason: String) = finish(turn, AgentPhase.FAILED, reason)

    /** Reconstructed from persisted messages when a workspace is opened, never from an old job. */
    fun restoreWaiting(context: String, assistantId: String?) = synchronized(lock) {
        if (!mutableState.value.busy) {
            if (assistantId != null) {
                mutableState.value = AgentSessionState(
                    AgentTurn(UUID.randomUUID().toString(), assistantId, context, AgentPhase.WAITING_USER))
            } else if (mutableState.value.turn?.phase == AgentPhase.WAITING_USER) {
                mutableState.value = AgentSessionState()
            }
            mutableBusy.value = false
        }
    }

    private fun finish(turn: AgentTurn, phase: AgentPhase, reason: String? = null) =
        synchronized(lock) {
            if (isCurrent(turn)) {
                mutableState.value = AgentSessionState(turn.copy(phase = phase, failure = reason), false)
                mutableBusy.value = false
                job = null
            }
        }

    /** Marks the identity stale before touching the socket/job, closing the late-callback window. */
    fun stop(reason: String = "已停止", invalidateContext: Boolean = false): AgentTurn? {
        val stopped = synchronized(lock) {
            if (invalidateContext) contextVersion++
            val current = mutableState.value
            if (!current.busy) return@synchronized null
            val turn = current.turn ?: return@synchronized null
            val previousJob = job
            mutableState.value = AgentSessionState(turn.copy(phase = AgentPhase.STOPPED, failure = reason), false)
            mutableBusy.value = false
            job = null
            turn to previousJob
        } ?: return null
        cancelRequest(stopped.first.id)
        stopped.second?.cancel(CancellationException(reason))
        return stopped.first
    }
}
