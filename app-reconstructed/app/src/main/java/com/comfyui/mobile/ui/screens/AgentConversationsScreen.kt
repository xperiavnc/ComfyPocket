package com.comfyui.mobile.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavController
import com.comfyui.mobile.agent.workspace.AgentConversation
import com.comfyui.mobile.agent.workspace.AgentConversationDeleteInfo
import com.comfyui.mobile.ui.AgentStorageMode
import com.comfyui.mobile.ui.AgentStorageStatus
import com.comfyui.mobile.ui.MainViewModel
import com.comfyui.mobile.ui.components.DialogFormColumn
import com.comfyui.mobile.ui.components.rememberDialogDismiss
import com.comfyui.mobile.ui.screens.agent.agentControlSurface
import java.text.DateFormat
import java.util.Date
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.delay

@Composable
fun AgentConversationsScreen(navController: NavController, viewModel: MainViewModel) {
    val rows by viewModel.agentConversations.collectAsState()
    val active by viewModel.activeAgentConversation.collectAsState()
    val status by viewModel.agentStorageStatus.collectAsState()
    val deletion by viewModel.agentConversationDeleteInfo.collectAsState()
    AgentConversationWorkspace(
        rows, active?.id, status, deletion,
        onBack = { navController.popBackStack() },
        onOpen = { viewModel.openAgentConversation(it) { navController.popBackStack() } },
        onNew = { viewModel.newAgentConversation(it) { navController.popBackStack() } },
        onRename = viewModel::renameAgentConversation,
        onRequestDelete = viewModel::requestDeleteAgentConversation,
        onDismissDelete = viewModel::dismissDeleteAgentConversation,
        onDelete = viewModel::deleteAgentConversation,
        onRetry = viewModel::retryAgentStorage,
        onDeleteMany = viewModel::deleteAgentConversations
    )
}

