package com.comfyui.mobile.agent

import com.comfyui.mobile.model.AgentCharacterSelection
import com.comfyui.mobile.model.AgentChatMessage
import com.comfyui.mobile.model.CharacterLore
import org.junit.Assert.*
import org.junit.Test

class AgentCharacterRemovalTest {
    private val kuro = CharacterLore("kuro", "小黑", "kuro, cat ears, hairclip, gradient hair")
    private val previous = listOf(
        AgentChatMessage("u", "user", "画小黑"),
        AgentChatMessage("a", "assistant", "好", expandedPrompt = "kuro",
            characterSelections = listOf(AgentCharacterSelection(kuro.id))))

    private fun resolve(text: String) = AgentCharacterPolicy.resolve(listOf(kuro), text, previous)

    @Test fun removalPhraseAfterTheNameDropsTheCharacter() {
        for (text in listOf("小黑相关全部撤掉，换成铃兰", "把小黑去掉", "小黑不要了"))
            assertTrue(text, resolve(text).characters.isEmpty())
        // Removing a garment keeps the character.
        assertEquals(1, resolve("小黑去掉帽子").characters.size)
        assertEquals(1, resolve("小黑的衣服去掉").characters.size)
    }

    @Test fun carriedCharacterNegatedByTheModelIsNotCompletedBackIn() {
        val context = resolve("保持动作，换成铃兰")
        assertEquals(1, context.characters.size)
        val result = AgentCharacterPolicy.complete(AgentReply("好", "1girl, fox tail",
            negativePrompt = "kuro, cat ears, hairclip, gradient hair"), context, "保持动作，换成铃兰")
        assertEquals("1girl, fox tail", result.prompt)
        assertTrue(result.characterSelections!!.isEmpty())
        assertTrue(result.characterStatus!!.contains("撤下 小黑"))
        assertTrue(AgentNegativePromptPolicy.conflicts(result.prompt, result.negativePrompt).isEmpty())
    }

    @Test fun namedCharacterStillCompletesWhenOnlyOneWordIsNegated() {
        val context = resolve("画小黑")
        val result = AgentCharacterPolicy.complete(AgentReply("好", "1girl",
            negativePrompt = "hairclip"), context, "画小黑")
        assertTrue(result.prompt.contains("kuro"))
        assertEquals(listOf("kuro"), result.characterSelections!!.map { it.loreId })
    }
}
