package com.comfyui.mobile.ui.screens.agent

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import com.comfyui.mobile.model.CharacterLore
import com.comfyui.mobile.model.CharacterOutfit
import com.comfyui.mobile.ui.theme.ComfyControllerTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AgentCharacterLoreDialogTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val winter = CharacterOutfit("winter", "冬装", "winter_token, coat, scarf")
    private val lore = CharacterLore("star", "星澜 · 星空旅人", "xl_token, blue hair, golden eyes",
        aliases = listOf("小澜", "Star Lan"), fixedTriggerWords = "xl_token",
        outfits = listOf(winter), defaultOutfitId = winter.id)
    private var activity: ComponentActivity? = null
    private class Calls {
        var saved: CharacterLore? = null
        var removed: String? = null
        var toggled: String? = null
        var closed = false
    }

    private fun screen(initial: CharacterLore? = null, dark: Boolean = true, fontScale: Float = 1f, block: (Calls) -> Unit) {
        val calls = Calls()
        val rows = mutableStateOf(listOf(lore, CharacterLore("moon", "月汐", "moon_token", enabled = false)))
        val shown = mutableStateOf(true)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        activity = controller.get()
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply { setContent {
                CompositionLocalProvider(LocalSquircleEnabled provides false,
                    LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    ComfyControllerTheme(darkTheme = dark, manageSystemBars = false) {
                        Scaffold {
                            AgentCharacterLoreDialog(shown.value, rows.value, initial,
                                onDismissRequest = { calls.closed = true; shown.value = false },
                                onSaveLore = { saved ->
                                    calls.saved = saved
                                    rows.value = rows.value.filterNot { it.id == saved.id } + saved
                                },
                                onToggleLore = { calls.toggled = it },
                                onRemoveLore = { id -> calls.removed = id; rows.value = rows.value.filterNot { it.id == id } })
                        }
                    }
                }
            } })
            compose.waitForIdle()
            block(calls)
        } finally { activity = null; controller.pause().stop().destroy() }
    }
    private fun node(tag: String) = compose.onNodeWithTag(tag)
    private fun render(name: String) = compose.runOnIdle {
        val view = requireNotNull(activity).window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file = File("build/agent-character-preview/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue(file.length() > 8000)
        println("CHARACTER_UI_RENDER ${file.absolutePath}")
    }

    @Test fun librarySearchesSavedAliasAndKeepsFullNameAboveSeparateActions() {
        screen {
            node("lore-search").performTextInput("小澜")
            node("lore-card-star").assertExists()
            node("lore-card-moon").assertDoesNotExist()
            compose.onNodeWithText(lore.name).assertExists()
            node("lore-edit-star").performScrollTo().performClick()
            node("lore-name").assertExists()
            node("lore-edit-save").assertIsDisplayed()
            node("lore-edit-cancel").performClick()
            assertNull(it.saved)
        }
    }

    @Test fun addsAliasesPinnedKeysAndOutfitAsOneStagedCharacterSave() {
        screen {
            node("lore-add").performClick()
            node("lore-name").performTextReplacement("新角色")
            node("lore-aliases").performTextReplacement("小新，New Star")
            node("lore-words").performTextReplacement("new_star, blue eyes")
            node("lore-advanced").performScrollTo().performClick()
            node("lore-pinned").performScrollTo().performClick()
            node("lore-fixed-words").performScrollTo().performTextReplacement("new_star")
            node("lore-outfit-add").performScrollTo().performClick()
            node("outfit-name").performTextReplacement("旅行装")
            node("outfit-dialogue-triggers").performScrollTo().performTextReplacement("换成旅行穿搭，穿旅行装")
            node("outfit-words").performScrollTo().performTextReplacement("travel_token, coat")
            node("outfit-save").assertIsDisplayed().performClick()
            assertNull(it.saved)
            node("lore-outfit-fallback").performScrollTo().performClick()
            compose.onAllNodesWithText("旅行装").onLast().performScrollTo().performClick()
            node("lore-edit-save").assertIsDisplayed().performClick()
            val saved = requireNotNull(it.saved)
            assertEquals("新角色", saved.name)
            assertEquals(listOf("小新", "New Star"), saved.aliases)
            assertEquals(true, saved.alwaysActive)
            assertEquals("new_star", saved.fixedTriggerWords)
            assertEquals("travel_token, coat", saved.outfits!!.single().triggerWords)
            assertEquals(listOf("换成旅行穿搭", "穿旅行装"), saved.outfits.single().dialogueTriggers)
            assertEquals(saved.outfits.single().id, saved.defaultOutfitId)
        }
    }

    @Test fun editingOrRemovingOutfitThenCancellingDoesNotPersistAnyDraft() {
        screen(initial = lore) {
            node("lore-outfit-edit-winter").performScrollTo().performClick()
            node("outfit-dialogue-triggers").performScrollTo().performTextReplacement("改成错误草稿")
            node("outfit-words").performScrollTo().performTextReplacement("changed_words")
            node("outfit-cancel").performClick()
            node("lore-outfit-delete-winter").performScrollTo().performClick()
            node("lore-edit-cancel").assertIsDisplayed().performClick()
            assertNull(it.saved)
            compose.onNodeWithText("1 套服装 · 默认：冬装").assertExists()
        }
    }

    @Test fun blankAndDuplicateCharacterNamesStayInEditorWithConcreteFeedback() {
        screen {
            node("lore-add").performClick()
            node("lore-edit-save").performClick()
            node("lore-edit-error").assertTextEquals("请填写角色对话识别名称与绘画外貌参考")
            node("lore-name").performTextReplacement("月汐")
            node("lore-words").performTextReplacement("new_token")
            node("lore-edit-save").performClick()
            node("lore-edit-error").assertTextEquals("角色名已存在，请使用不同名称")
            assertNull(it.saved)
            node("lore-edit-cancel").performClick()
        }
    }

    @Test fun deleteRequiresConfirmationAndToggleDoesNotOpenAnEditor() {
        screen {
            node("lore-enabled-star").performScrollTo().performClick()
            assertEquals("star", it.toggled)
            node("lore-name").assertDoesNotExist()
            node("lore-delete-star").performScrollTo().performClick()
            assertNull(it.removed)
            node("lore-delete-confirm").performClick()
            assertEquals("star", it.removed)
            node("lore-card-star").assertDoesNotExist()
            node("lore-library-close").assertIsDisplayed().performClick()
            assertTrue(it.closed)
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
    fun darkEditorAndOutfitPickerRenderWithFixedFooterAndOneScrollOwner() {
        screen(initial = lore) {
            node("lore-edit-save").assertIsDisplayed()
            render("editor-360-dark")
            node("lore-outfit-fallback").performScrollTo().performClick()
            node("lore-outfit-default-winter").performScrollTo().assertIsSelected()
            node("lore-outfit-edit-winter").performScrollTo()
            node("lore-edit-save").assertIsDisplayed()
            render("outfits-360-dark")
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h720dp-mdpi")
    fun narrowLightLargeFontCanScrollEditAndCancelWithoutCoveringActions() {
        screen(initial = lore, dark = false, fontScale = 1.3f) {
            node("lore-edit-save").assertIsDisplayed()
            node("lore-edit-cancel").assertIsDisplayed()
            node("lore-outfit-add").performScrollTo().assertIsDisplayed().performClick()
            node("outfit-name").performTextReplacement("雨衣")
            node("outfit-dialogue-triggers").performScrollTo().performTextReplacement("换成雨衣")
            node("outfit-words").performScrollTo().performTextReplacement("raincoat")
            node("outfit-save").assertIsDisplayed()
            node("outfit-cancel").assertIsDisplayed()
            node("outfit-dialogue-triggers").performScrollTo()
            render("outfit-320-light-large-text")
            node("outfit-cancel").performClick()
            node("lore-edit-cancel").assertIsDisplayed().performClick()
            assertNull(it.saved)
            render("library-320-light-large-text")
        }
    }
}
