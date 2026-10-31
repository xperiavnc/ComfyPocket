package com.comfyui.mobile.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.composed
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.navigation.NavController
import com.comfyui.mobile.agent.workspace.AgentConversation
import com.comfyui.mobile.agent.workspace.AgentConversationDeleteInfo
import com.comfyui.mobile.agent.workspace.AgentProject
import com.comfyui.mobile.ui.AgentStorageMode
import com.comfyui.mobile.ui.AgentStorageStatus
import com.comfyui.mobile.ui.MainViewModel
import com.comfyui.mobile.ui.components.DialogFormColumn
import com.comfyui.mobile.ui.components.rememberDialogDismiss
import com.comfyui.mobile.ui.designsystem.PocketPill
import com.comfyui.mobile.ui.designsystem.PocketTokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Full-page route kept for deep links and older navigation; Agent itself opens the drawer. */
@Composable
fun AgentConversationsScreen(navController: NavController, viewModel: MainViewModel) {
    Scaffold(containerColor = MiuixTheme.colorScheme.background) {
        AgentConversationsPanel(viewModel, onClose = { navController.popBackStack() })
    }
}

@Composable
internal fun AgentConversationsPanel(viewModel: MainViewModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val rows by viewModel.agentConversations.collectAsState()
    val archived by viewModel.agentArchivedConversations.collectAsState()
    val projects by viewModel.agentProjects.collectAsState()
    val active by viewModel.activeAgentConversation.collectAsState()
    val status by viewModel.agentStorageStatus.collectAsState()
    val deletion by viewModel.agentConversationDeleteInfo.collectAsState()
    AgentConversationWorkspace(
        rows, active?.id, status, deletion,
        onBack = onClose,
        onOpen = { viewModel.openAgentConversation(it) { onClose() } },
        onNew = { viewModel.newAgentConversation(it) { onClose() } },
        onRename = viewModel::renameAgentConversation,
        onRequestDelete = viewModel::requestDeleteAgentConversation,
        onDismissDelete = viewModel::dismissDeleteAgentConversation,
        onDelete = viewModel::deleteAgentConversation,
        onRetry = viewModel::retryAgentStorage,
        modifier = modifier,
        projects = projects,
        archived = archived,
        onNewInProject = { title, project -> viewModel.newAgentConversation(title, project) { onClose() } },
        onCreateProject = viewModel::createAgentProject,
        onRenameProject = viewModel::renameAgentProject,
        onArchiveProject = viewModel::setAgentProjectArchived,
        onDeleteProject = viewModel::deleteAgentProject,
        onMove = viewModel::moveAgentConversation,
        onArchive = viewModel::setAgentConversationArchived
    )
}

/**
 * Left drawer hosting [content]. It is a separate full-screen window so it also covers the app's
 * bottom navigation; the scrim, a left swipe on the panel and Back all close it.
 */
@Composable
internal fun AgentSessionDrawer(visible: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val transition = remember { MutableTransitionState(false) }
    transition.targetState = visible
    if (!visible && transition.isIdle && !transition.currentState) return
    val dismiss by rememberUpdatedState(onDismiss)
    Dialog(onDismissRequest = { dismiss() }, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.setDimAmount(0f)
            window?.setWindowAnimations(0)
        }
        val scheme = MiuixTheme.colorScheme
        val scope = rememberCoroutineScope()
        val drag = remember { Animatable(0f) }
        Scaffold(containerColor = Color.Transparent) {
            Box(Modifier.fillMaxSize()) {
                AnimatedVisibility(transition, enter = fadeIn(tween(220)), exit = fadeOut(tween(200))) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))
                        .pointerInput(Unit) { detectTapGestures { dismiss() } }
                        .semantics { contentDescription = "关闭会话列表" }
                        .testTag("agent-session-scrim"))
                }
                AnimatedVisibility(transition,
                    enter = slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { -it },
                    exit = slideOutHorizontally(tween(220, easing = FastOutSlowInEasing)) { -it }) {
                    Box(Modifier.widthIn(max = 400.dp).fillMaxWidth(0.88f).fillMaxHeight()
                        .offset { IntOffset(drag.value.roundToInt(), 0) }
                        .clip(RoundedCornerShape(topEnd = PocketTokens.Radius.XLarge, bottomEnd = PocketTokens.Radius.XLarge))
                        .background(scheme.surface)
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    if (drag.value < -size.width * 0.3f) dismiss()
                                    scope.launch { drag.animateTo(0f, tween(200)) }
                                },
                                onDragCancel = { scope.launch { drag.animateTo(0f, tween(200)) } }
                            ) { change, amount ->
                                change.consume()
                                scope.launch { drag.snapTo((drag.value + amount).coerceAtMost(0f)) }
                            }
                        }
                        .testTag("agent-session-drawer")) {
                        content()
                    }
                }
            }
        }
    }
}

