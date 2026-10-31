package com.comfyui.mobile.ui.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.basic.Card

/**
 * One titled group of preference rows: a [PocketSectionHeader] glued to a Miuix card.
 * Settings-type pages stack these so every secondary page reads as the same grouped list.
 */
@Composable
fun PocketSettingsGroup(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) PocketSectionHeader(text = title)
        Card(modifier = Modifier.fillMaxWidth(), content = content)
    }
}
