package com.comfyui.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.comfyui.mobile.ui.LocalFloatingNavClearance
import com.comfyui.mobile.ui.MainViewModel
import com.comfyui.mobile.model.LlmProfile
import com.comfyui.mobile.agent.AgentReasoningEffort
import com.comfyui.mobile.ui.screens.agent.AgentReasoningEffortSelector
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import com.comfyui.mobile.ui.components.DialogFormColumn

@Composable
fun AgentSettingsScreen(navController: NavController, viewModel: MainViewModel) {
    val llmBaseUrl by viewModel.llmBaseUrl.collectAsState()
    val llmApiKey by viewModel.llmApiKey.collectAsState()
    val llmModel by viewModel.llmModel.collectAsState()
    val llmReasoningEffort by viewModel.llmReasoningEffort.collectAsState()
    val llmProfiles by viewModel.llmProfiles.collectAsState()
    val currentLlmProfileId by viewModel.currentLlmProfileId.collectAsState()
    val currentLlmProfile by viewModel.currentLlmProfile.collectAsState()
    val agentSystemPrompt by viewModel.agentSystemPrompt.collectAsState()
    val agentDrawPrompt by viewModel.agentDrawPrompt.collectAsState()

    val floatingNavClearance = LocalFloatingNavClearance.current
    val scrollBehavior = MiuixScrollBehavior()
    val scheme = MiuixTheme.colorScheme

    var editingProfileId by remember(currentLlmProfileId) { mutableStateOf(currentLlmProfileId) }
    var profileName by remember(currentLlmProfileId, currentLlmProfile?.name) {
        mutableStateOf(currentLlmProfile?.name.orEmpty())
    }
    var baseUrl by remember(currentLlmProfileId, llmBaseUrl) { mutableStateOf(llmBaseUrl) }
    var apiKey by remember(currentLlmProfileId, llmApiKey) { mutableStateOf(llmApiKey) }
    var model by remember(currentLlmProfileId, llmModel) { mutableStateOf(llmModel) }
    var reasoningEffort by remember(currentLlmProfileId, llmReasoningEffort) { mutableStateOf(llmReasoningEffort) }
    var showPassword by remember { mutableStateOf(false) }

    // 独立编辑对话框状态，彻底隔离超长文本与主界面滚动，杜绝滑动卡顿
    var editDialogTarget by remember { mutableStateOf<PromptTarget?>(null) }
    var draftPromptText by rememberSaveable { mutableStateOf("") }
    var confirmResetTarget by remember { mutableStateOf<PromptTarget?>(null) }

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                title = "Agent 设置",
                color = scheme.background,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(MiuixIcons.Back, contentDescription = "返回")
                    }
                }
    )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    bottom = 28.dp + floatingNavClearance
    ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
            Spacer(modifier = Modifier.height(4.dp))

            // 1. 多模型 API 接入配置与预设管理
            Card(
                modifier = Modifier.fillMaxWidth(),
                insideMargin = PaddingValues(16.dp)
    ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "模型 API 配置",
                        style = MiuixTheme.textStyles.title3,
                        fontWeight = FontWeight.SemiBold
    )

                    // 多模型 Profile 快捷切换标签栏（末尾直接展示 + 新建）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
    ) {
                        llmProfiles.forEach { item ->
                            val isActive = item.id == currentLlmProfileId
                            val isEditingThis = item.id == editingProfileId
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        when {
                                            isActive -> scheme.primary
                                            isEditingThis -> scheme.primaryContainer
                                            else -> scheme.surfaceContainerHigh
                                        }
                                    )
                                    .clickable {
                                        editingProfileId = item.id
                                        profileName = item.name
                                        baseUrl = item.baseUrl
                                        apiKey = item.apiKey
                                        model = item.model
                                        reasoningEffort = AgentReasoningEffort.fromStored(item.reasoningEffort)
                                        viewModel.selectLlmProfile(item.id)
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
                                Text(
                                    text = if (isActive) "✓ ${item.displayName}" else item.displayName,
                                    fontSize = 12.sp,
                                    color = when {
                                        isActive -> scheme.onPrimary
                                        isEditingThis -> scheme.onPrimaryContainer
                                        else -> scheme.onSurface
                                    },
                                    fontWeight = if (isActive || isEditingThis) FontWeight.SemiBold else FontWeight.Normal
    )
                            }
                        }

                        // 直接在横向标签栏末尾显示 + 新建
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(scheme.secondaryContainer)
                                .clickable {
                                    val newP = viewModel.createNewLlmProfile()
                                    editingProfileId = newP.id
                                    profileName = newP.name
                                    baseUrl = newP.baseUrl
                                    apiKey = newP.apiKey
                                    model = newP.model
                                    reasoningEffort = AgentReasoningEffort.fromStored(newP.reasoningEffort)
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
                            Text(
                                text = "+ 新建",
                                fontSize = 12.sp,
                                color = scheme.primary,
                                fontWeight = FontWeight.SemiBold
    )
                        }
                    }

                    TextField(
                        value = profileName,
                        onValueChange = { profileName = it },
                        label = "名称",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
    )

                    // 常用服务商快速填入
                    Text(
                        text = "常用大模型服务商预设",
                        color = scheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.body2,
                        fontSize = 12.sp
    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
                        val allServers by viewModel.servers.collectAsState()
                        val rhServers = allServers.filter { it.isRunningHub }

                        listOf(
                            Triple("RunningHub (国内)", "https://llm.runninghub.cn/v1", "glm-5.2"),
                            Triple("RunningHub (海外)", "https://llm.runninghub.ai/v1", "glm-5.2"),
                            Triple("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
                            Triple("硅基流动", "https://api.siliconflow.cn/v1", "deepseek-ai/DeepSeek-V3")
                        ).forEach { (label, url, defaultModel) ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(scheme.surfaceContainerHigh)
                                    .clickable {
                                        baseUrl = url
                                        model = defaultModel
                                        if (profileName.isBlank() || profileName == "未命名配置") {
                                            profileName = label
                                        }
                                        if (label.contains("RunningHub")) {
                                            val isOverseas = label.contains("海外")
                                            val matchedRh = rhServers.firstOrNull { 
                                                if (isOverseas) it.effectiveRhSite == com.comfyui.mobile.model.RunningHubSite.OVERSEAS_AI 
                                                else it.effectiveRhSite == com.comfyui.mobile.model.RunningHubSite.DOMESTIC_CN 
                                            } // same-site profile only; never borrow another site's key
                                            if (matchedRh != null && apiKey.isBlank()) {
                                                apiKey = matchedRh.effectiveRhApiKey
                                            }
                                        }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 11.sp,
                                    color = scheme.primary,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    TextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = "API Base URL",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
    )
                    Text(
                        text = "兼容 OpenAI 规范，例如 https://api.deepseek.com/v1",
                        color = scheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.body2,
                        fontSize = 12.sp
    )

                    TextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = "API Key",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
    )

                    TextField(
                        value = model,
                        onValueChange = { model = it },
                        label = "模型名称",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
    )

                    AgentReasoningEffortSelector(reasoningEffort) { reasoningEffort = it }

                    Spacer(modifier = Modifier.height(2.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
                        Button(
                            onClick = {
                                val prof = LlmProfile(
                                    id = editingProfileId,
                                    name = profileName.trim().ifBlank { model.trim().ifBlank { "未命名配置" } },
                                    baseUrl = baseUrl.trim(),
                                    apiKey = apiKey.trim(),
                                    model = model.trim(),
                                    reasoningEffort = reasoningEffort.wireValue
    )
                                viewModel.addOrUpdateLlmProfile(prof, selectAfterSave = true)
                            },
                            colors = ButtonDefaults.buttonColorsPrimary(),
                            modifier = Modifier.weight(1f),
                    ) {
                            Text("保存并应用此配置")
                        }

                        if (llmProfiles.size > 1) {
                            Button(
                                onClick = {
                                    viewModel.deleteLlmProfile(editingProfileId)
                                },
                                colors = ButtonDefaults.buttonColors(),
                                modifier = Modifier.weight(0.45f)
    ) {
                                Text("删除", color = scheme.error)
                            }
                        }
                    }
                }
            }

            // 2. 系统人设提示词卡片（自然清爽的预览区，点击弹出全屏编辑，彻底解决滑动卡顿）
            PromptCard(
                title = "系统人设提示词",
                description = "定义 AI 助手的人格语气、回复风格。无需在此编写出图 tags。",
                promptContent = agentSystemPrompt,
                defaultPrompt = MainViewModel.DEFAULT_AGENT_SYSTEM_PROMPT,
                onEditClick = {
                    draftPromptText = agentSystemPrompt
                    editDialogTarget = PromptTarget.SYSTEM
                },
                onResetClick = {
                    confirmResetTarget = PromptTarget.SYSTEM
                }
    )

            // 3. 画图分镜提示词规则卡片
            PromptCard(
                title = "画图分镜规则",
                description = "指导大模型分析分镜，并将画面扩写为高质量 SD / Danbooru tags。",
                promptContent = agentDrawPrompt,
                defaultPrompt = MainViewModel.DEFAULT_AGENT_DRAW_PROMPT,
                onEditClick = {
                    draftPromptText = agentDrawPrompt
                    editDialogTarget = PromptTarget.DRAW
                },
                onResetClick = {
                    confirmResetTarget = PromptTarget.DRAW
                }
    )
        }
    }

    // 恢复默认二次确认弹窗，杜绝手滑误触导致自定义提示词瞬间丢失
    confirmResetTarget?.let { target ->
        val isSystem = target == PromptTarget.SYSTEM
        val targetTitle = if (isSystem) "系统人设提示词" else "画图分镜规则"
        OverlayDialog(
            show = true,
            title = "恢复默认确认",
            onDismissRequest = { confirmResetTarget = null }
    ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
                Text(
                    text = "确定要将「$targetTitle」恢复为系统默认吗？\n当前自定义的内容将被清除覆盖，且无法撤销。",
                    style = MiuixTheme.textStyles.body2,
                    color = scheme.onSurface
    )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
                    TextButton(
                        modifier = Modifier.weight(1f),
                        text = "取消",
                        onClick = { confirmResetTarget = null }
    )
                    Button(
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(),
                        onClick = {
                            if (isSystem) {
                                viewModel.resetAgentSystemPrompt()
                                viewModel.showUserMessage("已恢复默认人设")
                            } else {
                                viewModel.resetAgentDrawPrompt()
                                viewModel.showUserMessage("已恢复默认画图规则")
                            }
                            confirmResetTarget = null
                        }
    ) {
                        Text("确认恢复", color = scheme.error)
                    }
                }
            }
        }
    }

    // 独立富文本提示词编辑器弹窗
    if (editDialogTarget != null) {
        val target = editDialogTarget!!
        val dialogTitle = if (target == PromptTarget.SYSTEM) "编辑系统人设" else "编辑画图规则"

        OverlayDialog(
            show = true,
            title = dialogTitle,
            onDismissRequest = { editDialogTarget = null }
    ) {
            DialogFormColumn(
                footer = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
                        TextButton(
                            modifier = Modifier.weight(1f),
                            text = "取消",
                            onClick = { editDialogTarget = null }
    )
                        TextButton(
                            modifier = Modifier.weight(1f),
                            text = "保存生效",
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            onClick = {
                                val trimmed = draftPromptText.trim()
                                if (trimmed.isEmpty()) {
                                    viewModel.showUserMessage("提示词内容不能为空，如需恢复默认请使用「恢复默认」")
                                    return@TextButton
                                }
                                if (target == PromptTarget.SYSTEM) {
                                    viewModel.setAgentSystemPrompt(draftPromptText)
                                } else {
                                    viewModel.setAgentDrawPrompt(draftPromptText)
                                }
                                viewModel.showUserMessage("已保存提示词")
                                editDialogTarget = null
                            }
    )
                    }
                }
    ) {
                Text(
                    text = "当前字数: ${draftPromptText.length}",
                    fontSize = 12.sp,
                    color = scheme.onSurfaceVariantSummary
    )

                // 独立滚动文本编辑容器
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 280.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(scheme.surfaceContainerHigh)
                        .padding(12.dp)
    ) {
                    val editScrollState = rememberScrollState()
                    BasicTextField(
                        value = draftPromptText,
                        onValueChange = { draftPromptText = it },
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = scheme.onSurface,
                            lineHeight = 18.sp
    ),
                        cursorBrush = SolidColor(scheme.primary),
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(editScrollState)
    )
                }

            }
        }
    }
}

