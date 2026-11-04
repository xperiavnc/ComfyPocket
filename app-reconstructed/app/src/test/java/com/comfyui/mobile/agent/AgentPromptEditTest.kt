package com.comfyui.mobile.agent

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** edit_prompt: change the tags the user named, keep every other tag byte for byte. */
class AgentPromptEditTest {

    private fun snapshot() = AgentWorkflowSnapshot(
        mapOf("3" to mutableMapOf<String, Any>("class_type" to "KSampler",
            "inputs" to mutableMapOf<String, Any>("steps" to 20))),
        listOf(AgentFieldSpec("3", "steps", "KSampler", AgentFieldKind.INTEGER, min = 1.0, max = 60.0)))

    private fun toolbox(currentPrompt: String = "") =
        AgentDrawToolbox(snapshot(), currentPrompt = currentPrompt)

    private fun args(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    @Test fun editKeepsEveryTagItWasNotAskedAbout() = runBlocking {
        val box = toolbox("1girl, rain, umbrella, night")
        val result = box.call(AgentDrawToolbox.EDIT_PROMPT, args(
            """{"remove":["rain"],"replace":[{"from":"night","to":"snowy night"}],"add":["wet street"]}"""))
        assertTrue(result.error, result.ok)
        assertEquals("1girl, umbrella, snowy night, wet street", box.prompt)
        val data = result.data as Map<*, *>
        assertEquals("1girl, umbrella, snowy night, wet street", data["prompt"])
        assertEquals(listOf("rain"), data["removed"])
        assertEquals(listOf("wet street"), data["added"])
        assertEquals(listOf("night → snowy night"), data["replaced"])
    }

    @Test fun editCanPrependAndDeduplicatesWhatIsAlreadyThere() = runBlocking {
        val box = toolbox("1girl, rain")
        val result = box.call(AgentDrawToolbox.EDIT_PROMPT,
            args("""{"add":["rain","masterpiece"],"position":"prepend"}"""))
        assertTrue(result.error, result.ok)
        assertEquals("masterpiece, 1girl, rain", box.prompt)
        val data = result.data as Map<*, *>
        assertEquals(listOf("rain"), data["already_present"])
        assertEquals(listOf("masterpiece"), data["added"])
    }

    @Test fun editPrefersTheBaseArgumentOverTheTurnPrompt() = runBlocking {
        val box = toolbox("1girl, rain")
        val result = box.call(AgentDrawToolbox.EDIT_PROMPT, args("""{"base":"2girls, snow","add":["night"]}"""))
        assertTrue(result.error, result.ok)
        assertEquals("2girls, snow, night", box.prompt)
    }

    @Test fun editRefusesToLeaveAnEmptyPromptAndKeepsTheOldOne() = runBlocking {
        val box = toolbox("rain")
        val result = box.call(AgentDrawToolbox.EDIT_PROMPT, args("""{"remove":["rain"]}"""))
        assertFalse(result.ok)
        val error = result.error.orEmpty()
        assertTrue(error, error.contains("改完提示词为空"))
        assertNull(box.prompt)
    }

    @Test fun editWithNothingToEditAsksForAPromptFirst() = runBlocking {
        val result = toolbox().call(AgentDrawToolbox.EDIT_PROMPT, args("""{"add":["rain"]}"""))
        assertFalse(result.ok)
        val error = result.error.orEmpty()
        assertTrue(error, error.contains("还没有可改的提示词"))
    }

    @Test fun editWithoutAnyOperationIsRejected() = runBlocking {
        val result = toolbox("rain").call(AgentDrawToolbox.EDIT_PROMPT, args("{}"))
        assertFalse(result.ok)
        val error = result.error.orEmpty()
        assertTrue(error, error.contains("至少要有一项"))
    }

    @Test fun editReportsTagsItCouldNotFind() = runBlocking {
        val box = toolbox("1girl, rain")
        val result = box.call(AgentDrawToolbox.EDIT_PROMPT, args("""{"remove":["snow"]}"""))
        assertTrue(result.error, result.ok)
        assertEquals("1girl, rain", box.prompt)
        assertEquals(listOf("snow"), (result.data as Map<*, *>)["missing"])
    }
}
