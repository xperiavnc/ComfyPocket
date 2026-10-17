package com.comfyui.mobile.ui.screens.agent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.comfyui.mobile.danbooru.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun DanbooruCheckPanel(
    report: DanbooruCheckReport?, checking: TagCheckOrigin?,
    refreshEnabled: Boolean = true, onRefresh: () -> Unit = {}
) {
    var expanded by remember(report) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (checking != null) {
            Text("正在查询 Danbooru 标签 · ${checking.title}",
                style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary)
        }
        if (report != null) {
            Text("${report.origin.title} · ${report.summary}  ${if (expanded) "收起" else "详情"}",
                style = MiuixTheme.textStyles.footnote1,
                modifier = Modifier.clickable { expanded = !expanded })
            if (expanded) {
                report.items.forEach { item ->
                    Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("${item.query} · ${item.search.status.title}${if (item.search.fromCache) " · 缓存" else ""}",
                            style = MiuixTheme.textStyles.footnote1)
                        item.tags.forEach { tag ->
                            Text("${tag.name} · ${tag.matchKind} · ${categoryTitle(tag.category)} · ${tag.postCount?.toString() ?: "—"} 篇${if (tag.isDeprecated) " · 已弃用" else ""}",
                                style = MiuixTheme.textStyles.footnote2)
                        }
                        item.alias?.aliases.orEmpty().forEach { alias ->
                            Text("${alias.antecedentName} → ${alias.consequentName}", style = MiuixTheme.textStyles.footnote2)
                        }
                        item.alias?.takeIf { it.status != LookupStatus.FOUND }?.let {
                            Text("别名：${it.status.title}", style = MiuixTheme.textStyles.footnote2)
                        }
                        item.wiki?.let {
                            Text(it.wikiSummary?.takeIf(String::isNotBlank) ?: "Wiki：${it.status.title}",
                                style = MiuixTheme.textStyles.footnote2)
                        }
                    }
                }
                Text("本轮 HTTP ${report.networkRequests}/3；类别与作品数不代表当前模型效果",
                    style = MiuixTheme.textStyles.footnote2)
                TextButton(text = "刷新核对（不重新出图）",
                    onClick = onRefresh, enabled = refreshEnabled && checking == null)
            }
        }
    }
}
