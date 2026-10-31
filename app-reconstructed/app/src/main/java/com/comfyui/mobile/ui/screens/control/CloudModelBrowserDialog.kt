package com.comfyui.mobile.ui.screens.control

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.imageLoader
import com.comfyui.mobile.model.EditableNode
import com.comfyui.mobile.network.RhResourceItem
import com.comfyui.mobile.ui.CloudModelBrowserHost
import com.comfyui.mobile.ui.screens.modelbrowser.ModelBrowserFilterChipBar
import com.comfyui.mobile.ui.screens.modelbrowser.ModelBrowserManageFilterDialog
import com.comfyui.mobile.ui.screens.modelbrowser.ModelBrowserTypeTabs
import com.comfyui.mobile.ui.screens.modelbrowser.ModelBrowserPager
import com.comfyui.mobile.ui.screens.modelbrowser.ModelBrowserQuery
import com.comfyui.mobile.ui.screens.modelbrowser.ModelSelectionConfirmation
import com.comfyui.mobile.modelcenter.modelAnnotationName
import com.comfyui.mobile.ui.screens.modelbrowser.getBaseModelAliases
import com.comfyui.mobile.ui.screens.modelbrowser.loadPinnedFilterTags
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/** Chinese text for a resource/list failure (never a raw HTTP body). */
private fun resourceErrorText(e: Throwable): String {
    val rh = e as? com.comfyui.mobile.network.RhApiException
    return when {
        rh?.code == 1003 -> "请求过于频繁，请稍后再试"
        rh != null && com.comfyui.mobile.network.RhError.isKeyError(rh.code) -> "API Key 无效或无权限，请检查 RunningHub 服务器配置"
        rh?.code == 1601 || rh?.code == 1602 -> "请求缺少 API Key，请检查 RunningHub 服务器配置"
        rh?.code != null -> com.comfyui.mobile.network.RhError.message(rh.code, null)
        rh?.httpCode != null -> "服务器返回错误（HTTP ${rh.httpCode}），请稍后再试"
        e is java.io.IOException -> "网络连接失败，请检查网络后重试"
        e is IllegalStateException -> e.message ?: "加载失败，请稍后再试"
        else -> "加载失败，请稍后再试"
    }
}

