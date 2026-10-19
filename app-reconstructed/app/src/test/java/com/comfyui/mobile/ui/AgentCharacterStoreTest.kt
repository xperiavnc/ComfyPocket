package com.comfyui.mobile.ui

import android.content.Context
import androidx.lifecycle.ViewModelStore
import com.comfyui.mobile.model.CharacterLore
import com.comfyui.mobile.model.CharacterOutfit
import com.comfyui.mobile.model.ConfigBackupDto
import com.google.gson.Gson
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentCharacterStoreTest {
    private lateinit var vm: MainViewModel
    private lateinit var store: ViewModelStore
    private val outfit = CharacterOutfit("winter", "冬装", "winter_token, coat")
    private val lore = CharacterLore("star", "星澜", "xl_token, blue hair", aliases = listOf("小澜"),
        alwaysActive = true, fixedTriggerWords = "xl_token", outfits = listOf(outfit), defaultOutfitId = outfit.id)

    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("comfy_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        vm = MainViewModel(app)
        store = ViewModelStore().apply { put("vm", vm) }
    }
    @After fun close() { store.clear(); vm.repository.disconnect() }

    @Test fun oldJsonAndOldBackupStillLoadWithoutAssumingKotlinDefaults() {
        val old = Gson().fromJson("""{"id":"old","name":"旧角色","triggerWords":"old_token"}""", CharacterLore::class.java)
        assertTrue(old.isEnabled)
        assertNull(old.aliases)
        assertNull(old.outfits)
        assertNull(old.alwaysActive)
        val backup = Gson().fromJson("""{"characterLores":[{"id":"old","name":"旧角色","triggerWords":"old_token"}]}""",
            ConfigBackupDto::class.java)
        assertEquals("old_token", backup.characterLores!!.single().triggerWords)
    }

    @Test fun outfitsAliasesAndPinnedStateRoundTripThroughActualPreferencesAndBackupJson() {
        vm.saveCharacterLore(lore)
        assertEquals(lore, MainViewModelAgent.loadCharacterLores(vm).single())
        val backup = ConfigBackupDto(characterLores = MainViewModelAgent.loadCharacterLores(vm))
        assertEquals(lore, Gson().fromJson(Gson().toJson(backup), ConfigBackupDto::class.java).characterLores!!.single())
    }

    @Test fun existingModelImportOrQuickEditDoesNotEraseNewCharacterFields() {
        vm.saveCharacterLore(lore)
        vm.addOrUpdateCharacterLore(lore.name, "updated_token", lore.id, false)
        assertEquals(lore.copy(triggerWords = "updated_token", enabled = false), vm.characterLores.value.single())
        vm.addOrUpdateCharacterLore(lore.name, "new_imported_words")
        assertEquals(listOf(outfit), vm.characterLores.value.single().outfits)
        assertEquals(listOf("小澜"), vm.characterLores.value.single().aliases)
        assertFalse(vm.characterLores.value.single().isEnabled)
        vm.toggleCharacterLoreEnabled(lore.id)
        assertTrue(MainViewModelAgent.loadCharacterLores(vm).single().isEnabled)
        assertEquals("winter", vm.characterLores.value.single().defaultOutfitId)
    }

    @Test fun deletingDefaultOutfitClearsItsIdAndDuplicateNamesAreNotSaved() {
        vm.saveCharacterLore(lore)
        vm.saveCharacterLore(lore.copy(outfits = emptyList()))
        assertNull(MainViewModelAgent.loadCharacterLores(vm).single().defaultOutfitId)
        vm.saveCharacterLore(lore.copy(id = "duplicate"))
        assertEquals(1, vm.characterLores.value.size)
        vm.saveCharacterLore(lore.copy(outfits = listOf(outfit, outfit.copy(id = "different"))))
        assertTrue(vm.characterLores.value.single().outfits.orEmpty().isEmpty())
    }
}
