package com.comfyui.mobile.ui.screens.modelbrowser

import android.content.ClipData
import com.comfyui.mobile.ui.designsystem.PocketTokens
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.imageLoader
import com.comfyui.mobile.model.EditableNode
import com.comfyui.mobile.modelcenter.modelAnnotationName
import com.comfyui.mobile.modelcenter.modelCategoryLabel
import com.comfyui.mobile.network.RhResourceItem
import com.comfyui.mobile.ui.feedback.AppToast
import com.comfyui.mobile.ui.screens.modelcenter.ExpandableModelText
import com.comfyui.mobile.ui.screens.modelcenter.ModelAction
import com.comfyui.mobile.ui.screens.modelcenter.ModelStatusBadge
import com.comfyui.mobile.ui.screens.modelcenter.modelBytes
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * One scroll owner for model information. Native filename/option remain untouched;
 * triggers are inspected or applied here, never on the browsing cards.
 */
@Composable
internal fun ModelSelectionConfirmation(
    item: RhResourceItem,
    localMode: Boolean,
    resourceType: String,
    serverBaseUrl: String,
    phoneImages: ImageLoader?,
    phoneImageRequest: (String) -> Any,
    detailLoading: Boolean,
    detailError: String?,
    onRetryDetail: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    textNodes: List<EditableNode.TextInput> = emptyList(),
    onWriteTriggerNode: (EditableNode.TextInput, String) -> Unit = { _, _ -> },
    extraTriggerAction: (@Composable (RhResourceItem, String, () -> Unit) -> Unit)? = null
) {
    val scheme = MiuixTheme.colorScheme
    val context = LocalContext.current
    val triggers = item.triggerWords?.trim().orEmpty()
    val provider = item.localModel?.provider
    val intro = remember(item.desc, provider?.modelDescription) {
        cleanHtmlToPlainText(provider?.modelDescription?.takeIf(String::isNotBlank) ?: item.desc)
    }
    val versionDescription = remember(provider?.description, provider?.modelDescription, intro) {
        if (provider?.modelDescription.isNullOrBlank()) ""
        else cleanHtmlToPlainText(provider.description).takeUnless { it == intro }.orEmpty()
    }

    OverlayDialog(show = true, title = "确认选择模型", onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 540.dp).padding(top = 2.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                .testTag("model-picker-detail-scroll"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ModelSelectionHeader(item, localMode, resourceType, serverBaseUrl, phoneImages, phoneImageRequest)
                if (localMode && detailLoading) Text("正在更新模型资料…",
                    style = MiuixTheme.textStyles.footnote1, color = scheme.onSecondaryContainer,
                    modifier = Modifier.testTag("model-picker-detail-loading"))
                if (localMode && detailError != null) {
                    ConfirmationSection("资料加载失败") {
                        Text(detailError, style = MiuixTheme.textStyles.footnote1, color = scheme.error)
                        ModelAction("重试资料", onClick = onRetryDetail)
                    }
                }

                if (triggers.isNotBlank()) {
                    ConfirmationSection("触发词", action = {
                        ModelAction("复制", modifier = Modifier.testTag("model-picker-copy-triggers")) {
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText("模型触发词", triggers))
                            AppToast.show(context, "已复制触发词")
                        }
                    }) {
                        Column(Modifier.testTag("model-picker-triggers")) {
                            ExpandableModelText(triggers, "触发词")
                        }
                        extraTriggerAction?.invoke(item, triggers, onDismiss)
                        if (textNodes.isNotEmpty()) {
                            ConfirmationDisclosure("写入文本节点", "model-picker-trigger-nodes", surface = false) {
                                textNodes.forEach { node ->
                                    ModelAction("#${node.nodeId}  ${node.title}",
                                        modifier = Modifier.fillMaxWidth().testTag("model-picker-write-${node.nodeId}")) {
                                        onWriteTriggerNode(node, triggers)
                                    }
                                }
                            }
                        }
                    }
                } else if (localMode) {
                    ConfirmationSection("触发词") {
                        Text("暂无记录", style = MiuixTheme.textStyles.body2, color = scheme.onSecondaryContainer,
                            modifier = Modifier.testTag("model-picker-empty-triggers"))
                    }
                }

                if (intro.isNotBlank()) ConfirmationSection("模型简介") {
                    ExpandableModelText(intro, "简介")
                }
                if (versionDescription.isNotBlank()) {
                    ConfirmationDisclosure("版本说明", "model-picker-version") {
                        SelectionContainer { Text(versionDescription, style = MiuixTheme.textStyles.body2) }
                    }
                }
                if (localMode) item.localModel?.let { model ->
                    ConfirmationDisclosure("模型标注", "model-picker-annotation-toggle") {
                        SelectionContainer { ModelPickerAnnotation(model.annotation, showTitle = false) }
                    }
                }
                ConfirmationDisclosure("文件信息", "model-picker-file-toggle") {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(item.effectiveModelName, style = MiuixTheme.textStyles.footnote1,
                                modifier = Modifier.testTag("model-picker-native-option"))
                            item.localModel?.let { model ->
                                if (model.format.isNotBlank() || model.size != null)
                                    Text(listOf(model.format, modelBytes(model.size)).filter(String::isNotBlank)
                                        .joinToString(" · "), style = MiuixTheme.textStyles.footnote1,
                                        color = scheme.onSecondaryContainer)
                                provider?.let {
                                    val source = listOf(it.name, it.version, it.author)
                                        .filter(String::isNotBlank).joinToString(" · ")
                                    if (source.isNotBlank()) Text(source, style = MiuixTheme.textStyles.footnote1,
                                        color = scheme.onSecondaryContainer)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.onSurface.copy(alpha = .06f)))
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton("取消", onClick = onDismiss,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("model-picker-cancel"))
                TextButton("确定选择", onClick = onConfirm, colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("model-picker-confirm"))
            }
        }
    }
}

