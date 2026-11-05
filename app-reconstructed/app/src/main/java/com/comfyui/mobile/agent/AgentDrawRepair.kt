package com.comfyui.mobile.agent

/**
 * Draw mode, a reply that parses but carries no usable prompt: nothing can be drawn, and the turn used
 * to end with the bare "未解析到有效提示词，未出图（JSON 中没有有效的 prompt）". The repair asks for the one
 * field that is missing and shows the exact envelope to return, instead of repeating "结果无法解析".
 */
object AgentDrawRepair {
    /** Small enough for a model to copy, complete enough to be submitted as is. */
    const val SHAPE =
        """{"reply":"一句话说明","prompt":"1girl, solo, ...","negative_prompt":"lowres","should_draw":true}"""

    fun missingPrompt(reason: String?): String {
        val why = reason?.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
        return "上一条结果里没有可用的正向提示词$why：绘图模式必须给出 prompt。" +
            "请重新返回一个完整 JSON，例如 $SHAPE。" +
            "prompt 要写成一个字符串——逗号分隔的标签串，或按系统要求的整句描述——不能省略、不能留空。" +
            "只回复文字不算完成本轮；如果这一轮确实不该出图，返回 action=\"respond\"。"
    }
}
