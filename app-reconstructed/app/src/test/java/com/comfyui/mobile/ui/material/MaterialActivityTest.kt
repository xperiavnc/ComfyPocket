package com.comfyui.mobile.ui.material

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class MaterialActivityTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun actualActivityStartsOfflineAndRetainsUnsavedDraftAcrossRecreation() {
        val controller = Robolectric.buildActivity(MaterialSampleActivity::class.java).setup().visible()
        try {
            compose.waitForIdle()
            compose.onNodeWithText("Material 样板 · 离线演示").assertExists()
            compose.onNodeWithContentDescription("Danbooru 设置").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("用户名").performTextInput("draft-user")
            val before = ViewModelProvider(controller.get()).get("material-offline-editor", MaterialConnectionEditor::class.java)
            assertEquals("draft-user", before.draft.username)
            controller.recreate()
            compose.waitForIdle()
            val after = ViewModelProvider(controller.get()).get("material-offline-editor", MaterialConnectionEditor::class.java)
            assertSame(before, after)
            assertEquals("draft-user", after.draft.username)
            compose.onNodeWithTag("material-save").assertIsDisplayed()
        } finally { controller.pause().stop().destroy() }
    }
}
