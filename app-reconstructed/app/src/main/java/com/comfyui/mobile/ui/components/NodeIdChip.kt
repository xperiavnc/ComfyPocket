package com.comfyui.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Small "#12" capsule with the workflow node ID, same shape/size as the type capsule in
 * "管理节点参数" (neutral colour so it does not compete with the type label). The "#id" form
 * matches the run log / error messages ("节点 #12 ..."), which makes troubleshooting easier.
 */
@Composable
fun NodeIdChip(nodeId: String, modifier: Modifier = Modifier) {
    val scheme = MiuixTheme.colorScheme
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(scheme.onSecondaryContainer.copy(alpha = 0.10f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = "#$nodeId",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onSecondaryContainer,
            maxLines = 1
    )
    }
}
