package com.comfyui.mobile.agent

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentPromptBindingTest {
    private val graph: Map<String, Any> = Gson().fromJson(
        """{"1":{"class_type":"InlinePrompt","inputs":{"positive":"cat","negative":"blurry","linked":["2",0],"number":1}}}""",
        object : TypeToken<Map<String, Any>>() {}.type)
    private fun explicit(key: String) = AgentPromptBinding(PromptBindingMode.EXPLICIT, "1", key)
    private fun prefs() = RuntimeEnvironment.getApplication().getSharedPreferences("binding-test", Context.MODE_PRIVATE)
        .also { it.edit().clear().commit() }

    @Test fun sameNodeDifferentTextFieldsAreIndependent() {
        val positive = explicit("positive").explicitTarget(graph)
        assertEquals("1" to "positive", positive)
        val negative = AgentPromptBindings.negativeTargets(graph, explicit("negative"), positive)
        assertEquals(listOf("1" to "negative"), negative)
        assertEquals("blurry", AgentPromptBindings.text(graph, negative))
    }
    @Test fun sameFieldIsRejectedInsteadOfOverwritingPositive() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentPromptBindings.negativeTargets(graph, explicit("positive"), "1" to "positive")
        }
    }
    @Test fun linksNumbersAndRemovedFieldsAreNotConvertedToText() {
        for (key in listOf("linked", "number", "removed")) {
            assertThrows(IllegalArgumentException::class.java) { explicit(key).explicitTarget(graph) }
        }
    }
    @Test fun keepModeDoesNotPatchNegativeFields() {
        assertTrue(AgentPromptBindings.negativeTargets(graph, AgentPromptBinding(PromptBindingMode.KEEP), "1" to "positive").isEmpty())
    }
    @Test fun scopedBindingsPersistWithoutLeakingAcrossWorkflowsOrServers() {
        val store = AgentPromptBindingStore(prefs())
        store.save("local", "saved", "one", false, explicit("positive"))
        store.save("local", "saved", "one", true, explicit("negative"))
        assertEquals(explicit("positive"), store.load("local", "saved", "one", false))
        assertEquals(explicit("negative"), store.load("local", "saved", "one", true))
        assertEquals(PromptBindingMode.AUTO, store.load("cloud@cn", "saved", "one", true).mode)
        assertEquals(PromptBindingMode.AUTO, store.load("local", "saved", "two", true).mode)
        assertEquals(PromptBindingMode.AUTO, store.load("local", "ai_app", "one", true).mode)
    }
    @Test fun oldNodeOnlyConfigurationMigratesAndNewAutoChoiceOverridesIt() {
        val prefs = prefs()
        prefs.edit().putString("agent_target_node_local_saved_one", "1").commit()
        val store = AgentPromptBindingStore(prefs)
        assertEquals(AgentPromptBinding(PromptBindingMode.EXPLICIT, "1"), store.load("local", "saved", "one", false))
        store.save("local", "saved", "one", false, AgentPromptBinding())
        assertEquals(PromptBindingMode.AUTO, store.load("local", "saved", "one", false).mode)
    }
    @Test fun invalidStorageDoesNotCrashOrApplyAnUnknownBindingMode() {
        val prefs = prefs()
        prefs.edit().putString("agent_prompt_binding_negative__local_saved_one",
            """{"mode":"REMOVED","nodeId":"1","key":"negative"}""").commit()
        assertEquals(PromptBindingMode.AUTO, AgentPromptBindingStore(prefs).load("local", "saved", "one", true).mode)
    }
}