/** A rightward swipe that starts at the left edge opens the drawer; it never steals vertical scrolls. */
internal fun Modifier.agentEdgeSwipe(edge: Dp = 24.dp, onOpen: () -> Unit): Modifier = composed {
    val open by rememberUpdatedState(onOpen)
    pointerInput(Unit) {
        val edgePx = edge.toPx()
        val trigger = 56.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.position.x > edgePx) return@awaitEachGesture
            var dx = 0f
            var dy = 0f
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val delta = change.positionChange()
                dx += delta.x
                dy += delta.y
                if (abs(dy) > viewConfiguration.touchSlop && abs(dy) > abs(dx)) break
                if (dx > trigger && dx > abs(dy) * 1.5f) {
                    change.consume()
                    open()
                    break
                }
            }
        }
    }
}

/** "刚刚 / 5 分钟 / 3 小时 / 2 天 / 1 周", then a short date. */
internal fun agentRelativeTime(then: Long, now: Long): String {
    val elapsed = (now - then).coerceAtLeast(0)
    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour
    return when {
        elapsed < minute -> "刚刚"
        elapsed < hour -> "${elapsed / minute} 分钟"
        elapsed < day -> "${elapsed / hour} 小时"
        elapsed < 7 * day -> "${elapsed / day} 天"
        elapsed < 30 * day -> "${elapsed / (7 * day)} 周"
        else -> SimpleDateFormat("M/d", Locale.CHINA).format(Date(then))
    }
}

private data class SessionSection(
    val key: String, val project: AgentProject?, val title: String?, val rows: List<AgentConversation>
)

