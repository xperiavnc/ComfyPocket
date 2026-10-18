package com.comfyui.mobile.ui.screens.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.comfyui.mobile.ui.theme.BottomBarStyle
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Create
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Photos
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun BottomBarStylePicker(selected: BottomBarStyle, onSelect: (BottomBarStyle) -> Unit) {
    val scheme = MiuixTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("底栏风格", style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.SemiBold)
            Text("立即生效并记住选择，两种风格均使用老版页面切换动画",
                style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BottomBarStyle.entries.forEach { style ->
                    val active = style == selected
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                            .border(if (active) 2.dp else 1.dp,
                                if (active) scheme.primary else scheme.outline.copy(alpha = 0.35f),
                                RoundedCornerShape(16.dp))
                            .selectable(active, role = Role.RadioButton, onClick = { if (!active) onSelect(style) })
                            .testTag("bottom-bar-style-${style.id}")
                            .padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        BottomBarStylePreview(style)
                        Text(style.title, style = MiuixTheme.textStyles.body2,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (active) scheme.primary else scheme.onSurface,
                            textAlign = TextAlign.Center)
                        Text(if (active) "已选择" else "点按切换", style = MiuixTheme.textStyles.footnote2,
                            color = scheme.onSurfaceVariantSummary)
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomBarStylePreview(style: BottomBarStyle) {
    val scheme = MiuixTheme.colorScheme
    val classic = style == BottomBarStyle.CLASSIC
    val icons = listOf(MiuixIcons.Home, MiuixIcons.Tune, MiuixIcons.Create, MiuixIcons.GridView, MiuixIcons.Photos)
    Box(
        Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(10.dp))
            .background(scheme.background).clearAndSetSemantics { },
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.fillMaxWidth(0.6f).height(4.dp).background(scheme.outline.copy(alpha = 0.25f), CircleShape))
            Box(Modifier.fillMaxWidth().height(22.dp).background(scheme.surfaceContainerHigh, RoundedCornerShape(5.dp)))
        }
        Row(
            Modifier.padding(horizontal = if (classic) 8.dp else 0.dp, vertical = if (classic) 6.dp else 0.dp)
                .fillMaxWidth().clip(if (classic) CircleShape else RoundedCornerShape(0.dp))
                .background(scheme.surfaceContainer).padding(horizontal = 3.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icons.forEachIndexed { index, icon ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.clip(CircleShape)
                            .background(if (index == 4) scheme.primary.copy(alpha = 0.22f) else androidx.compose.ui.graphics.Color.Transparent)
                            .padding(horizontal = 3.dp, vertical = 3.dp)
                    ) {
                        Icon(icon, null, Modifier.size(13.dp), tint = if (index == 4) scheme.primary else scheme.onSurface)
                    }
                    if (!classic) Box(Modifier.width(9.dp).height(2.dp).background(scheme.onSurface.copy(alpha = 0.5f), CircleShape))
                }
            }
        }
    }
}