/** P2 owns sessions only. Project browsing and movement remain P3, not inert placeholder buttons. */
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
    onDeleteMany: (List<String>) -> Unit = {},
    drawer: Boolean = false
) {
    val scheme = MiuixTheme.colorScheme
    var query by rememberSaveable { mutableStateOf("") }
    var selectedIds by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var menuId by rememberSaveable { mutableStateOf<String?>(null) }
    var namingId by rememberSaveable { mutableStateOf<String?>(null) }
    var batchDeleteIds by remember { mutableStateOf<List<String>?>(null) }
    val selectionMode = selectedIds.isNotEmpty()
    val selectedIdsState = rememberUpdatedState(selectedIds)
    val selected = conversations.firstOrNull { it.id == selectedIds.singleOrNull() }
    val writable = status.mode == AgentStorageMode.ROOM
    val shown = remember(conversations, query) {
        val search = query.trim()
        conversations.filter { search.isEmpty() || it.title.contains(search, true) ||
            it.lastMessagePreview.contains(search, true) }
    }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    LaunchedEffect(conversations) {
        val validIds = conversations.mapTo(hashSetOf()) { it.id }
        val validSelected = selectedIds.filter { it in validIds }
        if (validSelected != selectedIds) selectedIds = validSelected
        batchDeleteIds?.let { ids ->
            val validBatch = ids.filter { it in validIds }
            batchDeleteIds = validBatch.takeIf { it.isNotEmpty() }
        }
    }
    PredictiveBackHandler(enabled = selectionMode && menuId == null && namingId == null &&
        deletion == null && batchDeleteIds == null) {
        progress -> progress.collect { }; selectedIds = emptyList()
    }
    BackHandler(enabled = drawer && !selectionMode && menuId == null && namingId == null &&
        deletion == null && batchDeleteIds == null) {
        onBack()
    }
    fun toggleSelection(id: String) {
        val current = selectedIdsState.value
        selectedIds = if (id in current) {
            current.filterNot { it == id }
        } else {
            current + id
        }
    }
    val drawerHeader: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { if (selectionMode) selectedIds = emptyList() else onBack() }) {
                Icon(
                    if (selectionMode) MiuixIcons.Back else MiuixIcons.Close,
                    if (selectionMode) "退出会话选择" else "关闭会话侧边栏"
                )
            }
            Text(
                if (!selectionMode) "会话" else "已选择 ${selectedIds.size} 个会话",
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                style = MiuixTheme.textStyles.title3.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!selectionMode) {
                IconButton(
                    enabled = writable,
                    onClick = { namingId = "new" },
                    modifier = Modifier.testTag("agent-session-new")
                ) {
                    Icon(MiuixIcons.Add, "新建会话")
                }
            } else {
                TextButton("删除", enabled = writable, onClick = {
                    if (selectedIds.size == 1) onRequestDelete(selectedIds.single())
                    else batchDeleteIds = selectedIds.toList()
                })
            }
        }
    }
    val workspaceBody: @Composable (Modifier) -> Unit = { bodyModifier ->
        Column(
            bodyModifier.fillMaxSize().imePadding()
                .padding(horizontal = if (drawer) 16.dp else 20.dp)
        ) {
            TextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                label = "搜索会话名称或摘要",
                modifier = Modifier.fillMaxWidth().testTag("agent-session-search")
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (query.isBlank()) "最近会话" else "搜索结果",
                        style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold))
                    Text("${shown.size} 个 · 按最近更新排序", style = MiuixTheme.textStyles.footnote1,
                        color = scheme.onSurfaceVariantSummary)
                }
                if (selected != null) TextButton("重命名", enabled = writable, onClick = { namingId = selected.id })
            }
            if (!writable) {
                TextButton(status.message.ifBlank { "正在读取会话" }, onClick = onRetry,
                    enabled = status.mode in setOf(AgentStorageMode.BLOCKED, AgentStorageMode.LEGACY))
            }
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f).testTag("agent-session-list"),
                contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (shown.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (query.isBlank()) "还没有已保存的会话" else "没有匹配的会话",
                            style = MiuixTheme.textStyles.body1)
                        Text(if (query.isBlank()) "发送消息、保存草稿或配置后，会话会出现在这里。"
                            else "仅搜索会话名称与摘要，不搜索消息全文。",
                            style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                        TextButton(if (query.isBlank()) "新建会话" else "清除搜索",
                            enabled = writable,
                            onClick = { if (query.isBlank()) namingId = "new" else query = "" })
                    }
                }
                items(shown, key = { it.id }) { row ->
                    val interactions = remember { MutableInteractionSource() }
                    val rowSelected = row.id in selectedIds
                    val rowColor by animateColorAsState(
                        if (rowSelected) scheme.secondaryContainer else Color.Transparent,
                        tween(160), label = "agentSessionSelection"
                    )
                    Row(
                        modifier = Modifier.animateItem().fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp)).background(rowColor)
                            .agentControlSurface(interactions)
                            .semantics { this.selected = rowSelected }
                            .combinedClickable(enabled = writable, interactionSource = interactions, indication = null,
                                onClick = { if (selectionMode) toggleSelection(row.id) else onOpen(row.id) },
                                onLongClick = { toggleSelection(row.id) })
                            .testTag("agent-session-${row.id}").padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.Medium))
                            Text(row.lastMessagePreview.ifBlank { "暂无消息" }, maxLines = 2,
                                overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.footnote1,
                                color = scheme.onSurfaceVariantSummary)
                            Text(buildString {
                                if (row.id == activeId) append("当前 · ")
                                append(if (row.mode == "chat") "Chat" else "绘图")
                                append(" · ").append(dateFormat.format(Date(row.updatedAt)))
                            }, style = MiuixTheme.textStyles.footnote2, color = scheme.primary)
                        }
                        if (selectionMode) {
                            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                if (rowSelected) {
                                    Icon(MiuixIcons.Ok, "已选择", Modifier.size(22.dp), tint = scheme.primary)
                                }
                            }
                        } else {
                            IconButton(enabled = writable, modifier = Modifier.size(48.dp).testTag("agent-session-more-${row.id}"),
                                onClick = { menuId = row.id }) {
                                Icon(MiuixIcons.More, "会话更多操作", Modifier.size(22.dp))
                            }
                        }
                    }
                }
            }
        }
    }
    if (drawer) {
        Column(
            Modifier.fillMaxSize().background(scheme.surfaceContainer)
                .semantics { paneTitle = "会话管理" }
        ) {
            Box(Modifier.fillMaxWidth().statusBarsPadding()) {
                drawerHeader()
            }
            workspaceBody(Modifier.weight(1f).navigationBarsPadding())
        }
    } else {
        Scaffold(containerColor = scheme.background, topBar = {
            TopAppBar(
                title = if (!selectionMode) "会话" else "已选择 ${selectedIds.size} 个会话",
                navigationIcon = {
                    IconButton(onClick = { if (selectionMode) selectedIds = emptyList() else onBack() }) {
                        Icon(MiuixIcons.Back, if (!selectionMode) "返回 Agent" else "退出会话选择")
                    }
                },
                actions = {
                    if (!selectionMode) TextButton("新建", enabled = writable,
                        onClick = { namingId = "new" }, modifier = Modifier.testTag("agent-session-new"))
                    else TextButton("删除", enabled = writable, onClick = {
                        if (selectedIds.size == 1) onRequestDelete(selectedIds.single())
                        else batchDeleteIds = selectedIds.toList()
                    })
                }
            )
        }) { padding ->
            workspaceBody(Modifier.padding(padding))
        }
    }
    conversations.firstOrNull { it.id == menuId }?.let { row ->
        key(row.id) {
            var next by remember { mutableStateOf<String?>(null) }
            SessionDialog("会话操作", onClosed = {
                menuId = null
                if (next == "rename") namingId = row.id
                if (next == "delete") onRequestDelete(row.id)
            }) { close ->
                Text(row.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton("重命名", enabled = writable, onClick = { next = "rename"; close() })
                TextButton("删除会话", enabled = writable, onClick = { next = "delete"; close() })
                TextButton("取消", onClick = close)
            }
        }
    }
    batchDeleteIds?.let { ids ->
        key(ids.joinToString("|")) {
            var confirmed by remember { mutableStateOf(false) }
            SessionDialog("删除 ${ids.size} 个会话？", onClosed = {
                batchDeleteIds = null
                if (confirmed) {
                    selectedIds = emptyList()
                    onDeleteMany(ids)
                }
            }) { close ->
                Text("将删除所选会话的聊天记录、草稿及附件引用。共享附件和相册原文件保留。",
                    style = MiuixTheme.textStyles.body2)
                Text(ids.mapNotNull { id -> conversations.firstOrNull { it.id == id }?.title }
                    .joinToString("\n").ifBlank { "所选会话已变更，请刷新后重试。" },
                    maxLines = 6, overflow = TextOverflow.Ellipsis,
                    style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton("取消", onClick = close, modifier = Modifier.weight(1f))
                    TextButton("确认删除", enabled = writable, modifier = Modifier.weight(1f),
                        onClick = { confirmed = true; close() })
                }
            }
        }
    }
    namingId?.let { id ->
        val original = conversations.firstOrNull { it.id == id }?.title.orEmpty()
        key(id) {
            var name by rememberSaveable { mutableStateOf(original) }
            var confirmed by remember { mutableStateOf(false) }
            SessionDialog(if (id == "new") "新建会话" else "重命名会话",
                onClosed = {
                    namingId = null
                    if (confirmed) {
                        if (id == "new") onNew(name.trim().ifEmpty { "新会话" }) else onRename(id, name.trim())
                    }
                }) { close ->
                TextField(name, { name = it.take(120) }, label = "会话名称",
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("agent-session-title"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton("取消", onClick = close, modifier = Modifier.weight(1f))
                    TextButton(if (id == "new") "创建" else "保存",
                        enabled = writable && (id == "new" || name.isNotBlank()),
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
                if (confirmed) {
                    selectedIds = emptyList()
                    onDelete(info.conversation.id)
                } else onDismissDelete()
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

/**
 * A real modal drawer host. It lives in a Dialog window so the app shell's bottom
 * navigation cannot render above the drawer or receive taps through its scrim.
 */
@Composable
internal fun AgentConversationDrawerDialog(
    conversations: List<AgentConversation>,
    activeId: String?,
    status: AgentStorageStatus,
    deletion: AgentConversationDeleteInfo?,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onNew: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onRequestDelete: (String) -> Unit,
    onDismissDelete: () -> Unit,
    onDelete: (String) -> Unit,
    onDeleteMany: (List<String>) -> Unit,
    onRetry: () -> Unit
) {
    val scheme = MiuixTheme.colorScheme
    var visible by remember { mutableStateOf(false) }
    var dismissing by remember { mutableStateOf(false) }
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    fun requestDismiss() {
        if (!dismissing) {
            dismissing = true
            visible = false
        }
    }
    LaunchedEffect(Unit) {
        visible = true
    }
    LaunchedEffect(dismissing) {
        if (dismissing) {
            delay(220)
            currentOnDismiss()
        }
    }
    Dialog(
        onDismissRequest = ::requestDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(Modifier.fillMaxSize().testTag("agent-conversation-drawer-window")) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(180)),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    Modifier.fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.32f))
                        .clickable(onClick = ::requestDismiss)
                        .testTag("agent-conversation-drawer-scrim")
                )
            }
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(120)) + slideInHorizontally(tween(220)) { -it },
                exit = fadeOut(tween(160)) + slideOutHorizontally(tween(180)) { -it },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .fillMaxWidth(0.82f)
                    .widthIn(max = 420.dp)
                    .background(scheme.surfaceContainer)
                    .testTag("agent-conversation-drawer")
            ) {
                AgentConversationWorkspace(
                    conversations = conversations,
                    activeId = activeId,
                    status = status,
                    deletion = deletion,
                    onBack = ::requestDismiss,
                    onOpen = { id ->
                        onOpen(id)
                        requestDismiss()
                    },
                    onNew = { title ->
                        onNew(title)
                        requestDismiss()
                    },
                    onRename = onRename,
                    onRequestDelete = onRequestDelete,
                    onDismissDelete = onDismissDelete,
                    onDelete = onDelete,
                    onDeleteMany = onDeleteMany,
                    onRetry = onRetry,
                    drawer = true
                )
            }
        }
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
