package com.comfyui.mobile.backend

import com.comfyui.mobile.data.ComfyRepository

/**
 * LOCAL ComfyUI backend: a thin wrapper over the existing [ComfyRepository] + WebSocketManager stack.
 * The existing local run pipeline in MainViewModel keeps calling the repository directly, so local
 * behaviour is unchanged; this wrapper gives the backend abstraction a local implementation.
 */
class LocalComfyBackend(
    private val repository: ComfyRepository,
    override val serverId: String
    ) : GenerationBackend {

    override val kind: BackendKind = BackendKind.LOCAL_COMFY

    override suspend fun submit(request: GenerationRequest): Result<TaskSnapshot> {
        val graph = request as? GenerationRequest.ComfyGraph
            ?: return Result.failure(IllegalArgumentException("本地 ComfyUI 只能运行本地工作流"))
        val pid = repository.queuePrompt(graph.prompt)
            ?: return Result.failure(Exception(repository.lastQueuePromptError ?: "提交失败"))
        return Result.success(TaskSnapshot(remoteId = pid, state = TaskState.QUEUED))
    }

    override suspend fun poll(remoteId: String): Result<TaskSnapshot> {
        val item = repository.getHistoryItem(remoteId)
        if (item != null) {
            val state = when {
                item.interrupted -> TaskState.CANCELLED
                item.statusStr.equals("error", ignoreCase = true) -> TaskState.FAILED
                else -> TaskState.SUCCESS
            }
            val err = if (state == TaskState.SUCCESS) null else TaskError(message = item.errorMessage ?: state.label)
            return Result.success(TaskSnapshot(remoteId = remoteId, state = state, error = err))
        }
        val active = repository.getActiveQueuePromptIds()
            ?: return Result.failure(Exception("无法获取队列状态"))
        return Result.success(TaskSnapshot(remoteId = remoteId, state = if (remoteId in active) TaskState.RUNNING else TaskState.QUEUED))
    }

    override suspend fun cancel(remoteId: String): Result<Unit> {
        repository.interrupt()
        return Result.success(Unit)
    }

    /** The repository singleton is re-targeted by applyServer(); nothing else is owned here. */
    override fun dispose() = Unit
}