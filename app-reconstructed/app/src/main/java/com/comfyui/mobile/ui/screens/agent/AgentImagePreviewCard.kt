package com.comfyui.mobile.ui.screens.agent

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.icon.extended.Report
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Agent image preview — fitted image over a blurred ambient copy of itself, so any aspect
 * ratio shows completely (portraits are no longer cropped into a strip). Busy phases blur
 * the image itself and float one status block on top.
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
    // Blur the picture itself while busy; a blurred scrim over a sharp image only looks grey.
    val imageBlur by animateDpAsState(if (overlayOn) 18.dp else 0.dp, tween(260), label = "preview_blur")

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

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = sidePad, vertical = 6.dp)
            .clip(RoundedCornerShape(PreviewCorner))
            .background(cardColors.color)
    ) {
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
                            Box(
                                Modifier.fillMaxSize().then(
                                    if (canOpenImage) Modifier.clickable { onImageClick(page) } else Modifier
                                )
                            ) {
                                // Ambient fill for letterbox areas: same image, cropped, heavily blurred and dimmed.
                                AsyncImage(
                                    model = url,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize().blur(28.dp).alpha(0.55f),
                                    contentScale = ContentScale.Crop
                                )
                                AsyncImage(
                                    model = url,
                                    contentDescription = if (page == 0) "最新生成" else "历史生成",
                                    modifier = Modifier.fillMaxSize().blur(imageBlur),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }
                    }
                }
            } else if (!overlayOn) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
    ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            MiuixIcons.Image,
                            contentDescription = null,
                            modifier = Modifier.size(44.dp),
                            tint = scheme.onSurfaceVariantSummary.copy(alpha = 0.7f)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "生成的图片会显示在这里",
                            color = scheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.footnote1
                        )
                    }
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
                            // Light veil keeps text legible over the blurred image.
                            .background(scheme.surface.copy(alpha = if (mediaList.isNotEmpty()) 0.42f else 0f))
                            .clickable(onClick = { /* block open while overlay */ })
    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
    ) {
                            Icon(
                                MiuixIcons.Image,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(48.dp)
                                    .graphicsLayer { alpha = if (accentComplete) 1f else pulseAlpha },
                                tint = titleColor.copy(alpha = 0.9f)
    )
                            Spacer(modifier = Modifier.height(12.dp))
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
                                        .padding(horizontal = 40.dp)
                                        .fillMaxWidth()
    )
                            }
                            if (!subtitle.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = subtitle,
                                    color = scheme.onSurface.copy(alpha = 0.8f),
                                    style = MiuixTheme.textStyles.body2,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
    )
                            }
                        }
                    }
                }
            }

            if (!overlayOn && mediaList.size > 1) {
                // Compact page counter + dots; the "最新/历史" wording lives in the header row.
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = if (showControls) 52.dp else 10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.32f))
                        .then(if (showControls) Modifier.clickable(onClick = onClearMediaListClick) else Modifier)
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
                    val dots = mediaList.size.coerceAtMost(MaxDots)
                    val selectedDot = pagerState.currentPage.coerceAtMost(dots - 1)
                    repeat(dots) { i ->
                        val selected = i == selectedDot
                        val w by animateDpAsState(if (selected) 14.dp else 5.dp, tween(200), label = "dot_w")
                        Box(
                            Modifier.size(width = w, height = 5.dp).clip(RoundedCornerShape(50))
                                .background(Color.White.copy(alpha = if (selected) 0.95f else 0.5f))
                        )
                    }
                    Text(
                        text = "(${pagerState.currentPage + 1}/${mediaList.size})",
                        color = Color.White,
                        style = MiuixTheme.textStyles.footnote2.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.padding(start = 2.dp)
    )
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

/** Inner preview radius: concentric with the 22dp workbench container minus its 8dp inset. */
private val PreviewCorner = 14.dp
private const val MaxDots = 6

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
