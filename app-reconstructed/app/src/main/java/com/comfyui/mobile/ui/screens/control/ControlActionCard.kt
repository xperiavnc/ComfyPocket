package com.comfyui.mobile.ui.screens.control

import androidx.compose.foundation.clickable
import com.comfyui.mobile.ui.designsystem.PocketTokens
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import com.comfyui.mobile.ui.rememberAppHaptics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.FileDownloads
import top.yukonga.miuix.kmp.icon.extended.Pause
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
fun ControlRunCard(
    batchCount: Int,
    cardColors: CardColors,
    onCard: Color,
    modifier: Modifier = Modifier,
    onBatchCountChange: (Int) -> Unit,
    onRunClick: () -> Unit,
    onStopClick: () -> Unit,
    onSaveClick: () -> Unit,
    /** Cloud official-task state (排队中 / 运行中 …), shown under the title. */
    statusText: String? = null,
    /** Cloud workflow: RunningHub workflowId value (the ID or "未设置 · 点此填写", tap to edit); null = hidden. */
    cloudIdText: String? = null,
    onCloudIdClick: (() -> Unit)? = null,
    cloudIdLabel: String = "工作流 ID"
    ) {
    val haptics = rememberAppHaptics()

    val scheme = MiuixTheme.colorScheme

    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = PocketTokens.Radius.Large,
        colors = cardColors,
        onClick = null,
        pressFeedbackType = PressFeedbackType.None,
        showIndication = false
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
            Text(
                text = "运行",
                style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold),
                color = onCard
    )
            if (!statusText.isNullOrBlank()) {
                Text(
                    text = statusText,
                    style = MiuixTheme.textStyles.body2,
                    color = scheme.primary
    )
            }
            if (!cloudIdText.isNullOrBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (onCloudIdClick != null) Modifier.clickable { onCloudIdClick() } else Modifier),
                    verticalAlignment = Alignment.CenterVertically
    ) {
                    Text(
                        text = cloudIdLabel,
                        style = MiuixTheme.textStyles.body2,
                        color = onCard.copy(alpha = 0.75f)
    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = cloudIdText,
                        style = MiuixTheme.textStyles.body2,
                        color = scheme.primary
    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
    ) {
                Text(
                    text = "批量",
                    style = MiuixTheme.textStyles.body2,
                    color = onCard.copy(alpha = 0.75f)
    )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "×$batchCount",
                    style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold),
                    color = onCard
    )
            }
            Slider(
                value = batchCount.toFloat(),
                onValueChange = { value ->
                    onBatchCountChange(value.toInt().coerceIn(1, 8))
                },
                valueRange = 1f..8f,
                modifier = Modifier.fillMaxWidth()
    )
            Button(
                onClick = { haptics.confirm(); onRunClick() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                cornerRadius = PocketTokens.Radius.Medium,
                colors = ButtonDefaults.buttonColorsPrimary()
    ) {
                Icon(MiuixIcons.Play, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (batchCount > 1) "运行 ×$batchCount" else "运行",
                    style = MiuixTheme.textStyles.footnote1.copy(fontWeight = FontWeight.SemiBold)
    )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
                Button(
                    onClick = { haptics.tick(); onStopClick() },
                    modifier = Modifier
                        .weight(1f)
                        .height(PocketTokens.ControlHeight),
                    cornerRadius = PocketTokens.Radius.Medium,
                    colors = ButtonDefaults.buttonColors(
                        color = scheme.error.copy(alpha = 0.12f),
                        contentColor = scheme.error
    )
    ) {
                    Icon(MiuixIcons.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("停止", style = MiuixTheme.textStyles.body2)
                }
                Button(
                    onClick = { haptics.confirm(); onSaveClick() },
                    modifier = Modifier
                        .weight(1f)
                        .height(PocketTokens.ControlHeight),
                    cornerRadius = PocketTokens.Radius.Medium,
                    colors = ButtonDefaults.buttonColors(
                        color = scheme.surfaceContainerHigh,
                        contentColor = onCard
    )
    ) {
                    Icon(MiuixIcons.FileDownloads, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("保存", style = MiuixTheme.textStyles.body2)
                }
            }
        }
    }
}
