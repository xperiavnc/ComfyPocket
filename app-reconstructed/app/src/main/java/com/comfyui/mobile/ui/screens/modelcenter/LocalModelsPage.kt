package com.comfyui.mobile.ui.screens.modelcenter

import androidx.compose.animation.AnimatedContent
import com.comfyui.mobile.ui.designsystem.PocketTokens
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import com.comfyui.mobile.modelcenter.*
import com.comfyui.mobile.ui.components.rememberDialogDismiss
import com.comfyui.mobile.ui.navigation.pocketPageEnter
import com.comfyui.mobile.ui.navigation.pocketPageExit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.DateFormat
import java.util.Date
import java.util.Locale

internal fun modelTime(value: Long?): String =
    value?.takeIf { it > 0 }?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) } ?: "未知"

internal fun modelBytes(value: Long?): String {
    if (value == null) return "大小未知"
    return when {
        value >= 1024L*1024*1024 -> String.format(Locale.US, "%.2f GiB", value / (1024.0*1024*1024))
        value >= 1024*1024 -> String.format(Locale.US, "%.1f MiB", value / (1024.0*1024))
        else -> "$value B"
    }
}

@Composable
internal fun ModelChip(label: String, selected: Boolean = false, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Text(label, color = if (selected) colors.primary else colors.onSurface,
        style = MiuixTheme.textStyles.footnote1,
        modifier = Modifier.background(
            if (selected) colors.primary.copy(alpha = .12f) else colors.surfaceContainerHigh,
            PocketTokens.Shape.Pill).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp))
}

@Composable
fun LocalModelsPage(vm: ModelCenterViewModel, state: ModelCenterState) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    AnimatedContent(state.detail?.id, transitionSpec = {
        val pop = targetState == null
        pocketPageEnter(pop, rtl) togetherWith pocketPageExit(pop, rtl)
    }, label = "local-model-details", modifier = Modifier.fillMaxSize()) { detailId ->
        if (detailId == null) LocalModelList(vm, state)
        else {
            // Retain the outgoing detail snapshot while its exit animation finishes.
            val item = remember(detailId) { state.detail }
            val latest = state.detail?.takeIf { it.id == detailId } ?: item
            latest?.let { LocalModelDetails(vm, state, it) }
        }
    }
}

