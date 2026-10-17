package com.comfyui.mobile.ui.screens.agent

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.comfyui.mobile.agent.AgentPromptFormat
import com.comfyui.mobile.danbooru.DanbooruSettings
import com.comfyui.mobile.danbooru.TagCallMode
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AgentPromptNetworkSettings(settings: DanbooruSettings, onSave: (DanbooruSettings) -> Unit) {
    var draft by remember(settings) { mutableStateOf(settings) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("提示词格式与标签核对", style = MiuixTheme.textStyles.title4)
            Text("格式按绘图模型选择，与聊天模型无关。人设只管对话，分镜只管画面；JSON 包装由 App 统一。",
                style = MiuixTheme.textStyles.footnote1)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AgentPromptFormat.entries.forEach { format ->
                    Button(onClick = { draft = draft.copy(promptFormat = format) },
                        colors = if (draft.promptFormat == format) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()) {
                        Text(format.title)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("联网核对 Danbooru 标签", Modifier.weight(1f))
                Switch(checked = draft.enabled, onCheckedChange = { draft = draft.copy(enabled = it) })
            }
            Text("每轮最多 3 次 HTTP 请求，通常增加一次模型修订；查不到或联网失败保留原词。查询名称、别名和词义，不搜索图片。",
                style = MiuixTheme.textStyles.footnote1)
            if (draft.enabled) {
                TagCallMode.entries.forEach { mode ->
                    Button(onClick = { draft = draft.copy(callMode = mode) },
                        colors = if (draft.callMode == mode) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()) {
                        Text(mode.title)
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("独立 HTTP 代理", Modifier.weight(1f))
                    Switch(checked = draft.proxyEnabled, onCheckedChange = { draft = draft.copy(proxyEnabled = it) })
                }
                if (draft.proxyEnabled) {
                    TextField(value = draft.proxyAddress,
                        onValueChange = { draft = draft.copy(proxyAddress = it) },
                        label = "http://主机:端口", singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                }
                Text("默认直连。这里的代理仅用于 Danbooru，不影响 ComfyUI、Civitai 或 LLM。",
                    style = MiuixTheme.textStyles.footnote1)
            }
            Button(onClick = { onSave(draft) }, colors = ButtonDefaults.buttonColorsPrimary()) {
                Text("保存提示词与联网设置")
            }
        }
    }
}
