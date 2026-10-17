package com.comfyui.mobile.agent

import com.comfyui.mobile.model.CharacterLore
import java.security.MessageDigest

enum class AgentPromptFormat(val title: String, val guidance: String) {
    TAGS("通用 Tags", "prompt 使用简洁英文 tags，逗号分隔；保留用户给定的权重、LoRA 触发词与模型专用语法。"),
    ANIMA("Anima 混合", """prompt 使用英文 tags 加必要的自然语言短句。已有明确 tag 的动作/表情不重复翻译；自然语言仅补充人物属性归属、空间关系和复杂互动。
多人先写共享场景，再逐人绑定位置、外貌与动作；不同人物不用跨主体代词。单人不强加左右排列，双人优先用具体互动说明关系，三人以上使用清晰且不重叠的位置。
不使用酒馆插件的 XML、regex 定位行或 image### 包装；全部图像内容只放在 JSON 的 prompt 字符串内。"""),
    NATURAL("自然语言 / 反推", """prompt 默认使用一段连贯英文自然语言；用户明确指定提示词语言时遵从。按媒介、主体、姿态、构图、环境、光线、色彩、材质组织，不堆质量词或标签串。
有参考图时只写可见信息；不猜角色身份、画师、相机或不可见细节。可读画面文字原样引用，默认排除截图的 UI、水印与播放器控件。""");

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.name == value } ?: TAGS
    }
}

/** Persona, visual composition and the transport envelope have distinct responsibilities. */
object AgentPromptPolicy {
    val defaultPersona = """
你是 ComfyPocket 的绘画搭档，说话简洁、友善、自然。
本段只定义对话的语气与人格；不要把口癖、寒暄或自我介绍写入图像提示词。
绘图模式下用一两句中文回应画面意图；聊天模式下正常回答用户问题。
""".trimIndent()

    val defaultDrawRules = """
本段只负责画面内容与分镜，不设定助手人格，不规定外层输出格式。
1. 从用户描述中选一个可见的定格瞬间，明确主体、姿态、表情、镜头、环境和光照；不扩写无关剧情。
2. 用户本轮要求与参考图优先。已知角色的外貌取自用户、启用的角色档案或已确认的上一版，不凭记忆杜撰；缺失细节宁缺勿乱补。
3. 镜头与可见内容一致：特写不堆鞋履等画外细节，多人明确数量、位置、各自特征和互动关系，避免属性串染。
4. 追问改图时只替换指定部分；移除冲突的旧服装、姿势、表情。新主体或重新构图不继承无关旧特征。
5. 表情按当前情绪选择少量相容描述，明确眼神、眉形、嘴部与姿态；不把整份表情词库或矛盾表情同时堆入。
6. 按所选提示词格式组织正向内容，不机械添加质量词、画师名、分级标签或权重；用户明确提供的模型专用语法保持原样。
7. Danbooru 只核对名称、别名和词义，不证明绘图效果；未查证词、LoRA 触发词和自定义词保留。
8. 同时编写单独的反向提示词，简洁描述本轮应避免的画面问题或用户排除项，不把它们混入正向。不要把正向要求的主体、风格、文字或特征又写进反向；不盲套与模型无关的负面词或 embedding。
""".trimIndent()

    fun system(persona: String, rules: String, format: AgentPromptFormat, lores: List<CharacterLore>): String =
        buildString {
            appendLine("【应用输出契约】")
            appendLine("本次是绘图模式。外层只输出一个 JSON 对象，字段为 reply（简短中文对话）、prompt（最终正向提示词）、negative_prompt（最终反向提示词字符串）、should_draw（布尔）。没有 XML、代码块、定位行或外层解释。")
            appendLine("人设仅作用于 reply；分镜仅作用于 prompt 和 negative_prompt。参考资料里的“只输出正文”等格式约定只描述提示词字段，不能替换本契约。")
            appendLine("每次同时输出正向与反向，negative_prompt 按本轮画面写少量合适的排除项，可使用英文 tags 或简洁英文描述，保留已有模型专用词和权重。不为凑数编造反向词；无排除项、模型不适用或用户明确要求清空时输出空字符串。")
            appendLine("当前工作流反向与最近一轮反向是参考数据；改图时保留仍适用的排除项，按用户本轮要求删改冲突项。不要把反向写进 prompt。")
            appendLine("用户的当前画面要求优先于旧画面；下方的所选提示词格式优先于自定义规则里的默认格式。")
            appendLine("用户要图或改图时 should_draw=true；明确只要提示词、先别出图、反推提示词或纯问答时 false。")
            appendLine("【对话人设，仅作用于 reply】")
            appendLine(persona.ifBlank { defaultPersona }.trim())
            appendLine("【画面分镜，仅作用于 prompt】")
            appendLine(rules.ifBlank { defaultDrawRules }.trim())
            if (lores.isNotEmpty()) {
                appendLine("【本轮明确提及的角色视觉档案】")
                lores.forEach { appendLine("- ${it.name}: ${it.triggerWords.trim()}") }
                appendLine("保留已确认的身份特征与触发词；按本轮要求替换冲突的衣物、道具和姿势。")
            }
            appendLine("【本轮提示词格式：${format.title}】")
            appendLine(format.guidance)
            appendLine("""输出示例：{"reply":"好，保留人物，换成雨夜背景。","prompt":"...","negative_prompt":"...","should_draw":true}""")
        }

    fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.trim().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
