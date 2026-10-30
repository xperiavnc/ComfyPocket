package com.comfyui.mobile.ui.screens.agent

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.comfyui.mobile.model.MediaOutput
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.icon.extended.Report
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * Agent image preview card — one frosted center overlay for all busy/complete phases.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AgentImagePreviewCard(
    mediaList: List<MediaOutput>,
    previewUrl: (MediaOutput) -> String,
    status: AgentCardStatus,
    wfLabel: String,
    cardColors: CardColors,
    sidePad: Dp,
    pagerState: PagerState,
    modifier: Modifier = Modifier,
    showControls: Boolean = true,
    onImageClick: (index: Int) -> Unit,
    onClearMediaListClick: () -> Unit,
    onWorkflowClick: () -> Unit,
    onStyleParamsClick: () -> Unit,
    onPromptClick: () -> Unit
    ) {
    val scheme = MiuixTheme.colorScheme
    val overlayOn = status.showOverlay
    // Keep last overlay payload so exit fade still draws "完成" (no empty flash).
    var heldOverlayStatus by remember { mutableStateOf(status) }
    SideEffect {
        if (status.showOverlay) heldOverlayStatus = status
    }
    val canOpenImage = mediaList.isNotEmpty() && !overlayOn

    val infiniteTransition = rememberInfiniteTransition(label = "agent_card_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
    ),
        label = "pulse_alpha"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = sidePad, vertical = 6.dp)
            .border(
                width = 1.dp,
                color = scheme.outline.copy(alpha = 0.26f),
                shape = RoundedCornerShape(28.dp)
            ),
        cornerRadius = 28.dp,
        colors = cardColors,
        onClick = { },
        pressFeedbackType = PressFeedbackType.Tilt,
        showIndication = true
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (mediaList.isNotEmpty()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
    ) { page ->
                    val media = mediaList.getOrNull(page)
                    if (media != null) {
                        val url = previewUrl(media)
                        if (media.sourceUnresolved && url.isBlank()) {
                            Column(modifier = Modifier.fillMaxSize().padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center) {
                                Icon(MiuixIcons.Image, contentDescription = null,
                                    tint = scheme.onSurfaceVariantSummary)
                                Spacer(Modifier.height(8.dp))
                                Text("历史媒体来源待确认", color = scheme.onSurfaceVariantSummary)
                                Text(media.filename, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                            }
                        } else {
                        AsyncImage(
                            model = url,
                            contentDescription = if (page == 0) "最新生成" else "历史生成",
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (canOpenImage) Modifier.clickable { onImageClick(page) }
                                    else Modifier
                                ),
                            contentScale = ContentScale.Crop
    )
                        }
                    }
                }
            } else if (!overlayOn) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
    ) {
                    Icon(
                        MiuixIcons.Image,
                        contentDescription = null,
                        modifier = Modifier.size(88.dp),
                        tint = scheme.primary.copy(alpha = 0.45f)
    )
                }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = overlayOn,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(280)),
                modifier = Modifier.fillMaxSize()
    ) {
                val overlayStatus = heldOverlayStatus
                val overlayFrame = AgentOverlayFrame(
                    phase = overlayStatus.phase,
                    title = overlayStatus.title ?: defaultTitle(overlayStatus.phase),
                    subtitle = overlayStatus.subtitle,
                    showProgress = overlayStatus.showProgress,
                    progress = overlayStatus.progress,
                )
                AnimatedContent(
                    targetState = overlayFrame,
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(180)) },
                    contentKey = { it.phase },
                    label = "agent_card_phase",
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
    ) { frame ->
                    val title = frame.title
                    val subtitle = frame.subtitle
                    val showProgress = frame.showProgress
                    val progress = frame.progress
                    val accentComplete = frame.phase == AgentCardPhase.BriefComplete
                    val titleColor = if (accentComplete) Color(0xFF2E7D32) else scheme.primary

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(onClick = { /* block open while overlay */ })
    ) {
                        if (mediaList.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(scheme.surface.copy(alpha = 0.68f))
                                    .blur(16.dp)
    )
                        }
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
    ) {
                            Icon(
                                MiuixIcons.Image,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(72.dp)
                                    .graphicsLayer { alpha = if (accentComplete) 1f else pulseAlpha },
                                tint = titleColor.copy(alpha = 0.9f)
    )
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = if (accentComplete) "✓  $title" else title,
                                color = titleColor,
                                style = MiuixTheme.textStyles.title3.copy(fontWeight = FontWeight.SemiBold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
    )
                            if (showProgress) {
                                Spacer(modifier = Modifier.height(12.dp))
                                AgentStatusProgressBar(
                                    progress = progress,
                                    modifier = Modifier
                                        .padding(horizontal = 28.dp)
                                        .fillMaxWidth()
    )
                            }
                            if (!subtitle.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = subtitle,
                                    color = titleColor.copy(alpha = 0.92f),
                                    style = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
    )
                            }
                        }
                    }
                }
            }

            if (!overlayOn && mediaList.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(scheme.surface.copy(alpha = 0.78f))
                        .then(if (showControls) Modifier.clickable(onClick = onClearMediaListClick) else Modifier)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
                    Text(
                        text = if (pagerState.currentPage == 0) "最新生成" else "历史生成",
                        color = scheme.onSurface,
                        style = MiuixTheme.textStyles.body2
    )
                    if (mediaList.size > 1) {
                        Text(
                            text = "(${pagerState.currentPage + 1}/${mediaList.size})",
                            color = scheme.primary,
                            style = MiuixTheme.textStyles.footnote1.copy(fontWeight = FontWeight.SemiBold)
    )
                    }
                }
            }

            // Consistent top-end error corner (idle only; overlay phases hide it)
            if (!status.errorBadge.isNullOrBlank() && !overlayOn) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(scheme.error.copy(alpha = 0.16f))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
                    Icon(
                        MiuixIcons.Report,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = scheme.error
    )
                    Text(
                        text = status.errorBadge,
                        color = scheme.error,
                        style = MiuixTheme.textStyles.footnote1.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
    )
                }
            }

            if (showControls) Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
                    .alpha(if (status.softenChips) 0.38f else 1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
                FrostedChip(
                    text = wfLabel,
                    modifier = Modifier.weight(1.1f),
                    enabled = !status.softenChips,
                    onClick = onWorkflowClick
    )
                FrostedChip(
                    text = "画风/画师",
                    modifier = Modifier.weight(1f),
                    enabled = !status.softenChips,
                    onClick = onStyleParamsClick
    )
                FrostedChip(
                    text = "提示词",
                    modifier = Modifier.weight(0.9f),
                    enabled = !status.softenChips,
                    onClick = onPromptClick
    )
            }
        }
    }
}

