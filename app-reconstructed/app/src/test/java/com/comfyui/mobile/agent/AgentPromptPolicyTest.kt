package com.comfyui.mobile.agent

import com.comfyui.mobile.data.AgentDrawIntent
import com.comfyui.mobile.data.AgentReplyParser
import org.junit.Assert.*
import org.junit.Test

class AgentPromptPolicyTest {
    @Test fun transportRetainsActionsAndAliasesWithoutBuiltInPersonalityOrComposition() {
        val system = AgentPromptPolicy.system("", "", emptyList())
        assertFalse(system.contains("Anima"))
        assertFalse(system.contains("ComfyPocket 的任务执行助手"))
        assertFalse(system.contains("双人"))
        assertTrue(system.contains("prompt_aliases"))
        assertTrue(system.contains("negative_prompt_aliases"))
        assertTrue(system.contains("description"))
    }

    @Test fun emptyRuleSlotsStayEmptyWithoutFallbackText() {
        val system = AgentPromptPolicy.system("", "", emptyList())
        assertFalse(system.contains("用户导入"))
        assertFalse(AgentPromptPolicy.chat("").contains("用户导入"))
        assertTrue(system.contains("只输出一个 JSON"))
        assertTrue(system.contains("negative_prompt"))
        assertFalse(system.contains("generation.execute"))
        assertFalse(system.contains("vision.processOne"))
    }
    @Test fun onlyTheModelChoosesWhenToAskAndCandidatesDoNotMandateQuestions() {
        val system = AgentPromptPolicy.system("", "", emptyList())
        assertTrue(system.contains("由你自行选择"))
        assertTrue(system.contains("普通文字问题不触发表单"))
        assertFalse(system.contains("必须另行询问"))
        assertFalse(system.contains("必须用 needs_clarification=true"))
        assertTrue(AgentPromptPolicy.chat("").contains("自行选择 action=\"ask_user\""))
    }
    @Test fun personaAndVisualRulesDoNotCompeteWithTransportContract() {
        val prompt = AgentPromptPolicy.system("自定义人设", "自定义分镜", emptyList())
        assertTrue(prompt.contains("用户导入的对话规则"))
        assertTrue(prompt.contains("只输出一个 JSON"))
        assertTrue(prompt.contains("自定义人设"))
        assertTrue(prompt.contains("自定义分镜"))
        assertFalse(prompt.contains("【本轮提示词格式"))
    }
    @Test fun animaPreservesBindingSentencesPunctuationAndQuotedText() {
        val raw = """{"reply":"好","prompt":"2girls, from front\nOn the left, Mira with blue hair is smiling. On the right, Lina holds a sign reading \"你好\".","should_draw":true}"""
        val prompt = AgentReplyParser.parse(raw, preserveNaturalLanguage = true).prompt
        assertTrue(prompt.contains("On the left, Mira"))
        assertTrue(prompt.contains("smiling. On the right"))
        assertTrue(prompt.contains("\"你好\""))
        assertTrue(prompt.contains("\n"))
    }
    @Test fun modelSyntaxAndEscapedParenthesesSurviveTagParsing() {
        val raw = """{"reply":"好","prompt":"(blue eyes:1.2), <lora:custom:0.8>, [red:blue:0.5], {day|night}, name \\(series\\), __custom_trigger__"}"""
        val prompt = AgentReplyParser.parse(raw).prompt
        assertEquals("(blue eyes:1.2), <lora:custom:0.8>, [red:blue:0.5], {day|night}, name \\(series\\), __custom_trigger__", prompt)
    }
    @Test fun reversePromptOnlyDoesNotTriggerRenderingButExplicitFollowupCan() {
        assertTrue(AgentDrawIntent.isExplicitVeto("反推这张图的提示词"))
        assertFalse(AgentDrawIntent.isExplicitVeto("反推后再出图"))
    }

    @Test fun outputContractRequiresSeparateNegativeEvenWithCustomRules() {
        val system = AgentPromptPolicy.system("自定义", "只输出正向 tags", emptyList())
        assertTrue(system.contains("negative_prompt（最终反向提示词字符串）"))
        assertTrue(system.contains("每次同时输出正向与反向"))
        assertTrue(system.contains("不要把反向写进 prompt"))
        assertTrue(system.contains("\"negative_prompt\""))
    }

    @Test fun negativePromptIsNotImageReversePromptingOrAnAutomaticDrawVeto() {
        assertFalse(AgentDrawIntent.isExplicitVeto("画一只猫，反向提示词添加 blurry"))
        assertFalse(AgentDrawIntent.isExplicitVeto("改一下反向提示词，然后出图"))
        assertTrue(AgentDrawIntent.isExplicitVeto("只要反向提示词"))
        assertTrue(AgentDrawIntent.isExplicitVeto("只要负向提示词"))
        assertTrue(AgentDrawIntent.isExplicitVeto("反推这张图片"))
    }

    @Test fun uploadedRulesAreInsertedOnceWithoutRewritingXmlOrPunctuation() {
        val rules = "<instruction>\r\n只使用用户定义的格式，保留 \"原文\"。\r\n</instruction>\n"
        val system = AgentPromptPolicy.system("", rules, emptyList())
        assertTrue(system.contains(rules))
        assertEquals(1, Regex("<instruction>").findAll(system).count())
        assertFalse(AgentPromptPolicy.chat("").contains(rules))
    }
}
