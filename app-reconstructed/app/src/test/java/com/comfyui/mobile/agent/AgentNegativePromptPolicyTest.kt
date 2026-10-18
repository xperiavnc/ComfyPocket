package com.comfyui.mobile.agent

import org.junit.Assert.*
import org.junit.Test

class AgentNegativePromptPolicyTest {
    @Test fun detectsWholeTagsAcrossCaseUnderscoresAndSimpleAttentionWeights() {
        assertEquals(listOf("red_eyes", "CAT"),
            AgentNegativePromptPolicy.conflicts("cat, (Red Eyes:1.2), rain", "blurry, red_eyes, CAT, cat"))
    }

    @Test fun plainAttentionAndNewlineListsAlsoWorkWithoutRewritingOriginalPieces() {
        assertEquals(listOf("(red hair:0.8)", "blue_eyes"),
            AgentNegativePromptPolicy.conflicts("((red_hair))\nBLUE EYES", "(red hair:0.8)，blue_eyes"))
    }

    @Test fun substringSynonymsAndDifferentAttributesAreNotTreatedAsExactConflicts() {
        assertTrue(AgentNegativePromptPolicy.conflicts(
            "soft focus, film grain, red hair, female, cat ears",
            "blurry, grainy, red eyes, male, cat").isEmpty())
    }

    @Test fun quotedImageTextDoesNotBecomeATag() {
        assertTrue(AgentNegativePromptPolicy.conflicts(
            """sign reading "cat, dog", "red eyes" """, "cat, dog, red eyes").isEmpty())
    }

    @Test fun modelMarkupSchedulesAndWildcardSyntaxRemainUntouched() {
        val syntax = """<lora:custom:0.8>, [red:blue:0.5], {day|night}, __custom_trigger__, embedding:known, name \(series\)"""
        // Custom trigger tokens have special meaning; diagnostics must not interpret their words.
        assertTrue(AgentNegativePromptPolicy.conflicts(syntax, "red, blue, day, night, custom trigger").isEmpty())
    }

    @Test fun malformedOrTruncatedBracketListsDoNotProduceSpuriousConflicts() {
        assertTrue(AgentNegativePromptPolicy.conflicts("cat, (red eyes", "cat").isEmpty())
        assertTrue(AgentNegativePromptPolicy.conflicts("cat", """cat, "unfinished""").isEmpty())
        assertTrue(AgentNegativePromptPolicy.conflicts("cat, [red)", "cat").isEmpty())
    }

    @Test fun zeroWeightedTagIsNotConsideredAPositiveRequirement() {
        assertTrue(AgentNegativePromptPolicy.conflicts("(red eyes:0), cat", "red eyes").isEmpty())
    }

    @Test fun nullAndEmptyAreValidAndDoNotCreateAFallbackTemplate() {
        assertTrue(AgentNegativePromptPolicy.conflicts("cat", null).isEmpty())
        assertTrue(AgentNegativePromptPolicy.conflicts("cat", "").isEmpty())
        assertTrue(AgentNegativePromptPolicy.conflicts("", "cat").isEmpty())
    }

    @Test fun requiresEvidenceWithoutBanningIndependentNegativeComposition() {
        val system = AgentPromptPolicy.system("custom persona", "custom draw rules", emptyList())
        assertTrue(system.contains("字段必填不等于内容必须非空"))
        assertTrue(system.contains("每轮独立分析反向"))
        assertTrue(system.contains("用户本轮明确的排除"))
        assertTrue(system.contains("不凭空编造 embedding"))
        assertTrue(system.contains("历史 AI 反向只是候选"))
        assertFalse(system.contains("你需要主动编写完整 negative_prompt"))
    }
}
