package com.comfyui.mobile.ui.screens.modelcenter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comfyui.mobile.modelcenter.*
import com.comfyui.mobile.ui.screens.market.descriptionText
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun LocalModelDetails(
    vm: ModelCenterViewModel, state: ModelCenterState, item: LocalModel,
    coverUrl: String? = vm.preview(item)
) {
    var editing by remember(item.id) { mutableStateOf(false) }
    BackHandler(enabled = !editing && state.detail?.id == item.id) { vm.openDetail(null) }
    LaunchedEffect(item.id, item.sha256, state.sourceScope, state.sourceTransport) { vm.requestSource(item.id) }
    val writable = state.connected && !state.fromCache && !state.detailLoading
    val colors = MiuixTheme.colorScheme
    val provider = item.provider
    val task = state.sourceTasks[modelHash(item.sha256)]
    val title = item.annotation.alias.ifBlank { provider?.name.orEmpty().ifBlank {
        item.name.substringBeforeLast('.', item.name)
    } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp)
        .testTag("local-model-detail"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.SemiBold,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                val byline = listOfNotNull(provider?.author?.takeIf(String::isNotBlank),
                    provider?.version?.takeIf(String::isNotBlank)).joinToString(" · ")
                if (byline.isNotBlank()) Text(byline, style = MiuixTheme.textStyles.footnote1,
                    color = colors.onSecondaryContainer, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            ModelAction("编辑标注", writable) { editing = true }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ModelStatusBadge(modelCategoryLabel(item.category))
            if (item.baseModel.isNotBlank()) ModelStatusBadge(item.baseModel, true)
            ModelStatusBadge(modelBytes(item.size))
            if (item.annotation.favorite) ModelStatusBadge("已收藏", true)
        }
        LocalModelCover(item, coverUrl, state.fromCache,
            Modifier.fillMaxWidth().height(if (coverUrl == null) 96.dp else 164.dp), contentScale = ContentScale.Fit)
        if (state.detailLoading) Text("正在同步本地文件状态…", style = MiuixTheme.textStyles.footnote2,
            color = colors.onSecondaryContainer)
        state.notice?.let { Text(it, style = MiuixTheme.textStyles.footnote1, color = colors.primary) }

        DetailSection("模型简介") {
            val intro = remember(provider?.modelDescription) { descriptionText(provider?.modelDescription.orEmpty()) }
            if (provider?.modelDescriptionFetched == true) {
                ExpandableModelText(intro.ifBlank { "作者尚未填写模型简介。" }, "简介")
            } else Text(when {
                modelHash(item.sha256) == null -> "尚缺完整文件指纹。连接电脑同步模型库后，手机会自动查询简介。"
                task?.active == true -> "正在由手机获取模型简介…"
                task?.status == "not-found" -> "来源未找到匹配文件。可继续填写自己的模型介绍与使用备注。"
                else -> "尚未获取模型简介。手机会按文件 SHA-256 查询，不根据文件名猜测。"
            }, style = MiuixTheme.textStyles.body2, color = colors.onSecondaryContainer)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (provider?.fetchedBy == "phone") "Civitai · 已保存到手机" else "Civitai · 手机直连",
                    style = MiuixTheme.textStyles.footnote2, color = colors.onSecondaryContainer,
                    modifier = Modifier.weight(1f))
                ModelAction(if (task?.active == true) "查询中…" else
                    if (provider?.modelDescriptionFetched == true) "刷新资料" else "重试查询",
                    state.sourceScope.isNotBlank() && modelHash(item.sha256) != null && task?.active != true) {
                    vm.enrich(listOf(item.id), refresh = true)
                }
            }
            if (task != null && !task.active && task.status != "ready")
                Text(task.message, style = MiuixTheme.textStyles.footnote2, color = colors.onSecondaryContainer)
        }
        TriggerWords(item)
        provider?.description?.takeIf(String::isNotBlank)?.let { html ->
            DetailSection("版本说明") {
                val text = remember(html) { descriptionText(html) }
                ExpandableModelText(text, "版本说明")
            }
        }
        AnnotationSummary(vm, item, writable) { editing = true }
        FileInformation(item, state.fromCache)
        Text("简介、版本与网络预览使用手机的 HTTP 代理和 Civitai Key。人工标注单独保存，不被来源资料覆盖。",
            style = MiuixTheme.textStyles.footnote2, color = colors.onSecondaryContainer)
    }
    if (editing) AnnotationDialog(item, onDismiss = { editing = false }, onSave = vm::save)
}

