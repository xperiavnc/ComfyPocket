package com.comfyui.mobile.ui.screens.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.comfyui.mobile.taglib.LocalTagEntry
import com.comfyui.mobile.ui.feedback.AppToast
import com.google.gson.JsonParser
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun LocalTagEntryText(entry: LocalTagEntry) {
    val context = LocalContext.current
    val character = entry.kind == "character"
    val tag = if (character) entry.trigger.ifBlank { entry.name } else entry.name
    fun copy(label: String, value: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(label, value))
        AppToast.show(context, "已复制$label")
    }
    Column(Modifier.fillMaxWidth()) {
        SelectionContainer(Modifier.fillMaxWidth()) {
            Column {
                Text("${entry.name} · ${entry.translation.ifBlank { "上游暂缺中文对照" }}",
                    style = MiuixTheme.textStyles.body2)
                Text("${if (character) "角色身份" else "普通词"} · ${entry.category} / ${entry.section}",
                    style = MiuixTheme.textStyles.footnote1)
                if (character) {
                    val detail = runCatching { JsonParser.parseString(entry.detail).asJsonObject }.getOrNull()
                    val general = detail?.getAsJsonArray("optionalAppearance")?.take(12)?.map { it.asString }.orEmpty()
                    val specific = detail?.getAsJsonArray("optionalSpecificAppearance")?.take(4)?.map {
                        it.asJsonObject.let { word ->
                            listOf(word["en"].asString, word["zh"].asString).filter(String::isNotBlank)
                                .distinct().joinToString(" · ")
                        }
                    }.orEmpty()
                    val appearance = if (general.isEmpty() && specific.isEmpty())
                        "词库未附带外貌资料；按本轮描述和启用的角色档案编写。"
                    else "可选外貌（不自动拼入）：${general.joinToString(", ")}" +
                        if (specific.isNotEmpty()) "\n角色专用参考：${specific.joinToString(", ")}" else ""
                    Text("身份：${entry.trigger}\n$appearance",
                        style = MiuixTheme.textStyles.footnote1)
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                text = if (character) "复制身份" else "复制标签",
                enabled = tag.isNotBlank(),
                modifier = Modifier.testTag("agent-local-tag-copy-tag"),
                onClick = { copy(if (character) "身份标签" else "标签", tag) }
            )
            if (entry.translation.isNotBlank()) TextButton(
                text = "复制中文",
                modifier = Modifier.testTag("agent-local-tag-copy-translation"),
                onClick = { copy("中文释义", entry.translation) }
            )
        }
    }
}