@Composable
internal fun LocalModelList(vm: ModelCenterViewModel, state: ModelCenterState,
                            coverUrl: (LocalModel) -> String? = { vm.preview(it) }) {
    if (!state.restored) {
        Text("正在恢复此电脑的模型缓存…", modifier = Modifier.padding(16.dp))
        return
    }
    var showFilters by remember { mutableStateOf(false) }
    var showIndexInfo by remember { mutableStateOf(false) }
    var keyword by remember(state.identity, state.query.q) { mutableStateOf(state.query.q) }
    val list = rememberLazyGridState(state.scrollIndex, state.scrollOffset)
    LaunchedEffect(keyword) {
        delay(350)
        if (keyword != state.query.q) vm.query(state.query.copy(q = keyword))
    }
    LaunchedEffect(state.identity, state.query) {
        if (state.scrollIndex == 0 && state.scrollOffset == 0) list.scrollToItem(0)
    }
    LaunchedEffect(list, state.identity) {
        snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
            .distinctUntilChanged().collectLatest { (index, offset) ->
                delay(350)
                vm.scroll(index, offset)
            }
    }
    LazyVerticalGrid(state = list, columns = if (state.compact) GridCells.Fixed(1) else GridCells.Adaptive(156.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().testTag("local-model-list")) {
        item(key = "controls", span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ModelCenterSearch(keyword, { keyword = it }, "搜索名称、别名或触发词",
                        Modifier.weight(1f).testTag("local-model-search"))
                    ModelAction("筛选", onClick = { showFilters = true })
                    IconButton(onClick = vm::compact, modifier = Modifier.size(40.dp)) {
                        Icon(if (state.compact) MiuixIcons.GridView else Icons.AutoMirrored.Filled.List,
                            if (state.compact) "切换到卡片视图" else "切换到紧凑列表", modifier = Modifier.size(22.dp))
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("", "LORA", "CHECKPOINT", "UNET", "VAE", "TEXT_ENCODER").forEach { category ->
                        ModelChip(modelCategoryLabel(category), state.query.category == category) {
                            vm.query(state.query.copy(category = category))
                        }
                    }
                }
                val stats = state.page.stats
                val scan = state.tasks.scan.takeIf { it.active } ?: state.page.scan
                val roots = state.capabilities?.roots.orEmpty()
                Card(cornerRadius = PocketTokens.Radius.Medium, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(if (state.loading && state.page.items.isEmpty())
                                    "正在读取${state.query.category.takeIf { it.isNotBlank() }?.let(::modelCategoryLabel) ?: "模型"}…"
                                    else "${state.page.total} 个结果 · 库占用 ${modelBytes(stats.bytes)}",
                                    style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium)
                                Text(if (state.fromCache) "离线缓存 · ${state.serverLabel.ifBlank { "本地模型库" }}"
                                    else "${state.serverLabel.ifBlank { "当前电脑" }} · " +
                                        modelIndexStatus(state.page, scan, roots),
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSecondaryContainer,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            ModelAction(if (scan.active) "暂停" else "同步", enabled = state.connected) {
                                vm.scan(if (scan.active) "pause" else "start")
                            }
                            ModelAction("详情") { showIndexInfo = !showIndexInfo }
                        }
                        if (scan.active) ModelProgressTrack(scan.hashed.toFloat() / scan.discovered.coerceAtLeast(1),
                            Modifier.padding(bottom = 8.dp))
                        if (showIndexInfo) {
                            Text("${if (state.page.scanComplete) "已索引" else "部分索引"} ${stats.locations} 个文件位置 · " +
                                "指纹 ${stats.hashed}/${stats.locations} · 唯一内容 ${stats.uniqueContents}\n" +
                                "${if (state.fromCache) "缓存时间" else "同步时间"}：${modelTime(if (state.fromCache) state.cacheAt else state.page.lastSync)}" +
                                if (state.fromCache) "\n仅查询已浏览缓存，文件状态需联网确认。" else "",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSecondaryContainer)
                            if (!state.fromCache) {
                                roots.filter { it.status == "unreadable" }.forEach { root ->
                                    Text("${modelCategoryLabel(root.category)}：${root.path}\n目录读取失败，原记录保留；请检查目录是否在线及读取权限。",
                                        style = MiuixTheme.textStyles.footnote2,
                                        color = MiuixTheme.colorScheme.error)
                                }
                                val absent = roots.count { it.status == "absent" }
                                if (absent > 0) Text("已跳过 $absent 个尚未创建的空目录，不影响索引完成。",
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSecondaryContainer)
                            }
                            ModelAction("手机补全本页资料", state.sourceScope.isNotBlank() && state.page.items.isNotEmpty()) {
                                vm.enrich(state.page.items.map { it.id }.take(100), refresh = true)
                            }
                        }
                    }
                }
                if (state.query.useCase.isNotBlank() || state.query.favorite || state.query.baseModel.isNotBlank() ||
                    state.query.rootId.isNotBlank() || state.query.source.isNotBlank())
                    ModelAction("已启用筛选 · 调整") { showFilters = true }
                state.error?.let { Text(it, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1) }
                state.notice?.let { Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary) }
                if (state.loading && state.page.items.isEmpty()) Text("正在读取模型…", style = MiuixTheme.textStyles.footnote1)
            }
        }
        if (!state.loading && state.page.items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (state.connected) "没有符合条件的模型" else "暂无此电脑的模型缓存",
                    style = MiuixTheme.textStyles.body2)
                Text("调整筛选，或连接电脑后同步模型库", style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(top = 6.dp))
                ModelAction("重新读取") { vm.refresh() }
            }
        }
        items(state.page.items, key = { it.id }) { model ->
            LaunchedEffect(model.id, model.sha256, state.sourceScope, state.sourceTransport) { vm.requestSource(model.id) }
            LocalModelCard(model, coverUrl(model), state.compact, state.fromCache, vm.bridgePreview(model)) { vm.openDetail(model.id) }
        }
        if (state.page.hasMore) item(span = { GridItemSpan(maxLineSpan) }) {
            TextButton(text = if (state.loading) "正在加载…" else "加载更多（已显示 ${state.page.items.size}）",
                enabled = !state.loading, onClick = { vm.refresh(append = true) }, modifier = Modifier.fillMaxWidth())
        }
    }
    if (showFilters) ModelFiltersDialog(state, onDismiss = { showFilters = false }, onSave = vm::query)
}