@Composable
private fun TriggerWords(item: LocalModel) {
    val context = LocalContext.current
    DetailSection("触发词") {
        val words = item.triggerWords.joinToString(", ")
        val manual = item.annotation.trainedWords.isNotEmpty() || "trainedWords" in item.annotation.fieldLocks
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (manual) "用户明确标注" else if (item.provider != null) "来源版本 · SHA-256 匹配" else "尚未确认来源",
                style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f))
            if (words.isNotBlank()) ModelAction("复制触发词") {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("触发词", words))
                Toast.makeText(context, "触发词已复制", Toast.LENGTH_SHORT).show()
            }
        }
        SelectionContainer {
            Text(words.ifBlank { if (manual) "用户将触发词留空。" else
                "来源未声明触发词；这不代表模型一定无需触发词。" }, style = MiuixTheme.textStyles.body2)
        }
    }
}

@Composable
private fun AnnotationSummary(vm: ModelCenterViewModel, item: LocalModel, writable: Boolean, edit: () -> Unit) {
    val ann = item.annotation
    val lines = buildList {
        if (ann.alias.isNotBlank()) add("别名：${ann.alias}")
        if (ann.aliases.isNotEmpty()) add("其他别名：${ann.aliases.joinToString(" / ")}")
        if (ann.useCases.isNotEmpty()) add("用途：${ann.useCases.mapNotNull(modelUseCases::get).joinToString(" / ")}")
        if (ann.character.isNotBlank()) add("角色 / 作品：${ann.character}")
        if (ann.outfit.isNotBlank()) add("服装 / 形态：${ann.outfit}")
        if (ann.tags.isNotEmpty()) add("标签：${ann.tags.joinToString(" / ")}")
        if (ann.baseModel.isNotBlank()) add("底模覆盖：${ann.baseModel}")
        ann.preferredStrength?.let { add("常用权重：$it") }
        if (ann.notes.isNotBlank()) add(ann.notes)
    }
    DetailSection(if (ann.classificationSource == "ai-suggestion") "我的标注 · AI 建议待确认" else "我的标注") {
        if (lines.isEmpty()) {
            Text("添加用途、角色或使用心得，方便下次查找。", style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSecondaryContainer)
            ModelAction("添加标注", writable, edit)
        } else SelectionContainer { Text(lines.joinToString("\n"), style = MiuixTheme.textStyles.body2) }
        if (item.revision > 0) {
            val scope = rememberCoroutineScope()
            var error by remember { mutableStateOf<String?>(null) }
            ModelAction("撤销上次标注", writable) {
                scope.launch { vm.save(item.annotation, undo = true).onFailure { error = it.message } }
            }
            error?.let { Text(it, color = MiuixTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun FileInformation(item: LocalModel, cached: Boolean) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    Card(cornerRadius = 16.dp, modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("文件信息", style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                ModelAction(if (expanded) "收起" else "展开", onClick = { expanded = !expanded })
            }
            Text(item.name, style = MiuixTheme.textStyles.footnote2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = MiuixTheme.colorScheme.onSecondaryContainer)
            if (expanded) SelectionContainer {
                Text("${item.format} · ${modelBytes(item.size)}\n${item.rootPath}/${item.relativePath}\n" +
                    "SHA-256：${item.sha256 ?: "尚未计算"}\n" +
                    "修改：${modelTime(item.mtime)}\n${modelLoadStatus(item, cached)}\n最近使用：${modelTime(item.lastUsed)}" +
                    item.provider?.let { "\n来源：${it.source}\nmodel ${it.modelId} / version ${it.versionId} / file ${it.fileId}\n" +
                        "资料更新：${modelTime(it.fetchedAt)}" }.orEmpty(),
                    style = MiuixTheme.textStyles.footnote1)
            }
        }
    }
}

@Composable
private fun ExpandableModelText(text: String, label: String) {
    var expanded by remember(text) { mutableStateOf(false) }
    var overflow by remember(text) { mutableStateOf(false) }
    SelectionContainer {
        Text(text, style = MiuixTheme.textStyles.body2, maxLines = if (expanded) Int.MAX_VALUE else 6,
            overflow = TextOverflow.Ellipsis, onTextLayout = { if (!expanded) overflow = it.hasVisualOverflow })
    }
    if (overflow || expanded) ModelAction(if (expanded) "收起$label" else "展开$label") { expanded = !expanded }
}

@Composable
private fun DetailSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(cornerRadius = 16.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}
