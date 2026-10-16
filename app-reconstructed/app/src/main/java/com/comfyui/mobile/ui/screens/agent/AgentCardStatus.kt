package com.comfyui.mobile.ui.screens.agent

/**
 * Top-card status for Agent preview. All non-idle phases share one frosted overlay layout.
 */
enum class AgentCardPhase {
    Idle,
    LlmThinking,
    Queued,
    GeneratingLocal,
    GeneratingCloud,
    BriefComplete,
}

data class AgentCardStatus(
    val phase: AgentCardPhase = AgentCardPhase.Idle,
    val title: String? = null,
    val subtitle: String? = null,
    /** Determinate 0..1; null with [showProgress] means indeterminate. */
    val progress: Float? = null,
    val showProgress: Boolean = false,
    /** Full frosted center overlay (LLM / queue / generate / complete). */
    val showOverlay: Boolean = false,
    val softenChips: Boolean = false,
    /** Top-end corner badge when idle (details stay in the chat bubble). */
    val errorBadge: String? = null
    )

object AgentCardStatusFactory {

    fun resolve(
        llmBusy: Boolean,
        imageBusy: Boolean,
        isDrawMode: Boolean,
        isCloudMode: Boolean,
        progress: Float,
        executingNodeName: String,
        rhAppStatus: String?,
        queueSize: Int,
        briefComplete: Boolean,
        errorMessage: String?
    ): AgentCardStatus {
        val err = errorMessage?.takeIf { it.isNotBlank() }?.let { shortError(it) }

        if (imageBusy && isCloudMode) {
            return AgentCardStatus(
                phase = AgentCardPhase.GeneratingCloud,
                title = "云端生成中",
                subtitle = rhAppStatus?.takeIf { it.isNotBlank() } ?: "任务进行中…",
                showProgress = true,
                progress = null,
                showOverlay = true,
                softenChips = true
    )
        }

        if (imageBusy) {
            val node = executingNodeName.trim()
            val hasProgress = progress > 0.01f
            val queued = node.isEmpty() && !hasProgress
            if (queued) {
                return AgentCardStatus(
                    phase = AgentCardPhase.Queued,
                    title = "已加入队列",
                    subtitle = when {
                        queueSize > 1 -> "排队中 · 约 $queueSize 项"
                        queueSize == 1 -> "即将开始…"
                        else -> "等待执行…"
                    },
                    showProgress = true,
                    progress = null,
                    showOverlay = true,
                    softenChips = true
    )
            }
            val pct = (progress * 100f).toInt().coerceIn(0, 100)
            val sub = buildList {
                if (node.isNotEmpty()) add(node)
                if (hasProgress) add("$pct%")
            }.joinToString(" · ").ifBlank { null }
            return AgentCardStatus(
                phase = AgentCardPhase.GeneratingLocal,
                title = "生成中",
                subtitle = sub,
                showProgress = true,
                progress = if (hasProgress) progress.coerceIn(0f, 1f) else null,
                showOverlay = true,
                softenChips = true
    )
        }

        if (llmBusy) {
            return AgentCardStatus(
                phase = AgentCardPhase.LlmThinking,
                title = if (isDrawMode) "构思提示词…" else "正在回复…",
                subtitle = null,
                showProgress = true,
                progress = null,
                showOverlay = true,
                softenChips = true
    )
        }

        if (briefComplete) {
            return AgentCardStatus(
                phase = AgentCardPhase.BriefComplete,
                title = "完成",
                subtitle = null,
                showProgress = false,
                showOverlay = true,
                softenChips = true,
                errorBadge = err
    )
        }

        return AgentCardStatus(
            phase = AgentCardPhase.Idle,
            errorBadge = err
    )
    }

    fun shortError(raw: String): String {
        val t = raw.trim().lineSequence().firstOrNull().orEmpty()
            .removePrefix("出错了：").removePrefix("出错了").trim()
        return when {
            t.contains("未解析") -> "提示词解析失败"
            t.contains("提交出图") || t.contains("提交失败") -> "提交失败"
            t.contains("节点") || t.contains("模板") || t.contains("工作流") -> "工作流配置有误"
            t.contains("API") || t.contains("Key") || t.contains("模型") -> "模型配置有误"
            t.length > 16 -> t.take(14) + "…"
            t.isBlank() -> "本轮失败"
            else -> t
        }
    }
}
