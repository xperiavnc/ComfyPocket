package com.comfyui.mobile.ui.screens.agent

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.comfyui.mobile.agent.AgentPromptFormat
import com.comfyui.mobile.agent.AgentPromptSettings
import com.comfyui.mobile.taglib.LocalTagEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AgentLocalTagSettings(
    settings: AgentPromptSettings,
    search: suspend (String, String) -> List<LocalTagEntry>,
    onSave: (AgentPromptSettings) -> Unit
) {
    var draft by remember(settings) { mutableStateOf(settings) }
    var query by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("tag") }
    var results by remember { mutableStateOf<List<LocalTagEntry>>(emptyList()) }
    var status by remember { mutableStateOf("输入中文、英文或别名查询") }
    LaunchedEffect(query, kind) {
        results = emptyList()
        if (query.isBlank()) { status = "输入中文、英文或别名查询"; return@LaunchedEffect }
        status = "正在离线查询…"
        delay(250)
        try {
            results = search(query, kind)
            status = if (results.isEmpty()) "未命中，仍可使用自定义提示词" else "找到 ${results.size} 项候选"
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            status = "本地词库读取失败，请重试；原始提示词不受影响"
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("提示词格式与本地词库", style = MiuixTheme.textStyles.title4)
            Text("人设只管对话，分镜负责编写正向和反向；JSON 格式由 App 统一。",
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
                Text("绘图前查询本地词库", Modifier.weight(1f))
                Switch(checked = draft.useLocalLibrary, onCheckedChange = { draft = draft.copy(useLocalLibrary = it) })
            }
            Text("内置 13,358 个普通词、34,122 个角色条目。离线检索候选后由 Agent 编写两组提示词，不访问 Danbooru，不增加模型修订轮次。未命中保留原词。",
                style = MiuixTheme.textStyles.footnote1)
            Button(onClick = { onSave(draft) }, colors = ButtonDefaults.buttonColorsPrimary()) {
                Text("保存提示词与词库设置")
            }
            Text("本地查词（不调用 AI、不出图）", style = MiuixTheme.textStyles.body2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("tag" to "普通词", "character" to "角色").forEach { (id, title) ->
                    Button(onClick = { kind = id },
                        colors = if (kind == id) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()) {
                        Text(title)
                    }
                }
            }
            TextField(value = query, onValueChange = { query = it.take(96) }, label = "中文 / 英文 / 别名",
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(status, style = MiuixTheme.textStyles.footnote1)
            if (results.isNotEmpty()) Column(
                Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                results.forEach { entry -> LocalTagEntryText(entry) }
            }
            Text("来源：ai-tag-toolbox 固定快照；角色身份与可选外貌分开。数据来源和重建方式见项目词库说明。",
                style = MiuixTheme.textStyles.footnote1)
        }
    }
}
