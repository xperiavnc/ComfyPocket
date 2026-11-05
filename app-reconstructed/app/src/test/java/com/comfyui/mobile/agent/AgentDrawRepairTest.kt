package com.comfyui.mobile.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDrawRepairTest {
    @Test fun missingPromptNamesTheFieldAndShowsTheEnvelopeToReturn() {
        val message = AgentDrawRepair.missingPrompt(null)
        assertTrue(message, message.contains("prompt"))
        assertTrue(message, message.contains(AgentDrawRepair.SHAPE))
        assertTrue(message, message.contains("should_draw"))
    }

    @Test fun theParseReasonIsKeptSoTheModelCanSeeWhatWasWrong() {
        assertTrue(AgentDrawRepair.missingPrompt("JSON 中没有有效的 prompt").contains("JSON 中没有有效的 prompt"))
    }

    @Test fun aBlankReasonDoesNotLeaveEmptyBrackets() {
        assertFalse(AgentDrawRepair.missingPrompt("  ").contains("（）"))
    }
}
