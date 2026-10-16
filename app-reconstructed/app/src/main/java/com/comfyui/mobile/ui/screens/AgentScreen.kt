package com.comfyui.mobile.ui.screens

import com.comfyui.mobile.model.CharacterLore
import com.comfyui.mobile.ui.screens.agent.AgentImagePreviewCard
import com.comfyui.mobile.ui.screens.agent.AgentCardStatusFactory
import com.comfyui.mobile.ui.screens.agent.AgentCapsuleBar
import com.comfyui.mobile.ui.screens.agent.AgentChatPanel
import com.comfyui.mobile.ui.screens.agent.AgentStyleAuxParamsDialog
import com.comfyui.mobile.ui.screens.agent.AgentCharacterLoreDialog
import com.comfyui.mobile.network.ConnectionState
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.delay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.comfyui.mobile.model.EditableNode
import com.comfyui.mobile.model.MediaType
import com.comfyui.mobile.ui.LocalFloatingNavClearance
import com.comfyui.mobile.ui.MainViewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import com.comfyui.mobile.ui.components.DialogFormColumn
@Composable
fun AgentScreen(navController: NavController, viewModel: MainViewModel) {
    val messages by viewModel.agentMessages.collectAsState()
    val busy by viewModel.agentBusy.collectAsState()
    val agentLlmBusy by viewModel.agentLlmBusy.collectAsState()
    val agentImageBusy by viewModel.agentImageBusy.collectAsState()
    val agentInteractionMode by viewModel.agentInteractionMode.collectAsState()
    val agentAttachments by viewModel.agentPendingAttachments.collectAsState()
    val latest by viewModel.agentLatestGenerationList.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val isConnected = connectionState == ConnectionState.CONNECTED
    val progress by viewModel.progress.collectAsState()
    val queueSize by viewModel.queueSize.collectAsState()
    val executingNodeName by viewModel.agentExecutingNodeName.collectAsState()
    val rhAppStatus by viewModel.rhAppStatus.collectAsState()
    val workflowLabel by viewModel.agentWorkflowLabel.collectAsState()
    val lastPrompt by viewModel.agentLastExpandedPrompt.collectAsState()
    val savedFiles by viewModel.savedWorkflowFiles.collectAsState()
    val targetNodeId by viewModel.agentTargetNodeId.collectAsState()
    val agentArtistNodeId by viewModel.agentArtistNodeId.collectAsState()
    val agentAuxNodeKeys by viewModel.agentAuxNodeKeys.collectAsState()
    val editableNodes by viewModel.agentEditableNodes.collectAsState()
    val favoriteArtists by viewModel.favoriteArtists.collectAsState()
    val characterLores by viewModel.characterLores.collectAsState()
    val llmProfiles by viewModel.llmProfiles.collectAsState()
    val currentLlmProfileId by viewModel.currentLlmProfileId.collectAsState()
    val currentLlmProfile by viewModel.currentLlmProfile.collectAsState()
    val llmModel by viewModel.llmModel.collectAsState()
    val servers by viewModel.servers.collectAsState()
    val currentServerId by viewModel.currentServerId.collectAsState()
    val isCloudMode = servers.firstOrNull { it.effectiveId == currentServerId || it.id == currentServerId }?.isRunningHub == true
    val floatingNavClearance = LocalFloatingNavClearance.current
    val scheme = MiuixTheme.colorScheme
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()

    var input by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }

    val agentImagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(4)
    ) { uris -> viewModel.addAgentImageAttachments(uris) }
    val agentFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> viewModel.addAgentFileAttachments(uris) }

    var showModelSwitchDialog by remember { mutableStateOf(false) }
    var showWorkflowSheet by remember { mutableStateOf(false) }
    var workflowSheetLevel by remember { mutableStateOf(1) }
    var showPromptDialog by remember { mutableStateOf(false) }
    var showStyleParamsDialog by remember { mutableStateOf(false) }
    var showSelectArtistNodeDialog by remember { mutableStateOf(false) }
    var showManageAuxNodesDialog by remember { mutableStateOf(false) }
    var showCharacterLoreDialog by remember { mutableStateOf(false) }
    var selectedCharacterLoreToEdit by remember { mutableStateOf<CharacterLore?>(null) }
    var quickEditTriggerLore by remember { mutableStateOf<CharacterLore?>(null) }
    var showImageViewer by remember { mutableStateOf(false) }
    var fullScreenMediaIndex by remember { mutableStateOf(0) }
    var showClearMediaConfirmDialog by remember { mutableStateOf(false) }
    var showCloudModelBrowser by remember { mutableStateOf(false) }
    var cloudModelInitialType by remember { mutableStateOf("CHECKPOINT") }
    var cloudModelTargetNodeId by remember { mutableStateOf<String?>(null) }
    var cloudModelTargetKey by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        viewModel.refreshAiApps()
        viewModel.refreshSavedWorkflows()
        viewModel.preloadAgentWorkflow()
    }
    LaunchedEffect(isConnected) {
        if (isConnected) {
            viewModel.checkLatestHistory()
        }
    }
    val lastMsg = messages.lastOrNull()
    LaunchedEffect(messages.size, lastMsg?.id, lastMsg?.isThinking, lastMsg?.errorMessage) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }
    LaunchedEffect(lastMsg?.content) {
        if (messages.isNotEmpty() && lastMsg?.role == "assistant") {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (lastVisible >= messages.size - 2) {
                listState.scrollToItem(messages.lastIndex)
            }
        }
    }

    val displayMediaList = remember(latest) {
        latest.filter { it.mediaType == MediaType.IMAGE || it.mediaType == MediaType.VIDEO }
    }
    val wfLabel = if (workflowLabel.isBlank()) {
        "工作流"
    } else {
        workflowLabel.substringBeforeLast('.').ifBlank { workflowLabel }
    }
    val cardColors = CardDefaults.defaultColors(
        color = scheme.surfaceContainerHighest,
        contentColor = scheme.onSurface
    )
    val sidePad = 20.dp
    val isDrawMode = agentInteractionMode == MainViewModel.AGENT_MODE_DRAW

    // 0.8s "完成" micro-feedback when an image run ends.
    // IMPORTANT: do not wait for LaunchedEffect to latch briefComplete — that leaves one
    // Idle frame (overlay/chips flash) between imageBusy=false and BriefComplete.
    var briefComplete by remember { mutableStateOf(false) }
    var wasImageBusy by remember { mutableStateOf(false) }
    var awaitingCompletePulse by remember { mutableStateOf(false) }

    SideEffect {
        if (agentImageBusy) {
            awaitingCompletePulse = false
            if (briefComplete) briefComplete = false
        } else if (wasImageBusy) {
            awaitingCompletePulse = true
        }
        wasImageBusy = agentImageBusy
    }

    // Latch complete on a separate effect from the 0.8s clear timer — clearing
    // awaitingCompletePulse must not cancel the delay (would stick on "完成").
    LaunchedEffect(awaitingCompletePulse, displayMediaList.isNotEmpty()) {
        if (!awaitingCompletePulse) return@LaunchedEffect
        if (displayMediaList.isNotEmpty()) {
            briefComplete = true
            awaitingCompletePulse = false
        } else {
            // Busy ended but media not yet in list — wait briefly, then drop the latch.
            delay(2500)
            if (displayMediaList.isEmpty()) awaitingCompletePulse = false
        }
    }
    LaunchedEffect(briefComplete) {
        if (!briefComplete) return@LaunchedEffect
        delay(800)
        briefComplete = false
    }

    val lastAssistantError = messages.lastOrNull { it.role == "assistant" && !it.errorMessage.isNullOrBlank() }
        ?.errorMessage
    // Bridge the busy→complete edge in the same frame (wasImageBusy still true before SideEffect).
    val effectiveBriefComplete = !agentImageBusy &&
        displayMediaList.isNotEmpty() &&
        (briefComplete || wasImageBusy || awaitingCompletePulse)
    val cardStatus = remember(
        agentLlmBusy, agentImageBusy, isDrawMode, isCloudMode, progress,
        executingNodeName, rhAppStatus, queueSize, effectiveBriefComplete, lastAssistantError
    ) {
        AgentCardStatusFactory.resolve(
            llmBusy = agentLlmBusy,
            imageBusy = agentImageBusy,
            isDrawMode = isDrawMode,
            isCloudMode = isCloudMode,
            progress = progress,
            executingNodeName = executingNodeName,
            rhAppStatus = rhAppStatus,
            queueSize = queueSize,
            briefComplete = effectiveBriefComplete,
            errorMessage = lastAssistantError
    )
    }


    // Edge-to-edge: adjustResize alone does not lift Compose content; pad by IME,
    // but do not stack it on top of floating-nav clearance (use the larger one).
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val bottomPad = maxOf(imeBottom, floatingNavClearance)

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                title = "Agent",
                largeTitle = "Agent",
                color = scheme.background,
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = { navController.navigate("agent_settings") }) {
                        Icon(MiuixIcons.Settings, contentDescription = "Agent 设置")
                    }
                }
    )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(top = innerPadding.calculateTopPadding())
                .padding(bottom = bottomPad)
    ) {
            AgentImagePreviewCard(
                mediaList = displayMediaList,
                previewUrl = { viewModel.previewUrl(it) },
                status = cardStatus,
                wfLabel = wfLabel,
                cardColors = cardColors,
                sidePad = sidePad,
                modifier = Modifier.weight(0.54f),
                onImageClick = { index ->
                    fullScreenMediaIndex = index
                    showImageViewer = true
                },
                onClearMediaListClick = {
                    showClearMediaConfirmDialog = true
                },
                onWorkflowClick = {
                    workflowSheetLevel = 1
                    showWorkflowSheet = true
                },
                onStyleParamsClick = { showStyleParamsDialog = true },
                onPromptClick = { showPromptDialog = true }
    )

            val modelDisplayName = currentLlmProfile?.displayName ?: llmModel.ifBlank { "大模型" }

            AgentCapsuleBar(
                modelDisplayName = modelDisplayName,
                interactionMode = agentInteractionMode,
                sidePad = sidePad,
                onModelSwitchClick = { showModelSwitchDialog = true },
                onInteractionModeChange = { mode ->
                    viewModel.setAgentInteractionMode(mode)
                },
                onResetChatClick = {
                    viewModel.clearAgentChat()
                    Toast.makeText(context, "已重置对话", Toast.LENGTH_SHORT).show()
                }
    )

            AgentChatPanel(
                messages = messages,
                input = input,
                onInputChange = { input = it },
                busy = busy,
                cardColors = cardColors,
                sidePad = sidePad,
                listState = listState,
                modifier = Modifier.weight(0.46f),
                attachments = agentAttachments,
                onPickImages = {
                    agentImagePickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onPickFiles = {
                    agentFilePickerLauncher.launch(
                        arrayOf("text/*", "application/json", "application/xml", "application/x-yaml")
                    )
                },
                onRemoveAttachment = { viewModel.removeAgentAttachment(it) },
                onStop = { viewModel.stopAgentGeneration() },
                onSendMessage = { text ->
                    viewModel.sendAgentMessage(text)
                    input = TextFieldValue("")
                }
    )
        }
    }

    OverlayDialog(
        show = showModelSwitchDialog,
        title = "切换模型与 API",
        onDismissRequest = { showModelSwitchDialog = false }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
                items(llmProfiles, key = { it.id }) { item ->
                    val isChosen = item.id == currentLlmProfileId
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (isChosen) {
                                    Modifier.border(
                                        1.5.dp,
                                        scheme.primary.copy(alpha = 0.5f),
                                        RoundedCornerShape(16.dp)
    )
                                } else Modifier
                            ),
                        cornerRadius = 16.dp,
                        colors = CardDefaults.defaultColors(
                            color = if (isChosen) {
                                scheme.secondaryContainer.copy(alpha = 0.7f)
                            } else {
                                scheme.surfaceContainerHigh
                            }
    ),
                        pressFeedbackType = PressFeedbackType.Tilt,
                        showIndication = true,
                        onClick = {
                            viewModel.selectLlmProfile(item.id)
                            showModelSwitchDialog = false
                        }
    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.displayName,
                                    style = MiuixTheme.textStyles.body1.copy(
                                        fontWeight = if (isChosen) FontWeight.SemiBold else FontWeight.Medium
    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
    )
                                if (item.shortSummary.isNotBlank() && item.shortSummary != item.displayName) {
                                    Text(
                                        text = item.shortSummary,
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = scheme.onSurfaceVariantSummary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
    )
                                }
                            }
                            if (isChosen) {
                                Icon(
                                    MiuixIcons.Ok,
                                    contentDescription = "已选择",
                                    tint = scheme.primary,
                                    modifier = Modifier.size(20.dp)
    )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = "管理",
                    onClick = {
                        showModelSwitchDialog = false
                        navController.navigate("agent_settings")
                    }
    )
                TextButton(
                    modifier = Modifier.weight(0.7f),
                    text = "关闭",
                    onClick = { showModelSwitchDialog = false }
    )
            }
        }
    }

    OverlayDialog(
        show = showWorkflowSheet,
        title = if (workflowSheetLevel == 1) "选择工作流" else "选择提示词节点",
        onDismissRequest = {
            showWorkflowSheet = false
            workflowSheetLevel = 1
        }
    ) {
        val textNodes = remember(editableNodes) {
            editableNodes
                .filterIsInstance<EditableNode.TextInput>()
                .filter { it.options.isNullOrEmpty() }
        }

        if (workflowSheetLevel == 1) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (workflowLabel.isNotBlank()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 14.dp,
                        colors = CardDefaults.defaultColors(color = scheme.surfaceContainerHigh),
                        insideMargin = PaddingValues(12.dp)
    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
    ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "当前：$wfLabel",
                                    style = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
    )
                                Text(
                                    text = "提示词节点：${if (targetNodeId.isBlank()) "自动探测" else "#$targetNodeId"}",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = scheme.primary
    )
                            }
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(scheme.primaryContainer)
                                    .clickable { workflowSheetLevel = 2 }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
                                Text(
                                    text = "设置节点",
                                    fontSize = 11.sp,
                                    color = scheme.onPrimaryContainer,
                                    fontWeight = FontWeight.SemiBold
    )
                                Icon(
                                    MiuixIcons.Edit,
                                    contentDescription = null,
                                    modifier = Modifier.size(11.dp),
                                    tint = scheme.onPrimaryContainer
    )
                            }
                        }
                    }
                }

                if (savedFiles.isEmpty()) {
                    Text(
                        text = "暂无本地存档，请先在「控制」页保存工作流",
                        style = MiuixTheme.textStyles.body2,
                        color = scheme.onSecondaryContainer
    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
                        items(savedFiles, key = { it.absolutePath }) { file ->
                            val label = file.name.substringBeforeLast('.').ifBlank { file.name }
                            val current = workflowLabel == file.name || workflowLabel == label
                            val sizeLabel = (file.length() / 1024f).let { kb ->
                                if (kb >= 1024f) String.format("%.1f MB", kb / 1024f)
                                else String.format("%.0f KB", kb)
                            }
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (current) {
                                            Modifier.border(
                                                1.5.dp,
                                                scheme.primary.copy(alpha = 0.5f),
                                                RoundedCornerShape(16.dp)
    )
                                        } else {
                                            Modifier
                                        }
                                    ),
                                cornerRadius = 16.dp,
                                colors = CardDefaults.defaultColors(
                                    color = if (current) {
                                        scheme.secondaryContainer.copy(alpha = 0.7f)
                                    } else {
                                        scheme.secondaryContainer
                                    }
    ),
                                pressFeedbackType = PressFeedbackType.Tilt,
                                showIndication = true,
                                onClick = {
                                    viewModel.setAgentDefaultWorkflow(
                                        source = MainViewModel.AGENT_WF_SAVED,
                                        id = file.name,
                                        label = label
    )
                                    workflowSheetLevel = 2
                                }
    ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
                                    Icon(
                                        MiuixIcons.File,
                                        contentDescription = null,
                                        tint = if (current) scheme.primary else scheme.onSecondaryContainer,
                                        modifier = Modifier.size(22.dp)
    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = label,
                                            style = MiuixTheme.textStyles.body1.copy(
                                                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium
    ),
                                            maxLines = 2
    )
                                        Text(
                                            text = sizeLabel,
                                            style = MiuixTheme.textStyles.footnote1,
                                            color = scheme.onSecondaryContainer
    )
                                    }
                                    if (current) {
                                        Icon(
                                            MiuixIcons.Ok,
                                            contentDescription = "当前",
                                            tint = scheme.primary,
                                            modifier = Modifier.size(22.dp)
    )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                TextButton(
                    text = "关闭",
                    onClick = {
                        showWorkflowSheet = false
                        workflowSheetLevel = 1
                    },
                    modifier = Modifier.fillMaxWidth()
    )
            }
        } else {
            // === 第 2 级：选择提示词注入节点 ===
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(scheme.surfaceContainerHigh)
                        .clickable { workflowSheetLevel = 1 }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
                    Icon(
                        MiuixIcons.Back,
                        contentDescription = "返回工作流",
                        modifier = Modifier.size(12.dp),
                        tint = scheme.primary
    )
                    Text(
                        text = "返回工作流列表",
                        style = MiuixTheme.textStyles.footnote1,
                        color = scheme.primary
    )
                }

                Text(
                    text = "选择 AI 扩写后提示词写入的节点，使用组合器时请选择描述提示词节点。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = scheme.onSurfaceVariantSummary
    )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
                    item(key = "auto") {
                        val isChosen = targetNodeId.isBlank()
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (isChosen) {
                                        Modifier.border(
                                            1.5.dp,
                                            scheme.primary.copy(alpha = 0.5f),
                                            RoundedCornerShape(16.dp)
    )
                                    } else Modifier
                                ),
                            cornerRadius = 16.dp,
                            colors = CardDefaults.defaultColors(
                                color = if (isChosen) {
                                    scheme.secondaryContainer.copy(alpha = 0.7f)
                                } else {
                                    scheme.surfaceContainerHigh
                                }
    ),
                            pressFeedbackType = PressFeedbackType.Tilt,
                            showIndication = true,
                            onClick = {
                                viewModel.setAgentTargetNodeId("")
                                showWorkflowSheet = false
                                workflowSheetLevel = 1
                            }
    ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "自动探测",
                                        style = MiuixTheme.textStyles.body1.copy(
                                            fontWeight = if (isChosen) FontWeight.SemiBold else FontWeight.Medium
    )
    )
                                    Text(
                                        text = "智能匹配正向提示词 · 防断线保护",
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = scheme.onSurfaceVariantSummary
    )
                                }
                                if (isChosen) {
                                    Icon(
                                        MiuixIcons.Ok,
                                        contentDescription = "已选择",
                                        tint = scheme.primary,
                                        modifier = Modifier.size(20.dp)
    )
                                }
                            }
                        }
                    }

                    if (textNodes.isEmpty()) {
                        item(key = "empty") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
    ) {
                                Text(
                                    text = "当前工作流暂无可用文本节点，请先选择工作流",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = scheme.onSurfaceVariantSummary
    )
                            }
                        }
                    } else {
                        items(textNodes, key = { "${it.nodeId}:${it.key}" }) { node ->
                            val isChosen = targetNodeId == node.nodeId
                            val previewText = node.text.trim().replace("\n", " ").take(45).ifBlank { "(空)" }
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (isChosen) {
                                            Modifier.border(
                                                1.5.dp,
                                                scheme.primary.copy(alpha = 0.5f),
                                                RoundedCornerShape(16.dp)
    )
                                        } else Modifier
                                    ),
                                cornerRadius = 16.dp,
                                colors = CardDefaults.defaultColors(
                                    color = if (isChosen) {
                                        scheme.secondaryContainer.copy(alpha = 0.7f)
                                    } else {
                                        scheme.surfaceContainerHigh
                                    }
    ),
                                pressFeedbackType = PressFeedbackType.Tilt,
                                showIndication = true,
                                onClick = {
                                    viewModel.setAgentTargetNodeId(node.nodeId)
                                    showWorkflowSheet = false
                                    workflowSheetLevel = 1
                                }
    ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = node.title,
                                            style = MiuixTheme.textStyles.body1.copy(
                                                fontWeight = if (isChosen) FontWeight.SemiBold else FontWeight.Medium
    ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
    )
                                        Text(
                                            text = "#${node.nodeId} · ${node.classType}",
                                            style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp),
                                            color = scheme.primary.copy(alpha = 0.8f)
    )
                                        Text(
                                            text = previewText,
                                            style = MiuixTheme.textStyles.footnote1,
                                            color = scheme.onSurfaceVariantSummary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
    )
                                    }
                                    if (isChosen) {
                                        Icon(
                                            MiuixIcons.Ok,
                                            contentDescription = "已选择",
                                            tint = scheme.primary,
                                            modifier = Modifier.size(20.dp)
    )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
                    TextButton(
                        modifier = Modifier.weight(1f),
                        text = "返回工作流",
                        onClick = { workflowSheetLevel = 1 }
    )
                    TextButton(
                        modifier = Modifier.weight(1f),
                        text = "完成",
                        onClick = {
                            showWorkflowSheet = false
                            workflowSheetLevel = 1
                        }
    )
                }
            }
        }
    }

    OverlayDialog(
        show = showPromptDialog,
        title = "出图提示词",
        onDismissRequest = { showPromptDialog = false }
    ) {
        val promptText = if (lastPrompt.isBlank()) "暂无提示词，先发一句出图吧。" else lastPrompt
        SelectionContainer {
            Text(
                text = promptText,
                color = scheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .verticalScroll(rememberScrollState())
    )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                text = "复制",
                onClick = {
                    if (lastPrompt.isNotBlank()) {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("prompt", lastPrompt))
                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                    }
                }
    )
            TextButton(text = "关闭", onClick = { showPromptDialog = false })
        }
    }

    if (showImageViewer && displayMediaList.isNotEmpty()) {
        FullScreenPreview(
            initialIndex = fullScreenMediaIndex.coerceIn(0, displayMediaList.lastIndex),
            mediaList = displayMediaList,
            viewModel = viewModel,
            isLocalGallery = false,
            onDismiss = { showImageViewer = false }
    )
    }

    if (showClearMediaConfirmDialog) {
        OverlayDialog(
            show = true,
            title = "清空生图记录",
            onDismissRequest = { showClearMediaConfirmDialog = false }
    ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
                Text(
                    text = "确定清空 Agent 当前的所有生图记录吗？清空后将恢复为初始状态。",
                    style = MiuixTheme.textStyles.body2,
                    color = scheme.onSurfaceVariantSummary
    )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
                    TextButton(
                        text = "取消",
                        onClick = { showClearMediaConfirmDialog = false },
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            viewModel.clearAgentLatestGenerationList()
                            showClearMediaConfirmDialog = false
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary()
    ) {
                        Text("确定清空", fontSize = 13.sp)
                    }
                }
            }
        }
    }

    val nonTargetTextNodes = remember(editableNodes, targetNodeId) {
        editableNodes
            .filterIsInstance<EditableNode.TextInput>()
            .filter { it.nodeId != targetNodeId && it.options.isNullOrEmpty() }
    }

    val boundArtistNode = remember(nonTargetTextNodes, agentArtistNodeId) {
        if (agentArtistNodeId.isNotBlank()) {
            editableNodes.filterIsInstance<EditableNode.TextInput>().firstOrNull { it.nodeId == agentArtistNodeId }
        } else {
            // Title-based auto-guess only; do not fall back to an arbitrary text node.
            nonTargetTextNodes.firstOrNull {
                val t = it.title.lowercase()
                t.contains("画师") || t.contains("artist") || t.contains("style") || t.contains("风格")
            }
        }
    }

    val auxDisplayNodes = remember(editableNodes, agentAuxNodeKeys) {
        if (agentAuxNodeKeys.isEmpty()) {
            emptyList()
        } else {
            editableNodes.filter { node ->
                val cKey = when (node) {
                    is EditableNode.TextInput -> "${node.nodeId}:${node.key}"
                    is EditableNode.NumberInput -> "${node.nodeId}:${node.key}"
                    is EditableNode.BooleanInput -> "${node.nodeId}:${node.key}"
                    is EditableNode.ImageInput -> "${node.nodeId}:${node.key}"
                }
                agentAuxNodeKeys.contains(cKey)
            }
        }
    }

    AgentStyleAuxParamsDialog(
        show = showStyleParamsDialog,
        onDismissRequest = { showStyleParamsDialog = false },
        boundArtistNode = boundArtistNode,
        favoriteArtists = favoriteArtists,
        onSelectArtistNodeClick = { showSelectArtistNodeDialog = true },
        onArtistChange = { nodeId, key, text ->
            viewModel.updateAgentNodeValue(nodeId, key, text)
        },
        onAddFavoriteArtist = { artist ->
            viewModel.addFavoriteArtist(artist)
        },
        onRemoveFavoriteArtist = { artist ->
            viewModel.removeFavoriteArtist(artist)
        },
        characterLores = characterLores,
        onManageCharacterLoresClick = {
            selectedCharacterLoreToEdit = null
            showCharacterLoreDialog = true
        },
        onSelectCharacterLoreToEdit = { lore ->
            quickEditTriggerLore = lore
        },
        onToggleCharacterLore = { id ->
            viewModel.toggleCharacterLoreEnabled(id)
        },
        auxDisplayNodes = auxDisplayNodes,
        onManageAuxNodesClick = { showManageAuxNodesDialog = true },
        onAuxParamChange = { nodeId, key, value ->
            viewModel.updateAgentNodeValue(nodeId, key, value)
        },
        onResetWorkflowClick = { viewModel.resetAgentSavedWorkflow() },
        isCloudMode = isCloudMode,
        onBrowseCloudModel = { nodeId, key, type ->
            cloudModelTargetNodeId = nodeId
            cloudModelTargetKey = key
            cloudModelInitialType = type
            showCloudModelBrowser = true
        }
    )

    com.comfyui.mobile.ui.screens.control.CloudModelBrowserDialog(
        show = showCloudModelBrowser,
        initialResourceType = cloudModelInitialType,
        viewModel = viewModel,
        onDismiss = { showCloudModelBrowser = false },
        textNodes = editableNodes.filterIsInstance<EditableNode.TextInput>(),
        extraTriggerAction = { lora, triggers, dismiss ->
            TextButton(
                text = "加入角色库",
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = {
                    val targetNodeId = cloudModelTargetNodeId
                    val targetKey = cloudModelTargetKey
                    if (targetNodeId != null && targetKey != null) {
                        viewModel.updateAgentNodeValue(targetNodeId, targetKey, lora.effectiveModelName)
                    }
                    val name = lora.effectiveResourceName.trim()
                    if (name.isNotBlank() && triggers.isNotBlank()) {
                        viewModel.addOrUpdateCharacterLore(name = name, triggerWords = triggers, enabled = true)
                    }
                    dismiss()
                    showCloudModelBrowser = false
                    viewModel.showToast("已选择模型并加入角色库")
                },
                modifier = Modifier.fillMaxWidth()
    )
        },
        onSelectModel = { item ->
            val targetNodeId = cloudModelTargetNodeId
            val targetKey = cloudModelTargetKey
            if (targetNodeId != null && targetKey != null) {
                viewModel.updateAgentNodeValue(targetNodeId, targetKey, item.effectiveModelName)
                viewModel.showToast("已选择模型: ${item.resourceName}")
            }
            showCloudModelBrowser = false
        },
        onSelectModelWithTriggerNode = { item, textNode, triggers ->
            val targetNodeId = cloudModelTargetNodeId
            val targetKey = cloudModelTargetKey
            if (targetNodeId != null && targetKey != null) {
                viewModel.updateAgentNodeValue(targetNodeId, targetKey, item.effectiveModelName)
                val cur = textNode.text.trim()
                val next = if (cur.isBlank()) triggers else "$cur, $triggers"
                viewModel.updateAgentNodeValue(textNode.nodeId, textNode.key, next)
                viewModel.showToast("已选择模型并写入 #${textNode.nodeId}")
            }
            showCloudModelBrowser = false
        }
    )

    // 画师节点选择弹窗
    OverlayDialog(
        show = showSelectArtistNodeDialog,
        title = "选择画师节点",
        onDismissRequest = { showSelectArtistNodeDialog = false }
    ) {
        val textNodes = remember(editableNodes, targetNodeId) {
            editableNodes
                .filterIsInstance<EditableNode.TextInput>()
                .filter { it.nodeId != targetNodeId && it.options.isNullOrEmpty() }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
            Text(
                text = "选择要与常用画师点选库绑定的文本节点：",
                style = MiuixTheme.textStyles.footnote1,
                color = scheme.onSurfaceVariantSummary
    )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 340.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
                item(key = "auto") {
                    val isChosen = agentArtistNodeId.isBlank()
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 16.dp,
                        colors = CardDefaults.defaultColors(
                            color = if (isChosen) scheme.secondaryContainer.copy(alpha = 0.7f) else scheme.surfaceContainerHigh
    ),
                        onClick = {
                            viewModel.setAgentArtistNodeId("")
                            showSelectArtistNodeDialog = false
                        }
    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
    ) {
                            Text("自动推断 / 取消绑定", style = MiuixTheme.textStyles.body1)
                            if (isChosen) {
                                Icon(MiuixIcons.Ok, contentDescription = "已选择", tint = scheme.primary, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }

                items(textNodes, key = { "${it.nodeId}:${it.key}" }) { node ->
                    val isChosen = agentArtistNodeId == node.nodeId
                    val preview = node.text.trim().replace("\n", " ").take(40).ifBlank { "(空)" }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 16.dp,
                        colors = CardDefaults.defaultColors(
                            color = if (isChosen) scheme.secondaryContainer.copy(alpha = 0.7f) else scheme.surfaceContainerHigh
    ),
                        onClick = {
                            viewModel.setAgentArtistNodeId(node.nodeId)
                            showSelectArtistNodeDialog = false
                        }
    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = node.title,
                                    style = MiuixTheme.textStyles.body1.copy(fontWeight = if (isChosen) FontWeight.SemiBold else FontWeight.Medium),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
    )
                                Text(
                                    text = "#${node.nodeId} · ${node.classType}",
                                    style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp),
                                    color = scheme.primary.copy(alpha = 0.8f)
    )
                                Text(
                                    text = preview,
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = scheme.onSurfaceVariantSummary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
    )
                            }
                            if (isChosen) {
                                Icon(MiuixIcons.Ok, contentDescription = "已选择", tint = scheme.primary, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            TextButton(
                text = "关闭",
                onClick = { showSelectArtistNodeDialog = false },
                modifier = Modifier.fillMaxWidth()
    )
        }
    }

    // 管理辅助显示节点弹窗
    OverlayDialog(
        show = showManageAuxNodesDialog,
        title = "管理辅助显示节点",
        onDismissRequest = { showManageAuxNodesDialog = false }
    ) {
        val allNodes = remember(editableNodes, targetNodeId) {
            editableNodes.filter { it.nodeId != targetNodeId }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
            Text(
                text = "勾选想要在 Agent 辅助面板中显示的参数节点：",
                style = MiuixTheme.textStyles.footnote1,
                color = scheme.onSurfaceVariantSummary
    )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
                items(allNodes, key = {
                    val key = when (it) {
                        is EditableNode.TextInput -> it.key
                        is EditableNode.NumberInput -> it.key
                        is EditableNode.BooleanInput -> it.key
                        is EditableNode.ImageInput -> it.key
                    }
                    "${it.nodeId}:$key"
                }) { node ->
                    val compound = when (node) {
                        is EditableNode.TextInput -> "${node.nodeId}:${node.key}"
                        is EditableNode.NumberInput -> "${node.nodeId}:${node.key}"
                        is EditableNode.BooleanInput -> "${node.nodeId}:${node.key}"
                        is EditableNode.ImageInput -> "${node.nodeId}:${node.key}"
                    }
                    val isChecked = agentAuxNodeKeys.contains(compound)

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 14.dp,
                        colors = CardDefaults.defaultColors(
                            color = if (isChecked) scheme.secondaryContainer.copy(alpha = 0.5f) else scheme.surfaceContainerHigh
    ),
                        onClick = { viewModel.toggleAgentAuxNode(compound, !isChecked) }
    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = node.title,
                                    style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.Medium),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
    )
                                Text(
                                    text = "#${node.nodeId} · ${node.classType}",
                                    style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp),
                                    color = scheme.onSurfaceVariantSummary
    )
                            }
                            Switch(
                                checked = isChecked,
                                onCheckedChange = { viewModel.toggleAgentAuxNode(compound, it) }
    )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            TextButton(
                text = "完成",
                onClick = { showManageAuxNodesDialog = false },
                modifier = Modifier.fillMaxWidth()
    )
        }
    }

    // 角色 / LoRA 触发词库弹窗
    AgentCharacterLoreDialog(
        show = showCharacterLoreDialog,
        characterLores = characterLores,
        initialLore = selectedCharacterLoreToEdit,
        onDismissRequest = {
            showCharacterLoreDialog = false
            selectedCharacterLoreToEdit = null
        },
        onSaveLore = { name, triggers, existingId, enabled ->
            viewModel.addOrUpdateCharacterLore(name, triggers, existingId, enabled)
        },
        onToggleLore = { id ->
            viewModel.toggleCharacterLoreEnabled(id)
        },
        onRemoveLore = { id ->
            viewModel.removeCharacterLore(id)
        }
    )

    // 快捷编辑角色触发词弹窗（长按胶囊直接唤起）
    quickEditTriggerLore?.let { lore ->
        var draftTriggers by remember(lore.id) { mutableStateOf(lore.triggerWords) }
        OverlayDialog(
            show = true,
            title = "快捷编辑「${lore.name}」触发词",
            onDismissRequest = { quickEditTriggerLore = null }
    ) {
            DialogFormColumn(
                footer = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
                        TextButton(
                            text = "取消",
                            onClick = { quickEditTriggerLore = null },
                            modifier = Modifier.weight(1f),
                    )
                        Button(
                            onClick = {
                                val clean = draftTriggers.trim()
                                if (clean.isNotBlank()) {
                                    viewModel.addOrUpdateCharacterLore(lore.name, clean, lore.id, lore.isEnabled)
                                    quickEditTriggerLore = null
                                } else {
                                    Toast.makeText(context, "触发词不能为空", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColorsPrimary()
    ) {
                            Text("保存", fontSize = 13.sp)
                        }
                    }
                }
    ) {
                TextField(
                    value = draftTriggers,
                    onValueChange = { draftTriggers = it },
                    label = "专属外貌与 LoRA 触发词",
                    useLabelAsPlaceholder = true,
                    singleLine = false,
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
    )
            }
        }
    }

}
