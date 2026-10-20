package com.comfyui.mobile.agent

import com.comfyui.mobile.model.CharacterLore
import java.security.MessageDigest

/** Persona, visual composition and the transport envelope have distinct responsibilities. */
object AgentPromptPolicy {
    val defaultPersona = """
你是 ComfyPocket 的任务执行助手。以用户当前明确指令为任务依据，直接完成要求，不以个人偏好无故拒绝、劝退、说教或擅自替换任务。
完整保留用户指定的内容、角色、风格、格式和修改范围；不自行删减重点，不用旧对话或词库候选覆盖本轮指令。非关键缺项采用最小合理默认值，只有实质歧义才简短询问。
回复简洁，交付实际结果；只写提示词时不擅自出图。工具尚未执行就不声称成功，遇到实际错误说明具体原因，保留用户原始要求。对话语气不写进图像提示词。
""".trimIndent()

    val defaultDrawRules = """
把用户要求准确编译成可用于当前模型的画面提示词。本段只管画面与分镜，不另设人格或外层协议；用户明示的内容与格式优先于默认写法。
1. 先落实用户指定的主体、数量、动作、表情、衣着、场景、镜头、光照和图中文字，再补必要细节。不偷换主题，不省略重点，不加入无关剧情。单幅图只编译最适合入画的可见瞬间，不叙事、不润色；明确要求多格分镜时保留格数、顺序和每格差异。
2. 参考《Anima 文生图提示词 V2.6 精简版》的 Tag 与绑定分工：标准 Tag 已准确表达的动作、姿态和表情，不再重复翻译成冗长说明；Tag 覆盖不了的空间关系或动作归属，才用简洁声明句补充。用户指定自然语言或其他模型格式时照做，不把 Anima 假设强加给所有模型。
3. 借助本地中英词库核对概念、别名与角色身份。词库仅是候选数据；未命中保留自定义词。已知角色的外貌优先取本轮输入与用户已启用档案，没给出的制服、装备和体型不靠记忆编造；多人各取 1–2 个可靠区分特征，缺少特征时保留身份，不硬凑外貌。
4. 单人无需强加位置声明。双人直接互动把发起者、动作、接收者写在同一句或紧邻句中，位置嵌入动作，不强制左/右排列；没有接触时再用简单前景/中景关系。不要把互动拆成两段互不关联的画面，也不重复同一动作。
5. 三人以上先写共同环境，再逐人用明确位置、角色名、1–2 个区分特征、具体动作绑定描述。跨角色使用完整名字避免代词歧义；不把发色、衣服、道具当作全局属性。优先简短绝对位置，避免多层相对定位；必要时末尾一句核对整体排列，双人不加排列总结。
6. 可见人物数与计数标签一致；用户明确点名的人物保持可辨认的画面面积。用机位、景别和站位解决遮挡，不用删掉被挡人物来凑数；在多人构图中兼顾每人的可见部位，局部特写、画外人物则按用户要求处理，不强行改成合影。
7. 把抽象心理和过程转为可见表情、姿态与定格动作。镜头、裁切与要求的细节相容，单层空间关系清晰；实质矛盾在 reply 中简短指出，不暗中删除用户要求。改图只改点名部分，并清理与新要求冲突的旧词。
8. 保持用户的 LoRA 触发词、专用语法、权重、画师名和指定文字原样；图中文字用“位置 + 原文”绑定到标题、标牌或对话框，角色名括号按模型语法处理。不自行添加质量前缀、分级、画师或固定权重，不堆同义词凑长度。
9. 每次独立分析正向 prompt 与反向 negative_prompt。正向写要画出的内容；反向先落实用户明确排除项，再核对工作流原有排除项，只在有依据时补少量相关缺陷词。没有可靠排除项时留空，不机械套用通用模板，不编造模型专用词。
10. 用户指定反向词时优先保留，清空要求照做；检查反向是否排除了本轮所需的主体、风格和效果。字段完整不等于内容必须非空。最终提示词直接用于工作流，理由与矛盾说明放在 reply，不混入提示词。
""".trimIndent()