@Composable
fun CloudModelBrowserDialog(
    show: Boolean,
    initialResourceType: String = "CHECKPOINT",
    viewModel: CloudModelBrowserHost,
    onDismiss: () -> Unit,
    onSelectModel: (RhResourceItem) -> Unit,
    onSelectModelWithTriggerNode: ((RhResourceItem, EditableNode.TextInput, String) -> Unit)? = null,
    textNodes: List<EditableNode.TextInput> = emptyList(),
    extraTriggerAction: (@Composable (lora: RhResourceItem, triggers: String, onDismiss: () -> Unit) -> Unit)? = null,
    /** Control page: LoRA with trigger words asks whether to append them. */
    askLoRATriggerWords: Boolean = true
    ) {
    if (!show) return

    val context = LocalContext.current
    val scheme = MiuixTheme.colorScheme
    val scope = rememberCoroutineScope()
    val servers by viewModel.servers.collectAsState()
    val currentServerId by viewModel.currentServerId.collectAsState()
    val currentServer = servers.firstOrNull { it.effectiveId == currentServerId || it.id == currentServerId }
    // Only the current RunningHub profile (no borrowing of another profile's API key).
    val rhServer = if (currentServer?.isRunningHub == true) currentServer else null
    val phoneImages = if (currentServer?.isRunningHub != true) viewModel.localModelImageLoader() else null

    var selectedType by remember(initialResourceType) { mutableStateOf(initialResourceType) }
    var selectedFilterTag by remember { mutableStateOf("全部") }
    val pinnedFilterTags = remember { mutableStateListOf<String>().apply { addAll(loadPinnedFilterTags(context)) } }
    var showManageFilterDialog by remember { mutableStateOf(false) }
    var searchKeyword by remember { mutableStateOf("") }
    var pendingSelectedItem by remember(currentServerId, currentServer) { mutableStateOf<RhResourceItem?>(null) }
    var detailLoading by remember(currentServerId) { mutableStateOf(false) }
    var detailError by remember(currentServerId) { mutableStateOf<String?>(null) }
    var detailAttempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(pendingSelectedItem?.effectiveId, currentServerId, detailAttempt) {
        val item = pendingSelectedItem
        detailError = null
        detailLoading = false
        if (item != null && currentServer?.isRunningHub != true) {
            detailLoading = true
            try {
                viewModel.localModelDetail(item).onSuccess { pendingSelectedItem = it }
                    .onFailure { detailError = resourceErrorText(it) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
            } catch (error: Exception) { detailError = resourceErrorText(error) }
            detailLoading = false
        }
    }
    val gridState = rememberLazyGridState()
    // Each server/profile gets its own request owner; old replies never populate a new server's list.
    val pager = remember(viewModel, currentServerId, currentServer) {
        ModelBrowserPager(scope) { query, page, refresh ->
            if (currentServer?.isRunningHub == true) {
                if (rhServer?.effectiveRhApiKey.isNullOrBlank()) {
                    Result.failure(IllegalStateException("请先配置 RunningHub API Key"))
                } else {
                    viewModel.searchCloudModels(
                        query.resourceType, query.keyword, page, ModelBrowserPager.PAGE_SIZE,
                        query.baseModel?.let(::getBaseModelAliases)?.takeIf { it.isNotEmpty() }
                    )
                }
            } else {
                viewModel.searchLocalModels(
                    query.resourceType, query.keyword, page, ModelBrowserPager.PAGE_SIZE,
                    query.baseModel, refresh
                )
            }
        }
    }
    DisposableEffect(pager) { onDispose { pager.close() } }
    val pageState = pager.state.collectAsState()
    val loadState by pageState
    val resources = loadState.records
    val isLoading = loadState.loading
    val hasNext = loadState.hasNext
    val errorMessage = loadState.error?.let(::resourceErrorText)

    fun search() {
        pager.search(ModelBrowserQuery(selectedType, searchKeyword.trim(),
            selectedFilterTag.takeUnless { it == "全部" }), refresh = true)
        // Request the next layout, rather than waiting for an absent loading grid.
        gridState.requestScrollToItem(0)
    }

    LaunchedEffect(pager, selectedType, selectedFilterTag) {
        search()
    }

    val shouldLoadMore by remember(pager, gridState) {
        derivedStateOf {
            val layoutInfo = gridState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisibleItemIndex = (layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) + 1
            // Wait for the appended rows to reach layout before deciding whether
            // the user is still near the end (otherwise old layout can fetch all pages).
            totalItems > 0 && totalItems >= pageState.value.records.size &&
                lastVisibleItemIndex >= totalItems - 4
        }
    }

    // Re-check after every completed page, including short pages that still fill
    // less than the viewport. Watching the edge boolean alone can stop at page 1.
    LaunchedEffect(shouldLoadMore, isLoading, hasNext, loadState.page, loadState.error) {
        if (shouldLoadMore && !isLoading && hasNext && loadState.error == null) {
            pager.loadNext()
        }
    }

    OverlayDialog(
        show = show && pendingSelectedItem == null && !showManageFilterDialog,
        title = if (currentServer?.isRunningHub == true) "云端模型库" else "本地模型库",
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
        ) {
            ModelBrowserTypeTabs(
                selectedType = selectedType,
                onTypeSelected = { selectedType = it }
            )

            Spacer(modifier = Modifier.height(10.dp))

            // 搜索栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = searchKeyword,
                    onValueChange = { searchKeyword = it },
                    label = "搜索模型或关键词...",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                    modifier = Modifier.weight(1f).testTag("model-picker-search")
                )
                Spacer(modifier = Modifier.width(8.dp))
                Card(
                    modifier = Modifier.size(46.dp),
                    cornerRadius = 12.dp,
                    colors = CardDefaults.defaultColors(color = scheme.primary),
                    onClick = { search() }
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(MiuixIcons.Search, contentDescription = "搜索", tint = scheme.onPrimary)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            ModelBrowserFilterChipBar(
                selectedFilterTag = selectedFilterTag,
                pinnedFilterTags = pinnedFilterTags,
                onFilterTagChange = { selectedFilterTag = it },
                onManageClick = { showManageFilterDialog = true }
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when {
                        isLoading && resources.isEmpty() -> "正在加载模型..."
                        loadState.query == null -> ""
                        else -> "已加载 ${resources.size} / ${loadState.total} 个" +
                            if (errorMessage != null) " · 加载失败" else ""
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (errorMessage != null) scheme.error else scheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f).testTag("model-picker-count")
                )
                Box(
                    modifier = Modifier.clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = !isLoading) { search() }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .testTag("model-picker-refresh")
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(MiuixIcons.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("刷新", style = MiuixTheme.textStyles.footnote1)
                    }
                }
            }

            // 列表内容
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if ((isLoading || loadState.query == null) && resources.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (currentServer?.isRunningHub == true) "正在检索云端模型库..." else "正在读取本地模型库...",
                            style = MiuixTheme.textStyles.body2,
                            color = scheme.onSecondaryContainer
                        )
                    }
                } else if (resources.isEmpty()) {
                    Column(modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = errorMessage ?: "未找到相关模型，请尝试更换分类或关键词",
                            style = MiuixTheme.textStyles.body2,
                            color = if (errorMessage != null) scheme.error else scheme.onSecondaryContainer
                        )
                        if (errorMessage != null) TextButton("重试", onClick = { pager.retry() })
                    }
                } else {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize().testTag("model-picker-grid")
                    ) {
                        itemsIndexed(resources, key = { index, item -> "${item.effectiveId}_$index" }) { _, item ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                cornerRadius = 12.dp,
                                colors = CardDefaults.defaultColors(color = scheme.surfaceContainer),
                                pressFeedbackType = PressFeedbackType.Sink,
                                showIndication = true,
                                onClick = {
                                    pendingSelectedItem = item
                                }
                            ) {
                                Column(modifier = Modifier.padding(6.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(110.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(scheme.surfaceContainerHigh)
                                    ) {
                                        val rawImgUrl = item.thumbnailUrl ?: item.posterUrl
                                        val imgUrl = when {
                                            rawImgUrl.isNullOrBlank() -> null
                                            rawImgUrl.startsWith("http://", ignoreCase = true) || rawImgUrl.startsWith("https://", ignoreCase = true) -> rawImgUrl
                                            else -> {
                                                val base = currentServer?.baseUrl?.trimEnd('/') ?: ""
                                                if (base.isNotBlank()) "$base/" + rawImgUrl.trimStart('/') else rawImgUrl
                                            }
                                        }
                                        if (!imgUrl.isNullOrBlank()) {
                                            val directImage = phoneImages != null && imgUrl.startsWith("https://") &&
                                                !imgUrl.startsWith(currentServer?.baseUrl.orEmpty())
                                            AsyncImage(
                                                model = if (directImage) viewModel.localModelImageRequest(imgUrl) else imgUrl,
                                                imageLoader = if (directImage) phoneImages else context.imageLoader,
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                        val bm = item.baseModel?.trim()
                                        if (!bm.isNullOrBlank()) {
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .padding(4.dp)
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(scheme.surface.copy(alpha = 0.85f))
                                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = bm,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = scheme.onSurface
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = item.effectiveResourceName.ifBlank { "未知模型" },
                                        style = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.SemiBold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (currentServer?.isRunningHub == true) item.effectiveModelName
                                            else item.modelAnnotationName,
                                        style = MiuixTheme.textStyles.body2,
                                        fontSize = 12.sp,
                                        color = scheme.onSecondaryContainer,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.testTag("model-picker-alias-${item.effectiveId}")
                                    )
                                }
                            }
                        }
                        if (isLoading && resources.isNotEmpty()) {
                            item(span = { GridItemSpan(2) }) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "正在加载更多模型...",
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = scheme.onSecondaryContainer
                                    )
                                }
                            }
                        } else if (errorMessage != null) {
                            item(span = { GridItemSpan(2) }) {
                                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(errorMessage, style = MiuixTheme.textStyles.footnote1, color = scheme.error)
                                    TextButton("重试", onClick = { pager.retry() })
                                }
                            }
                        } else if (hasNext) {
                            item(span = { GridItemSpan(2) }) {
                                TextButton("加载更多", onClick = { pager.loadNext() },
                                    modifier = Modifier.fillMaxWidth().testTag("model-picker-more"))
                            }
                        } else if (resources.isNotEmpty()) {
                            item(span = { GridItemSpan(2) }) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "已加载全部模型 (${resources.size})",
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = scheme.onSecondaryContainer.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = "关闭",
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    // 确认页拥有唯一内容滚动区，所有选择回调仍使用最新的原生模型记录。
    pendingSelectedItem?.let { item ->
        ModelSelectionConfirmation(
            item = item,
            localMode = currentServer?.isRunningHub != true,
            resourceType = selectedType,
            serverBaseUrl = currentServer?.baseUrl.orEmpty(),
            phoneImages = phoneImages,
            phoneImageRequest = viewModel::localModelImageRequest,
            detailLoading = detailLoading,
            detailError = detailError,
            onRetryDetail = { detailAttempt++ },
            onDismiss = { pendingSelectedItem = null },
            onConfirm = {
                val target = pendingSelectedItem
                pendingSelectedItem = null
                if (target != null) onSelectModel(target)
            },
            textNodes = textNodes,
            onWriteTriggerNode = { node, triggers ->
                val target = pendingSelectedItem
                pendingSelectedItem = null
                if (target != null) {
                    if (onSelectModelWithTriggerNode != null) onSelectModelWithTriggerNode(target, node, triggers)
                    else onSelectModel(target)
                }
            },
            extraTriggerAction = extraTriggerAction
        )
    }

    ModelBrowserManageFilterDialog(
        show = showManageFilterDialog,
        title = "管理筛选生态",
        selectedFilterTag = selectedFilterTag,
        pinnedFilterTags = pinnedFilterTags,
        onFilterTagSelected = { selectedFilterTag = it },
        onDismiss = { showManageFilterDialog = false }
    )
}
