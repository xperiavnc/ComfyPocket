package com.comfyui.mobile.ui.screens.agent

import androidx.compose.animation.core.spring
import com.comfyui.mobile.ui.designsystem.PocketTokens
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import top.yukonga.miuix.kmp.icon.extended.Tune
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.comfyui.mobile.ui.MainViewModel
import com.comfyui.mobile.ui.rememberAppHaptics
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Keep only frequent controls visible; low-frequency actions live in the workbench sheet. */
@Composable
fun AgentCapsuleBar(
    modelDisplayName: String,
    interactionMode: String,
    sidePad: Dp,
    modifier: Modifier = Modifier,
    onModelSwitchClick: () -> Unit,
    onInteractionModeChange: (String) -> Unit,
    onWorkbenchActionsClick: () -> Unit
    ) {
    val haptics = rememberAppHaptics()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = sidePad, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        AgentModeToggle(
            mode = interactionMode,
            onModeChange = { mode ->
                if (mode != interactionMode) {
                    haptics.tick()
                    onInteractionModeChange(mode)
                }
            }
    )
        Row(
            modifier = Modifier.weight(1f, fill = false).padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
            AgentCapsule(
                label = modelDisplayName,
                modifier = Modifier.weight(1f, fill = false),
                onClick = { haptics.tick(); onModelSwitchClick() }
    ) {
                Icon(
                    MiuixIcons.ExpandMore,
                    contentDescription = "切换模型与 API",
                    modifier = Modifier.size(12.dp),
                    tint = MiuixTheme.colorScheme.primary
    )
            }
            Box(
                modifier = Modifier
                    .size(CapsuleHeight)
                    .clip(CircleShape)
                    .background(MiuixTheme.colorScheme.surfaceContainerHighest)
                    .clickable { haptics.tick(); onWorkbenchActionsClick() },
                contentAlignment = Alignment.Center
    ) {
                // Tune, not a gear: the top bar gear already means "Agent 设置".
                Icon(
                    MiuixIcons.Tune,
                    contentDescription = "绘图参数与操作",
                    modifier = Modifier.size(18.dp),
                    tint = MiuixTheme.colorScheme.onSurface
    )
            }
        }
    }
}

// One control height for the whole toolbar row (mode switch, model pill, tune button).
private val CapsuleHeight = PocketTokens.CompactControlHeight
private val SegmentWidth = 60.dp

/** Sliding segmented switch: tap a side or swipe horizontally. */
@Composable
private fun AgentModeToggle(mode: String, onModeChange: (String) -> Unit) {
    val scheme = MiuixTheme.colorScheme
    val isDraw = mode != MainViewModel.AGENT_MODE_CHAT
    val thumbOffset by animateDpAsState(
        targetValue = if (isDraw) SegmentWidth else 0.dp,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
        label = "modeThumb"
    )
    val density = LocalDensity.current
    val swipeThreshold = with(density) { 16.dp.toPx() }
    Box(
        modifier = Modifier
            .size(width = SegmentWidth * 2, height = CapsuleHeight)
            .clip(PocketTokens.Shape.Pill)
            .background(scheme.surfaceContainerHighest)
            .pointerInput(mode) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        when {
                            total > swipeThreshold -> onModeChange(MainViewModel.AGENT_MODE_DRAW)
                            total < -swipeThreshold -> onModeChange(MainViewModel.AGENT_MODE_CHAT)
                        }
                    },
                    onHorizontalDrag = { change, delta -> change.consume(); total += delta }
    )
            }
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .padding(2.dp)
                .size(width = SegmentWidth - 4.dp, height = CapsuleHeight - 4.dp)
                .clip(PocketTokens.Shape.Pill)
                .background(scheme.primary)
    )
        Row(modifier = Modifier.fillMaxSize()) {
            listOf(MainViewModel.AGENT_MODE_CHAT, MainViewModel.AGENT_MODE_DRAW).forEach { m ->
                val selected = (m == MainViewModel.AGENT_MODE_DRAW) == isDraw
                Box(
                    modifier = Modifier
                        .size(width = SegmentWidth, height = CapsuleHeight)
                        .clip(PocketTokens.Shape.Pill)
                        .clickable { onModeChange(m) },
                    contentAlignment = Alignment.Center
    ) {
                    Text(
                        text = agentModeLabel(m),
                        color = if (selected) scheme.onPrimary else scheme.onSurface.copy(alpha = 0.72f),
                        style = MiuixTheme.textStyles.footnote1.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1
    )
                }
            }
        }
    }
}
fun agentModeLabel(mode: String): String = if (mode == MainViewModel.AGENT_MODE_CHAT) "Chat" else "绘图"

/** Shared pill: neutral surface container + onSurface text; chevron keeps primary. */
@Composable
fun AgentCapsule(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null
    ) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier = modifier
            .height(CapsuleHeight)
            .widthIn(min = 52.dp)
            .clip(PocketTokens.Shape.Pill)
            .background(scheme.surfaceContainerHighest)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)
    ) {
        Text(
            text = label,
            color = scheme.onSurface,
            style = MiuixTheme.textStyles.footnote1.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
    )
        trailing?.invoke(this)
    }
}
