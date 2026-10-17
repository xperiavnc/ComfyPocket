package com.comfyui.mobile.ui.material

import com.comfyui.mobile.danbooru.*
import org.junit.Assert.*
import org.junit.Test

class MaterialEditorTest {
    @Test fun validationKeepsIncompleteInputAndMarksTheAccountFields() {
        val editor = MaterialConnectionEditor()
        editor.begin(DanbooruSettings())
        editor.edit(editor.draft.copy(username = "demo"))
        assertNull(editor.validate())
        assertTrue(editor.errors.containsKey("account"))
        assertEquals("demo", editor.draft.username)
        assertTrue(editor.dirty)
    }
    @Test fun proxyValidationIsLocalAndDisablingItRetainsTheDraft() {
        val editor = MaterialConnectionEditor()
        editor.begin(DanbooruSettings())
        editor.edit(editor.draft.copy(proxyEnabled = true, proxyAddress = "https://bad"))
        assertNull(editor.validate())
        assertTrue(editor.errors.containsKey("proxy"))
        editor.edit(editor.draft.copy(proxyEnabled = false))
        assertNotNull(editor.validate())
        assertEquals("https://bad", editor.draft.proxyAddress)
    }
    @Test fun commitAndDiscardDoNotMutateTheOriginalConfiguration() {
        val original = DanbooruSettings("user", "fixture-key", true, "http://localhost:2080")
        val editor = MaterialConnectionEditor()
        editor.begin(original)
        editor.edit(original.copy(apiKey = "changed-key"))
        editor.discard()
        assertEquals(original, editor.draft)
        assertFalse(editor.dirty)
        editor.edit(original.copy(defaultFilter = ContentFilter.ALL))
        editor.committed()
        assertFalse(editor.dirty)
        assertEquals(ContentFilter.GENERAL, original.defaultFilter)
    }
    @Test fun editorLivesInAViewModelStoreWithoutPuttingSecretsInSavedState() {
        val store = androidx.lifecycle.ViewModelStore()
        val provider = androidx.lifecycle.ViewModelProvider.create(store,
            object : androidx.lifecycle.ViewModelProvider.Factory {
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST") return MaterialConnectionEditor() as T
                }
            })
        val editor = provider[MaterialConnectionEditor::class]
        editor.begin(DanbooruSettings())
        editor.edit(DanbooruSettings("user", "fixture-key"))
        assertSame(editor, provider[MaterialConnectionEditor::class])
        assertEquals("fixture-key", provider[MaterialConnectionEditor::class].draft.apiKey)
        store.clear()
    }
}
