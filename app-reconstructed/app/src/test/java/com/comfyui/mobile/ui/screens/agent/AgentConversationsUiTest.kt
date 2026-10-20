package com.comfyui.mobile.ui.screens.agent

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.navigation.compose.rememberNavController
import com.comfyui.mobile.Screen
import com.comfyui.mobile.ui.designsystem.PocketMaterialTheme
import com.comfyui.mobile.ui.navigation.PocketNavigationShell
import com.comfyui.mobile.ui.theme.BottomBarStyle
import com.comfyui.mobile.agent.workspace.AgentConversation
import com.comfyui.mobile.agent.workspace.AgentConversationDeleteInfo
import com.comfyui.mobile.ui.AgentStorageMode
import com.comfyui.mobile.ui.AgentStorageStatus
import com.comfyui.mobile.ui.screens.AgentConversationWorkspace
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
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AgentConversationsUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val rows = (0..23).map {
        AgentConversation("s$it", "local", if (it == 1) "角色与服装分镜研究 · 很长的会话名称用于验证省略效果" else "绘图会话 $it",
            mode = if (it == 2) "chat" else "draw", lastMessagePreview = "已保存的双向提示词、草稿和参考附件 $it",
            createdAt = 1, updatedAt = 1792533600000L - it * 100000L)
    }
    private fun capture(activity: ComponentActivity, name: String) {
        compose.waitForIdle()
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnIdle { view.draw(Canvas(bitmap)) }
        File("build/reports/agent-sessions/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
    private fun gesture(activity: ComponentActivity, cancel: Boolean = false) {
        compose.runOnIdle {
            activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 100f, 0f, BackEventCompat.EDGE_LEFT))
            activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(100f, 100f, 0.5f, BackEventCompat.EDGE_LEFT))
            if (cancel) activity.onBackPressedDispatcher.dispatchOnBackCancelled()
            else activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
    }

    @Test fun workspaceIsASecondaryPageAndBackDoesNotAlsoLeaveAgentWithEitherBottomBar() {
        for (style in listOf(BottomBarStyle.MATERIAL, BottomBarStyle.CLASSIC)) {
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
            try {
                controller.get().setContentView(ComposeView(controller.get()).apply { setContent {
                    val nav = rememberNavController()
                    CompositionLocalProvider(LocalSquircleEnabled provides false) {
                    PocketMaterialTheme(true) {
                        PocketNavigationShell(nav, bottomBarStyle = style) { entry ->
                            ComfyControllerTheme(true, manageSystemBars = false) {
                                Scaffold {
                                    if (entry.destination.route == "agent_sessions") {
                                        AgentConversationWorkspace(rows, "s0", AgentStorageStatus(AgentStorageMode.ROOM), null,
                                            { nav.popBackStack() }, {}, {}, { _, _ -> }, {}, {}, {}, {})
                                    } else if (entry.destination.route == Screen.Agent.route) {
                                        TextButton("打开会话", onClick = { nav.navigate("agent_sessions") })
                                    }
                                }
                            }
                        }
                    }
                    }
                } })
                compose.onNodeWithTag("pocket-tab-2").performClick()
                compose.onNodeWithText("打开会话").performClick()
                compose.onNodeWithTag("agent-session-s0").performTouchInput { longClick() }
                gesture(controller.get())
                compose.onNodeWithTag("agent-session-list").assertExists()
                gesture(controller.get())
                compose.onNodeWithText("打开会话").assertExists()
                compose.onNodeWithTag("pocket-tab-2").assertIsSelected()
                assertFalse(controller.get().isFinishing)
            } finally { controller.pause().stop().destroy() }
        }
    }
    @Test fun workspaceCanSearchScrollOpenRenameAndCreateThroughRealControls() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var opened: String? = null
        var renamed: Pair<String, String>? = null
        var created: String? = null
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply { setContent {
                CompositionLocalProvider(LocalSquircleEnabled provides false) {
                    ComfyControllerTheme(true, manageSystemBars = false) {
                        Scaffold {
                            AgentConversationWorkspace(rows, "s0", AgentStorageStatus(AgentStorageMode.ROOM), null,
                                {}, { opened = it }, { created = it }, { id, name -> renamed = id to name },
                                {}, {}, {}, {})
                        }
                    }
                }
            } })
            capture(controller.get(), "overview-dark")
            compose.onNodeWithTag("agent-session-list").performScrollToIndex(20)
            compose.onNodeWithTag("agent-session-s20").assertIsDisplayed()
            compose.onNodeWithTag("agent-session-search").performTextReplacement("分镜研究")
            compose.onNodeWithText("1 个 · 按最近更新排序").assertExists()
            compose.onNodeWithTag("agent-session-s1").performClick()
            assertEquals("s1", opened)
            compose.onNodeWithTag("agent-session-more-s1").performClick()
            compose.onNodeWithText("重命名").performClick()
            compose.onNodeWithTag("agent-session-title").performTextReplacement("重命名验证")
            compose.onNodeWithText("保存").performClick()
            compose.waitForIdle()
            assertEquals("s1" to "重命名验证", renamed)
            compose.onNodeWithTag("agent-session-new").performClick()
            compose.onNodeWithTag("agent-session-title").performTextReplacement("新绘图任务")
            compose.onNodeWithText("创建").performClick()
            compose.waitForIdle()
            assertEquals("新绘图任务", created)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun longPressSelectionAndDeleteDialogEachConsumeOnlyOneBackGesture() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var backs = 0
        var deleted = 0
        val info = mutableStateOf<AgentConversationDeleteInfo?>(null)
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply { setContent {
                CompositionLocalProvider(LocalSquircleEnabled provides false) {
                    ComfyControllerTheme(true, manageSystemBars = false) {
                        Scaffold {
                            AgentConversationWorkspace(rows, "s0", AgentStorageStatus(AgentStorageMode.ROOM), info.value,
                                { backs++ }, {}, {}, { _, _ -> },
                                { id -> info.value = AgentConversationDeleteInfo(rows.first { it.id == id }, 2, 1) },
                                { info.value = null }, { deleted++; info.value = null }, {})
                        }
                    }
                }
            } })
            compose.onNodeWithTag("agent-session-s0").performTouchInput { longClick() }
            compose.onNodeWithContentDescription("退出会话选择").assertExists()
            gesture(controller.get(), true)
            compose.onNodeWithContentDescription("退出会话选择").assertExists()
            gesture(controller.get())
            compose.onNodeWithContentDescription("退出会话选择").assertDoesNotExist()
            assertEquals(0, backs)
            compose.onNodeWithTag("agent-session-s0").performTouchInput { longClick() }
            compose.onNodeWithText("删除", substring = false).performClick()
            compose.onNodeWithText("未完成或待确认任务：1 个。删除记录不会取消服务器任务。").assertExists()
            capture(controller.get(), "delete-dark")
            gesture(controller.get())
            compose.onNodeWithText("删除会话？").assertDoesNotExist()
            compose.onNodeWithContentDescription("退出会话选择").assertExists()
            assertEquals(0, backs)
            assertEquals(0, deleted)
            compose.onNodeWithText("删除", substring = false).performClick()
            compose.onNodeWithText("确认删除").performClick()
            compose.waitForIdle()
            assertEquals(1, deleted)
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun narrowLightEmptyAndSearchStatesKeepCreationAndCancelReachable() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var creations = 0
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply { setContent {
                CompositionLocalProvider(LocalSquircleEnabled provides false) {
                    ComfyControllerTheme(false, manageSystemBars = false) {
                        Scaffold {
                            AgentConversationWorkspace(emptyList(), null, AgentStorageStatus(AgentStorageMode.ROOM), null,
                                {}, {}, { creations++ }, { _, _ -> }, {}, {}, {}, {})
                        }
                    }
                }
            } })
            compose.onNodeWithText("还没有已保存的会话").assertIsDisplayed()
            capture(controller.get(), "empty-320dp-light")
            compose.onNodeWithText("新建会话").performClick()
            compose.onNodeWithText("取消").performClick()
            compose.waitForIdle()
            assertEquals(0, creations)
            compose.onNodeWithTag("agent-session-search").performTextReplacement("不匹配")
            compose.onNodeWithText("没有匹配的会话").assertIsDisplayed()
            compose.onNodeWithText("清除搜索").performClick()
            compose.onNodeWithText("还没有已保存的会话").assertIsDisplayed()
        } finally { controller.pause().stop().destroy() }
    }
}
