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

    @Test fun eachProfileRoundTripsWithoutSharingTier() {
        val gson = Gson()
        val profiles = listOf(LlmProfile(id = "one", reasoningEffort = "max"), LlmProfile(id = "two", reasoningEffort = "xhigh"))
        val restored = gson.fromJson(gson.toJson(profiles), Array<LlmProfile>::class.java)
        assertEquals("max", restored[0].reasoningEffort)
        assertEquals("xhigh", restored[1].reasoningEffort)
    }
}
