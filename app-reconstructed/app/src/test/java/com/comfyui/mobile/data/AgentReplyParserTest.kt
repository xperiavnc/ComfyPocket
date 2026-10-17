package com.comfyui.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentReplyParserTest {

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
}
