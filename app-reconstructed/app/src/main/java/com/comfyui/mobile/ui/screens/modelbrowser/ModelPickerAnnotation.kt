package com.comfyui.mobile.ui.screens.modelbrowser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.comfyui.mobile.modelcenter.ModelAnnotation
import com.comfyui.mobile.modelcenter.modelUseCases
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ModelPickerAnnotation(annotation: ModelAnnotation, showTitle: Boolean = true) {
    val fields = listOf(
        "中文名称" to annotation.alias,
        "其他别名" to annotation.aliases.joinToString("、"),
        "标签" to annotation.tags.joinToString(" · "),
        "用途" to annotation.useCases.joinToString(" · ") { modelUseCases[it] ?: it },
        "角色" to annotation.character,
        "服装" to annotation.outfit,
        "标注底模" to annotation.baseModel,
        "推荐强度" to annotation.preferredStrength?.toString().orEmpty(),
        "收藏" to if (annotation.favorite) "已收藏" else "",
        "备注" to annotation.notes
    ).filter { it.second.isNotBlank() }
    Column(Modifier.testTag("model-picker-annotation"), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (showTitle) Text("模型标注", style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.SemiBold)
        if (fields.isEmpty()) Text("暂无标注", style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSecondaryContainer)
        fields.forEach { (name, value) ->
            Text("$name：$value", style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSecondaryContainer)
        }
    }
}
