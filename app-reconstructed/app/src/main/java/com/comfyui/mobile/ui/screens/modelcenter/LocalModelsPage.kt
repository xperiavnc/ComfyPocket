package com.comfyui.mobile.ui.screens.modelcenter

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import coil.compose.AsyncImage
import com.comfyui.mobile.modelcenter.*
import com.comfyui.mobile.ui.components.rememberDialogDismiss
import com.comfyui.mobile.ui.navigation.pocketPageEnter
import com.comfyui.mobile.ui.navigation.pocketPageExit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
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
            RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp))
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
private fun LocalModelList(vm: ModelCenterViewModel, state: ModelCenterState) {
    if (!state.restored) {
        Text("正在恢复此电脑的模型缓存…", modifier = Modifier.padding(16.dp))
        return
    }
    var showFilters by remember { mutableStateOf(false) }
    var keyword by remember(state.identity, state.query.q) { mutableStateOf(state.query.q) }
    val list = rememberLazyListState(state.scrollIndex, state.scrollOffset)
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
    LazyColumn(state = list, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(if (state.compact) 5.dp else 10.dp),
        modifier = Modifier.fillMaxSize().testTag("local-model-list")) {
      item(key = "controls") {
       Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(value = keyword, onValueChange = { keyword = it }, label = "名称、中文别名、角色、触发词",
                singleLine = true, useLabelAsPlaceholder = true, modifier = Modifier.weight(1f).testTag("local-model-search"))
            TextButton(text = "筛选", onClick = { showFilters = true })
            TextButton(text = if (state.compact) "卡片" else "紧凑", onClick = vm::compact)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("", "LORA", "CHECKPOINT", "UNET", "VAE", "TEXT_ENCODER").forEach { category ->
                ModelChip(modelCategoryLabel(category), state.query.category == category) {
                    vm.query(state.query.copy(category = category))
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (linkedMapOf("" to "全部用途", "unclassified" to "待分类") + modelUseCases).forEach { (key, label) ->
                ModelChip(label, state.query.useCase == key) { vm.query(state.query.copy(useCase = key)) }
            }
        }
        val stats = state.page.stats
        Text("${state.serverLabel.ifBlank { "未选择电脑" }} · " +
            if (state.fromCache) "离线 / 缓存 · 上次读取 ${modelTime(state.cacheAt)}"
            else "电脑已连接 · 同步 ${modelTime(state.page.lastSync)}",
            style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSecondaryContainer)
        Text(if (state.fromCache) "仅检索已缓存条目；文件是否仍存在需联网同步确认。"
            else "${if (state.page.scanComplete) "索引" else "部分索引"} ${stats.locations} 个位置 · ${modelBytes(stats.bytes)} · " +
                "已哈希 ${stats.hashed} / ${stats.locations} · 已知唯一内容 ${stats.uniqueContents}",
            style = MiuixTheme.textStyles.footnote1)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val scan = state.tasks.scan.takeIf { it.active } ?: state.page.scan
            Text(if (scan.active) "${if (scan.status == "hashing") "计算指纹" else "扫描目录"} · 已发现 ${scan.discovered} · 指纹 ${scan.hashed}"
                else "筛选结果 ${state.page.total} · 缺失记录 ${stats.missing}",
                modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.footnote1)
            TextButton(text = if (scan.active) "暂停同步" else "同步", enabled = state.connected,
                onClick = { vm.scan(if (scan.active) "pause" else "start") })
            TextButton(text = "本页补全", enabled = state.connected && !state.fromCache && state.page.items.isNotEmpty(),
                onClick = { vm.enrich(state.page.items.filter { it.present }.map { it.id }.take(100)) })
        }
        state.error?.let { Text(it, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1) }
        state.notice?.let { Text(it, style = MiuixTheme.textStyles.footnote1) }
        if (state.loading && state.page.items.isEmpty()) Text("正在读取电脑模型索引…")
        if (!state.loading && state.page.items.isEmpty()) {
            Text(if (state.connected) "当前筛选没有模型；首次同步会先显示清单，再后台计算指纹。"
                 else "暂无此电脑的模型缓存。连接并更新 Bridge 后同步；在线集市仍可手机直连。")
            TextButton(text = "重新读取", onClick = { vm.refresh() })
        }
       }
      }
            items(state.page.items, key = { it.id }) { model ->
                Card(onClick = { vm.openDetail(model.id) }, cornerRadius = 16.dp,
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(if (state.compact) 12.dp else 16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text((if (model.annotation.favorite) "★ " else "") + model.displayName,
                            style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${modelCategoryLabel(model.category)} · ${model.format} · ${modelBytes(model.size)}",
                            style = MiuixTheme.textStyles.footnote1)
                        Text(model.relativePath, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSecondaryContainer)
                        if (!state.compact) {
                            Text(model.rootPath, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MiuixTheme.textStyles.footnote1)
                            Text("${model.baseModel.ifBlank { "底模未知" }} · " +
                                (model.annotation.useCases.mapNotNull(modelUseCases::get).joinToString(" / ").ifBlank { "待分类" }) +
                                " · " + if (model.provider != null) "SHA 匹配资料" else "来源未知",
                                style = MiuixTheme.textStyles.footnote1)
                        }
                        if (!model.present || model.loadStatus in setOf("ambiguous", "unconfirmed"))
                            Text(modelLoadStatus(model, state.fromCache), style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.error)
                    }
                }
            }
            if (state.page.hasMore) item {
                TextButton(text = if (state.loading) "正在加载…" else "加载更多（已显示 ${state.page.items.size}）",
                    enabled = !state.loading, onClick = { vm.refresh(append = true) }, modifier = Modifier.fillMaxWidth())
            }
    }
    if (showFilters) ModelFiltersDialog(state, onDismiss = { showFilters = false }, onSave = vm::query)
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
private fun LocalModelDetails(vm: ModelCenterViewModel, state: ModelCenterState, item: LocalModel) {
    var editing by remember(item.id) { mutableStateOf(false) }
    var showCover by remember(item.id) { mutableStateOf(false) }
    BackHandler(enabled = !editing && state.detail?.id == item.id) { vm.openDetail(null) }
    val writable = state.connected && !state.fromCache && !state.detailLoading
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(text = "返回列表", onClick = { vm.openDetail(null) })
            Spacer(Modifier.weight(1f))
            TextButton(text = "编辑标注", enabled = writable, onClick = { editing = true })
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(item.displayName, style = MiuixTheme.textStyles.title3, modifier = Modifier.testTag("local-model-detail"))
            state.notice?.let { Text(it, color = MiuixTheme.colorScheme.primary) }
            if (state.detailLoading) Text("正在刷新资料…")
            DetailSection("文件位置与指纹") {
                SelectionContainer { Text("${modelCategoryLabel(item.category)} · ${item.format}\n" +
                    "${item.rootPath}/${item.relativePath}\n${modelBytes(item.size)} · 修改 ${modelTime(item.mtime)}\n" +
                    "SHA-256：${item.sha256 ?: "后台计算中 / 尚未计算"}\n" +
                    modelLoadStatus(item, state.fromCache) + "\n最近使用：${modelTime(item.lastUsed)}",
                    style = MiuixTheme.textStyles.footnote1) }
            }
            DetailSection("用户标注 · ${if (item.annotation.classificationSource == "ai-suggestion") "AI 建议，待确认" else "人工资料"}") {
                val ann = item.annotation
                Text("别名：${ann.alias.ifBlank { "未填写" }}\n其他别名：${ann.aliases.joinToString(" / ").ifBlank { "未填写" }}\n" +
                    "用途：${ann.useCases.mapNotNull(modelUseCases::get).joinToString(" / ").ifBlank { "待分类" }}\n" +
                    "角色 / 作品：${ann.character.ifBlank { "未知" }}\n服装 / 形态：${ann.outfit.ifBlank { "未知" }}\n" +
                    "标签：${ann.tags.joinToString(" / ").ifBlank { "无" }}\n" +
                    "底模覆盖：${ann.baseModel.ifBlank { "未填写" }}\n常用权重：${ann.preferredStrength ?: "未知"}\n" +
                    "备注：${ann.notes.ifBlank { "无" }}\n修订：${item.revision}" +
                    if (ann.favorite) " · 已收藏" else "", style = MiuixTheme.textStyles.footnote1)
                if (item.revision > 0) {
                    val scope = rememberCoroutineScope()
                    var error by remember { mutableStateOf<String?>(null) }
                    TextButton(text = "撤销上次标注", enabled = writable, onClick = {
                        scope.launch { vm.save(item.annotation, undo = true).onFailure { error = it.message } }
                    })
                    error?.let { Text(it, color = MiuixTheme.colorScheme.error) }
                }
            }
            DetailSection("该文件的触发词") {
                Text("来源：" + if (item.annotation.trainedWords.isNotEmpty() || "trainedWords" in item.annotation.fieldLocks)
                    "用户明确标注" else if (item.provider != null) "Civitai 文件 SHA-256 匹配" else "未知",
                    style = MiuixTheme.textStyles.footnote1)
                SelectionContainer { Text(item.triggerWords.joinToString("\n").ifBlank { "尚无已确认触发词，可手工填写；未知不代表无需触发词。" }) }
            }
            DetailSection("提供方资料 · 与人工标注分开保存") {
                val provider = item.provider
                if (provider == null) Text("未匹配来源。私人 / 自训模型也可保存人工资料。")
                else {
                    Text("${provider.name}\n版本：${provider.version.ifBlank { "未知" }}\n作者：${provider.author.ifBlank { "来源未提供" }}\n" +
                        "底模：${provider.baseModel.ifBlank { "未知" }}\n" +
                        "model ${provider.modelId} / version ${provider.versionId} / file ${provider.fileId}\n" +
                        "更新：${modelTime(provider.fetchedAt)}", style = MiuixTheme.textStyles.footnote1)
                    SelectionContainer { Text(android.text.Html.fromHtml(provider.description,
                        android.text.Html.FROM_HTML_MODE_LEGACY).toString().ifBlank { "来源未提供简介" }) }
                    if (provider.images.isNotEmpty()) {
                        TextButton(text = if (showCover) "隐藏封面" else "按需加载来源封面", onClick = { showCover = !showCover })
                        if (showCover) AsyncImage(model = vm.preview(item), contentDescription = "模型来源封面",
                            modifier = Modifier.fillMaxWidth().height(220.dp))
                    }
                    SelectionContainer { Text(provider.source, style = MiuixTheme.textStyles.footnote1) }
                }
                TextButton(text = if (provider == null) "补全资料" else "刷新来源资料", enabled = writable && item.present,
                    onClick = { vm.enrich(listOf(item.id), refresh = provider != null) })
                Text("请求由电脑发出，使用 ComfyUI 中已配置的 Key / 代理。只上传哈希，不上传模型；图片失败不影响文字资料。",
                    style = MiuixTheme.textStyles.footnote1)
            }
            Text("本批提供资料管理；Agent 接线与写角色词库在后续阶段接入。当前操作不会修改工作流或提交生图。",
                style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSecondaryContainer)
        }
    }
    if (editing) AnnotationDialog(item, onDismiss = { editing = false }, onSave = vm::save)
}

@Composable
private fun DetailSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(cornerRadius = 16.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold))
            content()
        }
    }
}

@Composable
private fun AnnotationDialog(item: LocalModel, onDismiss: () -> Unit,
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