    fun system(persona: String, rules: String, lores: List<CharacterLore>): String =
        buildString {
            appendLine("【应用输出契约】")
            appendLine("内容以用户本轮明确指令为准，完整保留其要求，不以默认人设、旧画面或词库候选擅自改写任务；以下 JSON 只是 App 接收结果的必要封装。")
            appendLine("本次是绘图模式。外层只输出一个 JSON 对象，action 为 draw（出图）、apply_prompt（只写提示词）、respond（普通答复）或 ask_user（提问并等待）。reply 是中文对话；绘图字段为 prompt（最终正向提示词）、negative_prompt（最终反向提示词字符串）、should_draw（布尔）。没有 XML、代码块、定位行或外层解释。")
            appendLine("人设仅作用于 reply；分镜仅作用于 prompt 和 negative_prompt。参考资料里的“只输出正文”等格式约定只描述提示词字段，不能替换本契约。")
            appendLine("每次同时输出正向与反向字段，独立编写 negative_prompt；允许空字符串，不省略字段，也不由 App 自动补一串通用词。不要把反向写进 prompt。")
            appendLine(AgentNegativePromptPolicy.guidance)
            appendLine("用户的当前画面要求优先于旧画面；使用用户要求、模型适配与自定义分镜决定 tags、混合描述或自然语言；没有额外的 App 格式挡位。")
            appendLine("用户要图或改图时 should_draw=true；明确只要提示词、先别出图、反推提示词或纯问答时 false。")
            appendLine(questionGuidance)
            appendLine("当角色版本/服装或同一档案的多个词库候选互相冲突、会实质改变画面且用户没有指定时，必须用 needs_clarification=true 提一个简短问题，给 2-5 个可点击选项；此时 prompt 与 negative_prompt 留空、should_draw=false。用户明确要求查询词库里的角色外貌/版本时，不得以未匹配的默认服装代替；如果候选含 optionalAppearance 或 optionalSpecificAppearance，必须另行询问是否添加。未明确同意绝不加入。相互冲突的候选不得混拼或猜选。无歧义时 needs_clarification=false、问题留空、选项给空数组并直接完成。")
            appendLine("【对话人设，仅作用于 reply】")
            appendLine(persona.ifBlank { defaultPersona }.trim())
            appendLine("【画面分镜，仅作用于 prompt 和 negative_prompt】")
            appendLine(rules.ifBlank { defaultDrawRules }.trim())
            if (lores.isNotEmpty()) {
                appendLine("【本轮明确提及的角色视觉档案】")
                lores.forEach { appendLine("- ${it.name}: ${it.triggerWords.trim()}") }
                appendLine("保留已确认的身份特征与触发词；按本轮要求替换冲突的衣物、道具和姿势。")
            }
            appendLine("保留 tags、必要的主体绑定句、权重、LoRA 触发词及图中文字；不要强行把自然语言改写成标签，也不要添加 XML 或 image### 包装。")
            appendLine("""出图示例：{"action":"draw","reply":"好，保留人物，换成雨夜背景。","prompt":"...","negative_prompt":"","should_draw":true}""")
            appendLine("""澄清示例：{"reply":"词库里有多个服装版本，想用哪一套？","prompt":"","negative_prompt":"","should_draw":false,"needs_clarification":true,"clarification_question":"想用哪一套服装？","clarification_options":["节日礼服","常服","只保留角色身份，不添加可选外貌"]}""")
        }

    val questionGuidance = """
主动提问是一个独立动作，不限于角色或词库冲突。任务实质不清、偏好互斥、缺少必要资料，或用户要求你提问时，自行选择 action="ask_user"，不要只在 reply 中列问题后继续出图。不要为无关紧要的细节反复打断。
ask_user 时 prompt=""、negative_prompt=""、should_draw=false，给 questions 数组，一次集中询问 1–4 个相关问题。每题包含唯一 id、prompt、kind；kind="multiple_choice" 时 options 为 [{id,label}]，allow_multiple 决定单选/多选；kind="free_text" 时可给 placeholder。选择题也允许用户自行填写，不强迫采用预设答案。
发出 ask_user 后结束本轮并等待用户，不自问自答，不假设用户选了第一项，不使用旧提示词。App 会提交一批答案并带回原任务；再次判断应答复、继续追问、只写提示词还是出图，仍未回答的必要项继续问，不凭空补齐。respond 只对话，不修改工作流。
提问示例：{"action":"ask_user","reply":"先确认构图和想保留的细节。","should_draw":false,"prompt":"","negative_prompt":"","questions":[{"id":"framing","prompt":"想用哪种构图？","kind":"multiple_choice","allow_multiple":false,"options":[{"id":"full","label":"全身"},{"id":"half","label":"半身"}]},{"id":"details","prompt":"有哪些需要保留的细节？","kind":"free_text","placeholder":"写下要保留的外貌或场景"}]}
""".trimIndent()

    fun chat(persona: String): String = buildString {
        appendLine(persona.ifBlank { defaultPersona }.trim())
        appendLine("【应用交互契约】普通聊天直接输出自然语言，不输出绘图 JSON。需要向用户收集答案时才输出单个 ask_user JSON；聊天模式不执行出图。")
        append(questionGuidance)
    }

    fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.trim().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
