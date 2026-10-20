package com.comfyui.mobile.agent

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class AgentReply(
    val tip: String,
    val prompt: String = "",
    val thinking: String = "",
    val shouldDraw: Boolean = false,
    val parseError: String? = null,
    /** null preserves workflow defaults; an explicit empty string clears the negative input. */
    val negativePrompt: String? = null,
    val characterSelections: List<com.comfyui.mobile.model.AgentCharacterSelection>? = null,
    val characterStatus: String? = null,
    val clarification: com.comfyui.mobile.model.AgentClarification? = null,
    val action: AgentAction? = null
)

enum class AgentAction { ASK_USER, RESPOND, APPLY_PROMPT, DRAW }
data class AgentProgress(val reasoning: String, val content: String)
enum class AgentMode { CHAT, DRAW }
data class AgentRunResult(val reply: AgentReply, val submittedId: String? = null)

fun interface AgentGenerationSource {
    suspend fun generate(onProgress: suspend (AgentProgress) -> Unit): AgentReply
}

interface AgentConversationObserver {
    suspend fun progress(progress: AgentProgress)
    suspend fun reply(reply: AgentReply)
}

/** Android/network adaptation stays outside the runner; tests exercise this same execution path. */
interface AgentWorkflowPort {
    suspend fun prepare()
    suspend fun patchPrompt(prompt: String, negativePrompt: String?)
    suspend fun promptApplied(prompt: String)
    suspend fun submit(): String
}

class AgentExecutionException(message: String) : Exception(message)

/** Prompt generation has no submission side effects. Applying and submitting are separate steps. */
class AgentDrawExecutor(private val workflow: AgentWorkflowPort) {
    suspend fun execute(
        reply: AgentReply,
        checkCurrent: suspend () -> Unit,
        phase: (AgentPhase) -> Unit
    ): String? {
        if (reply.prompt.isBlank()) {
            throw AgentExecutionException(
                "未解析到有效提示词，未出图" + (reply.parseError?.let { "（$it）" } ?: "")
            )
        }
        checkCurrent()
        phase(AgentPhase.APPLYING)
        workflow.prepare()
        checkCurrent()
        workflow.patchPrompt(reply.prompt, reply.negativePrompt)
        checkCurrent()
        workflow.promptApplied(reply.prompt)
        if (!reply.shouldDraw) return null
        checkCurrent()
        phase(AgentPhase.SUBMITTING)
        // Exactly one submission attempt. No automatic retry of a potentially accepted task.
        return workflow.submit()
    }
}

class AgentConversationRunner(private val session: AgentSessionController) {
    suspend fun run(
        turn: AgentTurn,
        mode: AgentMode,
        source: AgentGenerationSource,
        observer: AgentConversationObserver,
        workflow: AgentWorkflowPort
    ): AgentRunResult {
        suspend fun check() {
            currentCoroutineContext().ensureActive()
            session.requireCurrent(turn)
        }
        check()
        session.advance(turn, AgentPhase.GENERATING)
        val reply = source.generate { progress ->
            check()
            observer.progress(progress)
        }
        check()
        observer.reply(reply)
        val submitted = if (mode == AgentMode.DRAW && reply.clarification == null &&
            reply.action != AgentAction.ASK_USER && reply.action != AgentAction.RESPOND &&
            (reply.prompt.isNotBlank() || reply.shouldDraw || reply.parseError != null)) {
            AgentDrawExecutor(workflow).execute(reply, ::check) { session.advance(turn, it) }
        } else null
        check()
        return AgentRunResult(reply, submitted)
    }
}
