package com.comfyui.mobile.ui.screens.control

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comfyui.mobile.model.QueueDto
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
fun ControlQueueCard(
    queueRemaining: Int,
    queueList: List<QueueDto>,
    cardColors: CardColors,
    onCard: Color,
    modifier: Modifier = Modifier,
    onRefreshQueue: () -> Unit
    ) {
    val scheme = MiuixTheme.colorScheme

    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 18.dp,
        colors = cardColors,
        onClick = null,
        pressFeedbackType = PressFeedbackType.Tilt,
        showIndication = true
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
                Text(
                    "队列",
                    style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold),
                    color = onCard,
                    modifier = Modifier.weight(1f),
                    )
                Card(
                    cornerRadius = 999.dp,
                    insideMargin = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    colors = CardDefaults.defaultColors(
                        color = if (queueRemaining > 0) {
                            scheme.primary.copy(alpha = 0.14f)
                        } else {
                            scheme.surfaceContainerHigh.copy(alpha = 0.9f)
                        }
    ),
                    onClick = null,
                    pressFeedbackType = PressFeedbackType.Tilt,
                    showIndication = true
    ) {
                    Text(
                        text = if (queueRemaining > 0) "$queueRemaining 项" else "空闲",
                        style = MiuixTheme.textStyles.footnote1,
                        color = if (queueRemaining > 0) scheme.primary else onCard.copy(alpha = 0.65f)
    )
                }
                Card(
                    cornerRadius = 999.dp,
                    insideMargin = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    colors = CardDefaults.defaultColors(
                        color = scheme.surfaceContainerHigh.copy(alpha = 0.95f)
    ),
                    pressFeedbackType = PressFeedbackType.Tilt,
                    showIndication = true,
                    onClick = onRefreshQueue
    ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
                        Icon(
                            MiuixIcons.Refresh,
                            contentDescription = null,
                            tint = onCard.copy(alpha = 0.75f),
                            modifier = Modifier.size(14.dp)
    )
                        Text(
                            text = "刷新",
                            style = MiuixTheme.textStyles.footnote1,
                            color = onCard.copy(alpha = 0.75f)
    )
                    }
                }
            }

            if (queueList.isEmpty()) {
                Text(
                    text = "暂无排队任务",
                    style = MiuixTheme.textStyles.footnote1,
                    color = onCard.copy(alpha = 0.5f),
                    modifier = Modifier.padding(vertical = 4.dp)
    )
            } else {
                queueList.forEachIndexed { index, qItem ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 12.dp,
                        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        colors = CardDefaults.defaultColors(
                            color = scheme.surfaceContainerHigh.copy(alpha = 0.92f)
    ),
                        onClick = null,
                        pressFeedbackType = PressFeedbackType.Tilt,
                        showIndication = true
    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
                            Text(
                                text = "${index + 1}",
                                style = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.Bold),
                                color = scheme.primary,
                                modifier = Modifier.width(18.dp)
    )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Prompt ID: ${qItem.promptId}",
                                    style = MiuixTheme.textStyles.body2,
                                    color = onCard,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
    )
                                Text(
                                    text = "Node: ${qItem.id}",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = onCard.copy(alpha = 0.6f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
    )
                            }
                        }
                    }
                }
            }
        }
    }
}
