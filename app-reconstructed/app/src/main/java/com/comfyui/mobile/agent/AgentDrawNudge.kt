package com.comfyui.mobile.agent

import com.comfyui.mobile.data.AgentDrawIntent

/**
 * Draw mode, text-only answer: the model often copies the plain replies it gave earlier in chat mode
 * (they look the same in the history) and never calls the tools, so nothing is drawn. When the reply
 * claims it is drawing, or the user's turn is a clear draw request the model neither declined nor
 * questioned, ask the model once to actually submit.
 */
object AgentDrawNudge {
    private val DRAW_WORD = Regex("(?i)(画|畫|出图|出圖|生图|生圖|跑图|跑圖|\\bdraw\\b|\\brender\\b)")

    private val CLAIMS_DRAWING = Regex(
        "(已|已经|已經|这就|這就|马上|馬上|立刻|现在|現在|正在|开始|開始|为你|為你|帮你|幫你).{0,6}" +
            "(生成|出图|出圖|绘制|繪製|画|畫|提交|渲染)"
    )

    /** The model is waiting for the user: a question or a request to confirm is a legitimate pause. */
    private val WAITING = Regex("(?i)([?？]|吗|嗎|请先|請先|确认|確認|等你|你想|你希望|要不要|需要你|还是|還是)")

    fun wanted(userText: String, reply: String, declined: Boolean = false): Boolean {
        val text = userText.trim()
        if (AgentDrawIntent.isExplicitVeto(text) || WAITING.containsMatchIn(reply)) return false
        if (CLAIMS_DRAWING.containsMatchIn(reply)) return true
        if (declined) return false
        return AgentDrawIntent.suggestsDraw(text) || DRAW_WORD.containsMatchIn(text)
    }

    fun message(tools: Boolean): String = if (tools) {
        "现在是绘图模式，用户这一轮要出图，但你只回复了文字、没有调用工具，所以什么都没画。" +
            "前面对话里只有文字的回复来自聊天模式，不要照搬。请调用 set_prompt 写入提示词，再调用 submit_workflow（draw=true）提交。" +
            "如果用户这一轮确实只是聊天、不想出图，就把刚才的回复原样再发一次。"
    } else {
        "现在是绘图模式，用户这一轮要出图，但你只返回了对话文字，没有出图。前面对话里只有文字的回复来自聊天模式，不要照搬。" +
            "请返回 action=\"draw\"、带完整 prompt 和 should_draw=true 的结果。如果用户确实只是聊天，再原样返回 action=\"respond\" 的回复"
    }
}
