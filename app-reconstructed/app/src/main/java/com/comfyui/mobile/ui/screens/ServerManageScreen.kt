package com.comfyui.mobile.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.comfyui.mobile.model.RunningHubGpuTier
import com.comfyui.mobile.model.RunningHubSite
import com.comfyui.mobile.model.ServerProfile
import com.comfyui.mobile.model.ServerType
import com.comfyui.mobile.ui.LocalFloatingNavClearance
import com.comfyui.mobile.ui.MainViewModel
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

@Composable
fun ServerManageScreen(navController: NavController, viewModel: MainViewModel) {
    val schemeColors = MiuixTheme.colorScheme
    val servers by viewModel.servers.collectAsState()
    val currentId by viewModel.currentServerId.collectAsState()
    val floatingNavClearance = LocalFloatingNavClearance.current
    val scrollBehavior = MiuixScrollBehavior()
    val scope = rememberCoroutineScope()

    var editor by remember { mutableStateOf<ServerProfile?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var editingNew by remember { mutableStateOf(false) }

    var serverType by remember { mutableStateOf(ServerType.LOCAL_COMFYUI) }
    var name by remember { mutableStateOf("") }
    // Local ComfyUI fields
    var scheme by remember { mutableStateOf("http") }
    var ip by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8188") }
    // RunningHub fields
    var rhSite by remember { mutableStateOf(RunningHubSite.DOMESTIC_CN) }
    var rhGpuTier by remember { mutableStateOf(RunningHubGpuTier.STANDARD_24G) }
    var rhApiKey by remember { mutableStateOf("") }
    var showApiKey by remember { mutableStateOf(false) }
    var testStatusCode by remember { mutableStateOf<String?>(null) }
    var testErrorMessage by remember { mutableStateOf<String?>(null) }
    var testIsSuccess by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }

    var deleteTarget by remember { mutableStateOf<ServerProfile?>(null) }

    fun openNew() {
        editingNew = true
        editor = null
        serverType = ServerType.LOCAL_COMFYUI
        name = ""
        scheme = "http"
        ip = ""
        port = "8188"
        rhSite = RunningHubSite.DOMESTIC_CN
        rhGpuTier = RunningHubGpuTier.STANDARD_24G
        rhApiKey = ""
        showApiKey = false
        testStatusCode = null
        testErrorMessage = null
        testIsSuccess = false
        isTesting = false
        showEditor = true
    }

    fun openEdit(server: ServerProfile) {
        editingNew = false
        editor = server
        serverType = server.effectiveServerType
        name = if (server.isRunningHub) {
            if (ServerProfile.isDefaultRhName(server.effectiveName)) "" else server.effectiveName
        } else {
            if (server.effectiveName == server.effectiveHost) "" else server.effectiveName
        }
        scheme = if (server.effectiveScheme.equals("https", ignoreCase = true)) "https" else "http"
        ip = server.effectiveHost
        port = server.effectivePort
        rhSite = server.effectiveRhSite
        rhGpuTier = server.effectiveRhGpuTier
        rhApiKey = server.effectiveRhApiKey
        showApiKey = false
        testStatusCode = null
        testErrorMessage = null
        testIsSuccess = false
        isTesting = false
        showEditor = true
    }

    Scaffold(
        containerColor = schemeColors.background,
        topBar = {
            TopAppBar(
                title = "服务器",
                largeTitle = "服务器",
                color = schemeColors.background,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(MiuixIcons.Back, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { openNew() }) {
                        Icon(MiuixIcons.Add, contentDescription = "添加")
                    }
                }
    )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = 24.dp + floatingNavClearance
    ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
            if (servers.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Text(
                                text = "还没有服务器",
                                style = MiuixTheme.textStyles.title3.copy(fontWeight = FontWeight.SemiBold)
    )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "添加本地或云端服务器",
                                style = MiuixTheme.textStyles.body2,
                                color = schemeColors.onSecondaryContainer
    )
                            Spacer(modifier = Modifier.height(14.dp))
                            Button(
                                onClick = { openNew() },
                                colors = ButtonDefaults.buttonColorsPrimary(),
                                modifier = Modifier.fillMaxWidth()
    ) {
                                Text("添加服务器")
                            }
                        }
                    }
                }
            } else {
                items(servers, key = { it.effectiveId }) { server ->
                    val selected = server.effectiveId == currentId
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        pressFeedbackType = PressFeedbackType.Tilt,
                        showIndication = true,
                        onClick = { viewModel.selectServer(server.effectiveId) },
                        colors = CardDefaults.defaultColors(
                            color = if (selected) {
                                schemeColors.primary.copy(alpha = 0.12f)
                            } else {
                                schemeColors.surfaceContainer
                            }
    )
    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
    ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 6.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = server.displayName,
                                        style = MiuixTheme.textStyles.title3.copy(fontWeight = FontWeight.SemiBold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    val isRh = server.isRunningHub
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(
                                                if (isRh) schemeColors.primary.copy(alpha = 0.15f)
                                                else schemeColors.surfaceContainerHigh
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = if (isRh) "云端" else "本地",
                                            style = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.Medium),
                                            fontSize = 11.sp,
                                            color = if (isRh) schemeColors.primary else schemeColors.onSecondaryContainer,
                                            maxLines = 1
    )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = server.addressLabel,
                                    style = MiuixTheme.textStyles.body2,
                                    color = schemeColors.onSecondaryContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
    )
                            }
                            if (selected) {
                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(CircleShape)
                                        .background(schemeColors.primary.copy(alpha = 0.16f)),
                                    contentAlignment = Alignment.Center
    ) {
                                    Icon(
                                        imageVector = MiuixIcons.Ok,
                                        contentDescription = "当前选中",
                                        tint = schemeColors.primary,
                                        modifier = Modifier.size(16.dp)
    )
                                }
                                Spacer(modifier = Modifier.width(2.dp))
                            }
                            IconButton(
                                onClick = { openEdit(server) },
                                modifier = Modifier.size(36.dp)
    ) {
                                Icon(
                                    imageVector = MiuixIcons.Edit,
                                    contentDescription = "编辑",
                                    tint = schemeColors.onSecondaryContainer,
                                    modifier = Modifier.size(19.dp)
    )
                            }
                            IconButton(
                                onClick = { deleteTarget = server },
                                modifier = Modifier.size(36.dp)
    ) {
                                Icon(
                                    imageVector = MiuixIcons.Delete,
                                    contentDescription = "删除",
                                    tint = schemeColors.onSecondaryContainer.copy(alpha = 0.75f),
                                    modifier = Modifier.size(19.dp)
    )
                            }
                        }
                    }
                }
            }
        }
    }

    OverlayDialog(
        show = showEditor,
        title = if (editingNew) "添加服务器" else "编辑服务器",
        onDismissRequest = { showEditor = false }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .heightIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
    ) {
            // 服务器类型切换
            Text(
                text = "服务器类型",
                style = MiuixTheme.textStyles.body2,
                color = schemeColors.onSecondaryContainer
    )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
                listOf(
                    ServerType.LOCAL_COMFYUI to "本地",
                    ServerType.RUNNINGHUB to "云端"
                ).forEach { (type, label) ->
                    val isSelected = serverType == type
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isSelected) schemeColors.primary else schemeColors.surfaceContainerHigh
                            )
                            .clickable {
                                serverType = type
                                testStatusCode = null
                            }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center
    ) {
                        Text(
                            text = label,
                            style = MiuixTheme.textStyles.body2.copy(
                                fontSize = 14.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
    ),
                            color = if (isSelected) schemeColors.onPrimary else schemeColors.onSurface,
                            maxLines = 1
    )
                    }
                }
            }
            Spacer(modifier = Modifier.height(18.dp))

            if (serverType == ServerType.LOCAL_COMFYUI) {
                // 本地 ComfyUI 配置
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "名称（可选）",
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    useLabelAsPlaceholder = true
    )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "协议类型",
                    style = MiuixTheme.textStyles.body2,
                    color = schemeColors.onSecondaryContainer
    )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
                    listOf("http" to "HTTP", "https" to "HTTPS").forEach { (proto, label) ->
                        val isSelected = scheme.equals(proto, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(11.dp))
                                .background(
                                    if (isSelected) schemeColors.primary else schemeColors.surfaceContainerHigh
                                )
                                .clickable {
                                    scheme = proto
                                    if (proto == "https" && port == "8188") {
                                        port = "443"
                                    } else if (proto == "http" && port == "443") {
                                        port = "8188"
                                    }
                                }
                                .padding(vertical = 11.dp),
                            contentAlignment = Alignment.Center
    ) {
                            Text(
                                text = label,
                                style = MiuixTheme.textStyles.body2.copy(
                                    fontSize = 14.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
    ),
                                color = if (isSelected) schemeColors.onPrimary else schemeColors.onSurface,
                                maxLines = 1
    )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                TextField(
                    value = ip,
                    onValueChange = { newIp ->
                        ip = newIp
                        if (newIp.startsWith("https://", ignoreCase = true)) {
                            scheme = "https"
                            if (port == "8188") port = "443"
                        } else if (newIp.startsWith("http://", ignoreCase = true)) {
                            scheme = "http"
                            if (port == "443") port = "8188"
                        }
                    },
                    label = "IP / 域名",
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    useLabelAsPlaceholder = true
    )
                Spacer(modifier = Modifier.height(16.dp))
                TextField(
                    value = port,
                    onValueChange = { port = it },
                    label = "端口",
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    useLabelAsPlaceholder = true
    )
            } else {
                // RunningHub 云端配置
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "名称（可选）",
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    useLabelAsPlaceholder = true
    )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "站点",
                    style = MiuixTheme.textStyles.body2,
                    color = schemeColors.onSecondaryContainer
    )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
                    RunningHubSite.entries.forEach { site ->
                        val isSelected = rhSite == site
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(11.dp))
                                .background(
                                    if (isSelected) schemeColors.primary else schemeColors.surfaceContainerHigh
                                )
                                .clickable {
                                    rhSite = site
                                    testStatusCode = null
                                }
                                .padding(vertical = 11.dp),
                            contentAlignment = Alignment.Center
    ) {
                            Text(
                                text = site.displayName,
                                style = MiuixTheme.textStyles.body2.copy(
                                    fontSize = 14.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
    ),
                                color = if (isSelected) schemeColors.onPrimary else schemeColors.onSurface,
                                maxLines = 1
    )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "显存",
                    style = MiuixTheme.textStyles.body2,
                    color = schemeColors.onSecondaryContainer
    )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
                    RunningHubGpuTier.entries.forEach { tier ->
                        val isSelected = rhGpuTier == tier
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(11.dp))
                                .background(
                                    if (isSelected) schemeColors.primary else schemeColors.surfaceContainerHigh
                                )
                                .clickable {
                                    rhGpuTier = tier
                                }
                                .padding(vertical = 11.dp),
                            contentAlignment = Alignment.Center
    ) {
                            Text(
                                text = tier.displayName,
                                style = MiuixTheme.textStyles.body2.copy(
                                    fontSize = 13.5.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
    ),
                                color = if (isSelected) schemeColors.onPrimary else schemeColors.onSurface,
                                maxLines = 1
    )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
    ) {
                    TextField(
                        value = rhApiKey,
                        onValueChange = {
                            rhApiKey = it
                            testStatusCode = null
                        },
                        label = "API Key",
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        useLabelAsPlaceholder = true,
                        visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation()
    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        text = if (showApiKey) "隐藏" else "显示",
                        onClick = { showApiKey = !showApiKey }
    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "可在 ${if (rhSite == RunningHubSite.DOMESTIC_CN) "runninghub.cn" else "runninghub.ai"} 获取密钥",
                    style = MiuixTheme.textStyles.body2,
                    fontSize = 12.5.sp,
                    color = schemeColors.onSecondaryContainer
    )
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
    ) {
                    TextButton(
                        text = if (isTesting) "测试中..." else "测试连接",
                        onClick = {
                            if (rhApiKey.trim().isBlank()) {
                                testStatusCode = "400 BAD_REQUEST"
                                testIsSuccess = false
                                return@TextButton
                            }
                            scope.launch {
                                isTesting = true
                                testStatusCode = "CONNECTING"
                                testIsSuccess = false
                                val res = viewModel.testRunningHubConnection(rhSite, rhApiKey.trim())
                                isTesting = false
                                res.onSuccess {
                                    testStatusCode = "200 OK"
                                    testErrorMessage = null
                                    testIsSuccess = true
                                }.onFailure { err ->
                                    testIsSuccess = false
                                    val (code, detail) = ServerConnectionTestHelper.parseErrorToCode(err.message.orEmpty())
                                    testStatusCode = code
                                    testErrorMessage = detail
                                }
                            }
                        }
    )

                    testStatusCode?.let { code ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    when {
                                        code == "CONNECTING" -> schemeColors.surfaceContainerHigh
                                        testIsSuccess -> schemeColors.primary.copy(alpha = 0.15f)
                                        else -> schemeColors.error.copy(alpha = 0.14f)
                                    }
                                )
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = code,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    code == "CONNECTING" -> schemeColors.onSecondaryContainer
                                    testIsSuccess -> schemeColors.primary
                                    else -> schemeColors.error
                                },
                                maxLines = 1
    )
                        }
                    }
                }
                testErrorMessage?.takeIf { !testIsSuccess && !isTesting }?.let { msg ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = msg,
                        style = MiuixTheme.textStyles.body2,
                        color = schemeColors.error,
                        fontSize = 12.sp
    )
                }
            }

            Spacer(modifier = Modifier.height(22.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = "取消",
                    onClick = { showEditor = false },
                    modifier = Modifier.weight(1f),
                    )
                Spacer(modifier = Modifier.width(14.dp))
                TextButton(
                    text = "保存",
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        val ok = if (serverType == ServerType.RUNNINGHUB) {
                            viewModel.upsertRunningHubServer(
                                id = if (editingNew) null else editor?.id,
                                name = name,
                                site = rhSite,
                                apiKey = rhApiKey,
                                gpuTier = rhGpuTier,
                                selectAfter = true
    )
                        } else {
                            viewModel.upsertServer(
                                id = if (editingNew) null else editor?.id,
                                name = name,
                                ip = ip,
                                port = port,
                                scheme = scheme,
                                selectAfter = true
    )
                        }
                        if (ok) showEditor = false
                    },
                    modifier = Modifier.weight(1f),
                    )
            }
        }
    }

    OverlayDialog(
        show = deleteTarget != null,
        title = "删除服务器",
        onDismissRequest = { deleteTarget = null }
    ) {
        Column {
            Text(
                text = "确定删除「${deleteTarget?.displayName.orEmpty()}」吗？",
                style = MiuixTheme.textStyles.body1
    )
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = "取消",
                    onClick = { deleteTarget = null },
                    modifier = Modifier.weight(1f),
                    )
                Spacer(modifier = Modifier.width(12.dp))
                TextButton(
                    text = "删除",
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        deleteTarget?.let { viewModel.deleteServer(it.effectiveId) }
                        deleteTarget = null
                    },
                    modifier = Modifier.weight(1f),
                    )
            }
        }
    }
}

