package com.comfyui.mobile.ui.screens.control

import androidx.compose.foundation.background
import com.comfyui.mobile.ui.designsystem.PocketTokens
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.comfyui.mobile.model.MediaOutput
import com.comfyui.mobile.ui.components.ResultMediaTile
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
fun ControlLatestMediaCard(
    latestGenerationList: List<MediaOutput>,
    cardColors: CardColors,
    onCard: Color,
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
    getViewUrl: (MediaOutput) -> String,
    onItemClick: (Int) -> Unit
    ) {
    val scheme = MiuixTheme.colorScheme
    val innerShape = PocketTokens.Shape.Medium

    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = cornerRadius,
        colors = cardColors,
        onClick = null,
        pressFeedbackType = PressFeedbackType.Sink,
        showIndication = true
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                "本次生成 · ${latestGenerationList.size}",
                style = MiuixTheme.textStyles.title3.copy(fontWeight = FontWeight.SemiBold),
                color = onCard
    )
            Spacer(modifier = Modifier.height(12.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
    ) {
                items(latestGenerationList.size) { index ->
                    val media = latestGenerationList[index]
                    val url = getViewUrl(media)
                    Box(
                        modifier = Modifier
                            .width(140.dp)
                            .height(140.dp)
                            .clip(innerShape)
                            .background(scheme.surfaceVariant),
                        contentAlignment = Alignment.Center
    ) {
                        // Image (animated GIF) / video frame + play overlay / audio / file card (tap = open, long-press = share).
                        ResultMediaTile(
                            media = media,
                            url = url,
                            modifier = Modifier.fillMaxSize(),
                            textColor = onCard,
                            onClick = { onItemClick(index) }
    )
                    }
                }
            }
        }
    }
}
