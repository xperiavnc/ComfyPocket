package com.comfyui.mobile.agent

import com.comfyui.mobile.model.AgentCharacterSelection
import com.comfyui.mobile.model.AgentChatMessage
import com.comfyui.mobile.model.CharacterLore
import com.comfyui.mobile.model.CharacterOutfit
import org.junit.Assert.*
import org.junit.Test

class AgentCharacterPolicyTest {
    private val winter = CharacterOutfit("winter", "冬装", "winter_token, coat, scarf")
    private val casual = CharacterOutfit("casual", "便服", "casual_token, shirt")
    private val star = CharacterLore("star", "星澜", "xl_token, blue hair", aliases = listOf("小澜", "Star_Lan"),
        fixedTriggerWords = "xl_token", outfits = listOf(winter, casual), defaultOutfitId = winter.id)
    private val moon = CharacterLore("moon", "月汐", "moon_token, red eyes",
        aliases = listOf("小汐"), outfits = listOf(winter.copy(id = "moon-winter", triggerWords = "moon_coat"),
            casual.copy(id = "moon-casual", triggerWords = "moon_shirt")))
    private fun resolve(text: String, history: List<AgentChatMessage> = emptyList(), lores: List<CharacterLore> = listOf(star)) =
        AgentCharacterPolicy.resolve(lores, text, history)
    private fun history(vararg selections: AgentCharacterSelection) = listOf(
        AgentChatMessage("u", "user", "画星澜"),
        AgentChatMessage("a", "assistant", "好", expandedPrompt = "1girl, xl_token",
            characterSelections = selections.toList()))
    private fun complete(text: String, prompt: String, history: List<AgentChatMessage> = emptyList()) =
        AgentCharacterPolicy.complete(AgentReply("好", prompt, negativePrompt = "blurry"), resolve(text, history), text)

    @Test fun registeredAliasesAndUnderscoreSpaceCaseMatchWithoutModelGuessing() {
        assertEquals(star.id, resolve("画小澜").characters.single().lore.id)
        assertEquals(star.id, resolve("画 STAR LAN").characters.single().lore.id)
        assertTrue(resolve("画一个没登记的昵称").characters.isEmpty())
        val block = resolve("画小澜").promptData()
        assertTrue(block.contains("登记名称与别名"))
        assertTrue(block.contains("不猜译名"))
        assertTrue(block.contains("小澜"))
        assertTrue(block.contains("xl_token"))
    }

    @Test fun latinNamesDoNotAccidentallyMatchLongerNames() {
        val ann = CharacterLore("ann", "Ann", "ann_token")
        assertTrue(resolve("draw Anna", lores = listOf(ann)).characters.isEmpty())
        assertEquals(ann, resolve("画Ann", lores = listOf(ann)).characters.single().lore)
    }

    @Test fun longestRegisteredNameWinsOverAContainedAlias() {
        val longName = moon.copy(name = "Star Lan Extended")
        assertEquals(moon.id, resolve("draw Star Lan Extended", lores = listOf(star, longName)).characters.single().lore.id)
    }

    @Test fun canonicalNameWinsOverAnotherCharactersAlias() {
        assertEquals(star.id, resolve("画星澜", lores = listOf(star, moon.copy(aliases = listOf("星澜"))))
            .characters.single().lore.id)
    }

    @Test fun sharedAliasIsReportedRatherThanGuessingACharacter() {
        val context = resolve("画小澜", lores = listOf(star, moon.copy(aliases = listOf("小澜"))))
        assertTrue(context.characters.isEmpty())
        assertEquals(listOf("小澜"), context.ambiguousNames)
    }

    @Test fun disabledCharactersStayOffEvenWhenPinnedOrNamed() {
        val context = resolve("画小澜", lores = listOf(star.copy(enabled = false, alwaysActive = true)))
        assertTrue(context.characters.isEmpty())
        assertFalse(context.promptData().contains("xl_token"))
    }

    @Test fun pinnedCharacterWorksWithoutNameButUserCanSkipLibraryOrExcludeIt() {
        val pinned = listOf(star.copy(alwaysActive = true))
        assertEquals(star.id, resolve("画雨夜人像", lores = pinned).characters.single().lore.id)
        assertTrue(resolve("本次不用角色库，画雨夜", lores = pinned).characters.isEmpty())
        assertTrue(resolve("不要星澜，画风景", lores = pinned).characters.isEmpty())
    }

    @Test fun followupRestoresAppResolvedCharacterAndOutfit() {
        val context = resolve("保持人物，换背景", history(AgentCharacterSelection(star.id, casual.id)))
        assertEquals(star.id, context.characters.single().lore.id)
        assertEquals(casual.id, context.characters.single().outfit!!.id)
        assertEquals("沿用上一画面", context.characters.single().reason)
    }

