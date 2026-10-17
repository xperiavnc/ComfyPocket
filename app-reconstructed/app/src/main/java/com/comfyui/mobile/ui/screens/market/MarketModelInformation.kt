package com.comfyui.mobile.ui.screens.market

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import coil.ImageLoader
import coil.compose.AsyncImage
import com.comfyui.mobile.model.CivitaiModelItem
import com.comfyui.mobile.model.CivitaiVersionItem
import androidx.compose.material3.Text
import com.comfyui.mobile.ui.designsystem.PocketTextButton as TextButton
import androidx.compose.material3.MaterialTheme

@Composable
fun MarketModelInformation(
    model: CivitaiModelItem,
    version: CivitaiVersionItem?,
    imageLoader: ImageLoader,
    imageRequest: (String) -> Any
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    fun copy(label: String, value: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(context, "已复制$label", Toast.LENGTH_SHORT).show()
    }
    val intro = remember(model.description) { descriptionText(model.description) }
    val versionIntro = remember(version?.description) { descriptionText(version?.description.orEmpty()) }
    Text("模型简介", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
    SelectionContainer {
        Text(intro.ifBlank { "作者未填写模型简介" }, style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurface, modifier = Modifier.padding(top = 6.dp))
    }
    if (versionIntro.isNotBlank() && versionIntro != intro) {
        Spacer(Modifier.height(10.dp))
        Text("版本说明", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
        SelectionContainer {
            Text(versionIntro, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
        }
    }
    Spacer(Modifier.height(12.dp))
    val words = version?.trainedWords.orEmpty().joinToString(", ")
    CopyableText("触发词", words.ifBlank { "作者未声明触发词" },
        onCopy = if (words.isBlank()) null else { { copy("触发词", words) } })

    val examples = version?.images.orEmpty()
    var exampleIndex by remember(model.id, version?.id) { mutableIntStateOf(0) }
    val example = examples.getOrNull(exampleIndex) ?: examples.firstOrNull()
    Spacer(Modifier.height(12.dp))
    Text("示例图与提示词", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
    if (example == null) {
        Text("本版本暂无公开示例图与提示词。", style = MaterialTheme.typography.bodySmall,
            color = scheme.onSecondaryContainer, modifier = Modifier.padding(top = 6.dp))
    } else {
        if (examples.size > 1) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                examples.forEachIndexed { index, _ ->
                    TextButton(text = (if (index == exampleIndex) "✓ " else "") + "示例 ${index + 1}",
                        onClick = { exampleIndex = index })
                }
            }
        }
        Text(if (example.communityExample) "社区示例（不一定由模型作者生成）" else "作者预览",
            style = MaterialTheme.typography.bodySmall, color = scheme.onSecondaryContainer)
        AsyncImage(model = imageRequest(example.url), imageLoader = imageLoader,
            contentDescription = "当前提示词对应的示例图", contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().height(180.dp).padding(vertical = 6.dp))
        CopyableText("正向提示词", example.prompt.ifBlank { "该图片未公开正向提示词" },
            onCopy = if (example.prompt.isBlank()) null else { { copy("正向提示词", example.prompt) } })
        Spacer(Modifier.height(8.dp))
        CopyableText("反向提示词", example.negativePrompt.ifBlank { "该图片未公开反向提示词" },
            onCopy = if (example.negativePrompt.isBlank()) null else { { copy("反向提示词", example.negativePrompt) } })
        val parameters = listOf(
            "Sampler" to example.sampler, "Steps" to example.steps,
            "CFG" to example.cfgScale, "Seed" to example.seed
        ).filter { it.second.isNotBlank() }.joinToString(" · ") { "${it.first}: ${it.second}" }
        if (parameters.isNotBlank()) {
            SelectionContainer {
                Text(parameters, style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSecondaryContainer, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun CopyableText(title: String, value: String, onCopy: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f).padding(top = 8.dp))
            if (onCopy != null) TextButton(text = "复制", onClick = onCopy)
        }
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodyMedium) }
    }
}

/** Render HTML as inert, selectable text: no WebView, scripts, remote embeds or link execution. */
private fun descriptionText(html: String): String {
    if (!Regex("</?[a-zA-Z][^>]*>").containsMatchIn(html)) return html
    val stripped = html.replace(Regex("<(script|style)\\b[^>]*>.*?</\\1>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
    return HtmlCompat.fromHtml(stripped, HtmlCompat.FROM_HTML_MODE_COMPACT)
        .toString().replace("\uFFFC", "").trim()
}
