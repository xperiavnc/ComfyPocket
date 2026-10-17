package com.comfyui.mobile.ui.designsystem

import androidx.activity.ComponentActivity
import androidx.compose.material3.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MaterialDependencyTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun materialComponentsResolveOnTheExistingComposeRuntime() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var clicks = 0
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply {
                setContent { PocketMaterialTheme {
                    Surface { Button(onClick = { clicks++ }) { Text("Material 试编") } }
                } }
            })
            compose.onNodeWithText("Material 试编").performClick()
            org.junit.Assert.assertEquals(1, clicks)
        } finally { controller.pause().stop().destroy() }
    }
}