    @Test fun oldConversationCanCarryOnlyTheLastUsersRegisteredCharacter() {
        val old = listOf(AgentChatMessage("u", "user", "画小澜"),
            AgentChatMessage("a", "assistant", "我猜她叫另一个名字", expandedPrompt = "xl_token"))
        assertEquals(star.id, resolve("她换个背景", old).characters.single().lore.id)
    }

    @Test fun latestEmptySelectionNewSubjectAndExplicitNewCharacterDoNotLeakOldIdentity() {
        val h = history(AgentCharacterSelection(star.id))
        assertTrue(resolve("画一只猫", h).characters.isEmpty())
        assertTrue(resolve("换一个新角色", h).characters.isEmpty())
        assertTrue(resolve("继续", history()).characters.isEmpty())
        assertEquals(moon.id, resolve("画月汐", h, listOf(star, moon)).characters.single().lore.id)
    }

    @Test fun failedAssistantClaimCannotActivateOrPersistACharacter() {
        val h = listOf(AgentChatMessage("u", "user", "画月汐"), AgentChatMessage("a", "assistant", "星澜",
            expandedPrompt = "xl_token", errorMessage = "failed",
            characterSelections = listOf(AgentCharacterSelection(star.id))))
        assertTrue(resolve("继续", h).characters.isEmpty())
    }

    @Test fun namedOutfitOverridesDefaultAndOnlyOneOutfitIsProvided() {
        val item = resolve("画小澜的便服").characters.single()
        assertEquals(casual, item.outfit)
        val block = resolve("画小澜的便服").promptData()
        assertTrue(block.contains("casual_token"))
        assertFalse(block.contains("winter_token"))
    }

    @Test fun customClothingClearsDefaultAndSurvivesTheNextBackgroundEdit() {
        val first = resolve("小澜换成红色连衣裙").characters.single()
        assertNull(first.outfit)
        assertTrue(first.customOutfit)
        val next = resolve("保持人物，换成雪景", history(first.selection)).characters.single()
        assertNull(next.outfit)
        assertTrue(next.customOutfit)
    }

    @Test fun keepClothesDoesNotMistakeAMentionOfClothingForChangingIt() {
        val item = resolve("保持服装，改背景", history(AgentCharacterSelection(star.id, casual.id))).characters.single()
        assertEquals(casual, item.outfit)
        assertFalse(item.customOutfit)
    }

    @Test fun multipleCharactersHaveTheirOwnNamedOutfitsAndAreNotFlattened() {
        val context = resolve("星澜穿便服，月汐穿冬装", lores = listOf(star, moon))
        assertEquals(listOf("casual", "moon-winter"), context.characters.map { it.outfit?.id })
        val result = AgentCharacterPolicy.complete(AgentReply("好", "2girls, scene"), context)
        assertEquals("2girls, scene", result.prompt)
        assertEquals(2, result.characterSelections!!.size)
        assertTrue(result.characterStatus!!.contains("多人按角色提供"))
    }

    @Test fun completesOnlyFixedKeysAndSelectedOutfitNotWholeAppearanceOrNegative() {
        val result = complete("画小澜", "1girl, white hair")
        assertEquals("1girl, white hair, xl_token, winter_token, coat, scarf", result.prompt)
        assertFalse(result.prompt.contains("blue hair"))
        assertEquals("blurry", result.negativePrompt)
        assertEquals(listOf(AgentCharacterSelection(star.id, winter.id, false)), result.characterSelections)
        assertTrue(result.characterStatus!!.contains("补齐 4 项"))
    }

    @Test fun weightedExistingKeysAreNotDuplicatedAndModelSyntaxIsPreserved() {
        val result = complete("画星澜", """1girl, (xl_token:1.2), WINTER_TOKEN, coat, scarf, <lora:test:0.7>, sign reading "a,b".""")
        assertFalse(result.characterStatus!!.contains("补齐"))
        assertTrue(result.prompt.contains("(xl_token:1.2)"))
        assertTrue(result.prompt.contains("<lora:test:0.7>"))
        assertTrue(result.prompt.contains("\"a,b\""))
    }

    @Test fun switchingOutfitRemovesOnlyExactKnownOldTagsAndKeepsSharedTerms() {
        val result = complete("星澜换便服", "1girl, xl_token, winter_token, coat, scarf, portrait")
        assertEquals("1girl, xl_token, portrait, casual_token, shirt", result.prompt)
        assertTrue(result.characterStatus!!.contains("移除 3 项"))
        val sentence = complete("星澜换便服", "Star stands beside a coat rack.")
        assertTrue(sentence.prompt.startsWith("Star stands beside a coat rack."))
    }