object ServerConnectionTestHelper {
    fun parseErrorToCode(raw: String): Pair<String, String> {
        val trimmed = raw.trim()
        val httpMatch = Regex("""HTTP\s*(\d{3})""", RegexOption.IGNORE_CASE).find(trimmed)
        if (httpMatch != null) {
            val codeNum = httpMatch.groupValues[1]
            val name = when (codeNum) {
                "401" -> "401 UNAUTHORIZED"
                "403" -> "403 FORBIDDEN"
                "404" -> "404 NOT_FOUND"
                "500" -> "500 SERVER_ERR"
                "502" -> "502 BAD_GATEWAY"
                "503" -> "503 SERVICE_UNAVAIL"
                "504" -> "504 GATEWAY_TIMEOUT"
                else -> "HTTP $codeNum"
            }
            val cleanMsg = trimmed
                .replace(Regex("""\(HTTP\s*\d{3}\)"""), "")
                .replace(Regex("""HTTP\s*\d{3}:\s*"""), "")
                .trim()
                .ifBlank { "请求失败" }
            return name to cleanMsg
        }

        val codeMatch = Regex("""(?:code|错误代码|错误)\s*[:：]?\s*(\d+)""", RegexOption.IGNORE_CASE).find(trimmed)
        if (codeMatch != null) {
            val codeNum = codeMatch.groupValues[1]
            val label = when (codeNum) {
                "802" -> "CODE 802 (KEY_INVALID)"
                "416" -> "CODE 416 (NO_BALANCE)"
                "421" -> "CODE 421 (MAX_CONCURRENCY)"
                "433" -> "CODE 433 (PARAM_INVALID)"
                else -> "CODE $codeNum"
            }
            val cleanMsg = trimmed.replace(Regex("""(?:错误代码|code)\s*[:：]?\s*\d+"""), "").trim().ifBlank { "调用失败" }
            return label to cleanMsg
        }

        if (trimmed.contains("站点不匹配")) {
            return "SITE_MISMATCH" to trimmed
        }

        if (trimmed.contains("Key", ignoreCase = true) && trimmed.contains("无效")) {
            return "401 INVALID_KEY" to "API Key 无效"
        }
        if (trimmed.contains("超时") || trimmed.contains("timeout", ignoreCase = true)) {
            return "408 TIMEOUT" to "连接超时"
        }
        if (trimmed.contains("Failed to connect") || trimmed.contains("ConnectException") || trimmed.contains("无法连接")) {
            return "ERR CONNECT" to "无法连接服务器"
        }
        if (trimmed.contains("UnknownHost") || trimmed.contains("域名")) {
            return "ERR DNS" to "无法解析服务器地址"
        }

        return "ERR FAIL" to (if (trimmed.length > 20) trimmed.take(18) + "..." else trimmed.ifBlank { "未知错误" })
    }
}
