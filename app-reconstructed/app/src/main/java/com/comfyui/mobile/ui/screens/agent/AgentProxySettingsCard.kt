package com.comfyui.mobile.ui.screens.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.comfyui.mobile.agent.AgentProxySettings
import com.comfyui.mobile.ui.designsystem.PocketCard
import com.comfyui.mobile.ui.designsystem.PocketSurface
import com.comfyui.mobile.ui.designsystem.PocketTokens
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** HTTP proxy for Agent model API calls; LAN endpoints always go direct. */
@Composable
internal fun AgentProxySettingsCard(
    saved: AgentProxySettings,
    onSave: (AgentProxySettings) -> String?,
    modifier: Modifier = Modifier
) {
    val scheme = MiuixTheme.colorScheme
    var draft by remember { mutableStateOf(saved) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(saved) { draft = saved; error = null }
    val dirty = draft.copy(address = draft.address.trim(), username = draft.username.trim()) != saved

    PocketCard(modifier = modifier.fillMaxWidth().testTag("agent-proxy"), surface = PocketSurface.Base) {
        Column(verticalArrangement = Arrangement.spacedBy(PocketTokens.Space.S)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("HTTP 代理", style = MiuixTheme.textStyles.body1)
                    Text(
                        when {
                            !draft.enabled -> "Agent 请求直连"
                            saved.enabled && !dirty -> "已启用 · ${saved.label().orEmpty()}"
                            else -> "局域网地址始终直连"
                        },
                        style = MiuixTheme.textStyles.footnote2, color = scheme.onSurfaceVariantSummary
                    )
                }
                Switch(draft.enabled, onCheckedChange = {
                    draft = draft.copy(enabled = it)
                    error = null
                    // Turning it off needs no other input, so apply right away.
                    if (!it && saved.enabled) error = onSave(draft.copy(enabled = false))
                }, modifier = Modifier.testTag("agent-proxy-enabled"))
            }
            AnimatedVisibility(draft.enabled) {
                Column(verticalArrangement = Arrangement.spacedBy(PocketTokens.Space.XS)) {
                    TextField(draft.address, { draft = draft.copy(address = it); error = null },
                        label = "http://代理主机:端口", useLabelAsPlaceholder = true, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth().testTag("agent-proxy-address"))
                    TextField(draft.username, { draft = draft.copy(username = it); error = null },
                        label = "用户名（可选）", useLabelAsPlaceholder = true, singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("agent-proxy-username"))
                    TextField(draft.password, { draft = draft.copy(password = it); error = null },
                        label = "密码（可选）", useLabelAsPlaceholder = true, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth().testTag("agent-proxy-password"))
                    error?.let {
                        Text(it, color = scheme.error, style = MiuixTheme.textStyles.footnote1,
                            modifier = Modifier.testTag("agent-proxy-error"))
                    }
                    Button(
                        onClick = { error = onSave(draft) },
                        enabled = dirty || !saved.enabled,
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = PocketTokens.ControlHeight)
                            .testTag("agent-proxy-save"),
                        insideMargin = PocketTokens.ButtonMargin
                    ) { Text("保存代理") }
                }
            }
        }
    }
}
