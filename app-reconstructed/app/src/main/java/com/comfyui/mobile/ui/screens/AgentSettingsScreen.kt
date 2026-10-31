package com.comfyui.mobile.ui.screens

import com.comfyui.mobile.ui.designsystem.PocketSectionHeader

import androidx.activity.compose.rememberLauncherForActivityResult
import com.comfyui.mobile.ui.designsystem.PocketTokens
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.testTag
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
import com.comfyui.mobile.ui.screens.agent.AgentGenerationLimitsControls
import com.comfyui.mobile.ui.screens.agent.AgentSettingsDisclosure
import com.comfyui.mobile.ui.screens.agent.AgentChoiceChip
import com.comfyui.mobile.ui.screens.agent.AgentLimitsDraft
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
import com.comfyui.mobile.ui.components.rememberDialogDismiss

@Composable
fun AgentSettingsScreen(navController: NavController, viewModel: MainViewModel) {
    val llmBaseUrl by viewModel.llmBaseUrl.collectAsState()
    val llmApiKey by viewModel.llmApiKey.collectAsState()
    val llmModel by viewModel.llmModel.collectAsState()
    val llmReasoningEffort by viewModel.llmReasoningEffort.collectAsState()
    val llmProfiles by viewModel.llmProfiles.collectAsState()
    val currentLlmProfileId by viewModel.currentLlmProfileId.collectAsState()
    val currentLlmProfile by viewModel.currentLlmProfile.collectAsState()
    val ruleFiles by viewModel.agentRuleFiles.collectAsState()
    val importingRules by viewModel.agentRulesImporting.collectAsState()
    val tagSettings by viewModel.agentPromptSettings.collectAsState()
    val libraryState by viewModel.localTagLibraryState.collectAsState()
    val importLibrary = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::installLocalTagLibrary)
    }

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
    var limits by remember(currentLlmProfileId, currentLlmProfile?.generationLimits) {
        mutableStateOf(AgentLimitsDraft.from(currentLlmProfile?.generationLimits
            ?: com.comfyui.mobile.agent.AgentGenerationLimits()))
    }
    var showPassword by remember { mutableStateOf(false) }

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
                .padding(top = innerPadding.calculateTopPadding())
                .testTag("agent-settings-scroll")
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PocketTokens.PagePadding)
                .padding(
                    bottom = 28.dp + floatingNavClearance
    ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
            Spacer(modifier = Modifier.height(4.dp))

            PocketSectionHeader("模型服务")

            // Profile selection stays visible; connection details and generation parameters expand separately.
            Card(
                modifier = Modifier.fillMaxWidth(),
                insideMargin = PaddingValues(16.dp)
    ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "模型配置",
                        style = MiuixTheme.textStyles.title4,
                        fontWeight = FontWeight.SemiBold
    )

                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        llmProfiles.forEach { item ->
                            AgentChoiceChip(item.displayName, item.id == currentLlmProfileId,
                                Modifier.testTag("agent-profile-${item.id}")) {
                                editingProfileId = item.id
                                profileName = item.name
                                baseUrl = item.baseUrl
                                apiKey = item.apiKey
                                model = item.model
                                reasoningEffort = AgentReasoningEffort.fromStored(item.reasoningEffort)
                                limits = AgentLimitsDraft.from(item.generationLimits)
                                viewModel.selectLlmProfile(item.id)
                            }
                        }
                        AgentChoiceChip("＋ 新建", action = true, modifier = Modifier.testTag("agent-profile-new")) {
                            val newP = viewModel.createNewLlmProfile()
                            editingProfileId = newP.id
                            profileName = newP.name
                            baseUrl = newP.baseUrl
                            apiKey = newP.apiKey
                            model = newP.model
                            reasoningEffort = AgentReasoningEffort.fromStored(newP.reasoningEffort)
                            limits = AgentLimitsDraft.from(newP.generationLimits)
                        }
                    }

                    AgentSettingsDisclosure(
                        title = "连接配置",
                        summary = model.ifBlank { "填写服务地址、密钥和模型名称" },
                        initiallyExpanded = baseUrl.isBlank() || model.isBlank()
                    ) {
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
                                    .clip(PocketTokens.Shape.Small)
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

                    TextButton(if (showPassword) "隐藏密钥" else "显示密钥", onClick = { showPassword = !showPassword })

                    TextField(
                        value = model,
                        onValueChange = { model = it },
                        label = "模型名称",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
    )

                    }
                    AgentSettingsDisclosure(
                        title = "生成参数",
                        summary = "${reasoningEffort.label} · 上下文 ${limits.context.ifBlank { "默认" }} · 输出 ${limits.output.ifBlank { "默认" }}"
                    ) {
                        AgentReasoningEffortSelector(reasoningEffort) { reasoningEffort = it }
                        AgentGenerationLimitsControls(limits) { limits = it }
                    }

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
                                    reasoningEffort = reasoningEffort.wireValue,
                                    contextTokens = limits.parsed().contextTokens,
                                    maxOutputTokens = limits.parsed().outputTokens,
                                    outputTokenParameter = limits.parameter.wireValue
    )
                                viewModel.addOrUpdateLlmProfile(prof, selectAfterSave = true)
                            },
                            enabled = runCatching { limits.parsed() }.isSuccess,
                            colors = ButtonDefaults.buttonColorsPrimary(),
                            modifier = Modifier.weight(1f).height(44.dp),
                    ) {
                            Text("保存配置")
                        }

                        if (llmProfiles.size > 1) {
                            Button(
                                onClick = {
                                    viewModel.deleteLlmProfile(editingProfileId)
                                },
                                colors = ButtonDefaults.buttonColors(),
                                modifier = Modifier.weight(0.45f).height(44.dp)
    ) {
                                Text("删除", color = scheme.error)
                            }
                        }
                    }
                }
            }

            com.comfyui.mobile.ui.screens.agent.AgentRuleFilesPanel(
                files = ruleFiles, importing = importingRules,
                onImport = viewModel::importAgentRules, onRemove = viewModel::removeAgentRules)
            PocketSectionHeader("可选资源")
            com.comfyui.mobile.ui.screens.agent.AgentLocalTagSettings(
                settings = tagSettings, libraryState = libraryState, search = viewModel::searchLocalTags,
                onImport = { importLibrary.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                onRemove = viewModel::removeLocalTagLibrary, onSave = viewModel::setAgentPromptSettings)
        }
    }

}
