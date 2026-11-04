package com.comfyui.mobile.agent

import com.comfyui.mobile.model.AgentChatMessage
import org.junit.Assert.*
import org.junit.Test

/** The follow-up edit window: how much of what was already drawn still reaches the model. */
class AgentHistoryWindowTest {

    private fun user(text: String) = AgentChatMessage("u-$text", "user", text)

    private fun draw(id: String, prompt: String, negative: String? = null) =
        AgentChatMessage(id, "assistant", "画好了", expandedPrompt = prompt, expandedNegativePrompt = negative)

    private fun chat(id: String) = AgentChatMessage(id, "assistant", "随便聊聊")

    @Test fun theLatestThreeDrawsKeepTheirPrompt() {
        val history = listOf(
            user("画一张"), draw("a", "rain, night"),
            user("改一下"), draw("b", "snow, night"),
            user("再改"), draw("c", "snow, day"),
            user("再来"), draw("d", "snow, dusk"))
        assertEquals(setOf(3, 5, 7), AgentHistoryWindow.promptCarriers(history))
    }

    @Test fun chatRepliesDoNotPushADrawOutOfTheWindow() {
        val history = listOf(draw("a", "rain"), user("谢谢"), chat("t"), user("那张再改改"), chat("u"))
        assertEquals(setOf(0), AgentHistoryWindow.promptCarriers(history))
    }

    @Test fun aReplyThatDidNotDrawCarriesNothing() {
        val history = listOf(draw("a", "rain"), chat("t"), draw("b", ""))
        assertEquals(setOf(0), AgentHistoryWindow.promptCarriers(history))
    }

    @Test fun anOlderDrawKeepsItsNegativePromptToo() {
        val history = listOf(draw("a", "rain", "blurry"), draw("b", "snow", "lowres"))
        assertEquals(setOf(0, 1), AgentHistoryWindow.promptCarriers(history))
    }

    @Test fun nothingToCarryInAnEmptyConversation() {
        assertTrue(AgentHistoryWindow.promptCarriers(emptyList()).isEmpty())
        assertTrue(AgentHistoryWindow.promptCarriers(listOf(user("画一张"))).isEmpty())
    }
}
