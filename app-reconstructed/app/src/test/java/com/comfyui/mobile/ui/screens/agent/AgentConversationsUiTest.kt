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
import com.comfyui.mobile.ui.screens.AgentSessionDrawer
import com.comfyui.mobile.ui.screens.agentRelativeTime
import com.comfyui.mobile.agent.workspace.AgentProject
import androidx.activity.ComponentDialog
import org.robolectric.shadows.ShadowDialog
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
            compose.onNodeWithTag("agent-session-s1").performTouchInput { longClick() }
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

    @Test fun longPressActionsAndDeleteDialogEachConsumeOnlyOneBackGesture() {
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
            compose.onNodeWithText("会话操作").assertExists()
            gesture(controller.get(), true)
            compose.onNodeWithText("会话操作").assertExists()
            gesture(controller.get())
            compose.onNodeWithText("会话操作").assertDoesNotExist()
            assertEquals(0, backs)
            compose.onNodeWithTag("agent-session-s0").performTouchInput { longClick() }
            compose.onNodeWithText("删除会话").performClick()
            compose.onNodeWithText("未完成或待确认任务：1 个。删除记录不会取消服务器任务。").assertExists()
            capture(controller.get(), "delete-dark")
            gesture(controller.get())
            compose.onNodeWithText("删除会话？").assertDoesNotExist()
            compose.onNodeWithTag("agent-session-list").assertExists()
            assertEquals(0, backs)
            assertEquals(0, deleted)
            compose.onNodeWithTag("agent-session-s0").performTouchInput { longClick() }
            compose.onNodeWithText("删除会话").performClick()
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

    @Test fun workspacesGroupSessionsAndOfferCreateMoveArchiveThroughRealControls() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val now = 1792533600000L
        val project = AgentProject("pc", "local", "PC", createdAt = 1, updatedAt = now)
        val grouped = listOf(
            AgentConversation("g1", "local", "分组会话", projectId = "pc", createdAt = 1, updatedAt = now - 3 * 3_600_000L),
            AgentConversation("u1", "local", "散落会话", createdAt = 1, updatedAt = now - 2 * 86_400_000L)
        )
        val old = AgentConversation("a1", "local", "旧会话", createdAt = 1, updatedAt = now, archivedAt = now)
        var createdIn: Pair<String, String?>? = null
        var createdProject: String? = null
        var moved: Pair<String, String?>? = null
        var archivedCall: Pair<String, Boolean>? = null
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply { setContent {
                CompositionLocalProvider(LocalSquircleEnabled provides false) {
                    ComfyControllerTheme(true, manageSystemBars = false) {
                        Scaffold {
                            AgentConversationWorkspace(grouped, "g1", AgentStorageStatus(AgentStorageMode.ROOM), null,
                                {}, {}, {}, { _, _ -> }, {}, {}, {}, {},
                                projects = listOf(project), archived = listOf(old),
                                onNewInProject = { title, id -> createdIn = title to id },
                                onCreateProject = { createdProject = it },
                                onMove = { id, target -> moved = id to target },
                                onArchive = { id, value -> archivedCall = id to value },
                                now = now)
                        }
                    }
                }
            } })
            compose.onNodeWithTag("agent-project-pc").assertIsDisplayed()
            compose.onNodeWithText("未分组").assertIsDisplayed()
            compose.onNodeWithText("3 小时").assertExists()
            compose.onNodeWithText("2 天").assertExists()
            capture(controller.get(), "workspaces-dark")

            compose.onNodeWithTag("agent-project-new-pc").performClick()
            compose.onNodeWithText("创建").performClick()
            compose.waitForIdle()
            assertEquals("新会话" to "pc", createdIn)

            compose.onNodeWithTag("agent-project-pc").performClick()
            compose.onNodeWithTag("agent-session-g1").assertDoesNotExist()
            compose.onNodeWithTag("agent-project-pc").performClick()
            compose.onNodeWithTag("agent-session-g1").assertExists()

            compose.onNodeWithTag("agent-session-u1").performTouchInput { longClick() }
            compose.onNodeWithText("移动到工作区").performClick()
            compose.waitForIdle()
            compose.onNode(hasText("PC") and SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Button))
                .performClick()
            compose.waitForIdle()
            assertEquals("u1" to "pc", moved)

            compose.onNodeWithTag("agent-session-g1").performTouchInput { longClick() }
            compose.onNodeWithText("归档会话").performClick()
            compose.waitForIdle()
            assertEquals("g1" to true, archivedCall)

            compose.onNodeWithTag("agent-project-create").performClick()
            compose.onNodeWithTag("agent-project-title").performTextReplacement("APK")
            compose.onNodeWithText("创建").performClick()
            compose.waitForIdle()
            assertEquals("APK", createdProject)

            compose.onNodeWithTag("agent-session-tab-archived").performClick()
            compose.onNodeWithTag("agent-session-a1").assertIsDisplayed()
            compose.onNodeWithTag("agent-session-a1").performTouchInput { longClick() }
            compose.onNodeWithText("恢复会话").performClick()
            compose.waitForIdle()
            assertEquals("a1" to false, archivedCall)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun drawerOpensOverContentAndClosesWithBackAndScrim() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val visible = mutableStateOf(false)
        try {
            controller.get().setContentView(ComposeView(controller.get()).apply { setContent {
                CompositionLocalProvider(LocalSquircleEnabled provides false) {
                    ComfyControllerTheme(true, manageSystemBars = false) {
                        Scaffold {
                            TextButton("打开会话", onClick = { visible.value = true })
                            AgentSessionDrawer(visible.value, onDismiss = { visible.value = false }) {
                                TextButton("抽屉内容", onClick = {})
                            }
                        }
                    }
                }
            } })
            compose.onNodeWithTag("agent-session-drawer").assertDoesNotExist()
            compose.onNodeWithText("打开会话").performClick()
            compose.onNodeWithText("抽屉内容").assertIsDisplayed()
            compose.runOnIdle {
                (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
            }
            compose.waitForIdle()
            assertFalse(visible.value)
            compose.onNodeWithTag("agent-session-drawer").assertDoesNotExist()
            compose.onNodeWithText("打开会话").performClick()
            compose.onNodeWithTag("agent-session-scrim").performTouchInput { click(centerRight) }
            compose.waitForIdle()
            assertFalse(visible.value)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun relativeTimeUsesShortChineseUnits() {
        val now = 1792533600000L
        assertEquals("刚刚", agentRelativeTime(now - 5_000, now))
        assertEquals("5 分钟", agentRelativeTime(now - 5 * 60_000, now))
        assertEquals("3 小时", agentRelativeTime(now - 3 * 3_600_000L, now))
        assertEquals("2 天", agentRelativeTime(now - 2 * 86_400_000L, now))
        assertEquals("1 周", agentRelativeTime(now - 8 * 86_400_000L, now))
        assertEquals("刚刚", agentRelativeTime(now + 10_000, now))
    }
}
