@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.comfyui.mobile.ui.material

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import com.comfyui.mobile.danbooru.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Activity-retained, not SavedState: rotation retains edits without serializing credentials. */
class MaterialConnectionEditor : ViewModel() {
    private var sessionOpen = false
    var baseline by mutableStateOf(DanbooruSettings())
        private set
    var draft by mutableStateOf(DanbooruSettings())
        private set
    var errors by mutableStateOf(emptyMap<String, String>())
        private set
    var busy by mutableStateOf(false)
    var status by mutableStateOf<String?>(null)
    val dirty get() = draft != baseline
    fun begin(value: DanbooruSettings) { sessionOpen = true; baseline = value; draft = value; errors = emptyMap(); status = null }
    fun beginIfNeeded(value: DanbooruSettings) { if (!sessionOpen) begin(value) }
    fun finish() { sessionOpen = false; discard() }
    fun edit(value: DanbooruSettings) { draft = value; errors = emptyMap(); status = null }
    fun validate(): DanbooruSettings? {
        val issues = mutableMapOf<String, String>()
        if (draft.username.isBlank() != draft.apiKey.isBlank()) issues["account"] = "用户名和 API Key 请同时填写，或同时留空"
        runCatching { draft.proxyUrl() }.exceptionOrNull()?.let { issues["proxy"] = it.message ?: "请检查代理地址" }
        val validated = runCatching { draft.validated() }
        if (issues.isEmpty()) validated.exceptionOrNull()?.let { issues["account"] = it.message ?: "请检查输入" }
        errors = issues
        return validated.getOrNull().takeIf { issues.isEmpty() }
    }
    fun committed() { baseline = draft; errors = emptyMap() }
    fun discard() { draft = baseline; errors = emptyMap(); status = null }
}

