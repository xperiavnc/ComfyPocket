package com.comfyui.mobile.ui.screens.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 36dp visual chip inside a >=44dp touch target; text never forces a giant button. */
@Composable
internal fun AgentChoiceChip(
    label: String, selected: Boolean = false, modifier: Modifier = Modifier,
    action: Boolean = false, onClick: () -> Unit
) {
    val colors = MiuixTheme.colorScheme
    Box(modifier.widthIn(max = 216.dp).heightIn(min = 44.dp)
        .then(if (action) Modifier.clickable(role = Role.Button, onClick = onClick)
        else Modifier.selectable(selected, role = Role.RadioButton, onClick = onClick))
        .padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.clip(RoundedCornerShape(10.dp))
            .background(if (selected) colors.primary.copy(alpha = .15f) else colors.surfaceContainerHigh)
            .heightIn(min = 36.dp).padding(horizontal = 12.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center) {
            Text(label, style = MiuixTheme.textStyles.footnote1,
                color = if (selected || action) colors.primary else colors.onSurface,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
