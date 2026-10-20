package com.comfyui.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentReplyParserTest {

    @Test fun askUserActionWinsOverMisleadingPromptAndFalseClarificationFlag() {
        val result = AgentReplyParser.parse(
            """{"action":"ask_user","reply":"先问两个问题","prompt":"must_not_draw","should_draw":true,"needs_clarification":false,"questions":[{"id":"outfit","prompt":"哪种服装？","kind":"multiple_choice","allow_multiple":false,"options":[{"id":"a","label":"便服"},{"id":"b","label":"礼服"}]},{"id":"details","prompt":"补充细节？","kind":"free_text","placeholder":"自由填写"}]}""")
        assertEquals("", result.prompt)
        assertNull(result.negativePrompt)
        assertEquals(false, result.shouldDraw)
        assertEquals(com.comfyui.mobile.agent.AgentAction.ASK_USER, result.action)
        val questions = result.clarification!!.questions!!
        assertEquals(2, questions.size)
        assertEquals(listOf("a", "b"), questions[0].options!!.map { it.id })
        assertFalse(questions[0].allowMultiple)
        assertEquals("free_text", questions[1].kind)
        assertEquals("自由填写", questions[1].placeholder)
    }

    @Test fun askUserQuestionAliasAndMultipleSelectionAreAccepted() {
        val result = AgentReplyParser.parse(
            """{"action":"ask_user_question","questions":[{"id":"details","question":"保留哪些？","type":"multiple_choice","multi_select":true,"options":[{"id":"a","label":"帽子"},{"id":"b","label":"手套"}]}]}""")
        assertTrue(result.clarification!!.questions!!.single().allowMultiple)
        assertEquals("保留哪些？", result.clarification.questions!!.single().prompt)
    }

    @Test fun truncatedAskActionPausesEvenWithACompletePromptField() {
        for (raw in listOf(
            """{"action":"ask_user","reply":"请确认","prompt":"must_not_draw","questions":[{"id":"q1","prompt":"未完成""",
            """{"needs_clarification":true,"reply":"请先确认","prompt":"must_not_draw","questions":[""",
            """{"reply":"请确认","prompt":"must_not_draw","action":"ask_user"""
        )) {
            val result = AgentReplyParser.parse(raw)
            assertEquals("", result.prompt)
            assertEquals(false, result.shouldDraw)
            assertTrue(result.clarification != null)
        }
    }

    @Test fun plainWaitingReplyKeepsEveryLineAndCreatesAFreeAnswer() {
        val reply = "可以，请先确认 3 点：\n1）保留哪套服装？\n2）构图要全身还是半身？\n3）背景用什么？你确认后我再改提示词。"
        val result = AgentReplyParser.parse(reply)
        assertEquals(reply, result.reply)
        assertEquals(reply, result.clarification!!.questions!!.single().prompt)
        assertEquals(false, result.shouldDraw)
    }

    @Test fun jsonReplyThatAsksToWaitAlsoPausesInsteadOfApplyingItsPrompt() {
        val result = AgentReplyParser.parse(
            """{"reply":"等待你回答后再出图。","prompt":"must_not_apply","should_draw":true}""")
        assertTrue(result.clarification != null)
        assertEquals("", result.prompt)
        assertEquals(false, result.shouldDraw)
    }

    @Test fun imageTextQuestionsAndRhetoricalRepliesDoNotBecomeInteractiveForms() {
        val result = AgentReplyParser.parse(
            """{"reply":"在牌子上写出提问。","prompt":"A sign reading \"请先确认：需要什么？\"","should_draw":true}""",
            preserveNaturalLanguage = true)
        assertNull(result.clarification)
        assertEquals(true, result.shouldDraw)
        assertTrue(result.prompt.contains("请先确认"))
        assertNull(AgentReplyParser.parse("""{"reply":"为什么不试试雨夜？","prompt":"rain"}""").clarification)
    }

    @Test fun respondActionNeverAppliesAnAccidentallyIncludedPrompt() {
        val result = AgentReplyParser.parse(
            """{"action":"respond","reply":"这是配置说明。","prompt":"must_not_apply","negative_prompt":"must_not_apply","should_draw":true}""")
        assertEquals("", result.prompt)
        assertNull(result.negativePrompt)
        assertEquals(false, result.shouldDraw)
        assertEquals(com.comfyui.mobile.agent.AgentAction.RESPOND, result.action)
    }

    @Test fun duplicateQuestionIdsAreNormalizedAndEmptyOptionsBecomeFreeText() {
        val result = AgentReplyParser.parse(
            """{"action":"ask_user","questions":[{"id":"same","prompt":"第一题","kind":"free_text"},{"id":"same","prompt":"第二题","kind":"multiple_choice","options":[null,{}]}]}""")
        val questions = result.clarification!!.questions!!
        assertEquals(2, questions.map { it.id }.distinct().size)
        assertEquals("free_text", questions[1].kind)
    }

    private fun assertNoJson(prompt: String) {
        for (bad in listOf("{", "}", "\"", "prompt", "reply", "should_draw", "\":")) {
            assertFalse("prompt leaked '$bad': $prompt", prompt.contains(bad))
        }
    }

    @Test fun plainJson() {
        val r = AgentReplyParser.parse("""{"reply":"好的","prompt":"1girl, red hair","should_draw":true}""")
        assertEquals("好的", r.reply)
        assertEquals("1girl, red hair", r.prompt)
        assertEquals(true, r.shouldDraw)
    }

    @Test fun fencedJson() {
        val raw = "```json\n{\"reply\":\"换校服\",\"prompt\":\"1girl, school uniform\",\"should_draw\":true}\n```"
        val r = AgentReplyParser.parse(raw)
        assertEquals("1girl, school uniform", r.prompt)
        assertEquals("换校服", r.reply)
    }

    @Test fun trailingComma() {
        val r = AgentReplyParser.parse("""{"reply":"ok","prompt":"1girl, smile","should_draw":false,}""")
        assertEquals("1girl, smile", r.prompt)
        assertEquals(false, r.shouldDraw)
    }

    @Test fun reasoningBeforeJsonWithBraces() {
        val raw = "我先想想 {构图} 和光线……\n{\"reply\":\"来啦\",\"prompt\":\"1girl, sunset\",\"should_draw\":true}"
        val r = AgentReplyParser.parse(raw)
        assertEquals("1girl, sunset", r.prompt)
        assertEquals("来啦", r.reply)
    }

    @Test fun nestedQuotesAndBracesInsideStrings() {
        val raw = """{"reply":"他说\"好\"","prompt":"1girl, holding sign, text \"{hi}\"","should_draw":true}"""
        val r = AgentReplyParser.parse(raw)
        assertEquals("他说\"好\"", r.reply)
        assertTrue(r.prompt.startsWith("1girl, holding sign"))
        assertNoJson(r.prompt)
    }

    @Test fun truncatedAfterPrompt() {
        val raw = """{"reply":"好","prompt":"1girl, long hair, blue eyes","should_dr"""
        val r = AgentReplyParser.parse(raw)
        assertEquals("1girl, long hair, blue eyes", r.prompt)
        assertNull(r.shouldDraw)
    }

    @Test fun truncatedInsidePromptGivesNoPrompt() {
        val r = AgentReplyParser.parse("""{"reply":"好","prompt":"1girl, long ha""")
        assertEquals("", r.prompt)
        assertTrue(r.error != null)
    }

    @Test fun leakedKeyFragmentsAreStripped() {
        val p = AgentReplyParser.sanitizePrompt("""{, prompt": "1girl, red dress", should_draw": true}""")
        assertEquals("1girl, red dress", p)
    }

    @Test fun multipleObjectsPreferPromptOne() {
        val raw = """{"note":"x"} {"reply":"嗯","prompt":"cat, garden","should_draw":"true"}"""
        val r = AgentReplyParser.parse(raw)
        assertEquals("cat, garden", r.prompt)
        assertEquals(true, r.shouldDraw)
    }

    @Test fun smartQuotes() {
        val r = AgentReplyParser.parse("{\u201creply\u201d:\u201c好\u201d,\u201cprompt\u201d:\u201c1girl, hat\u201d}")
        assertEquals("1girl, hat", r.prompt)
    }

    @Test fun promptAsArray() {
        val r = AgentReplyParser.parse("""{"reply":"好","prompt":["1girl","smile"]}""")
        assertEquals("1girl, smile", r.prompt)
    }

    @Test fun labelledPlainText() {
        val r = AgentReplyParser.parse("回复：好的呀\nprompt: 1girl, beach, swimsuit")
        assertEquals("1girl, beach, swimsuit", r.prompt)
        assertEquals("好的呀", r.reply)
    }

    @Test fun chineseOnlyNeverBecomesPrompt() {
        val r = AgentReplyParser.parse("这个问题我来解释一下。")
        assertEquals("", r.prompt)
    }

    @Test fun replyThatIsJsonIsDropped() {
        val r = AgentReplyParser.parse("""{"reply":"{\"prompt\":\"x\"}","prompt":"1girl"}""")
        assertEquals("", r.reply)
        assertEquals("1girl", r.prompt)
    }

    @Test fun positiveAndNegativeAreParsedIndependently() {
        val result = AgentReplyParser.parse("""{"reply":"好","prompt":"cat, rain","negative_prompt":"blurry, (extra limbs:1.2), <embedding:custom>","should_draw":true}""")
        assertEquals("cat, rain", result.prompt)
        assertEquals("blurry, (extra limbs:1.2), <embedding:custom>", result.negativePrompt)
        assertFalse(result.prompt.contains("blurry"))
    }

    @Test fun omittedInvalidAndExplicitEmptyNegativeHaveDifferentMeanings() {
        assertNull(AgentReplyParser.parse("""{"prompt":"cat"}""").negativePrompt)
        for (value in listOf("null", "false", "123", """{"text":"blurry"}""", """"纯中文无效标签"""")) {
            assertNull(AgentReplyParser.parse("""{"prompt":"cat","negative_prompt":$value}""").negativePrompt)
        }
        assertEquals("", AgentReplyParser.parse("""{"prompt":"cat","negative_prompt":""}""").negativePrompt)
        assertEquals("", AgentReplyParser.parse("""{"prompt":"cat","negative_prompt":[]}""").negativePrompt)
    }

    @Test fun negativeAliasesArraysAndNaturalLanguageKeepTheirSyntax() {
        val array = AgentReplyParser.parse("""{"positive":"cat","negativePrompt":["blurry","bad hands"]}""")
        assertEquals("blurry, bad hands", array.negativePrompt)
        val natural = AgentReplyParser.parse(
            """{"prompt":"A person holds a sign.","negative_prompt":"Avoid distorted letters in \"Hello\".\nKeep hands free of extra fingers."}""",
            preserveNaturalLanguage = true)
        assertEquals("Avoid distorted letters in \"Hello\".\nKeep hands free of extra fingers.", natural.negativePrompt)
    }

    @Test fun labelledSectionsDoNotAppendNegativeOrDrawFlagToPositive() {
        val parsed = AgentReplyParser.parse("回复：好的\n正向提示词：cat, rain\n反向提示词：blurry, bad hands\nshould_draw: true")
        assertEquals("好的", parsed.reply)
        assertEquals("cat, rain", parsed.prompt)
        assertEquals("blurry, bad hands", parsed.negativePrompt)
        assertEquals(true, parsed.shouldDraw)
        val reverseOrder = AgentReplyParser.parse("negative_prompt: blurry\nprompt: cat")
        assertEquals("cat", reverseOrder.prompt)
        assertEquals("blurry", reverseOrder.negativePrompt)
    }

    @Test fun truncatedNegativeIsNotMisinterpretedAsAnExplicitClear() {
        assertNull(AgentReplyParser.parse("""{"prompt":"cat","negative_prompt":"blur""").negativePrompt)
        assertEquals("blurry", AgentReplyParser.parse(
            """{"prompt":"cat","negative_prompt":"blurry","should_dra"""
        ).negativePrompt)
    }

    @Test fun clarificationIsParsedAsAChoiceWithoutDrawing() {
        val result = AgentReplyParser.parse(
            """{"reply":"词库里有多个角色版本。","prompt":"","negative_prompt":"","should_draw":false,"needs_clarification":true,"clarification_question":"使用哪种外貌？","clarification_options":["常服（casual）","节日服（festival）"]}"""
        )
        assertEquals("", result.prompt)
        assertEquals(false, result.shouldDraw)
        assertEquals("使用哪种外貌？", result.clarification?.question)
        assertEquals(listOf("常服（casual）", "节日服（festival）"), result.clarification?.options)
    }

    @Test fun explicitClarificationFlagStillPausesWhenModelOmitsQuestion() {
        val result = AgentReplyParser.parse(
            """{"reply":"选一套","prompt":"not_ready","should_draw":true,"needs_clarification":true,"clarification_options":["冬装","便服"]}"""
        )
        assertEquals("请先确认本轮要采用的提示词。", result.clarification?.question)
        assertEquals(listOf("冬装", "便服"), result.clarification?.options)
        assertNull(AgentReplyParser.parse("""{"prompt":"cat","question":"picture caption"}""").clarification)
    }
}