    @Test fun explicitUserExclusionOrNoCompletionRequestIsRespected() {
        assertFalse(complete("画星澜，不要xl_token", "1girl").prompt.contains("xl_token"))
        assertEquals("1girl", complete("画星澜，不要自动补齐", "1girl").prompt)
        val result = complete("星澜换便服，保留围巾", "1girl, scarf")
        assertTrue(result.prompt.contains("scarf"))
    }

    @Test fun blankModelPromptIsNotFabricatedFromLibrary() {
        val result = complete("画星澜", "")
        assertEquals("", result.prompt)
        assertTrue(result.characterSelections!!.isEmpty())
    }

    @Test fun deletedOutfitFallsBackOnlyWhenItWasNotACustomOutfit() {
        val context = resolve("换个背景", history(AgentCharacterSelection(star.id, "deleted")))
        assertEquals(winter.id, context.characters.single().outfit!!.id)
        assertNull(resolve("换个背景", history(AgentCharacterSelection(star.id, "deleted", true)))
            .characters.single().outfit)
    }

    @Test fun longerOutfitNameWinsAndDefaultCanBeRestoredAfterCustomClothes() {
        val extended = winter.copy(id = "extended", name = "秋季便服", triggerWords = "autumn_token")
        assertEquals("extended", resolve("星澜穿秋季便服", lores = listOf(star.copy(outfits = listOf(casual, extended))))
            .characters.single().outfit!!.id)
        assertEquals(winter.id, resolve("她恢复默认服装", history(AgentCharacterSelection(star.id, customOutfit = true)))
            .characters.single().outfit!!.id)
    }

    @Test fun characterChangeAndLibraryOptOutMustNotRecoverAnOldIdentityPrompt() {
        val h = history(AgentCharacterSelection(star.id, casual.id))
        assertFalse(resolve("画月汐", h, listOf(star, moon)).reusePreviousPrompt)
        assertFalse(resolve("本次不用角色库", h).reusePreviousPrompt)
        assertFalse(resolve("画猫", h).reusePreviousPrompt)
        assertTrue(resolve("保持人物，换背景", h).reusePreviousPrompt)
    }

    @Test fun exactOldOutfitCleanupAlsoRecognizesSimpleWeightsAndUnderscores() {
        val result = complete("星澜换便服", "1girl, (winter_token:1.2), coat, scarf")
        assertEquals("1girl, xl_token, casual_token, shirt", result.prompt)
    }

    @Test fun excludedOutfitIsNotMistakenForAnExplicitSelection() {
        for (text in listOf("星澜不要穿冬装", "星澜不穿冬装", "不要星澜的冬装", "Star Lan do not wear 冬装")) {
            val context = resolve(text)
            assertEquals(text, star.id, context.characters.single().lore.id)
            assertNull(text, context.characters.single().outfit)
            assertTrue(text, context.characters.single().customOutfit)
            assertFalse(text, AgentCharacterPolicy.complete(AgentReply("好", "1girl"), context, text).prompt.contains("winter_token"))
        }
    }

    @Test fun preservingIdentityDoesNotAccidentallyProtectAnOldOutfit() {
        val result = complete("保持人物，星澜换便服", "1girl, winter_token, coat, scarf")
        assertEquals("1girl, xl_token, casual_token, shirt", result.prompt)
    }

    @Test fun changingBackgroundDoesNotClearOutfitMerelyMentionedInAnotherClause() {
        assertEquals(casual.id, resolve("换背景，服装保持不变", history(AgentCharacterSelection(star.id, casual.id)))
            .characters.single().outfit!!.id)
    }

    @Test fun userArchiveSuppressesDictionaryIdentityCandidatesButKeepsOrdinaryTags() {
        val identity = com.comfyui.mobile.taglib.LocalTagEntry(
            "guessed_name", "猜测译名", "character", "", "character", "guessed_token", "", "小澜", true)
        val tag = identity.copy(name = "rain", kind = "tag", trigger = "", matchedTerm = "雨")
        val report = com.comfyui.mobile.taglib.LocalTagReport(listOf(identity, tag), revision = "fixture")
        assertEquals(listOf(tag), AgentCharacterPolicy.constrainCandidates(resolve("画小澜"), report).entries)
        assertEquals(report, AgentCharacterPolicy.constrainCandidates(resolve("画风景"), report))
    }

    @Test fun lookupExcludesRegisteredIdentityTermsWithoutChangingUserRequestOrSceneWords() {
        val user = "画小澜，蓝发，rain, blue_hair"
        val lookup = AgentCharacterPolicy.lookupText(resolve(user), user)
        assertFalse(lookup.contains("小澜"))
        assertTrue(lookup.contains("蓝发"))
        assertTrue(lookup.contains("rain"))
        assertTrue(lookup.contains("blue hair"))
        assertEquals("画小澜，蓝发，rain, blue_hair", user)
    }
}
