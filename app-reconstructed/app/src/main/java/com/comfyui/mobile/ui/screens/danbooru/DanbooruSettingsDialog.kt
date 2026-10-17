package com.comfyui.mobile.ui.screens.danbooru

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.comfyui.mobile.danbooru.*
import com.comfyui.mobile.ui.components.DialogFormColumn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun DanbooruSettingsDialog(
    initial: DanbooruSettings, state: DanbooruState, onDismiss: () -> Unit,
    onSave: suspend (DanbooruSettings) -> Result<Unit>,
    onTest: suspend (DanbooruSettings) -> String,
    onLogout: suspend () -> Result<Unit>, onClear: suspend () -> String
) {
    // Credentials are deliberately not rememberSaveable.
    var draft by remember { mutableStateOf(initial) }
    var visible by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val transform = if (visible) VisualTransformation.None else PasswordVisualTransformation()
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { block() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status = if (e is DanbooruException || e is IllegalArgumentException) e.message else "操作失败，请重试；原设置保留" }
            finally { busy = false }
        }
    }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Card(Modifier.fillMaxWidth().imePadding()) {
            Column(Modifier.padding(16.dp)) {
                Text("Danbooru 设置", style = MiuixTheme.textStyles.title3)
                Text(state.account.title + state.identity?.let { " · ${it.name} / ${it.level}" }.orEmpty(),
                    style = MiuixTheme.textStyles.footnote1)
                state.accountError?.let { Text(it, style = MiuixTheme.textStyles.footnote1) }
                DialogFormColumn(footer = {
                    status?.let { Text(it, Modifier.padding(top = 8.dp), style = MiuixTheme.textStyles.footnote1) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(text = "取消", onClick = { if (!busy) onDismiss() })
                        TextButton(text = if (busy) "处理中…" else "保存", onClick = {
                            action {
                                val snapshot = draft.validated()
                                onSave(snapshot).onSuccess { onDismiss() }.onFailure { status = it.message ?: "保存失败" }
                            }
                        })
                    }
                }) {
                    Text("手机直连官方 HTTPS 站点。账号/Key 与代理仅用于 Danbooru，不影响集市或 ComfyUI。",
                        style = MiuixTheme.textStyles.footnote1)
                    TextField(draft.username, { if (!busy) draft = draft.copy(username = it) },
                        label = "Danbooru 用户名", singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextField(draft.apiKey, { if (!busy) draft = draft.copy(apiKey = it) },
                        label = "API Key（与用户名同时留空为匿名）", singleLine = true,
                        visualTransformation = transform, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth())
                    Text("只读 Key 权限：users:profile、posts:index、posts:show。IP 限制需匹配手机或代理出口；登录仍遵循账号原有权限。",
                        style = MiuixTheme.textStyles.footnote1)
                    DanbooruSettingSwitch("使用独立 HTTP 代理", draft.proxyEnabled) { if (!busy) draft = draft.copy(proxyEnabled = it) }
                    if (draft.proxyEnabled) {
                        TextField(draft.proxyAddress, { if (!busy) draft = draft.copy(proxyAddress = it) }, label = "http://主机:端口",
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        TextField(draft.proxyUsername, { if (!busy) draft = draft.copy(proxyUsername = it) }, label = "代理用户名（可选）",
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        TextField(draft.proxyPassword, { if (!busy) draft = draft.copy(proxyPassword = it) }, label = "代理密码（可选）",
                            singleLine = true, visualTransformation = transform, modifier = Modifier.fillMaxWidth())
                        Text("127.0.0.1 是手机本机；电脑代理请填局域网 IP。代理覆盖 API、缩略图、预览和原图。",
                            style = MiuixTheme.textStyles.footnote1)
                    }
                    TextButton(text = if (visible) "隐藏 Key 与密码" else "显示 Key 与密码", onClick = { visible = !visible })
                    Text("默认内容筛选", style = MiuixTheme.textStyles.body2)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ContentFilter.entries.filter { it != ContentFilter.CUSTOM }.forEach { filter ->
                            TextButton(text = (if (draft.defaultFilter == filter) "✓ " else "") + filter.title,
                                onClick = { if (!busy) draft = draft.copy(defaultFilter = filter) })
                        }
                    }
                    Text("默认复制格式", style = MiuixTheme.textStyles.body2)
                    Row {
                        TagCopyMode.entries.forEach { mode ->
                            TextButton(text = (if (draft.copyOptions.mode == mode) "✓ " else "") + mode.title,
                                onClick = { if (!busy) draft = draft.copy(copyOptions = draft.copyOptions.copy(mode = mode)) })
                        }
                    }
                    DanbooruSettingSwitch("下划线转空格", draft.copyOptions.underscoresToSpaces) {
                        if (!busy) draft = draft.copy(copyOptions = draft.copyOptions.copy(underscoresToSpaces = it))
                    }
                    DanbooruSettingSwitch("提示词括号转义（默认关闭）", draft.copyOptions.escapeParentheses) {
                        if (!busy) draft = draft.copy(copyOptions = draft.copyOptions.copy(escapeParentheses = it))
                    }
                    TextButton(text = "测试当前输入（不会保存）", onClick = {
                        action { status = "正在测试当前输入，尚未执行保存…"; status = onTest(draft.validated()) }
                    })
                    TextButton(text = "退出 Danbooru 登录", onClick = {
                        action { onLogout().onSuccess {
                            draft = draft.copy(username = "", apiKey = "")
                            status = "已退出登录，仅清除本账号在线缓存；相册与代理保留"
                        }.onFailure { status = "退出失败，原配置保留" } }
                    })
                    TextButton(text = "清除 Danbooru 缓存", onClick = { action { status = onClear() } })
                    Text("首次分享原图可能需要获取不同于预览的原始字节；相同原图后续分享/保存复用缓存，淘汰或清除后会重新读取。",
                        style = MiuixTheme.textStyles.footnote1)
                }
            }
        }
    }
}

@Composable
internal fun DanbooruSettingSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).padding(end = 6.dp), style = MiuixTheme.textStyles.body2)
        Switch(checked, onChange)
    }
}
