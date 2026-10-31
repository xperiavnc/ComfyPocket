package com.comfyui.mobile.ui.screens

import com.comfyui.mobile.ui.designsystem.PocketCard
import com.comfyui.mobile.ui.designsystem.PocketSurface

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
import com.comfyui.mobile.ui.screens.agent.AgentModelPicker
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
    // Model list for the form's current address; seeded from the profile's last fetch.
    var fetchedModels by remember(currentLlmProfileId) { mutableStateOf(currentLlmProfile?.availableModels) }
    var fetchingModels by remember { mutableStateOf(false) }
    var modelError by remember { mutableStateOf<String?>(null) }
    var showModelPicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val savedProfile = llmProfiles.firstOrNull { it.id == editingProfileId }
    val dirty = savedProfile == null || savedProfile.name != profileName.trim() ||
        savedProfile.baseUrl != baseUrl.trim() || savedProfile.apiKey != apiKey.trim() ||
        savedProfile.model != model.trim() ||
        AgentReasoningEffort.fromStored(savedProfile.reasoningEffort) != reasoningEffort ||
        AgentLimitsDraft.from(savedProfile.generationLimits) != limits
    val fetchModels: (Boolean) -> Unit = { openPicker ->
        if (baseUrl.isBlank()) modelError = "请先填写 API Base URL"
        else if (!fetchingModels) {
            fetchingModels = true
            modelError = null
            val forUrl = baseUrl
            viewModel.fetchLlmModels(editingProfileId, baseUrl, apiKey) { result ->
                fetchingModels = false
                if (forUrl != baseUrl) return@fetchLlmModels
                result.onSuccess { list ->
                    fetchedModels = list
                    if (model.isBlank()) list.firstOrNull()?.let { model = it }
                    if (openPicker) showModelPicker = true
                }.onFailure { e ->
                    modelError = (e as? com.comfyui.mobile.data.LlmException)
                        ?.let { "${it.errorType.title}：${it.userMessage}" } ?: "获取失败：${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
    }

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
            PocketCard(modifier = Modifier.fillMaxWidth(), surface = PocketSurface.Base) {
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
                                fetchedModels = item.availableModels
                                modelError = null
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
                            fetchedModels = null
                            modelError = null
                        }
                    }

                    AgentSettingsDisclosure(
                        title = "连接配置",
                        summary = if (model.isBlank()) "填写服务地址、密钥和模型名称" else listOfNotNull(
                            model.trim(),
                            runCatching { java.net.URI(baseUrl.trim()).host }.getOrNull()?.takeIf { it.isNotBlank() }
                        ).joinToString(" · "),
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
                            com.comfyui.mobile.ui.designsystem.PocketPill(
                                label = label,
                                selected = baseUrl.trim().trimEnd('/') == url,
                                onClick = {
                                        if (baseUrl.trim().trimEnd('/') != url) {
                                            fetchedModels = null
                                            modelError = null
                                        }
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
                            )
                        }
                    }

                    TextField(
                        value = baseUrl,
                        onValueChange = {
                            if (it.trim() != baseUrl.trim()) { fetchedModels = null; modelError = null }
                            baseUrl = it
                        },
                        label = "API Base URL",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
    )
                    Text(
                        text = if (baseUrl.isBlank()) "兼容 OpenAI 规范，例如 https://api.deepseek.com/v1"
                            else "将请求：${com.comfyui.mobile.data.LlmClient.resolveEndpoint(baseUrl.trim())}",
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
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
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = when {
                                fetchingModels -> "正在获取模型列表…"
                                modelError != null -> modelError.orEmpty()
                                fetchedModels != null -> "已获取 ${fetchedModels.orEmpty().size} 个模型，可直接选择"
                                else -> "可从服务商获取模型列表后选择"
                            },
                            modifier = Modifier.weight(1f),
                            color = if (modelError != null && !fetchingModels) scheme.error else scheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.footnote1,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        TextButton(
                            text = if (fetchedModels == null) "获取模型" else "选择模型",
                            enabled = !fetchingModels,
                            onClick = { if (fetchedModels == null) fetchModels(true) else showModelPicker = true },
                            modifier = Modifier.testTag("agent-settings-models")
                        )
                    }

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
                            modifier = Modifier.weight(1f).heightIn(min = com.comfyui.mobile.ui.designsystem.PocketTokens.ControlHeight),
                            insideMargin = com.comfyui.mobile.ui.designsystem.PocketTokens.ButtonMargin,
                    ) {
                            Text("保存配置")
                        }

                        if (llmProfiles.size > 1) {
                            Button(
                                onClick = { confirmDelete = true },
                                colors = ButtonDefaults.buttonColors(),
                                modifier = Modifier.weight(0.45f).heightIn(min = com.comfyui.mobile.ui.designsystem.PocketTokens.ControlHeight),
                                insideMargin = com.comfyui.mobile.ui.designsystem.PocketTokens.ButtonMargin
    ) {
                                Text("删除", color = scheme.error)
                            }
                        }
                    }
                    if (dirty) Text(
                        text = if (savedProfile == null) "新配置尚未保存" else "有未保存的修改",
                        modifier = Modifier.fillMaxWidth().testTag("agent-settings-dirty"),
                        style = MiuixTheme.textStyles.footnote1,
                        color = scheme.onSurfaceVariantSummary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
            if (showModelPicker) AgentModelPicker(
                models = fetchedModels,
                current = model.trim(),
                loading = fetchingModels,
                error = modelError,
                onRefresh = { fetchModels(false) },
                onPick = { model = it },
                onDismiss = { showModelPicker = false }
            )
            if (confirmDelete) {
                val dismissal = rememberDialogDismiss { confirmDelete = false }
                val doomed = savedProfile?.name ?: profileName
                OverlayDialog(show = dismissal.show, title = "删除配置「$doomed」？",
                    onDismissRequest = dismissal::dismiss, onDismissFinished = dismissal::onDismissFinished) {
                    Column(dismissal.modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PocketTokens.Space.M)) {
                    Text("配置和其中的 API Key 会从这台设备删除，删除后会切换到另一个配置。",
                        modifier = Modifier.fillMaxWidth(), style = MiuixTheme.textStyles.body2,
                        color = scheme.onSurfaceVariantSummary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PocketTokens.Space.S)) {
                        TextButton("取消", onClick = dismissal::dismiss, modifier = Modifier.weight(1f))
                        Button(
                            onClick = { viewModel.deleteLlmProfile(editingProfileId); dismissal.dismiss() },
                            modifier = Modifier.weight(1f)
                        ) { Text("删除", color = scheme.error) }
                    }
                    }
                }
            }

            com.comfyui.mobile.ui.screens.agent.AgentRuleFilesPanel(
                files = ruleFiles, importing = importingRules,
                onImport = viewModel::importAgentRules, onRemove = viewModel::removeAgentRules,
                onSelect = viewModel::selectAgentRules)
            PocketSectionHeader("可选资源")
            com.comfyui.mobile.ui.screens.agent.AgentLocalTagSettings(
                settings = tagSettings, libraryState = libraryState, search = viewModel::searchLocalTags,
                onImport = { importLibrary.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                onRemove = viewModel::removeLocalTagLibrary, onSave = viewModel::setAgentPromptSettings)
        }
    }

}
