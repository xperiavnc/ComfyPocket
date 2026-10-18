package com.comfyui.mobile.market

import com.comfyui.mobile.model.CivitaiModelItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MarketOpenState(
    val loading: Boolean = false,
    val error: String? = null,
    val model: CivitaiModelItem? = null,
    val versionId: Long? = null,
    val warning: String? = null
)

/** Explicit link/number lookup, not an automatic fallback for excluded search hits. */
class MarketModelOpener(
    private val scope: CoroutineScope,
    private val lookup: suspend (CivitaiModelReference) -> MarketDetailResult
) {
    private val mutableState = MutableStateFlow(MarketOpenState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var generation = 0L

    fun invalidate() {
        generation++
        job?.cancel()
        mutableState.value = MarketOpenState()
    }

    fun consume() { mutableState.value = state.value.copy(model = null) }

    fun open(value: String) {
        invalidate()
        val reference = CivitaiJson.modelReference(value)
        if (reference == null) {
            mutableState.value = MarketOpenState(error = "请输入有效模型编号，或 civitai.com / civitai.red 的 HTTPS 模型链接")
            return
        }
        val requestGeneration = generation
        mutableState.value = MarketOpenState(loading = true)
        job = scope.launch {
            try {
                val result = lookup(reference)
                if (requestGeneration == generation) mutableState.value = MarketOpenState(
                    model = result.model, versionId = reference.versionId, warning = result.warning)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (requestGeneration == generation) mutableState.value = MarketOpenState(
                    error = error.message ?: "模型详情读取失败，请重试")
            }
        }
    }
}
