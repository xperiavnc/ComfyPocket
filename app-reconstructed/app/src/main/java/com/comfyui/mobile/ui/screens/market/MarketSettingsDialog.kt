package com.comfyui.mobile.ui.screens.market

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.comfyui.mobile.market.CivitaiSettings
import com.comfyui.mobile.market.MarketConnectionMode
import com.comfyui.mobile.ui.components.DialogFormColumn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.comfyui.mobile.ui.designsystem.PocketButtonDefaults as ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import com.comfyui.mobile.ui.designsystem.PocketTextButton as TextButton
import com.comfyui.mobile.ui.designsystem.PocketTextField as TextField
import com.comfyui.mobile.ui.designsystem.PocketDialog as OverlayDialog
import androidx.compose.material3.MaterialTheme

@Composable
fun MarketSettingsDialog(
    initial: CivitaiSettings,
    onDismiss: () -> Unit,
    onSave: suspend (CivitaiSettings) -> Result<Unit>,
    onTest: suspend (CivitaiSettings) -> String
) {
    // Do not use rememberSaveable for credentials (SavedState is not a secret store).
    var draft by remember { mutableStateOf(initial) }
    var showSecrets by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val passwordTransformation = if (showSecrets) VisualTransformation.None else PasswordVisualTransformation()
    BackHandler(enabled = !saving) { onDismiss() }

    OverlayDialog(
        show = true,
        title = "集市连接设置",
        onDismissRequest = { if (!saving) onDismiss() }
    ) {
        DialogFormColumn(
            footer = {
                status?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurface, modifier = Modifier.padding(top = 10.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(text = "取消", onClick = { if (!saving) onDismiss() })
                    TextButton(
                        text = if (saving) "保存中…" else "保存",
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        onClick = {
                            if (!saving && !testing) {
                                saving = true
                                scope.launch {
                                    try {
                                        onSave(draft).onSuccess { onDismiss() }.onFailure {
                                            status = it.message ?: "保存失败，请重试"
                                        }
                                    } finally {
                                        saving = false
                                    }
                                }
                            }
                        }
                    )
                }
            }
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(MarketConnectionMode.DIRECT to "手机直连", MarketConnectionMode.BRIDGE to "电脑桥接")
                    .forEach { (mode, title) ->
                        TextButton(
                            text = (if (draft.mode == mode) "✓ " else "") + title,
                            onClick = { if (!saving && !testing) draft = draft.copy(mode = mode) }
                        )
                    }
            }
            Text(
                if (draft.mode == MarketConnectionMode.DIRECT)
                    "手机独立浏览 Civitai，无需电脑在线；安装模型仍交给电脑下载。"
                else "浏览经由当前 ComfyUI 电脑转发，使用电脑自己的网络与 Key。",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSecondaryContainer
            )
            Spacer(Modifier.height(12.dp))
            SettingSwitch("启用自定义 HTTP 代理（手机直连）", draft.proxyEnabled) {
                if (!saving && !testing) draft = draft.copy(proxyEnabled = it)
            }
            if (draft.proxyEnabled) {
                TextField(
                    value = draft.proxyAddress,
                    onValueChange = { if (!saving && !testing) draft = draft.copy(proxyAddress = it) },
                    label = "http://代理主机:端口", singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Text(
                    "127.0.0.1 指手机本机；使用电脑代理时请填写电脑的局域网 IP，并允许局域网连接。",
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSecondaryContainer,
                    modifier = Modifier.padding(top = 4.dp)
                )
                TextField(
                    value = draft.proxyUsername,
                    onValueChange = { if (!saving && !testing) draft = draft.copy(proxyUsername = it) },
                    label = "代理用户名（可选）", singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                TextField(
                    value = draft.proxyPassword,
                    onValueChange = { if (!saving && !testing) draft = draft.copy(proxyPassword = it) },
                    label = "代理密码（可选）", singleLine = true,
                    visualTransformation = passwordTransformation,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            TextField(
                value = draft.apiKey,
                onValueChange = { if (!saving && !testing) draft = draft.copy(apiKey = it) },
                label = "Civitai Key（留空为匿名访问）", singleLine = true,
                visualTransformation = passwordTransformation,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            TextButton(text = if (showSecrets) "隐藏密钥与密码" else "显示密钥与密码",
                onClick = { showSecrets = !showSecrets })
            Text(
                "Key 和代理密码由手机密钥库加密保存，不进入配置导出或日志，也不发送给电脑。" +
                    "电脑下载的 Key 请到 ComfyUI 设置 → Mobile Bridge → Civitai 配置。" +
                    "Key 用于账号已有访问权限；购买、抢先体验及地区限制仍由 Civitai 判断。",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSecondaryContainer
            )
            Spacer(Modifier.height(8.dp))
            SettingSwitch("显示成人内容（直连，遵循账号与地区限制）", draft.includeMatureContent) {
                if (!saving && !testing) draft = draft.copy(includeMatureContent = it)
            }
            TextButton(
                text = if (testing) "测试中…" else "测试手机直连与 Key",
                onClick = {
                    if (!saving && !testing) {
                        testing = true
                        status = null
                        scope.launch {
                            try {
                                status = onTest(draft)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                status = error.message ?: "连接测试失败，请检查设置"
                            } finally {
                                testing = false
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun SettingSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
