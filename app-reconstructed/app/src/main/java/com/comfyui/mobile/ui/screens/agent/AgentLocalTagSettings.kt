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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.comfyui.mobile.agent.AgentPromptSettings
import com.comfyui.mobile.taglib.LocalTagEntry
import com.comfyui.mobile.taglib.LocalTagLibrary
import java.util.Locale
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
    val app = LocalContext.current.applicationContext
    val library = remember(app) { LocalTagLibrary(app) }
    val counts = library.counts
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
            Text("本地词库", style = MiuixTheme.textStyles.title4)
            Text("人设只管对话，分镜负责编写正向和反向；JSON 格式由 App 统一。",
                style = MiuixTheme.textStyles.footnote1)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("绘图前查询本地词库", Modifier.weight(1f))
                Switch(checked = draft.useLocalLibrary, onCheckedChange = { draft = draft.copy(useLocalLibrary = it) })
            }
            Text("内置 ${String.format(Locale.ROOT, "%,d", counts.first)} 个标签、${String.format(Locale.ROOT, "%,d", counts.second)} 个角色；${String.format(Locale.ROOT, "%,d", library.translatedCount)} 项有中文对照，${String.format(Locale.ROOT, "%,d", library.untranslatedCount)} 项保留上游英文待补译。ai-tag-toolbox 全部词表、别名、角色与专用特征均已收录。可按中文、英文和别名离线查询，再由 Agent 编写正向和反向；未命中保留原词。",
                style = MiuixTheme.textStyles.footnote1)
            Button(onClick = { onSave(draft) }, colors = ButtonDefaults.buttonColorsPrimary()) {
                Text("保存词库设置")
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
            Text("来源：ai-tag-toolbox 与 ffdkj Danbooru 中英对照固定快照；角色身份与可选外貌分开。来源、翻译限制和重建方式见项目词库说明。",
                style = MiuixTheme.textStyles.footnote1)
        }
    }
}
