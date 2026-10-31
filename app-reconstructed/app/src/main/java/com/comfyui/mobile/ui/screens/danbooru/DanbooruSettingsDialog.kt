package com.comfyui.mobile.ui.screens.danbooru

import androidx.compose.foundation.layout.*
import com.comfyui.mobile.ui.designsystem.PocketTokens
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.comfyui.mobile.danbooru.*
import com.comfyui.mobile.ui.components.rememberDialogDismiss
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun DanbooruSettingsDialog(
    initial: DanbooruSettings, state: DanbooruState, onDismiss: () -> Unit,
    onSave: suspend (DanbooruSettings) -> Result<Unit>,
    onTest: suspend (DanbooruSettings) -> String,
    onLogout: suspend () -> Result<Unit>, onClear: suspend () -> String
) {
    // Credentials stay out of SavedState, as in the original connection form.
    var draft by remember { mutableStateOf(initial) }
    var visible by remember { mutableStateOf(false) }
    val dismissal = rememberDialogDismiss(onDismiss)
    var busy by remember { mutableStateOf(false) }
    var preferences by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val transform = if (visible) VisualTransformation.None else PasswordVisualTransformation()
    fun action(block: suspend () -> Unit) {
        if (busy || !dismissal.show) return
        busy = true
        scope.launch {
            try { block() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status = if (e is DanbooruException || e is IllegalArgumentException) e.message else "操作失败，请重试；原设置保留" }
            finally { busy = false }
        }
    }
    OverlayDialog(
        show = dismissal.show, modifier = dismissal.modifier, title = "Danbooru 设置",
        summary = state.account.title + state.identity?.let { " · ${it.name}" }.orEmpty(),
        onDismissRequest = { if (!busy) dismissal.dismiss() },
        onDismissFinished = dismissal::onDismissFinished
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DanbooruPill("连接", !preferences, Modifier.weight(1f)) { if (!busy) preferences = false }
                DanbooruPill("偏好", preferences, Modifier.weight(1f)) { if (!busy) preferences = true }
            }
            // Measure fixed tabs/actions before assigning the remaining height to the form.
            // In short windows or with the IME visible, fields scroll instead of hiding Save.
            Column(Modifier.weight(1f, fill = false).heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!preferences) {
                        state.accountError?.let { SettingNote(it) }
                        DanbooruInput(draft.username, { draft = draft.copy(username = it) }, "用户名", !busy)
                        DanbooruInput(draft.apiKey, { draft = draft.copy(apiKey = it) }, "API Key", !busy, transform, KeyboardType.Password)
                        SettingNote("用户名与 Key 同时留空即可匿名浏览。")
                        DanbooruSettingSwitch("独立 HTTP 代理", draft.proxyEnabled, !busy) { draft = draft.copy(proxyEnabled = it) }
                        if (draft.proxyEnabled) {
                            DanbooruInput(draft.proxyAddress, { draft = draft.copy(proxyAddress = it) },
                                "代理地址 · http://主机:端口", !busy, keyboardType = KeyboardType.Uri)
                            DanbooruInput(draft.proxyUsername, { draft = draft.copy(proxyUsername = it) }, "代理用户名（可选）", !busy)
                            DanbooruInput(draft.proxyPassword, { draft = draft.copy(proxyPassword = it) },
                                "代理密码（可选）", !busy, transform, KeyboardType.Password)
                            SettingNote("127.0.0.1 指手机本机；电脑代理请填局域网 IP。")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DanbooruInlineAction(if (visible) "隐藏密钥" else "显示密钥", enabled = !busy) { visible = !visible }
                            DanbooruSmallAction("测试连接", Modifier.weight(1f), enabled = !busy) {
                                action { status = "正在测试，尚未保存…"; status = onTest(draft.validated()) }
                            }
                        }
                        DanbooruInlineAction(if (help) "收起连接说明" else "连接说明", enabled = !busy) { help = !help }
                        if (help) SettingNote(
                            "此配置仅作用于 Danbooru 的 API、缩略图、预览与原图，不影响集市或 ComfyUI。\n" +
                                "只读 Key 权限：users:profile、posts:index、posts:show；IP 限制需匹配手机或代理出口。登录沿用账号原有权限。")
                        if (initial.loggedIn) DanbooruSmallAction("退出 Danbooru 登录", enabled = !busy) {
                            action {
                                onLogout().onSuccess {
                                    draft = draft.copy(username = "", apiKey = "")
                                    status = "已退出登录，相册与代理设置保留"
                                }.onFailure { status = "退出失败，原配置保留" }
                            }
                        }
                    } else {
                        Text("默认内容评级", style = MiuixTheme.textStyles.body2)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ContentFilter.entries.filter { it != ContentFilter.CUSTOM }.forEach { value ->
                                DanbooruPill(value.title, draft.defaultFilter == value) {
                                    if (!busy) draft = draft.copy(defaultFilter = value)
                                }
                            }
                        }
                        Text("默认复制格式", style = MiuixTheme.textStyles.body2)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TagCopyMode.entries.forEach { mode ->
                                DanbooruPill(mode.title, draft.copyOptions.mode == mode) {
                                    if (!busy) draft = draft.copy(copyOptions = draft.copyOptions.copy(mode = mode))
                                }
                            }
                        }
                        DanbooruSettingSwitch("下划线转空格", draft.copyOptions.underscoresToSpaces, !busy) {
                            draft = draft.copy(copyOptions = draft.copyOptions.copy(underscoresToSpaces = it))
                        }
                        DanbooruSettingSwitch("括号转义", draft.copyOptions.escapeParentheses, !busy) {
                            draft = draft.copy(copyOptions = draft.copyOptions.copy(escapeParentheses = it))
                        }
                        DanbooruSmallAction("清除在线缓存", enabled = !busy) { action { status = onClear() } }
                        SettingNote("分享与保存复用原图缓存；清除缓存不影响登录设置或系统相册。")
                    }
            }
            status?.let {
                Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DanbooruSmallAction("取消", Modifier.weight(1f), enabled = !busy, onClick = dismissal::dismiss)
                DanbooruSmallAction(if (busy) "处理中…" else "保存", Modifier.weight(1f), primary = true, enabled = !busy) {
                    action {
                        onSave(draft.validated()).onSuccess { dismissal.dismiss() }
                            .onFailure { status = it.message ?: "保存失败" }
                    }
                }
            }
        }
    }
}

@Composable
private fun DanbooruInput(
    value: String, onChange: (String) -> Unit, label: String, enabled: Boolean,
    transform: VisualTransformation = VisualTransformation.None, keyboardType: KeyboardType = KeyboardType.Text
) {
    TextField(value, onChange, label = label, enabled = enabled, singleLine = true, useLabelAsPlaceholder = true,
        textStyle = MiuixTheme.textStyles.body2, insideMargin = DpSize(14.dp, 12.dp), cornerRadius = PocketTokens.Radius.Medium,
        visualTransformation = transform, keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth())
}

@Composable
private fun SettingNote(text: String) {
    Text(text, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
}

@Composable
internal fun DanbooruSettingSwitch(title: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).padding(end = 12.dp), style = MiuixTheme.textStyles.body2)
        Switch(checked, onChange, enabled = enabled)
    }
}
