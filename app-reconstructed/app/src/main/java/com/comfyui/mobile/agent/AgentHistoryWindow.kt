package com.comfyui.mobile.agent

import com.comfyui.mobile.model.AgentChatMessage

/**
 * Decides which past replies still show their prompt to the model.
 *
 * The history used to carry the prompt of the last reply only, so "把上次那张的雨改成雪" reached a model that
 * could no longer see what "上次那张" was: an edit request had to be re-described from scratch and the previous
 * tags were silently dropped. Keeping the latest few draws instead makes follow-up edits land on real text.
 */
internal object AgentHistoryWindow {

    /** How many of the latest draws keep their prompt in the history the model sees. */
    const val KEEP_DRAW_PROMPTS = 3

    /**
     * Indices into [history] whose prompt (and negative prompt) should be sent with the conversation.
     * A reply counts only when it actually drew something, so chit-chat cannot push a real draw out of the window.
     */
    fun promptCarriers(history: List<AgentChatMessage>, keep: Int = KEEP_DRAW_PROMPTS): Set<Int> {
        if (keep <= 0) return emptySet()
        return history.withIndex()
            .filter { (_, message) -> message.role == "assistant" && !message.expandedPrompt.isNullOrBlank() }
            .map { (index, _) -> index }
            .takeLast(keep)
            .toSet()
    }
}
