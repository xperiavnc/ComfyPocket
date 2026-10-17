package com.comfyui.mobile.data

/**
 * Draw-mode intent + prompt recovery for edit turns (换装 etc.).
 * Prefer recall unless explicit veto / pure non-visual chat.
 * Pure Kotlin — unit-testable.
 */
object AgentDrawIntent {

    private val VETO = Regex(
        "(?i)(只要提示词|只要\\s*tags?|只要prompt|只要英文|" +
            "先别(出图|画|生成|渲染)|不要(出图|画|生成|渲染)|别(出图|画|生成|渲染)|先不要画|先别提交|不要提交|" +
            "explain\\s+(the\\s+)?tags?|only\\s+(the\\s+)?prompt|don'?t\\s+draw|do\\s+not\\s+draw|no\\s+render|without\\s+rendering)"
    )

    private val STRONG = Regex(
        "(?i)(" +
            "改图|修图|出图|生图|跑图|渲染|" +
            "画一张|画一張|画一个|画一個|画个|画個|画张|画張|画一下|帮我画|幫我畫|来一张|來一張|出一张|出一張|做一张|做一張|" +
            "再画|再畫|再来一张|再來一張|重画|重畫|重新生成|重新画|重新畫|" +
            "生成一张|生成一張|生成一个|生成一個|生成张|生成張|提交出图|提交生图|" +
            // clothing / outfit — include 换个服装 (换装 alone does NOT match 换个服装)
            "换装|換裝|换服饰|換服飾|换服装|換服裝|换个服装|換個服裝|换套服装|換套服裝|" +
            "换一身|換一身|换一套|換一套|换件|換件|换条|換條|换身|換身|" +
            "改衣服|换衣服|換衣服|换个衣服|換個衣服|换身衣服|換身衣服|换衣|換衣|" +
            "换发型|換髮型|换髮型|换背景|換背景|换姿势|換姿勢|换姿态|換姿態|换个姿势|換個姿勢|" +
            "改成|换成|換成|改一下|调整画面|調整畫面|" +
            "把.+改成|把.+换成|把.+換成|改完再画|改完再畫|按这个出图|按這個出圖|" +
            "(加点|加一点|加一點|加个|加個)(?!意见|意見|油|班|钱|錢).{0,10}|" +
            "(去掉|删掉|刪掉|移除).{1,12}|" +
            "图里|圖裡|图中|圖中|这张图|這張圖|那张图|那張圖|上一张|上一張|" +
            "立绘|立繪|壁纸|壁紙|插画|插畫|封面|角色图|角色圖|" +
            "redraw|regenerate|draw\\s+again|generate\\s+(an?\\s+)?image|change\\s+(the\\s+)?outfit|re-?render|" +
            "make\\s+(an?\\s+)?image|draw\\s+(me\\s+)?(a|an)\\b" +
            ")"
    )

    private val VISUAL_NOUN = Regex(
        "(?i)(女孩|男孩|少女|少年|女人|男人|人物|角色|公主|骑士|騎士|猫娘|兽耳|獸耳|" +
            "头发|頭髮|瞳|眼睛|裙子|制服|泳装|泳裝|和服|铠甲|鎧甲|翅膀|服装|衣服|连衣裙|旗袍|" +
            "场景|場景|背景|森林|海边|海邊|教室|房间|房間|夜景|日落|星空|" +
            "姿势|姿勢|表情|光影|构图|構圖|特写|特寫|全身|半身|" +
            "二次元|动漫|動漫|插画|插畫|立绘|立繪|壁纸|壁紙|美少女|" +
            "1girl|1boy|masterpiece|best quality|solo|looking at viewer|cowboy shot|" +
            "upper body|full body|from side|from behind)"
    )

    private val PURE_CHAT = Regex(
        "(?i)^(" +
            "你好|您好|嗨|哈喽|哈囉|在吗|在嗎|早上好|晚安|谢谢|謝謝|感谢|感謝|" +
            "哈哈+|呵呵+|嗯+|哦+|好的|收到|ok|okay" +
            ")!*[。.!?！？]*$" +
            "|(你是谁|你是什麼|你是什么|什么模型|什麼模型|这是啥模型|這是啥模型|" +
            "怎么用|怎麼用|如何使用|你能做什么|你能做什麼|帮助|幫助|说明一下|說明一下)"
    )

    data class PromptRecovery(
        val prompt: String,
        /** True when we reused / patched last prompt because parse yielded nothing usable. */
        val recoveredFromLast: Boolean,
        val reason: String
    )

    fun isExplicitVeto(userText: String): Boolean {
        val t = userText.trim()
        if (t.isEmpty()) return false
        val reverseOnly = Regex("反推|反向提示词").containsMatchIn(t) &&
            !Regex("(并|再|然后|接着).{0,6}(出图|生图|生成|画)").containsMatchIn(t)
        return reverseOnly || VETO.containsMatchIn(t)
    }

    fun suggestsDraw(userText: String): Boolean {
        val t = userText.trim()
        if (t.isEmpty() || isExplicitVeto(t)) return false
        return STRONG.containsMatchIn(t)
    }

