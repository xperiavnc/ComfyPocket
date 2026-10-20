package com.comfyui.mobile.agent

import com.comfyui.mobile.model.CharacterLore
import com.comfyui.mobile.model.CharacterOutfit
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

/** The same fixture is also run against the preserved prior source for the change record. */
class AgentOutfitAliasBehaviorTest {
    @Test fun defaultMustExposeWardrobeAndNeverReplaceTheModelsRegisteredChoice() {
        val lore = CharacterLore("suzuran", "铃兰", "suzuran_token", fixedTriggerWords = "suzuran_token",
            outfits = listOf(
                CharacterOutfit("casual", "常服", "casual_token, shirt"),
                CharacterOutfit("formal", "礼服", "formal_token, dress")),
            defaultOutfitId = "casual")
        val context = AgentCharacterPolicy.resolve(listOf(lore), "画铃兰，服装你来选", emptyList())
        val gson = Gson()
        // JSON deserialization keeps this probe source-compatible with the prior release.
        val reply = gson.fromJson(
            """{"tip":"按礼服绘制","prompt":"portrait, formal_token","thinking":"","shouldDraw":true,"outfitSelections":[{"loreId":"suzuran","outfitId":"formal"}],"positiveAliases":[{"tag":"portrait","zh":"肖像"},{"tag":"formal_token","zh":"礼服触发词"},{"tag":"suzuran_token","zh":"角色触发词"},{"tag":"dress","zh":"连衣裙"}]}""",
            AgentReply::class.java)
        val result = AgentCharacterPolicy.complete(reply, context, "画铃兰，服装你来选")
        val allOutfitsVisible = context.promptData().contains("formal_token")
        val aliases = gson.toJsonTree(result).asJsonObject["positiveAliases"]?.asJsonArray
        val translated = aliases?.size() ?: 0
        println("OUTFIT_ALIAS_BEHAVIOR allOutfitsVisible=$allOutfitsVisible outfit=${result.characterSelections!!.single().outfitId} positive=${result.prompt} aliases=$translated")
        assertTrue("A default is a fallback, not a reason to hide other real outfits", allOutfitsVisible)
        assertEquals("formal", result.characterSelections!!.single().outfitId)
        assertEquals("portrait, formal_token, suzuran_token, dress", result.prompt)
        assertEquals(4, translated)
    }
}
