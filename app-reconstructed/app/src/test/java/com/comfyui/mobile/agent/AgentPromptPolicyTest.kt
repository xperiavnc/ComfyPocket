package com.comfyui.mobile.agent

import com.comfyui.mobile.data.AgentDrawIntent
import com.comfyui.mobile.data.AgentReplyParser
import org.junit.Assert.*
import org.junit.Test

class AgentPromptPolicyTest {
    @Test fun personaAndVisualRulesDoNotCompeteWithTransportContract() {
        assertFalse(AgentPromptPolicy.defaultPersona.contains("should_draw"))
        assertFalse(AgentPromptPolicy.defaultDrawRules.contains("只输出最终"))
        val prompt = AgentPromptPolicy.system("自定义人设", "自定义分镜", AgentPromptFormat.ANIMA, emptyList())
        assertTrue(prompt.contains("人设仅作用于 reply"))
        assertTrue(prompt.contains("只输出一个 JSON"))
        assertTrue(prompt.contains("自定义人设"))
        assertTrue(prompt.contains("自定义分镜"))
        assertTrue(prompt.contains("本轮提示词格式：Anima 混合"))
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
}