private enum class PromptTarget {
    SYSTEM, DRAW
}

@Composable
private fun PromptCard(
    title: String,
    description: String,
    promptContent: String,
    defaultPrompt: String,
    onEditClick: () -> Unit,
    onResetClick: () -> Unit
    ) {
    val scheme = MiuixTheme.colorScheme
    val isCustomized = promptContent.trim() != defaultPrompt.trim()

    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
    ) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.title3,
                    fontWeight = FontWeight.SemiBold
    )
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (isCustomized) scheme.primaryContainer else scheme.surfaceContainerHigh)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
                    Text(
                        text = if (isCustomized) "自定义" else "默认",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isCustomized) scheme.onPrimaryContainer else scheme.onSurfaceVariantSummary
    )
                }
            }

            Text(
                text = description,
                fontSize = 12.sp,
                color = scheme.onSurfaceVariantSummary,
                lineHeight = 16.sp
    )

            // 轻量文本预览框：自然的卡片次级背景，不加厚重描边
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(scheme.surfaceContainerHigh)
                    .clickable(onClick = onEditClick)
                    .padding(12.dp)
    ) {
                Text(
                    text = promptContent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = scheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 17.sp
    )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
                Button(
                    onClick = onEditClick,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                    ) {
                    Icon(MiuixIcons.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("编辑提示词", fontSize = 13.sp)
                }

                if (isCustomized) {
                    TextButton(
                        text = "恢复默认",
                        onClick = onResetClick,
                        modifier = Modifier.weight(0.7f)
    )
                }
            }
        }
    }
}