@Composable
internal fun AgentConversationWorkspace(
    conversations: List<AgentConversation>,
    activeId: String?,
    status: AgentStorageStatus,
    deletion: AgentConversationDeleteInfo?,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onNew: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onRequestDelete: (String) -> Unit,
    onDismissDelete: () -> Unit,
    onDelete: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    projects: List<AgentProject> = emptyList(),
    archived: List<AgentConversation> = emptyList(),
    onNewInProject: (String, String?) -> Unit = { title, _ -> onNew(title) },
    onCreateProject: (String) -> Unit = {},
    onRenameProject: (String, String) -> Unit = { _, _ -> },
    onArchiveProject: (String, Boolean) -> Unit = { _, _ -> },
    onDeleteProject: (String) -> Unit = {},
    onMove: (String, String?) -> Unit = { _, _ -> },
    onArchive: (String, Boolean) -> Unit = { _, _ -> },
    now: Long = System.currentTimeMillis()
) {
    val scheme = MiuixTheme.colorScheme
    var archivedTab by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var collapsed by rememberSaveable { mutableStateOf(listOf<String>()) }
    var menuId by rememberSaveable { mutableStateOf<String?>(null) }
    var movingId by rememberSaveable { mutableStateOf<String?>(null) }
    var projectMenuId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var namingId by rememberSaveable { mutableStateOf<String?>(null) }
    val writable = status.mode == AgentStorageMode.ROOM
    val search = query.trim()
    val liveProjects = projects.filter { it.archivedAt == null }
    val sections = remember(conversations, archived, projects, search, archivedTab) {
        fun matches(row: AgentConversation) = search.isEmpty() || row.title.contains(search, true) ||
            row.lastMessagePreview.contains(search, true)
        if (!archivedTab) {
            val shown = conversations.filter(::matches)
            if (liveProjects.isEmpty()) listOf(SessionSection("all", null, null, shown))
            else {
                val ids = liveProjects.map { it.id }.toSet()
                val grouped = liveProjects.map { project ->
                    val rows = shown.filter { it.projectId == project.id }
                    SessionSection("p-${project.id}", project, project.name, rows)
                }.filter { search.isEmpty() || it.rows.isNotEmpty() }
                    .sortedByDescending { section -> maxOf(section.project!!.updatedAt, section.rows.maxOfOrNull { it.updatedAt } ?: 0) }
                val loose = shown.filter { it.projectId == null || it.projectId !in ids }
                grouped + listOfNotNull(loose.takeIf { it.isNotEmpty() }?.let { SessionSection("loose", null, "未分组", it) })
            }
        } else {
            val shown = archived.filter(::matches)
            val archivedProjects = projects.filter { it.archivedAt != null }
            val ids = archivedProjects.map { it.id }.toSet()
            archivedProjects.map { project ->
                SessionSection("p-${project.id}", project, project.name, shown.filter { it.projectId == project.id })
            }.filter { search.isEmpty() || it.rows.isNotEmpty() } +
                listOfNotNull(shown.filter { it.projectId !in ids }.takeIf { it.isNotEmpty() }
                    ?.let { SessionSection("loose", null, "已归档会话", it) })
        }
    }
    val total = sections.sumOf { it.rows.size }
    val allRows = conversations + archived
    Column(modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(MiuixIcons.Back, "返回 Agent") }
            Text("会话", modifier = Modifier.weight(1f),
                style = MiuixTheme.textStyles.title3.copy(fontWeight = FontWeight.SemiBold))
            IconButton(onClick = { namingId = "project-new" }, enabled = writable,
                modifier = Modifier.testTag("agent-project-create")) {
                Icon(MiuixIcons.GridView, contentDescription = "新建工作区")
            }
            IconButton(onClick = { namingId = "new" }, enabled = writable,
                modifier = Modifier.testTag("agent-session-new")) {
                Icon(MiuixIcons.Add, contentDescription = "新建会话")
            }
        }
        Column(Modifier.padding(horizontal = PocketTokens.PagePadding)) {
            Row(horizontalArrangement = Arrangement.spacedBy(PocketTokens.Space.XS)) {
                PocketPill("进行中", Modifier.weight(1f).testTag("agent-session-tab-active"), selected = !archivedTab) {
                    archivedTab = false
                }
                PocketPill(if (archived.isEmpty()) "已归档" else "已归档 · ${archived.size}",
                    Modifier.weight(1f).testTag("agent-session-tab-archived"), selected = archivedTab) {
                    archivedTab = true
                }
            }
            Spacer(Modifier.height(PocketTokens.Space.S))
            TextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                label = "搜索会话名称或摘要",
                modifier = Modifier.fillMaxWidth().testTag("agent-session-search")
            )
            if (search.isNotEmpty() && total > 0) {
                Text("$total 个 · 按最近更新排序", Modifier.padding(top = PocketTokens.Space.XS),
                    style = MiuixTheme.textStyles.footnote2, color = scheme.onSurfaceVariantSummary)
            }
            if (!writable) {
                TextButton(status.message.ifBlank { "正在读取会话" }, onClick = onRetry,
                    enabled = status.mode in setOf(AgentStorageMode.BLOCKED, AgentStorageMode.LEGACY))
            }
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).testTag("agent-session-list"),
            contentPadding = PaddingValues(start = PocketTokens.Space.XS, end = PocketTokens.Space.XS,
                top = PocketTokens.Space.S, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (total == 0 && sections.none { it.project != null }) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = PocketTokens.Space.M),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(when {
                        search.isNotEmpty() -> "没有匹配的会话"
                        archivedTab -> "没有已归档的会话"
                        else -> "还没有已保存的会话"
                    }, style = MiuixTheme.textStyles.body1)
                    Text(when {
                        search.isNotEmpty() -> "仅搜索会话名称与摘要，不搜索消息全文。"
                        archivedTab -> "长按会话或工作区可归档，归档后聊天记录仍完整保留。"
                        else -> "发送消息、保存草稿或配置后，会话会出现在这里。"
                    }, style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                    if (search.isNotEmpty()) TextButton("清除搜索", onClick = { query = "" })
                    else if (!archivedTab) TextButton("新建会话", enabled = writable, onClick = { namingId = "new" })
                }
            }
            for (section in sections) {
                val open = search.isNotEmpty() || section.key !in collapsed
                if (section.title != null) item(key = "h-${section.key}") {
                    SectionHeader(section, open, showActions = section.project != null, archivedTab = archivedTab,
                        writable = writable,
                        onToggle = { collapsed = if (open) collapsed + section.key else collapsed - section.key },
                        onMore = { projectMenuId = section.project?.id },
                        onNew = { section.project?.let { namingId = "new@${it.id}" } })
                }
                if (open) {
                    if (section.project != null && section.rows.isEmpty() && !archivedTab) item(key = "e-${section.key}") {
                        Text("还没有会话", Modifier.padding(start = 40.dp, top = 4.dp, bottom = 8.dp),
                            style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                    }
                    items(section.rows, key = { it.id }) { row ->
                        SessionRow(row, active = row.id == activeId, indent = section.title != null,
                            time = agentRelativeTime(row.updatedAt, now), writable = writable,
                            onOpen = { onOpen(row.id) }, onMenu = { menuId = row.id })
                    }
                }
            }
        }
    }

    allRows.firstOrNull { it.id == menuId }?.let { row ->
        key(row.id) {
            var next by remember { mutableStateOf<String?>(null) }
            val isArchived = archived.any { it.id == row.id }
            SessionDialog("会话操作", onClosed = {
                menuId = null
                when (next) {
                    "rename" -> namingId = row.id
                    "move" -> movingId = row.id
                    "archive" -> onArchive(row.id, !isArchived)
                    "delete" -> onRequestDelete(row.id)
                }
            }) { close ->
                Text(row.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton("重命名", enabled = writable, onClick = { next = "rename"; close() })
                if (!isArchived && (liveProjects.isNotEmpty() || row.projectId != null)) {
                    TextButton("移动到工作区", enabled = writable, onClick = { next = "move"; close() })
                }
                TextButton(if (isArchived) "恢复会话" else "归档会话", enabled = writable,
                    onClick = { next = "archive"; close() })
                TextButton("删除会话", enabled = writable, onClick = { next = "delete"; close() })
                TextButton("取消", onClick = close)
            }
        }
    }
    conversations.firstOrNull { it.id == movingId }?.let { row ->
        key(row.id) {
            var target by remember { mutableStateOf<Pair<Boolean, String?>?>(null) }
            SessionDialog("移动到工作区", onClosed = {
                movingId = null
                target?.let { onMove(row.id, it.second) }
            }) { close ->
                Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                TextButton("未分组", enabled = writable && row.projectId != null,
                    onClick = { target = true to null; close() })
                liveProjects.forEach { project ->
                    TextButton(project.name, enabled = writable && project.id != row.projectId,
                        onClick = { target = true to project.id; close() })
                }
                TextButton("取消", onClick = close)
            }
        }
    }
    projects.firstOrNull { it.id == projectMenuId }?.let { project ->
        key(project.id) {
            var next by remember { mutableStateOf<String?>(null) }
            val isArchived = project.archivedAt != null
            SessionDialog("工作区操作", onClosed = {
                projectMenuId = null
                when (next) {
                    "rename" -> namingId = "project:${project.id}"
                    "new" -> namingId = "new@${project.id}"
                    "archive" -> onArchiveProject(project.id, !isArchived)
                    "delete" -> deletingProjectId = project.id
                }
            }) { close ->
                Text(project.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton("重命名", enabled = writable, onClick = { next = "rename"; close() })
                if (!isArchived) TextButton("新建会话", enabled = writable, onClick = { next = "new"; close() })
                TextButton(if (isArchived) "恢复工作区" else "归档工作区", enabled = writable,
                    onClick = { next = "archive"; close() })
                TextButton("删除工作区", enabled = writable, onClick = { next = "delete"; close() })
                TextButton("取消", onClick = close)
            }
        }
    }
    projects.firstOrNull { it.id == deletingProjectId }?.let { project ->
        key(project.id) {
            var confirmed by remember { mutableStateOf(false) }
            val count = allRows.count { it.projectId == project.id }
            SessionDialog("删除工作区？", onClosed = {
                deletingProjectId = null
                if (confirmed) onDeleteProject(project.id)
            }) { close ->
                Text(project.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold))
                Text("工作区内的 $count 个会话会移到「未分组」，聊天记录不会删除。", style = MiuixTheme.textStyles.body2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton("取消", onClick = close, modifier = Modifier.weight(1f))
                    TextButton("删除工作区", enabled = writable, modifier = Modifier.weight(1f),
                        onClick = { confirmed = true; close() })
                }
            }
        }
    }
    namingId?.let { id ->
        val newSession = id == "new" || id.startsWith("new@")
        val projectTarget = id.removePrefix("project:").takeIf { id.startsWith("project:") }
        val isProject = id == "project-new" || projectTarget != null
        val original = when {
            projectTarget != null -> projects.firstOrNull { it.id == projectTarget }?.name.orEmpty()
            newSession || isProject -> ""
            else -> allRows.firstOrNull { it.id == id }?.title.orEmpty()
        }
        key(id) {
            var name by rememberSaveable { mutableStateOf(original) }
            var confirmed by remember { mutableStateOf(false) }
            val creating = newSession || id == "project-new"
            SessionDialog(when {
                newSession -> "新建会话"
                id == "project-new" -> "新建工作区"
                isProject -> "重命名工作区"
                else -> "重命名会话"
            }, onClosed = {
                namingId = null
                if (confirmed) {
                    val clean = name.trim()
                    when {
                        id == "new" -> onNew(clean.ifEmpty { "新会话" })
                        id.startsWith("new@") -> onNewInProject(clean.ifEmpty { "新会话" }, id.removePrefix("new@"))
                        id == "project-new" -> onCreateProject(clean)
                        projectTarget != null -> onRenameProject(projectTarget, clean)
                        else -> onRename(id, clean)
                    }
                }
            }) { close ->
                TextField(name, { name = it.take(if (isProject) 60 else 120) },
                    label = if (isProject) "工作区名称" else "会话名称", singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(if (isProject) "agent-project-title" else "agent-session-title"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton("取消", onClick = close, modifier = Modifier.weight(1f))
                    TextButton(if (creating) "创建" else "保存",
                        enabled = writable && (newSession || name.isNotBlank()),
                        modifier = Modifier.weight(1f), onClick = {
                            confirmed = true
                            close()
                        })
                }
            }
        }
    }
    deletion?.let { info ->
        key(info.conversation.id) {
            var confirmed by remember { mutableStateOf(false) }
            SessionDialog("删除会话？", onClosed = {
                if (confirmed) onDelete(info.conversation.id) else onDismissDelete()
            }) { close ->
                Text(info.conversation.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold))
                Text("将删除聊天记录、草稿及 ${info.attachmentCount} 个附件的会话引用。共享附件和相册原文件保留。",
                    style = MiuixTheme.textStyles.body2)
                Text("未完成或待确认任务：${info.unfinishedTaskCount} 个。删除记录不会取消服务器任务。",
                    style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton("取消", onClick = close, modifier = Modifier.weight(1f))
                    TextButton("确认删除", enabled = writable, modifier = Modifier.weight(1f),
                        onClick = { confirmed = true; close() })
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    section: SessionSection, open: Boolean, showActions: Boolean, archivedTab: Boolean, writable: Boolean,
    onToggle: () -> Unit, onMore: () -> Unit, onNew: () -> Unit
) {
    val scheme = MiuixTheme.colorScheme
    val rotation by animateFloatAsState(if (open) 0f else -90f, tween(200), label = "section-chevron")
    Row(Modifier.fillMaxWidth().clip(PocketTokens.Shape.Medium).clickable(onClick = onToggle)
        .testTag(section.project?.let { "agent-project-${it.id}" } ?: "agent-section-${section.key}")
        .padding(start = PocketTokens.Space.XS, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = scheme.onSurfaceVariantSummary,
            modifier = Modifier.size(20.dp).rotate(rotation))
        Spacer(Modifier.width(PocketTokens.Space.XS))
        Text(section.title.orEmpty(), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.SemiBold))
        Text("${section.rows.size}", style = MiuixTheme.textStyles.footnote2, color = scheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = PocketTokens.Space.XS))
        if (showActions) {
            IconButton(onClick = onMore, enabled = writable, modifier = Modifier.size(40.dp)
                .testTag("agent-project-more-${section.project!!.id}")) {
                Icon(MiuixIcons.More, "工作区更多操作", Modifier.size(20.dp))
            }
            if (!archivedTab) IconButton(onClick = onNew, enabled = writable, modifier = Modifier.size(40.dp)
                .testTag("agent-project-new-${section.project.id}")) {
                Icon(MiuixIcons.Add, "在此工作区新建会话", Modifier.size(20.dp))
            }
        } else Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun SessionRow(
    row: AgentConversation, active: Boolean, indent: Boolean, time: String, writable: Boolean,
    onOpen: () -> Unit, onMenu: () -> Unit
) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = if (indent) 20.dp else 0.dp)
            .clip(PocketTokens.Shape.Medium)
            .background(if (active) scheme.surfaceContainerHigh else Color.Transparent)
            .combinedClickable(enabled = writable, onClickLabel = "打开会话", onLongClickLabel = "会话操作",
                onClick = onOpen, onLongClick = onMenu)
            .testTag("agent-session-${row.id}")
            .padding(horizontal = PocketTokens.Space.S, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (active) scheme.primary else scheme.onSurface,
                style = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.Medium))
            Text(buildString {
                if (row.mode == "chat") append("Chat · ")
                append(row.lastMessagePreview.ifBlank { "暂无消息" })
            }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MiuixTheme.textStyles.footnote2, color = scheme.onSurfaceVariantSummary)
        }
        Spacer(Modifier.width(PocketTokens.Space.XS))
        Text(time, style = MiuixTheme.textStyles.footnote2, color = scheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun SessionDialog(
    title: String, onClosed: () -> Unit, content: @Composable ColumnScope.(close: () -> Unit) -> Unit
) {
    val dismissal = rememberDialogDismiss(onClosed)
    OverlayDialog(show = dismissal.show, title = title, onDismissRequest = dismissal::dismiss,
        onDismissFinished = dismissal::onDismissFinished) {
        DialogFormColumn(modifier = dismissal.modifier, footer = {}) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                content(dismissal::dismiss)
            }
        }
    }
}
