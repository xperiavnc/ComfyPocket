package com.comfyui.mobile.ui.screens.agent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comfyui.mobile.ui.components.DialogFormColumn
import com.comfyui.mobile.ui.components.rememberDialogDismiss
import com.comfyui.mobile.ui.designsystem.PocketTokens
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Filters model ids by every whitespace-separated term, case-insensitively, keeping list order. */
internal fun filterModels(models: List<String>, query: String): List<String> {
    val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (terms.isEmpty()) return models
    return models.filter { id -> val low = id.lowercase(); terms.all { it in low } }
}

/**
 * Searchable list of the upstream's models. [models] null = never fetched (the dialog fetches on
 * open); a typed name that is not in the list can still be used as-is, for services whose /models
 * is incomplete or missing.
 */
@Composable
fun AgentModelPicker(
    models: List<String>?,
    current: String,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val scheme = MiuixTheme.colorScheme
    var query by rememberSaveable { mutableStateOf("") }
    var picked by remember { mutableStateOf<String?>(null) }
    val dismissal = rememberDialogDismiss {
        picked?.let(onPick)
        onDismiss()
    }
    val all = models.orEmpty()
    val shown = remember(all, query) { filterModels(all, query) }
    val typed = query.trim()
    OverlayDialog(show = dismissal.show, title = "选择模型", onDismissRequest = dismissal::dismiss,
        onDismissFinished = dismissal::onDismissFinished) {
        DialogFormColumn(modifier = dismissal.modifier.testTag("agent-model-picker"), footer = {}) {
            Column(verticalArrangement = Arrangement.spacedBy(PocketTokens.Space.XS)) {
                TextField(value = query, onValueChange = { query = it }, label = "搜索或输入模型名称",
                    useLabelAsPlaceholder = true, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("agent-model-search"))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = when {
                            loading -> "正在获取模型列表…"
                            models == null -> "还没有获取过模型列表"
                            query.isNotBlank() -> "匹配 ${shown.size} / ${all.size} 个模型"
                            else -> "共 ${all.size} 个模型"
                        },
                        modifier = Modifier.weight(1f),
                        style = MiuixTheme.textStyles.footnote1,
                        color = scheme.onSurfaceVariantSummary
                    )
                    TextButton(if (models == null) "获取" else "刷新", enabled = !loading, onClick = onRefresh,
                        modifier = Modifier.testTag("agent-model-refresh"))
                }
                if (!error.isNullOrBlank() && !loading) Text(error, style = MiuixTheme.textStyles.footnote1,
                    color = scheme.error, maxLines = 4, overflow = TextOverflow.Ellipsis)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    if (typed.isNotEmpty() && all.none { it == typed }) item(key = "\u0000custom") {
                        ModelRow("使用「$typed」", selected = false, tag = "agent-model-custom") {
                            picked = typed; dismissal.dismiss()
                        }
                    }
                    items(shown, key = { it }) { id ->
                        ModelRow(id, selected = id == current, tag = "agent-model-$id") {
                            picked = id; dismissal.dismiss()
                        }
                    }
                }
                TextButton("取消", onClick = dismissal::dismiss, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ModelRow(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .clip(PocketTokens.Shape.Medium)
            .semantics { this.selected = selected }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = PocketTokens.Space.S, vertical = PocketTokens.Space.XS)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MiuixTheme.textStyles.body2,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) scheme.primary else scheme.onSurface)
        if (selected) {
            Spacer(Modifier.width(PocketTokens.Space.XS))
            Icon(MiuixIcons.Ok, contentDescription = "当前模型", tint = scheme.primary, modifier = Modifier.size(18.dp))
        }
    }
}
