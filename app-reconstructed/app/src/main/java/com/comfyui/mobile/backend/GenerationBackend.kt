package com.comfyui.mobile.backend

import com.comfyui.mobile.model.ServerProfile

/** Which execution backend a server profile / task belongs to. Local and cloud never share state. */
enum class BackendKind {
    LOCAL_COMFY,
    RUNNINGHUB;

    companion object {
        fun of(profile: ServerProfile?): BackendKind =
            if (profile?.isRunningHub == true) RUNNINGHUB else LOCAL_COMFY

        fun fromName(name: String?): BackendKind? = entries.firstOrNull { it.name == name }
    }
}

enum class TaskState(val label: String) {
    SUBMITTING("提交中"),
    QUEUED("排队中"),
    RUNNING("运行中"),
    /** Site or API key changed: not polled until the original site/key is restored. */
    PAUSED("已暂停"),
    SUCCESS("已完成"),
    FAILED("失败"),
    CANCELLED("已取消"),
    LOST("状态未知");

    val isTerminal: Boolean
        get() = this == SUCCESS || this == FAILED || this == CANCELLED || this == LOST

    /** Still on RunningHub and should occupy the keep-alive / dashboard "in flight" slot. */
    val isActive: Boolean
        get() = this == SUBMITTING || this == QUEUED || this == RUNNING

    companion object {
        fun fromName(name: String?): TaskState? = entries.firstOrNull { it.name == name }
    }
}

/** One (nodeId, fieldName) -> value pair of a RunningHub template (AI app / workflow). */
data class RhFieldValue(
    val nodeId: String,
    val fieldName: String,
    val fieldValue: String
    )

sealed class GenerationRequest {
    abstract val title: String?
    abstract val workflowKey: String?

    /** A full ComfyUI API graph for a LOCAL ComfyUI server. */
    data class ComfyGraph(
        val prompt: Map<String, Any>,
        override val title: String? = null,
        override val workflowKey: String? = null
    ) : GenerationRequest()

    /** RunningHub workflow via /task/openapi/create: [workflowJson] = full edited API graph (JSON string). */
    data class RhWorkflow(
        val workflowId: String?,
        val workflowJson: String? = null,
        val nodeInfoList: List<RhFieldValue> = emptyList(),
        val instanceType: String? = null,
        override val title: String? = null,
        override val workflowKey: String? = null
    ) : GenerationRequest()

    /** RunningHub AI app via /task/openapi/ai-app/run. */
    data class RhWebApp(
        val webappId: String,
        val nodeInfoList: List<RhFieldValue>,
        val instanceType: String? = null,
        override val title: String? = null,
        override val workflowKey: String? = null
    ) : GenerationRequest()
}

/** A produced file. For RunningHub [url] is the remote fileUrl and [localUri] the downloaded copy. */
data class TaskOutput(
    val url: String? = null,
    val fileType: String? = null,
    val nodeId: String? = null,
    val localUri: String? = null,
    val filename: String? = null,
    val subfolder: String? = null,
    val type: String? = null
    )

enum class CostSource {
    /** Reported by the RunningHub task API (consumeCoins / consumeMoney). */
    API,
    /** Account balance before/after a single clean task (legacy proxy path). */
    BALANCE_DELTA,
    /** Guess; never shown as a real cost. */
    ESTIMATE,
}

data class TaskCost(
    val coins: Double? = null,
    val money: Double? = null,
    val thirdPartyMoney: Double? = null,
    val durationSec: Double? = null,
    val source: CostSource? = CostSource.API
    )

data class TaskError(
    val message: String? = null,
    val code: Int? = null,
    val retryable: Boolean? = false,
    val nodeId: String? = null,
    val nodeName: String? = null
    )

data class TaskSnapshot(
    val remoteId: String,
    val state: TaskState,
    val outputs: List<TaskOutput> = emptyList(),
    val cost: TaskCost? = null,
    val error: TaskError? = null,
    val message: String? = null
    )

/**
 * Exactly one backend is active at a time (see [BackendProvider]). Implementations must not share
 * HTTP clients, sockets or credentials with each other.
 */
interface GenerationBackend {
    val kind: BackendKind
    val serverId: String

    suspend fun submit(request: GenerationRequest): Result<TaskSnapshot>

    suspend fun poll(remoteId: String): Result<TaskSnapshot>

    suspend fun cancel(remoteId: String): Result<Unit>

    /** Releases connections / in-flight calls owned by this backend. */
    fun dispose()
}