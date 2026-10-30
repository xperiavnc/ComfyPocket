package com.comfyui.mobile.ui.screens.agent

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.semantics.Role
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Transient feedback only: callers clip the entire hit area, not a text-only subregion. */
@Composable
internal fun Modifier.agentControlFeedback(
    onClickLabel: String,
    selected: Boolean? = null,
    interactionSource: MutableInteractionSource? = null,
    drawFeedback: Boolean = true,
    enabled: Boolean = true,
    role: Role = if (selected == null) Role.Button else Role.Tab,
    onClick: () -> Unit
): Modifier {
    val interactions = interactionSource ?: remember { MutableInteractionSource() }
    val feedback = if (drawFeedback) agentControlSurface(interactions) else this
    return if (selected == null) feedback.clickable(
        interactionSource = interactions, indication = null,
        role = role, onClickLabel = onClickLabel, enabled = enabled, onClick = onClick
    ) else feedback.selectable(
        selected = selected, interactionSource = interactions, indication = null,
        role = role, enabled = enabled, onClick = onClick
    )
}

/** Share a surface across independent child actions without nesting their click handlers. */
@Composable
internal fun Modifier.agentControlSurface(interactions: MutableInteractionSource): Modifier {
    val pressed by interactions.collectIsPressedAsState()
    val focused by interactions.collectIsFocusedAsState()
    val alpha by animateFloatAsState(
        targetValue = if (pressed) .08f else if (focused) .05f else 0f,
        animationSpec = tween(if (pressed) 70 else 160),
        label = "agentControlPress"
    )
    val color = MiuixTheme.colorScheme.onSurface
    return drawWithContent {
        drawContent()
        if (alpha > 0f) drawRect(color.copy(alpha = alpha))
    }
}