@Composable
internal fun LocalModelCard(model: LocalModel, previewUrl: String?, compact: Boolean, cached: Boolean,
                            bridgeFallback: String? = null, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Card(onClick = onClick, cornerRadius = PocketTokens.Radius.Large, modifier = Modifier.fillMaxWidth().testTag("model-card-${model.id}")) {
        if (compact) Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            LocalModelCover(model, previewUrl, cached, Modifier.width(64.dp).height(80.dp), bridgeFallback = bridgeFallback)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                ModelCardName(model)
                Text("${modelCategoryLabel(model.category)} · ${modelBytes(model.size)}",
                    style = MiuixTheme.textStyles.footnote1, color = colors.onSecondaryContainer)
                Text(model.baseModel.ifBlank { model.relativePath.substringBeforeLast('/', "未标注底模") },
                    style = MiuixTheme.textStyles.footnote2, color = colors.onSecondaryContainer,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!model.present) ModelStatusBadge("文件待定位")
            }
        } else Column {
            Box(Modifier.padding(6.dp)) {
                LocalModelCover(model, previewUrl, cached, Modifier.fillMaxWidth().aspectRatio(.95f), bridgeFallback = bridgeFallback)
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    ModelStatusBadge(modelCategoryLabel(model.category))
                    if (model.annotation.favorite) ModelStatusBadge("收藏", true)
                }
            }
            Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                ModelCardName(model)
                Text(model.baseModel.ifBlank { "未标注底模" }, style = MiuixTheme.textStyles.footnote2,
                    color = colors.onSecondaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (model.present) modelBytes(model.size) else "文件待定位",
                    style = MiuixTheme.textStyles.footnote2, color = colors.onSecondaryContainer)
            }
        }
    }
}

@Composable
private fun ModelCardName(model: LocalModel) {
    Text(model.annotation.alias.ifBlank { model.name.substringBeforeLast('.', model.name) },
        style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.SemiBold,
        maxLines = 2, overflow = TextOverflow.Ellipsis)
}

internal fun modelLoadStatus(item: LocalModel, cached: Boolean): String = if (cached)
    "缓存记录 · 文件存在性待联网确认" else when (item.loadStatus) {
    "selectable" -> "注册节点可选：${item.nodeModelName}"
    "ambiguous" -> "多目录同名，节点加载路径有歧义"
    "missing" -> "文件已缺失，资料与标注保留"
    "unregistered" -> "目录已取消注册"
    "unconfirmed" -> "目录未能完整读取，文件状态待确认"
    else -> "文件已入库；尚未获得精确节点选项（未猜测加载路径）"
}

@Composable
private fun ModelFiltersDialog(state: ModelCenterState, onDismiss: () -> Unit, onSave: (LocalModelQuery) -> Unit) {
    val dismiss = rememberDialogDismiss(onDismiss)
    var query by remember { mutableStateOf(state.query) }
    OverlayDialog(show = dismiss.show, title = "筛选本地模型", onDismissRequest = dismiss::dismiss,
        onDismissFinished = dismiss::onDismissFinished) {
        Column(dismiss.modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("排序")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    mapOf("name" to "名称", "size" to "大小", "mtime" to "修改时间", "recent" to "最近使用").forEach { (key, label) ->
                        ModelChip(label, query.sort == key) { query = query.copy(sort = key) }
                    }
                }
                Text("最近使用尚无记录时显示未知，不以修改时间代替。", style = MiuixTheme.textStyles.footnote1)
                Text("模型用途")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (linkedMapOf("" to "全部用途", "unclassified" to "待分类") + modelUseCases).forEach { (key, label) ->
                        ModelChip(label, query.useCase == key) { query = query.copy(useCase = key) }
                    }
                }
                ModelChip("仅收藏", query.favorite) { query = query.copy(favorite = !query.favorite) }
                TextField(value = query.baseModel, onValueChange = { query = query.copy(baseModel = it) },
                    label = "底模系列（精确匹配，留空为全部）", singleLine = true)
                Text("资料状态")
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    mapOf("" to "全部来源", "matched" to "已哈希匹配", "unknown" to "来源未知",
                        "complete" to "已有底模与触发词", "missing" to "缺失文件").forEach { (key, label) ->
                        ModelChip(label, query.source == key) { query = query.copy(source = key) }
                    }
                }
                Text("注册目录")
                ModelChip("全部目录", query.rootId.isBlank()) { query = query.copy(rootId = "") }
                state.capabilities?.roots.orEmpty().forEach { root ->
                    ModelChip("${modelCategoryLabel(root.category)} · ${root.path}", query.rootId == root.id) {
                        query = query.copy(rootId = root.id)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(text = "重置", onClick = { query = LocalModelQuery() })
                TextButton(text = "取消", onClick = dismiss::dismiss)
                TextButton(text = "应用", onClick = { onSave(query); dismiss.dismiss() })
            }
        }
    }
}

