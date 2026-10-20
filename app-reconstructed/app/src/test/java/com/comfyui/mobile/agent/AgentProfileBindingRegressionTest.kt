package com.comfyui.mobile.agent

import com.comfyui.mobile.model.AgentCharacterSelection
import com.comfyui.mobile.model.AgentChatMessage
import com.comfyui.mobile.model.CharacterLore
import com.comfyui.mobile.model.CharacterOutfit
import org.junit.Assert.*
import org.junit.Test

class AgentProfileBindingRegressionTest {
    private val lore = CharacterLore("registered", "小黑", "user_identity, white hair, red eyes",
        outfits = listOf(CharacterOutfit("school", "校服", "user_uniform, sailor collar")))

    @Test fun originalProfileDrawingWordsAreNotMerelyOptionalWhenFixedFieldWasNeverFilled() {
        val text = "帮我生成小黑图片，校服"
        val context = AgentCharacterPolicy.resolve(listOf(lore), text, emptyList())
        val reply = AgentCharacterPolicy.complete(
            AgentReply("好", "portrait, guessed_identity", shouldDraw = true), context, text)
        assertTrue("The saved original identity trigger must reach the actual prompt", reply.prompt.contains("user_identity"))
        assertTrue(reply.prompt.contains("white hair"))
        assertTrue(reply.prompt.contains("red eyes"))
        assertTrue(reply.prompt.contains("user_uniform"))
        assertFalse(reply.prompt.contains("小黑"))
        println("PROFILE_BINDING actual=${reply.prompt}")
    }

    @Test fun clearingOnlyTheNegativePreservesTheRegisteredCharacterAndOutfit() {
        val history = listOf(AgentChatMessage("u", "user", "画小黑，校服"),
            AgentChatMessage("a", "assistant", "好", expandedPrompt = "portrait",
                characterSelections = listOf(AgentCharacterSelection(lore.id, "school"))))
        val context = AgentCharacterPolicy.resolve(listOf(lore), "清空反向提示词", history)
        assertEquals(lore.id, context.characters.singleOrNull()?.lore?.id)
        assertEquals("school", context.characters.single().outfit?.id)
        println("NEGATIVE_ONLY_EDIT character=${context.characters.single().lore.id} outfit=${context.characters.single().outfit?.id}")
    }

    @Test fun aUserGroundedAppearanceOverrideSurvivesTheNextBackgroundEdit() {
        val text = "画小黑，white hair 改为 blue hair，校服"
        val context = AgentCharacterPolicy.resolve(listOf(lore), text, emptyList())
        val reply = AgentCharacterPolicy.complete(AgentReply("好", "portrait, blue hair"), context, text,
            listOf(AgentProfileTagOverride(lore.id, "white hair", "white hair 改为 blue hair")))
        assertTrue(reply.prompt.contains("user_identity"))
        assertFalse(reply.prompt.contains("white hair"))
        val history = listOf(AgentChatMessage("u", "user", text),
            AgentChatMessage("a", "assistant", "好", expandedPrompt = reply.prompt, characterSelections = reply.characterSelections))
        val next = AgentCharacterPolicy.complete(AgentReply("好", "portrait, blue hair, rain"),
            AgentCharacterPolicy.resolve(listOf(lore), "保持人物，换背景", history), "保持人物，换背景")
        assertTrue(next.prompt.contains("user_identity"))
        assertFalse(next.prompt.contains("white hair"))
        assertEquals(listOf("white hair"), next.characterSelections!!.single().overriddenProfileTags)
    }

    @Test fun anInventedQuoteOrUnrelatedProfileCannotRemoveSavedDrawingWords() {
        val text = "画小黑，校服"
        val context = AgentCharacterPolicy.resolve(listOf(lore), text, emptyList())
        val result = AgentCharacterPolicy.complete(AgentReply("好", "portrait"), context, text, listOf(
            AgentProfileTagOverride(lore.id, "user_identity", "模型推测另一个作品人物"),
            AgentProfileTagOverride("another", "white hair", text)))
        assertTrue(result.prompt.contains("user_identity"))
        assertTrue(result.prompt.contains("white hair"))
        assertNull(result.characterSelections!!.single().overriddenProfileTags)
    }

    @Test fun optionalFixedFieldStillLetsTheUserChooseWhichWordsAreMandatory() {
        val explicit = lore.copy(fixedTriggerWords = "user_identity")
        val text = "画小黑，校服"
        val result = AgentCharacterPolicy.complete(AgentReply("好", "portrait, blue hair"),
            AgentCharacterPolicy.resolve(listOf(explicit), text, emptyList()), text)
        assertTrue(result.prompt.contains("user_identity"))
        assertFalse(result.prompt.contains("white hair"))
        assertFalse(result.prompt.contains("red eyes"))
    }

    @Test fun aLongerGuessedTokenDoesNotSatisfyTheExactSavedTrigger() {
        val text = "画小黑，校服"
        val result = AgentCharacterPolicy.complete(AgentReply("好", "portrait, user_identity_variant"),
            AgentCharacterPolicy.resolve(listOf(lore), text, emptyList()), text)
        assertTrue(AgentCharacterPolicy.pieces(result.prompt).contains("user_identity"))
    }
}
