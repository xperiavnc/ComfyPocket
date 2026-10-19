package com.comfyui.mobile.ui.screens.modelcenter

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comfyui.mobile.modelcenter.*
import com.comfyui.mobile.ui.components.rememberDialogDismiss
import com.comfyui.mobile.ui.feedback.AppToast
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The selected item and server are captured by the caller; paths always belong to the computer. */
@Composable
internal fun ModelFileDialog(
    item: LocalModel, roots: List<ModelRoot>, onDismiss: () -> Unit,
    directories: suspend (String, String) -> Result<ModelDirectories>,
    onApply: suspend (ModelFileWrite) -> Result<ModelFileResult>
) {
    val dismiss = rememberDialogDismiss(onDismiss)
    val colors = MiuixTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var action by remember { mutableStateOf<String?>(null) }
    val extension = item.name.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
    var name by remember { mutableStateOf(item.name.removeSuffix(extension)) }
    val candidates = roots.filter { it.category == item.category }.ifEmpty {
        listOf(ModelRoot(item.rootId, item.category, item.rootPath))
    }
    var rootId by remember { mutableStateOf(item.rootId) }
    var directory by remember { mutableStateOf(item.relativePath.substringBeforeLast('/', "")) }
    var listing by remember { mutableStateOf<ModelDirectories?>(null) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var browseEpoch by remember { mutableIntStateOf(0) }
    fun browse(path: String) {
        directory = path
        val selectedRoot = rootId
        val epoch = ++browseEpoch
        loading = true
        scope.launch {
            directories(selectedRoot, path).onSuccess {
                if (epoch == browseEpoch) { listing = it; error = null }
            }.onFailure {
                if (epoch == browseEpoch) { listing = null; error = it.message }
            }
            if (epoch == browseEpoch) loading = false
        }
    }
    LaunchedEffect(action, rootId) {
        if (action == "move") browse(directory)
    }
    val title = when (action) {
        "rename" -> "重命名服务器文件"; "move" -> "移动服务器模型"; "delete" -> "删除服务器模型"
        else -> "文件管理"
    }
    OverlayDialog(show = dismiss.show, title = title,
        onDismissRequest = { if (!busy) dismiss.dismiss() }, onDismissFinished = dismiss::onDismissFinished) {
        Column(dismiss.modifier.fillMaxWidth().testTag("model-file-dialog"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(item.name, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (action == null) {
                    listOf(Triple("rename", "重命名", "修改电脑上的文件名，保留原扩展名"),
                        Triple("move", "移动文件夹", "选择服务器目录，也可新建子文件夹"),
                        Triple("delete", "删除模型", "永久删除电脑上的模型及同名附属文件")).forEach { (key, label, note) ->
                        Row(Modifier.fillMaxWidth().clickable { action = key; error = null }
                            .padding(vertical = 10.dp).testTag("model-file-$key"),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(label, style = MiuixTheme.textStyles.body2,
                                    color = if (key == "delete") colors.error else colors.onSurface)
                                Text(note, style = MiuixTheme.textStyles.footnote2, color = colors.onSecondaryContainer)
                            }
                            Text("›", color = colors.onSecondaryContainer)
                        }
                    }
                } else if (action == "rename") {
                    Text("文件名（扩展名 $extension 保持不变）", style = MiuixTheme.textStyles.footnote1,
                        color = colors.onSecondaryContainer)
                    TextField(name, { name = it }, label = "文件名", singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("model-file-name"))
                    Text("改名后：$name$extension", style = MiuixTheme.textStyles.footnote2,
                        color = colors.onSecondaryContainer)
                } else if (action == "move") {
                    Text("服务器根目录", style = MiuixTheme.textStyles.footnote1)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        candidates.forEachIndexed { index, root ->
                            ModelChip("${index + 1} · ${root.path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')}",
                                root.id == rootId) {
                                if (!busy) { rootId = root.id; directory = ""; listing = null }
                            }
                        }
                    }
                    Text(candidates.firstOrNull { it.id == rootId }?.path.orEmpty(),
                        style = MiuixTheme.textStyles.footnote2, color = colors.onSecondaryContainer)
                    TextField(directory, { directory = it }, label = "子文件夹（留空为根目录）",
                        singleLine = true, modifier = Modifier.fillMaxWidth().testTag("model-file-directory"))
                    Text("填写不存在的子文件夹会在移动时创建；例如 characters/new。",
                        style = MiuixTheme.textStyles.footnote2, color = colors.onSecondaryContainer)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ModelAction("浏览此路径", !loading && !busy) { browse(directory) }
                        ModelAction("上一级", !loading && !busy && directory.isNotBlank()) {
                            browse(directory.trimEnd('/').substringBeforeLast('/', ""))
                        }
                    }
                    if (loading) Text("正在读取服务器文件夹…", style = MiuixTheme.textStyles.footnote2)
                    listing?.let { folders ->
                        Column(Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                            folders.directories.forEach { child ->
                                ModelAction(child, !busy, Modifier.fillMaxWidth()) {
                                    browse(listOf(folders.path, child).filter(String::isNotBlank).joinToString("/"))
                                }
                            }
                        }
                        if (folders.directories.isEmpty()) Text("此路径下没有子文件夹", style = MiuixTheme.textStyles.footnote2)
                        if (folders.truncated) Text("目录较多，仅显示前 500 个；也可直接填写目标路径。",
                            style = MiuixTheme.textStyles.footnote2)
                    }
                } else {
                    Text("确认永久删除这台电脑上的模型文件？此操作不只是从手机列表移除。",
                        style = MiuixTheme.textStyles.body2, color = colors.error)
                    Text("${item.rootPath}/${item.relativePath}", style = MiuixTheme.textStyles.footnote2,
                        color = colors.onSecondaryContainer)
                }
                if (action != null) Text("同名预览图与附属资料会一并处理。重命名或移动后，已有工作流引用的旧文件路径需重新选择。",
                    style = MiuixTheme.textStyles.footnote2, color = colors.onSecondaryContainer)
            }
            error?.let { Text(it, style = MiuixTheme.textStyles.footnote2, color = colors.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ModelAction("取消", !busy, onClick = dismiss::dismiss)
                if (action != null) ModelAction(if (busy) "处理中…" else if (action == "delete") "确认删除" else "确认",
                    !busy && (action != "rename" || name.isNotBlank()), Modifier.testTag("model-file-confirm")) {
                    busy = true
                    error = null
                    val request = ModelFileWrite.forItem(item, action!!, name + extension, rootId,
                        directory.replace('\\', '/').trim('/'))
                    scope.launch {
                        try {
                            onApply(request).onSuccess { result ->
                                AppToast.show(context, result.feedback)
                                dismiss.dismiss()
                            }.onFailure { error = it.message ?: "文件操作失败，请刷新后重试" }
                        } finally { busy = false }
                    }
                }
            }
        }
    }
}
