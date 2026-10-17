package com.comfyui.mobile.ui.screens.control

import com.comfyui.mobile.ui.designsystem.PocketIcons

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.comfyui.mobile.ui.designsystem.PocketButton as Button
import com.comfyui.mobile.ui.designsystem.PocketButtonDefaults as ButtonDefaults
import com.comfyui.mobile.ui.designsystem.PocketCard as Card
import androidx.compose.material3.CardColors
import com.comfyui.mobile.ui.designsystem.PocketCardDefaults as CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text




import androidx.compose.material3.MaterialTheme

@Composable
fun ControlWorkflowHeaderCard(
    workflowName: String?,
    cardColors: CardColors,
    onCard: Color,
    cornerRadius: Dp,
    pillRadius: Dp,
    modifier: Modifier = Modifier,
    isCloudMode: Boolean = false,
    onLocalClick: () -> Unit,
    onImportClick: () -> Unit,
    onCloudSyncClick: (() -> Unit)? = null
    ) {
    val scheme = MaterialTheme.colorScheme

    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = cornerRadius,
        colors = cardColors,
        onClick = null) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
            Text(
                if (isCloudMode) "云端工作流" else "选择工作流",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = onCard
    )

            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 18.dp,
                colors = CardDefaults.defaultColors(
                    color = scheme.primaryContainer.copy(alpha = 0.55f)
    ),
                onClick = onLocalClick) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
                    Card(
                        modifier = Modifier.size(40.dp),
                        cornerRadius = 12.dp,
                        colors = CardDefaults.defaultColors(
                            color = scheme.primary.copy(alpha = 0.18f)
    ),
                        onClick = null) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                PocketIcons.File,
                                contentDescription = null,
                                tint = scheme.primary,
                                modifier = Modifier.size(22.dp)
    )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = workflowName?.removeSuffix(".json")?.ifBlank { null } ?: "未选择工作流",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = scheme.onPrimaryContainer,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
    )
                        Text(
                            text = if (workflowName.isNullOrBlank()) {
                                if (isCloudMode) "点「云端」选择，或点「同步」拉取" else "点「本地」选择，或「导入」文件"
                            } else {
                                "当前已加载"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onPrimaryContainer.copy(alpha = 0.75f)
    )
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isCloudMode) {
                    // Cloud: text-only 「云端」(saved workflows of this site) + 「同步」(pull from RunningHub); no file import.
                    Button(
                        onClick = onLocalClick,
                        modifier = Modifier.weight(1f).height(48.dp),
                        cornerRadius = pillRadius,
                        colors = ButtonDefaults.buttonColors()
    ) {
                        Text("云端")
                    }
                    if (onCloudSyncClick != null) {
                        Button(
                            onClick = onCloudSyncClick,
                            modifier = Modifier.weight(1f).height(48.dp),
                            cornerRadius = pillRadius,
                            colors = ButtonDefaults.buttonColors()
    ) {
                            Text("同步")
                        }
                    }
                } else {
                    Button(
                        onClick = onLocalClick,
                        modifier = Modifier.weight(1f).height(48.dp),
                        cornerRadius = pillRadius,
                        colors = ButtonDefaults.buttonColors()
    ) {
                        Icon(PocketIcons.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("本地")
                    }
                    Button(
                        onClick = onImportClick,
                        modifier = Modifier.weight(1f).height(48.dp),
                        cornerRadius = pillRadius,
                        colors = ButtonDefaults.buttonColors()
    ) {
                        Icon(PocketIcons.Import, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("导入")
                    }
                }
            }
        }
    }
}