    fun looksLikeVisualPrompt(userText: String): Boolean {
        val t = userText.trim()
        if (t.isEmpty() || isExplicitVeto(t)) return false
        if (looksLikePureChat(t)) return false
        if (suggestsDraw(t)) return true

        val commaParts = t.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
        if (commaParts.size >= 3 &&
            Regex("(?i)\\b(1girl|1boy|masterpiece|best quality|solo)\\b").containsMatchIn(t)
        ) {
            return true
        }

        if (t.length >= 8 && VISUAL_NOUN.containsMatchIn(t)) {
            val askOnly = Regex("(?i)(为什么|為什麼|怎么理解|怎麼理解|是什么意思|是什麼意思|区别|區別|原理|科普)")
            if (askOnly.containsMatchIn(t) && !STRONG.containsMatchIn(t)) return false
            return true
        }
        return false
    }

    fun looksLikePureChat(userText: String): Boolean {
        val t = userText.trim()
        if (t.isEmpty()) return true
        if (PURE_CHAT.containsMatchIn(t) && t.length <= 40) return true
        if (t.length <= 4 && !VISUAL_NOUN.containsMatchIn(t) && !STRONG.containsMatchIn(t)) return true
        return false
    }

    fun looksLikeEditRequest(userText: String): Boolean {
        val t = userText.trim()
        if (t.isEmpty() || isExplicitVeto(t)) return false
        return suggestsDraw(t) || looksLikeVisualPrompt(t)
    }

    /**
     * When the LLM returns chat-only / Chinese-only "prompt" that sanitizes to empty,
     * reuse the last English tags and append a light English outfit hint from the user text.
     */
    fun recoverPromptIfNeeded(
        parsedPrompt: String,
        userText: String,
        lastPrompt: String
    ): PromptRecovery {
        val parsed = parsedPrompt.trim()
        if (parsed.isNotEmpty()) {
            return PromptRecovery(parsed, recoveredFromLast = false, reason = "parsed")
        }
        if (isExplicitVeto(userText)) {
            return PromptRecovery("", recoveredFromLast = false, reason = "veto")
        }
        val last = lastPrompt.trim()
        if (last.isEmpty()) {
            return PromptRecovery("", recoveredFromLast = false, reason = "no_last_prompt")
        }
        // Recover for edit / visual / any non-pure-chat Draw turn.
        if (!looksLikeEditRequest(userText) && looksLikePureChat(userText)) {
            return PromptRecovery("", recoveredFromLast = false, reason = "pure_chat")
        }
        val merged = appendEditHints(last, userText)
        return PromptRecovery(merged, recoveredFromLast = true, reason = "recovered_last_prompt")
    }

    /** Append coarse English outfit hints so a blank LLM edit still changes the render a bit. */
    fun appendEditHints(lastPrompt: String, userText: String): String {
        val t = userText
        val hints = mutableListOf<String>()
        fun has(vararg keys: String) = keys.any { t.contains(it, ignoreCase = true) }
        when {
            has("泳装", "泳裝", "比基尼") -> hints.addAll(listOf("swimsuit", "bikini"))
            has("校服") -> hints.add("school uniform")
            has("连衣裙", "連身裙", "洋装", "洋裝") -> hints.add("dress")
            has("旗袍") -> hints.add("china dress")
            has("和服", "浴衣") -> hints.add("kimono")
            has("西装", "西裝") -> hints.add("suit")
            has("婚纱", "婚紗") -> hints.add("wedding dress")
            has("睡衣", "睡袍") -> hints.add("pajamas")
            has("盔甲", "铠甲", "鎧甲") -> hints.add("armor")
            has("换装", "換裝", "换服装", "換服裝", "换个服装", "換個服裝",
                "换衣服", "換衣服", "换个衣服", "換個衣服", "换一身", "换一套", "换衣") ->
                hints.addAll(listOf("different outfit", "change of clothes"))
        }
        if (hints.isEmpty()) return lastPrompt.trim().trimEnd(',')
        val base = lastPrompt.trim().trimEnd(',')
        val extra = hints.filter { h -> !base.contains(h, ignoreCase = true) }
        if (extra.isEmpty()) return base
        return "$base, ${extra.joinToString(", ")}"
    }

    fun resolveShouldDraw(
        llmFlag: Boolean?,
        userText: String,
        promptNonEmpty: Boolean,
        preferDrawWhenAmbiguous: Boolean = true
    ): Pair<Boolean, String> {
        if (!promptNonEmpty) return false to "no_prompt"
        if (isExplicitVeto(userText)) return false to "user_veto"

        val strong = suggestsDraw(userText)
        val visual = looksLikeVisualPrompt(userText)
        val pureChat = looksLikePureChat(userText) && !strong && !visual

        if (preferDrawWhenAmbiguous) {
            if (pureChat) {
                return when (llmFlag) {
                    true -> true to "llm_true"
                    else -> false to "draw_mode_pure_chat"
                }
            }
            return when (llmFlag) {
                true -> true to "llm_true"
                false -> true to "draw_mode_override_llm_false"
                null -> true to "draw_mode_default_true"
            }
        }

        return when (llmFlag) {
            true -> true to "llm_true"
            false -> when {
                strong || visual -> true to "fallback_override_llm_false"
                else -> false to "llm_false"
            }
            null -> when {
                strong -> true to "fallback_keywords"
                visual -> true to "fallback_visual"
                else -> false to "default_false"
            }
        }
    }
}
