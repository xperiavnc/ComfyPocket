package com.comfyui.mobile.backend

import com.comfyui.mobile.model.ServerProfile
import com.comfyui.mobile.network.RhApiException
import com.comfyui.mobile.network.RhError
import com.comfyui.mobile.network.RhHttpClient
import com.comfyui.mobile.network.RhNodeInfo
import com.comfyui.mobile.network.RhOutputsResult
import com.comfyui.mobile.network.RhSubmitResult

/**
 * RunningHub cloud backend on the official task API. Owns its own [RhHttpClient] (strict TLS,
 * Bearer auth, key never in URLs); nothing here touches the local ComfyUI stack.
 */
class RunningHubBackend(profile: ServerProfile) : GenerationBackend {

    override val kind: BackendKind = BackendKind.RUNNINGHUB
    override val serverId: String = profile.effectiveId
    val site = profile.effectiveRhSite
    val defaultInstanceType: String = profile.effectiveRhGpuTier.instanceType
    val gpuTierLabel: String = profile.effectiveRhGpuTier.displayName

    val client = RhHttpClient(profile.effectiveRhSite, profile.effectiveRhApiKey)

    val hasKey: Boolean get() = client.hasKey

    override suspend fun submit(request: GenerationRequest): Result<TaskSnapshot> {
        val res: Result<RhSubmitResult> = when (request) {
            is GenerationRequest.RhWebApp -> client.runAiApp(
                webappId = request.webappId,
                nodes = request.nodeInfoList.map { RhNodeInfo(it.nodeId, it.fieldName, it.fieldValue) },
                instanceType = request.instanceType ?: defaultInstanceType
    )
            is GenerationRequest.RhWorkflow -> client.createTask(
                workflowId = request.workflowId,
                nodes = request.nodeInfoList.map { RhNodeInfo(it.nodeId, it.fieldName, it.fieldValue) },
                instanceType = request.instanceType ?: defaultInstanceType,
                workflowJson = request.workflowJson
    )
            is GenerationRequest.ComfyGraph ->
                return Result.failure(IllegalArgumentException("RunningHub 云端后端不接受本地工作流图"))
        }
        return res.map { r ->
            val state = when (r.taskStatus?.uppercase()) {
                "QUEUED" -> TaskState.QUEUED
                "SUCCESS" -> TaskState.RUNNING // outputs still have to be fetched
                "FAILED" -> TaskState.RUNNING // let poll() report the failure reason
                else -> TaskState.RUNNING
            }
            TaskSnapshot(remoteId = r.taskId, state = state, message = r.promptTips?.takeIf { it.isNotBlank() && it != "null" })
        }
    }

    override suspend fun poll(remoteId: String): Result<TaskSnapshot> {
        val res = client.taskOutputs(remoteId)
        res.exceptionOrNull()?.let { e ->
            if (e is RhApiException && e.code == RhError.TASK_NOT_FOUND) {
                return Result.success(
                    TaskSnapshot(remoteId, TaskState.LOST, error = TaskError(message = e.message, code = e.code))
                )
            }
            return Result.failure(e)
        }
        return when (val r = res.getOrThrow()) {
            is RhOutputsResult.Running -> Result.success(
                TaskSnapshot(remoteId, if (r.queued) TaskState.QUEUED else TaskState.RUNNING)
            )
            is RhOutputsResult.Failed -> Result.success(
                TaskSnapshot(
                    remoteId,
                    TaskState.FAILED,
                    error = TaskError(
                        message = r.message,
                        code = RhError.TASK_FAILED,
                        nodeId = r.reason?.nodeId,
                        nodeName = r.reason?.nodeName
    )
    )
            )
            is RhOutputsResult.Success -> {
                val files = r.files
                fun num(sel: (com.comfyui.mobile.network.RhOutputFile) -> String?): Double? =
                    files.mapNotNull { f -> sel(f)?.trim()?.toDoubleOrNull() }.maxOrNull()
                val cost = TaskCost(
                    coins = num { it.consumeCoins },
                    money = num { it.consumeMoney },
                    thirdPartyMoney = num { it.thirdPartyConsumeMoney },
                    durationSec = num { it.taskCostTime },
                    source = CostSource.API
    )
                Result.success(
                    TaskSnapshot(
                        remoteId = remoteId,
                        state = TaskState.SUCCESS,
                        outputs = files.map { TaskOutput(url = it.fileUrl, fileType = it.fileType, nodeId = it.nodeId) },
                        cost = if (cost.coins == null && cost.money == null && cost.durationSec == null) null else cost
    )
                )
            }
        }
    }

    override suspend fun cancel(remoteId: String): Result<Unit> = client.cancelTask(remoteId)

    override fun dispose() = client.dispose()
}