@Composable
internal fun AnnotationDialog(item: LocalModel, onDismiss: () -> Unit,
                             onSave: suspend (ModelAnnotation, Boolean) -> Result<Unit>) {
    val dismiss = rememberDialogDismiss(onDismiss)
    var ann by remember { mutableStateOf(item.annotation) }
    var aliases by remember { mutableStateOf(ann.aliases.joinToString("\n")) }
    var tags by remember { mutableStateOf(ann.tags.joinToString("\n")) }
    var words by remember { mutableStateOf(ann.trainedWords.joinToString("\n")) }
    var weight by remember { mutableStateOf(ann.preferredStrength?.toString().orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    OverlayDialog(show = dismiss.show, title = "编辑模型标注", onDismissRequest = { if (!busy) dismiss.dismiss() },
        onDismissFinished = dismiss::onDismissFinished) {
        Column(dismiss.modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(ann.alias, { ann = ann.copy(alias = it) }, label = "中文名称 / 主别名")
                TextField(aliases, { aliases = it }, label = "其他别名（每行一项）")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    modelUseCases.forEach { (key, label) -> ModelChip(label, key in ann.useCases) {
                        ann = ann.copy(useCases = if (key in ann.useCases) ann.useCases - key else ann.useCases + key)
                    } }
                }
                TextField(ann.character, { ann = ann.copy(character = it) }, label = "角色 / 作品")
                TextField(ann.outfit, { ann = ann.copy(outfit = it) }, label = "服装 / 形态")
                TextField(ann.baseModel, { ann = ann.copy(baseModel = it) }, label = "底模系列（未知可留空）")
                TextField(words, { words = it }, label = "用户确认触发词（每行一项，保留原拼写）")
                TextField(tags, { tags = it }, label = "自定义标签（每行一项）")
                TextField(weight, { weight = it }, label = "常用权重（未知留空，-10 到 10）")
                TextField(ann.notes, { ann = ann.copy(notes = it) }, label = "备注")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ModelChip("收藏", ann.favorite) { ann = ann.copy(favorite = !ann.favorite) }
                    ModelChip("锁定人工分类", "useCases" in ann.fieldLocks) {
                        ann = ann.copy(fieldLocks = if ("useCases" in ann.fieldLocks) emptyList()
                        else listOf("alias", "aliases", "useCases", "character", "outfit", "trainedWords", "baseModel"))
                    }
                }
                Text("人工资料和来源资料始终分开；补全不覆盖人工内容。保存不会自动启用角色或加载 LoRA。",
                    style = MiuixTheme.textStyles.footnote1)
            }
            error?.let { Text(it, color = MiuixTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(text = "取消", enabled = !busy, onClick = dismiss::dismiss)
                TextButton(text = if (busy) "保存中…" else "保存到电脑", enabled = !busy, onClick = {
                    val strength = if (weight.isBlank()) null else weight.toDoubleOrNull()
                    if (weight.isNotBlank() && (strength == null || !strength.isFinite() || strength !in -10.0..10.0)) {
                        error = "常用权重应在 -10 到 10 之间，未知请留空"
                    } else {
                        busy = true
                        scope.launch {
                            fun lines(raw: String) = raw.lines().filter { it.isNotBlank() }.distinct()
                            try {
                                onSave(ann.copy(aliases = lines(aliases), tags = lines(tags), trainedWords = lines(words),
                                    preferredStrength = strength, classificationSource = "user",
                                    classificationStatus = if (ann.useCases.isEmpty()) "unclassified" else "confirmed"), false)
                                    .onSuccess { dismiss.dismiss() }.onFailure { error = it.message ?: "保存失败，草稿保留" }
                            } finally { busy = false }
                        }
                    }
                })
            }
        }
    }
}