private data class AgentOverlayFrame(
    val phase: AgentCardPhase,
    val title: String,
    val subtitle: String?,
    val showProgress: Boolean,
    val progress: Float?,
)

private fun defaultTitle(phase: AgentCardPhase): String = when (phase) {
    AgentCardPhase.LlmThinking -> "正在回复…"
    AgentCardPhase.Queued -> "已加入队列"
    AgentCardPhase.GeneratingCloud -> "云端生成中"
    AgentCardPhase.BriefComplete -> "完成"
    else -> "生成中"
}

@Composable
internal fun AgentStatusProgressBar(
    progress: Float?,
    modifier: Modifier = Modifier
    ) {
    val scheme = MiuixTheme.colorScheme
    val h = 4.dp
    if (progress != null) {
        LinearProgressIndicator(
            progress = progress.coerceIn(0f, 1f),
            modifier = modifier
                .height(h)
                .clip(RoundedCornerShape(99.dp)),
            colors = ProgressIndicatorDefaults.progressIndicatorColors(
                foregroundColor = scheme.primary,
                backgroundColor = scheme.surface.copy(alpha = 0.35f)
    )
    )
    } else {
        val density = LocalDensity.current
        val transition = rememberInfiniteTransition(label = "indeterminate")
        val shift by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1100, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
    ),
            label = "indeterminate_shift"
    )
        BoxWithConstraints(
            modifier = modifier
                .height(h)
                .clip(RoundedCornerShape(99.dp))
                .background(scheme.surface.copy(alpha = 0.35f))
    ) {
            val travel = (maxWidth * 0.66f).coerceAtLeast(0.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.34f)
                    .height(h)
                    .graphicsLayer { translationX = with(density) { (travel * shift).toPx() } }
                    .clip(RoundedCornerShape(99.dp))
                    .background(scheme.primary)
    )
        }
    }
}
