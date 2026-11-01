package com.comfyui.mobile.ui.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.comfyui.mobile.ui.navigation.PocketStandard
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Height of a [PocketSegmented] track; matches the compact search field next to it. */
val PocketSegmentedHeight = 44.dp

/**
 * One rounded track with equal segments; the selected one is a tinted pill inside it.
 * Used for every "pick one of a few" row on a page so they read as one family.
 *
 * Segments share the width equally while every label fits. On narrow screens or with
 * large fonts the track wraps onto more lines instead of cutting labels off.
 */
@Composable
fun PocketSegmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    tagPrefix: String? = null,
) {
    val scheme = MiuixTheme.colorScheme
    val gap = 2.dp
    Layout(
        content = {
            options.forEachIndexed { index, label ->
                PocketSegment(label, index == selected, tagPrefix?.let { "$it-$index" }) { onSelect(index) }
            }
        },
        // Fixed corners instead of 50%: a wrapped (taller) track must stay a rounded rectangle, not an oval.
        modifier = modifier.clip(RoundedCornerShape(PocketSegmentedHeight / 2)).background(scheme.surfaceContainerHigh)
            .padding(4.dp).selectableGroup()
    ) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val n = measurables.size
        if (n == 0) return@Layout layout(0, 0) {}
        val natural = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        val width = constraints.maxWidth
        // Rows of indices; every row is stretched to the full width so the track edges stay straight.
        val rows: List<List<Int>> = when {
            width == Constraints.Infinity -> listOf(natural.indices.toList())
            natural.sum() + gapPx * (n - 1) <= width -> listOf(natural.indices.toList())
            else -> pocketSegmentRows(natural.map { it.coerceAtMost(width) }, width, gapPx)
        }
        val placed = rows.map { row ->
            val free = if (width == Constraints.Infinity) 0 else width - gapPx * (row.size - 1)
            val equal = if (row.isEmpty()) 0 else free / row.size
            val widths = if (width == Constraints.Infinity) row.map { natural[it] }
                // Equal segments while every label fits one; otherwise share the spare room evenly.
                else if (row.all { natural[it] <= equal }) row.map { equal }
                else {
                    val spare = (free - row.sumOf { natural[it].coerceAtMost(width) }).coerceAtLeast(0)
                    row.map { natural[it].coerceAtMost(width) + spare / row.size }
                }
            // Hand the rounding remainder to the last segment so the right edge lines up.
            val fixed = if (width == Constraints.Infinity) widths
                else widths.toMutableList().also { it[it.lastIndex] += free - widths.sum() }
            row.mapIndexed { k, i -> measurables[i].measure(Constraints.fixedWidth(fixed[k].coerceAtLeast(0))) }
        }
        val heights = placed.map { row -> row.maxOf { it.height } }
        val total = placed.maxOf { row -> row.sumOf { it.width } + gapPx * (row.size - 1) }
        layout(if (width == Constraints.Infinity) total else width, heights.sum() + gapPx * (rows.size - 1)) {
            var y = 0
            placed.forEachIndexed { r, row ->
                var x = 0
                row.forEach { it.place(x, y + (heights[r] - it.height) / 2); x += it.width + gapPx }
                y += heights[r] + gapPx
            }
        }
    }
}

/** Fewest rows that fit, split as evenly as possible (5 → 3 + 2, never 4 + 1). */
internal fun pocketSegmentRows(widths: List<Int>, max: Int, gap: Int): List<List<Int>> {
    fun span(from: Int, to: Int) = (from until to).sumOf { widths[it] } + gap * (to - from - 1)
    val n = widths.size
    for (count in 2..n) {
        val base = n / count
        val extra = n % count
        // Earlier rows take the extra item, so the eye reads a full first line.
        var from = 0
        val rows = (0 until count).map { r ->
            val size = base + if (r < extra) 1 else 0
            (from until from + size).toList().also { from += size }
        }
        if (rows.all { it.isEmpty() || span(it.first(), it.last() + 1) <= max }) return rows
    }
    return widths.indices.map { listOf(it) }
}

@Composable
private fun PocketSegment(label: String, selected: Boolean, tag: String?, onClick: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(
        when {
            selected -> scheme.primary.copy(alpha = if (pressed) 0.24f else 0.16f)
            pressed -> scheme.onSurface.copy(alpha = 0.06f)
            else -> Color.Transparent
        }, tween(160, easing = PocketStandard), label = "segment-bg")
    val content by animateColorAsState(if (selected) scheme.primary else scheme.onSurfaceSecondary,
        tween(160, easing = PocketStandard), label = "segment-fg")
    Box(
        Modifier.heightIn(min = PocketSegmentedHeight - 8.dp).clip(PocketTokens.Shape.Pill).background(background)
            .selectable(selected, interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = MiuixTheme.textStyles.footnote1, color = content, maxLines = 1, textAlign = TextAlign.Center,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
    }
}
