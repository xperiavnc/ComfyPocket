package com.comfyui.mobile.ui.screens.agent

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.Magnifier
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.comfyui.mobile.taglib.LocalTagEntry
import com.comfyui.mobile.ui.theme.ComfyControllerTheme
import java.io.File
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AgentLocalTagCopyTest {
    @get:Rule val compose = createEmptyComposeRule()

    // Robolectric has no backing Surface for the platform selection magnifier.
    // Keep the real selection/clipboard path; only the magnifier rendering is stubbed.
    @Implements(Magnifier::class)
    class NoSurfaceMagnifier {
        @Implementation fun show(sourceCenterX: Float, sourceCenterY: Float) {}
        @Implementation fun show(
            sourceCenterX: Float, sourceCenterY: Float,
            magnifierCenterX: Float, magnifierCenterY: Float
        ) {}
        @Implementation fun dismiss() {}
        @Implementation fun update() {}
    }

    private fun entry(
        name: String = "flower",
        translation: String = "花",
        kind: String = "tag",
        trigger: String = "",
        detail: String = ""
    ) = LocalTagEntry(name, translation, "accessory", "花卉", kind, trigger, detail, translation, true)

    private class RecordingToolbar : TextContextMenuProvider {
        var copy: (() -> Unit)? = null

        override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
            val item = dataProvider.data().components.filterIsInstance<TextContextMenuItem>()
                .first { it.key == TextContextMenuKeys.CopyKey }
            copy = { item.onClick(object : TextContextMenuSession { override fun close() {} }) }
            awaitCancellation()
        }
    }

    private fun screen(
        item: LocalTagEntry,
        toolbar: RecordingToolbar = RecordingToolbar(),
        largeText: Boolean = false,
        block: (ComponentActivity) -> Unit
    ) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply {
                setContent {
                    CompositionLocalProvider(
                        LocalSquircleEnabled provides false,
                        LocalTextContextMenuToolbarProvider provides toolbar,
                        LocalDensity provides Density(LocalDensity.current.density, if (largeText) 1.3f else 1f)
                    ) {
                        ComfyControllerTheme(darkTheme = !largeText, manageSystemBars = false) {
                            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp)) {
                                LocalTagEntryText(item)
                            }
                        }
                    }
                }
            })
            compose.waitForIdle()
            block(controller.get())
        } finally { controller.pause().stop().destroy() }
    }

    private fun clipboard(activity: ComponentActivity): String =
        (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .primaryClip!!.getItemAt(0).text.toString()

    @Test fun copyingTagsKeepsOriginalUnderscoresAndParentheses() {
        screen(entry(name = "hana_(dqkmb362)", translation = "花（dqkmb362）")) { activity ->
            compose.onNodeWithText("复制标签").performClick()
            assertEquals("hana_(dqkmb362)", clipboard(activity))
            compose.onNodeWithText("复制中文").performClick()
            assertEquals("花（dqkmb362）", clipboard(activity))
        }
    }

    @Test fun characterIdentityCopyExcludesDisplayNameTranslationAndOptionalAppearance() {
        val identity = "(xl_token:1.15), star_lan_(winter)"
        screen(entry("star_lan", "星澜", "character", identity,
            """{"optionalAppearance":["blue_hair","winter_coat"],"optionalSpecificAppearance":[{"en":"star_hair_ornament","zh":"星形发饰"}]}""")
        ) { activity ->
            compose.onNodeWithText("复制身份").performClick()
            assertEquals(identity, clipboard(activity))
            compose.onNodeWithText("复制中文").performClick()
            assertEquals("星澜", clipboard(activity))
            compose.onNodeWithText("star_hair_ornament · 星形发饰", substring = true).assertExists()
        }
    }

    @Test fun characterWithoutSeparateTriggerCanStillCopyItsCanonicalTag() {
        screen(entry("hatsune_miku", "初音未来", "character")) { activity ->
            compose.onNodeWithText("复制身份").performClick()
            assertEquals("hatsune_miku", clipboard(activity))
        }
    }

    @Test fun missingTranslationDoesNotCopyAPlaceholderAsChinese() {
        screen(entry("custom_tag", "")) { activity ->
            compose.onNodeWithText("上游暂缺中文对照", substring = true).assertExists()
            compose.onNodeWithText("复制中文").assertDoesNotExist()
            compose.onNodeWithText("复制标签").performClick()
            assertEquals("custom_tag", clipboard(activity))
        }
    }

    @Test
    @Config(sdk = [34], shadows = [NoSurfaceMagnifier::class])
    fun longPressAllowsCopyingOnlyTheSelectedWord() {
        val toolbar = RecordingToolbar()
        screen(entry(), toolbar) { activity ->
            compose.onNodeWithText("flower · 花").performTouchInput { longClick(Offset(12f, center.y)) }
            compose.waitUntil(timeoutMillis = 5000) { toolbar.copy != null }
            compose.runOnIdle { toolbar.copy!!.invoke() }
            compose.waitForIdle()
            assertEquals("flower", clipboard(activity))
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h720dp-mdpi")
    fun longBilingualNamesAndCopyActionsStayUsableOnNarrowScreens() {
        val tag = "star_lan_(light_gold_celebration)_(fixture_series)"
        screen(entry(tag, "星澜（轻金庆典版本）（测试作品）", "character", tag,
            """{"optionalAppearance":["long_hair","golden_ribbon"],"optionalSpecificAppearance":[{"en":"star_hair_ornament","zh":"星形发饰"}]}"""),
            largeText = true
        ) { activity ->
            val copy = compose.onNodeWithText("复制身份").assertIsDisplayed()
            val translation = compose.onNodeWithText("复制中文").assertIsDisplayed()
            for (node in listOf(copy, translation)) {
                val bounds = node.fetchSemanticsNode().boundsInRoot
                assertTrue(bounds.left >= 0f && bounds.right <= 320f)
            }
            copy.performClick()
            assertEquals(tag, clipboard(activity))
            val view = activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            compose.runOnIdle { view.draw(Canvas(bitmap)) }
            val file = File("build/local-tag-copy-preview/lookup-320-light-large-text.png").apply {
                parentFile!!.mkdirs()
            }
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            assertTrue(file.length() > 8000)
            println("LOCAL_TAG_COPY_RENDER ${file.absolutePath}")
        }
    }
}
