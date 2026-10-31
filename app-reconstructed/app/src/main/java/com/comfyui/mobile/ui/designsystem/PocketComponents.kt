package com.comfyui.mobile.ui.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/** Surface level for [PocketCard]: base sits on the page, raised sits inside or above base. */
enum class PocketSurface { Base, Raised }

/**
 * Standard content card: Large radius, token padding, one of two surface levels, Sink press feedback
 * when clickable. Wraps the Miuix card so pages keep the Miuix look.
 */
@Composable
fun PocketCard(
    modifier: Modifier = Modifier,
    surface: PocketSurface = PocketSurface.Raised,
    contentPadding: PaddingValues = PaddingValues(PocketTokens.Space.M),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = if (surface == PocketSurface.Base) PocketCardColors.base() else PocketCardColors.raised()
    if (onClick == null) {
        // Non-interactive overload: the clickable one installs a press detector that steals taps
        // from fields and buttons inside the card.
        Card(
            modifier = modifier,
            cornerRadius = PocketTokens.Radius.Large,
            insideMargin = contentPadding,
            colors = colors,
            content = content
        )
    } else {
        Card(
            modifier = modifier,
            cornerRadius = PocketTokens.Radius.Large,
            insideMargin = contentPadding,
            colors = colors,
            onClick = onClick,
            pressFeedbackType = PressFeedbackType.Sink,
            showIndication = true,
            content = content
        )
    }
}

/**
 * Stadium chip for filters, starters and small menus. Selected = primary fill; idle = container fill
 * with no outline. Min height keeps a comfortable touch target.
 */
@Composable
fun PocketPill(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    idleColor: Color = MiuixTheme.colorScheme.surfaceContainerHigh,
    onClick: () -> Unit
) {
    val scheme = MiuixTheme.colorScheme
    val bg = if (selected) scheme.primary else idleColor
    val fg = when {
        selected -> scheme.onPrimary
        enabled -> scheme.onSurface
        else -> scheme.onSurface.copy(alpha = 0.38f)
    }
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = PocketTokens.CompactControlHeight)
            .clip(PocketTokens.Shape.Pill)
            .background(bg)
            .semantics { this.selected = selected }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = PocketTokens.Space.M - PocketTokens.Space.XXS / 2, vertical = PocketTokens.Space.XS),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            ),
            color = fg,
            maxLines = 1
        )
    }
}

/**
 * Round icon button. [filled] = primary action (send); otherwise a quiet surface disc.
 * [content] receives the tint to apply to its icon.
 */
@Composable
fun PocketIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    enabled: Boolean = true,
    size: Dp = PocketTokens.ControlHeight,
    idleColor: Color = MiuixTheme.colorScheme.surface,
    content: @Composable (tint: Color) -> Unit
) {
    val scheme = MiuixTheme.colorScheme
    val bg = when {
        filled && enabled -> scheme.primary
        enabled -> idleColor
        else -> idleColor.copy(alpha = 0.55f)
    }
    val tint = when {
        filled && enabled -> scheme.onPrimary
        enabled -> scheme.onSurface
        else -> scheme.onSurface.copy(alpha = 0.38f)
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content(tint)
    }
}

/**
 * Small "expand" chevron. Material's KeyboardArrowDown: the Miuix ExpandMore glyph renders as a
 * corner-bracket shape below ~16dp, which looked broken next to pills and breadcrumbs.
 */
@Composable
fun PocketChevron(tint: Color, modifier: Modifier = Modifier, size: Dp = 16.dp, contentDescription: String? = null) {
    androidx.compose.material3.Icon(
        imageVector = Icons.Default.KeyboardArrowDown,
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        tint = tint
    )
}

/**
 * One row in an action list (sheet/dialog): left-aligned label, optional summary, trailing chevron.
 * [destructive] colours the label with the error colour and drops the chevron.
 */
@Composable
fun PocketActionRow(
    label: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .clip(PocketTokens.Shape.Medium)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = PocketTokens.Space.M, vertical = PocketTokens.Space.XS),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                color = when {
                    !enabled -> scheme.onSurfaceVariantSummary.copy(alpha = 0.5f)
                    destructive -> scheme.error
                    else -> scheme.onSurface
                },
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Medium
            )
            if (!summary.isNullOrBlank()) Text(
                text = summary,
                color = scheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!destructive) androidx.compose.material3.Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = scheme.onSurfaceVariantSummary
        )
    }
}

/** Groups [PocketActionRow]s on one rounded surface; separate groups by placing several. */
@Composable
fun PocketActionGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(PocketTokens.Shape.Large)
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .padding(PocketTokens.Space.XXS),
        content = content
    )
}
