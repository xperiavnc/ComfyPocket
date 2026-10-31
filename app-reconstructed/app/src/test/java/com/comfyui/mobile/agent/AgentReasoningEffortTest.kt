package com.comfyui.mobile.agent

import com.comfyui.mobile.model.LlmProfile
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class AgentReasoningEffortTest {
    @Test fun xhighAndMaxAreDistinctPersistedTiers() {
        assertEquals(AgentReasoningEffort.XHIGH, AgentReasoningEffort.fromStored("xhigh"))
        assertEquals(AgentReasoningEffort.MAX, AgentReasoningEffort.fromStored("max"))
        assertEquals(8, AgentReasoningEffort.entries.size)
    }

    @Test fun oldAndInvalidValuesKeepProviderDefaults() {
        val old = Gson().fromJson("""{"id":"old","model":"fixture"}""", LlmProfile::class.java)
        assertEquals(AgentReasoningEffort.DEFAULT, AgentReasoningEffort.fromStored(old.reasoningEffort))
        assertEquals(AgentReasoningEffort.DEFAULT, AgentReasoningEffort.fromStored("unsupported"))
        assertNull(AgentReasoningEffort.DEFAULT.wireValue)
    }

    @Test fun decisionStepNeverReasonsAboveLow() {
        assertEquals(AgentReasoningEffort.LOW, AgentReasoningEffort.forDecision(AgentReasoningEffort.DEFAULT))
        assertEquals(AgentReasoningEffort.LOW, AgentReasoningEffort.forDecision(AgentReasoningEffort.MAX))
        assertEquals(AgentReasoningEffort.NONE, AgentReasoningEffort.forDecision(AgentReasoningEffort.NONE))
        assertEquals(AgentReasoningEffort.MINIMAL, AgentReasoningEffort.forDecision(AgentReasoningEffort.MINIMAL))
    }

    @Test fun onlyReasoningParameterErrorsTriggerTheEffortFallback() {
        assertTrue(AgentReasoningEffort.rejectedBy(400, "Unrecognized request argument supplied: reasoning_effort"))
        assertTrue(AgentReasoningEffort.rejectedBy(422, "reasoning_effort: value must be one of high, medium"))
        assertFalse(AgentReasoningEffort.rejectedBy(401, "reasoning_effort unsupported"))
        assertFalse(AgentReasoningEffort.rejectedBy(400, "tools schema invalid"))
        assertFalse(AgentReasoningEffort.rejectedBy(400, null))
    }

    @Test fun eachProfileRoundTripsWithoutSharingTier() {
        val gson = Gson()
        val profiles = listOf(LlmProfile(id = "one", reasoningEffort = "max"), LlmProfile(id = "two", reasoningEffort = "xhigh"))
        val restored = gson.fromJson(gson.toJson(profiles), Array<LlmProfile>::class.java)
        assertEquals("max", restored[0].reasoningEffort)
        assertEquals("xhigh", restored[1].reasoningEffort)
    }
}