@Composable
internal fun MaterialConnectionScreen(
    editor: MaterialConnectionEditor, online: Boolean, onClose: () -> Unit,
    onSave: suspend (DanbooruSettings) -> Result<Unit>,
    onTest: suspend (DanbooruSettings) -> String,
    onClear: suspend () -> String, onSaved: (String) -> Unit, active: Boolean = true
) {
    val draft = editor.draft
    val scope = rememberCoroutineScope()
    var discardOpen by remember { mutableStateOf(false) }
    var clearOpen by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    var preferences by remember { mutableStateOf(false) }
    fun leave() {
        if (editor.busy || !active) return
        if (editor.dirty) discardOpen = true else onClose()
    }
    PredictiveBackHandler(enabled = active && !discardOpen && !clearOpen) { events -> events.collect {}; leave() }
    fun action(block: suspend () -> Unit) {
        if (editor.busy || !active) return
        editor.busy = true
        scope.launch {
            try { block() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { editor.status = if (e is DanbooruException || e is IllegalArgumentException) e.message else "操作失败，输入已保留，请重试" }
            finally { editor.busy = false }
        }
    }
    fun save() {
        val value = editor.validate() ?: return
        action {
            onSave(value).onSuccess {
                editor.committed()
                onSaved(if (online) "Danbooru 设置已保存" else "样板设置已保存，真实配置未变")
                onClose()
            }.onFailure { editor.status = it.message ?: "保存失败，输入已保留" }
        }
    }
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = { TopAppBar(title = { Text("Danbooru 设置") }, navigationIcon = {
            IconButton(onClick = ::leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回画廊") }
        }) },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = ::leave, enabled = !editor.busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("取消") }
                    Button(onClick = ::save, enabled = !editor.busy,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("material-save")) {
                        Text(if (editor.busy) "处理中…" else "保存")
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (online) "独立账号和 HTTP 代理，仅作用于 Danbooru。"
                else "离线样板 · 仅保存于本次演示，不联网，不修改真实账号或代理。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { preferences = !preferences }) { Text(if (preferences) "收起偏好" else "偏好") }
            if (!preferences) {
                Text("账号", style = MaterialTheme.typography.titleMedium)
                ConnectionField(draft.username, "用户名", !editor.busy, editor.errors["account"]) { editor.edit(draft.copy(username = it)) }
                ConnectionField(draft.apiKey, "API Key", !editor.busy, editor.errors["account"],
                    secret = !visible, keyboard = KeyboardType.Password) { editor.edit(draft.copy(apiKey = it)) }
                Text("同时留空即可匿名浏览；测试成功不代表已保存。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (draft.username.isNotBlank() || draft.apiKey.isNotBlank()) TextButton(
                    enabled = !editor.busy, onClick = { editor.edit(draft.copy(username = "", apiKey = "")) }
                ) { Text("清空账号，保存后退出") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { visible = !visible }, enabled = !editor.busy) { Text(if (visible) "隐藏密钥" else "显示密钥") }
                    OutlinedButton(onClick = {
                        val value = editor.validate() ?: return@OutlinedButton
                        action {
                            editor.status = if (online) onTest(value) else { onTest(value); "离线校验通过；未联网验证账号，也未保存" }
                        }
                    }, enabled = !editor.busy) { Text("测试当前输入") }
                }
                HorizontalDivider()
                SettingSwitch("独立 HTTP 代理", draft.proxyEnabled, !editor.busy) { editor.edit(draft.copy(proxyEnabled = it)) }
                if (draft.proxyEnabled) {
                    ConnectionField(draft.proxyAddress, "代理地址", !editor.busy, editor.errors["proxy"],
                        keyboard = KeyboardType.Uri, placeholder = "http://主机:端口") { editor.edit(draft.copy(proxyAddress = it)) }
                    Text("127.0.0.1 指手机本机；电脑代理请填写局域网 IP。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ConnectionField(draft.proxyUsername, "代理用户名（可选）", !editor.busy) { editor.edit(draft.copy(proxyUsername = it)) }
                    ConnectionField(draft.proxyPassword, "代理密码（可选）", !editor.busy, secret = !visible,
                        keyboard = KeyboardType.Password) { editor.edit(draft.copy(proxyPassword = it)) }
                }
            } else {
                Text("默认内容评级", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ContentFilter.entries.filter { it != ContentFilter.CUSTOM }.forEach { value ->
                        FilterChip(selected = draft.defaultFilter == value, enabled = !editor.busy,
                            onClick = { editor.edit(draft.copy(defaultFilter = value)) }, label = { Text(value.title) })
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TagCopyMode.entries.forEach { value ->
                        FilterChip(draft.copyOptions.mode == value, enabled = !editor.busy,
                            onClick = { editor.edit(draft.copy(copyOptions = draft.copyOptions.copy(mode = value))) }, label = { Text(value.title) })
                    }
                }
                SettingSwitch("下划线转空格", draft.copyOptions.underscoresToSpaces, !editor.busy) {
                    editor.edit(draft.copy(copyOptions = draft.copyOptions.copy(underscoresToSpaces = it)))
                }
                SettingSwitch("括号转义", draft.copyOptions.escapeParentheses, !editor.busy) {
                    editor.edit(draft.copy(copyOptions = draft.copyOptions.copy(escapeParentheses = it)))
                }
                TextButton(onClick = { clearOpen = true }, enabled = !editor.busy) { Text("清除在线缓存") }
            }
            editor.status?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("material-form-status")) }
            Text("配置只用于 Danbooru；不影响 Civitai 或 ComfyUI。只读 Key 需要 users:profile、posts:index、posts:show，IP 限制应匹配实际出口。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
        }
    }
    if (active && discardOpen) AlertDialog(onDismissRequest = { discardOpen = false },
        title = { Text("保留这次修改？") }, text = { Text("当前设置尚未保存。") },
        confirmButton = { TextButton(onClick = { discardOpen = false; save() }) { Text("保存并返回") } },
        dismissButton = {
            FlowRow {
                TextButton(onClick = { discardOpen = false }) { Text("继续编辑") }
                TextButton(onClick = { editor.discard(); discardOpen = false; onClose() }) { Text("放弃修改") }
            }
        })
    if (active && clearOpen) AlertDialog(onDismissRequest = { clearOpen = false },
        title = { Text("清除 Danbooru 在线缓存？") },
        text = { Text("只清除此来源的列表、图片和临时分享缓存；账号配置与系统相册保持不变。") },
        confirmButton = { TextButton(onClick = { clearOpen = false; action { editor.status = onClear() } }) { Text("清除") } },
        dismissButton = { TextButton(onClick = { clearOpen = false }) { Text("取消") } })
}

@Composable
private fun ConnectionField(
    value: String, label: String, enabled: Boolean, error: String? = null,
    secret: Boolean = false, keyboard: KeyboardType = KeyboardType.Text, placeholder: String = "",
    onChange: (String) -> Unit
) {
    OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth(), enabled = enabled, singleLine = true,
        label = { Text(label) }, placeholder = { Text(placeholder) }, isError = error != null,
        supportingText = error?.let { { Text(it) } },
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard))
}

@Composable
internal fun SettingSwitch(title: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked, onChange, enabled = enabled)
    }
}
