package com.comfyui.mobile.ui.screens.aiapps

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.comfyui.mobile.model.AiApp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import java.io.File

@Composable
fun AiAppEmptyCard(
    modifier: Modifier = Modifier,
    isCloudMode: Boolean = false
    ) {
    val scheme = MiuixTheme.colorScheme
    val cardColors = CardDefaults.defaultColors(
        color = scheme.secondaryContainer,
        contentColor = scheme.onSecondaryContainer
    )
    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 28.dp,
        colors = cardColors,
        onClick = null,
        pressFeedbackType = PressFeedbackType.Sink,
        showIndication = false
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
            Icon(
                MiuixIcons.Image,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = scheme.onSecondaryContainer.copy(alpha = 0.55f)
    )
            Text(
                if (isCloudMode) "暂无应用" else "暂无 AI 应用",
                style = MiuixTheme.textStyles.title3.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.onSecondaryContainer
    )
            Text(
                if (isCloudMode) "点右上角导入" else "在控制页保存后会出现",
                style = MiuixTheme.textStyles.body2,
                color = scheme.onSecondaryContainer.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
    )
        }
    }
}

@Composable
fun AiAppCard(
    app: AiApp,
    coverFile: File?,
    isDragging: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onSettingsClick: () -> Unit
    ) {
    val scheme = MiuixTheme.colorScheme
    val context = LocalContext.current
    val cardColors = CardDefaults.defaultColors(
        color = scheme.secondaryContainer,
        contentColor = scheme.onSecondaryContainer
    )

    Card(
        modifier = modifier,
        cornerRadius = 28.dp,
        colors = cardColors,
        onClick = if (!isDragging) onClick else null,
        pressFeedbackType = if (!isDragging) PressFeedbackType.Sink else PressFeedbackType.None,
        showIndication = !isDragging
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(148.dp)
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(scheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (coverFile != null) {
                    val coverModel = remember(coverFile.absolutePath, app.updatedAt, context) {
                        com.comfyui.mobile.ui.components.coverRequest(context, coverFile, "${coverFile.absolutePath}:${app.updatedAt}")
                    }
                    AsyncImage(
                        model = coverModel,
                        contentDescription = app.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(MiuixIcons.Image, contentDescription = null, modifier = Modifier.size(40.dp))
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    if (coverFile != null) {
                        val coverModel = remember(coverFile.absolutePath, app.updatedAt, context) {
                            com.comfyui.mobile.ui.components.coverRequest(context, coverFile, "${coverFile.absolutePath}:${app.updatedAt}", crossfade = false)
                        }
                        AsyncImage(
                            model = coverModel,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                                .blur(18.dp),
                            contentScale = ContentScale.Crop
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MiuixTheme.colorScheme.surface.copy(alpha = 0.38f))
                    )

                    Text(
                        app.title,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 10.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        style = MiuixTheme.textStyles.subtitle,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                }

                val settingsIconSize = 20.dp
                val settingsHitSize = settingsIconSize + 4.dp
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(settingsHitSize)
                        .shadow(2.dp, CircleShape, clip = false)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.28f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onSettingsClick() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        MiuixIcons.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(settingsIconSize),
                        tint = Color.White
                    )
                }
            }
        }
    }
}
