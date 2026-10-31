package com.comfyui.mobile.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentActionParsingTest {
    @Test fun plainProseIsNotAnAction() {
        assertNull(AgentInteractionProtocol.objectValue("我先想一想，再决定。"))
    }

    @Test fun missingSummaryFallsBackInsteadOfFailing() {
        val obj = AgentInteractionProtocol.objectValue("{\"action\":\"continue_task\"}")!!
        assertEquals("按用户本轮任务继续", AgentInteractionProtocol.plan(obj).summary)
    }
}