@Composable
private fun ModelSelectionHeader(
    item: RhResourceItem, localMode: Boolean, resourceType: String, serverBaseUrl: String,
    phoneImages: ImageLoader?, phoneImageRequest: (String) -> Any
) {
    val context = LocalContext.current
    val scheme = MiuixTheme.colorScheme
    val rawImage = item.thumbnailUrl ?: item.posterUrl
    val image = rawImage?.takeIf(String::isNotBlank)?.let {
        if (it.startsWith("http://", true) || it.startsWith("https://", true)) it
        else serverBaseUrl.trimEnd('/').takeIf(String::isNotBlank)?.let { base -> "$base/${it.trimStart('/')}" }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (image != null) {
            val direct = localMode && phoneImages != null && image.startsWith("https://", true) &&
                !image.startsWith(serverBaseUrl)
            AsyncImage(model = if (direct) phoneImageRequest(image) else image,
                imageLoader = if (direct) phoneImages else context.imageLoader,
                contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.width(64.dp).height(80.dp).clip(PocketTokens.Shape.Medium)
                    .background(scheme.surfaceContainer).testTag("model-picker-confirm-cover"))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(item.effectiveResourceName.ifBlank { item.effectiveModelName },
                style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("model-picker-confirm-name"))
            if (localMode && item.modelAnnotationName.isNotBlank())
                Text(item.modelAnnotationName, style = MiuixTheme.textStyles.body2, color = scheme.primary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("model-picker-confirm-alias"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val type = item.localModel?.category?.takeIf(String::isNotBlank)
                    ?: item.resourceType?.takeIf(String::isNotBlank) ?: resourceType
                if (type.isNotBlank()) ModelStatusBadge(if (type == "GGUF") "GGUF" else modelCategoryLabel(type))
                item.baseModel?.takeIf(String::isNotBlank)?.let { ModelStatusBadge(it, positive = true) }
            }
        }
    }
}

@Composable
private fun ConfirmationSection(
    title: String, action: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit
) {
    Card(cornerRadius = PocketTokens.Radius.Large, modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                action?.invoke()
            }
            content()
        }
    }
}

@Composable
private fun ConfirmationDisclosure(
    title: String, tag: String, surface: Boolean = true, content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val scheme = MiuixTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(PocketTokens.Shape.Large)
        .then(if (surface) Modifier.background(scheme.surfaceContainer) else Modifier)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .clickable(role = Role.Button) { expanded = !expanded }
            .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
            .testTag(tag).padding(horizontal = if (surface) 12.dp else 0.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            Text(if (expanded) "收起" else "展开", style = MiuixTheme.textStyles.footnote1, color = scheme.primary)
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(start = if (surface) 12.dp else 0.dp, end = if (surface) 12.dp else 0.dp,
                bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
        }
    }
}
