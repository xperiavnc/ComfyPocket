package com.comfyui.mobile.agent

import com.comfyui.mobile.model.CharacterLore

/** App transport only. Persona and drawing instructions come exclusively from user rule files. */
object AgentPromptPolicy {
    /** Placed before the imported files so the model reads them as binding, not as background. */
    internal const val RULE_PRIORITY = "【规则优先级】下方用户导入的规则是必须遵守的硬性要求，优先于你的默认写法与习惯：语气、格式、提示词词序、权重语法、禁用词、构图与分镜要求都以规则为准。只有外层 JSON 传输协议高于规则；用户本轮明确提出的要求与规则冲突时，以用户本轮要求为准。"
    internal const val RULE_SELF_CHECK = "【输出前自检】逐条对照上方导入规则检查 reply、prompt 与 negative_prompt，有不符合的先改正再输出。"

    fun system(persona: String, rules: String, lores: List<CharacterLore>): String = buildString {
        appendLine("【应用输出协议】")
        appendLine("本次是绘图模式。外层只输出一个 JSON 对象，action 为 draw（出图）、apply_prompt（只写提示词）、respond（普通答复）或 ask_user（提问并等待）。reply 为对话正文；prompt 为最终正向提示词，negative_prompt（最终反向提示词字符串），should_draw 为布尔。")
        appendLine("JSON 字段名固定为 \"prompt\"、\"negative_prompt\"、\"should_draw\"；不要省略字段。")
        appendLine("每次同时输出正向与反向字段；空反向用空字符串，不要把反向写进 prompt。提示词文件描述的正文格式作用于字段内部，外层仍按此 JSON 协议传输。")
        appendLine("prompt_aliases 与 negative_prompt_aliases 为展示用数组 [{\"tag\":\"对应原文词项或完整描述句\",\"zh\":\"中文别名或简短释义\"}]，只对应实际提示词；空反向对应 []。中文对照不追加到绘图字段。")
        appendLine(questionGuidance)
        if (persona.isNotBlank() || rules.isNotBlank()) appendLine(RULE_PRIORITY)
        if (persona.isNotBlank()) {
            appendLine("【用户导入的对话规则】")
            appendLine(persona)
        }
        if (rules.isNotBlank()) {
            appendLine("【用户导入的绘图规则】")
            appendLine(rules)
        }
        if (lores.isNotEmpty()) {
            appendLine("【本轮角色资料】")
            lores.forEach { appendLine("- ${it.name}: ${it.triggerWords}") }
        }
        if (persona.isNotBlank() || rules.isNotBlank()) appendLine(RULE_SELF_CHECK)
    }

    val questionGuidance = """
【应用提问协议】
由你自行选择 action="ask_user" 或调用 ask_user_question，不使用关键词触发表单。普通文字问题不触发表单。
提问时给 questions 数组，一次 1–4 题；每题包含唯一 id、prompt、kind。kind="multiple_choice" 时 options=[{id,label,description}]，allow_multiple 指定单选/多选；kind="free_text" 时可给 placeholder。
ask_user 时 prompt=""、negative_prompt=""、should_draw=false；提问后结束本轮等待用户。App 会带回原任务和用户答案，再由你选择下一动作。respond 仅对话，apply_prompt 仅写入提示词，draw 才提交生成。
""".trimIndent()

    fun chat(persona: String): String = buildString {
        appendLine("【应用交互协议】普通聊天直接输出自然语言，不输出绘图 JSON。需要收集用户答案时使用 ask_user_question 或单个 ask_user JSON；聊天模式不执行出图。")
        appendLine(questionGuidance)
        if (persona.isNotBlank()) {
            appendLine(RULE_PRIORITY)
            appendLine("【用户导入的对话规则】")
            appendLine(persona)
            appendLine("【输出前自检】回复前对照上方对话规则检查语气与格式，不符合的先改正。")
        }
    }
}
