// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package com.comfyui.mobile.ui.liquid

// Adapted from Kyant0/AndroidLiquidGlass — https://github.com/Kyant0/AndroidLiquidGlass (Apache 2.0).

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as colorLerp
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.lerp as floatLerp
import com.comfyui.mobile.ui.animation.DampedDragAnimation
import com.comfyui.mobile.ui.animation.InteractiveHighlight
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BadgedBox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.highlight.BloomStroke
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.highlight.LightPosition
import top.yukonga.miuix.kmp.blur.highlight.LightSource
import top.yukonga.miuix.kmp.blur.sensor.rememberDeviceTilt
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

private val iosIndicatorSpecular: Highlight = Highlight(
    width = 1.dp,
    alpha = 1f,
    style = BloomStroke(
        color = Color.White.copy(alpha = 0.12f),
        innerBlurRadius = 2.0.dp,
        primaryLight = LightSource(
            position = LightPosition(0.5f, -0.3f, -0.05f),
            color = Color.White,
            intensity = 1f
    ),
        secondaryLight = LightSource(
            position = LightPosition(0.5f, 0.8f, -0.5f),
            color = Color.White,
            intensity = 0.4f
    ),
        dualPeak = true
    )
    )

// Mirrors HighlightStyle.kt's LIGHT_REF — keep in sync.
private const val LIGHT_REF_X = 0.5f
private const val LIGHT_REF_Y = 0.7f
private const val GRAVITY_DIR_THRESHOLD_SQ = 0.01f // |g_xy| > 0.1, ≈ 6° tilt

/** Tracks gravity for a `dualPeak` highlight's primary light, with an extra UV-clockwise offset on top. */
@Composable
private fun rememberGravityRotatedHighlight(
    base: Highlight,
    extraDegrees: Float = 0f
    ): Highlight {
    val baseStyle = base.style as BloomStroke
    val tilt by rememberDeviceTilt()
    val rotatedPrimary = remember(tilt, baseStyle.primaryLight, extraDegrees) {
        val basePrimary = baseStyle.primaryLight
        val gx = tilt.gravityX
        val gy = tilt.gravityY
        val gMagSq = gx * gx + gy * gy
        val (lx0, ly0) = if (gMagSq > GRAVITY_DIR_THRESHOLD_SQ) {
            val invMag = 1f / sqrt(gMagSq)
            (gx * invMag) to (gy * invMag)
        } else {
            0f to -1f
        }
        val rad = extraDegrees * PI / 180.0
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val lx = c * lx0 - s * ly0
        val ly = s * lx0 + c * ly0
        basePrimary.copy(
            position = LightPosition(
                x = LIGHT_REF_X + lx,
                y = LIGHT_REF_Y + ly,
                z = basePrimary.position.z
    )
    )
    }
    return remember(base, rotatedPrimary) {
        base.copy(style = baseStyle.copy(primaryLight = rotatedPrimary))
    }
}

@Composable
fun IosLiquidGlassNavigationBar(
    items: List<NavigationItem>,
    selectedIndex: Int,
    onItemClick: (Int) -> Unit,
    backdrop: LayerBackdrop?,
    isBlurActive: Boolean,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    badge: (Int) -> (@Composable () -> Unit)? = { null }
    ) {
    val pillShape = remember { CircleShape }
    val accentColor = MiuixTheme.colorScheme.primary
    val tabContentColor = MiuixTheme.colorScheme.onSurface
    val surfaceContainer = MiuixTheme.colorScheme.surfaceContainer
    val containerColor = if (isBlurActive) surfaceContainer.copy(alpha = 0.22f) else surfaceContainer

    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val animationScope = rememberCoroutineScope()
    val tabsCount = items.size

    var tabWidthPx by remember { mutableFloatStateOf(0f) }
    var totalWidthPx by remember { mutableFloatStateOf(0f) }

    val offsetAnimation = remember { Animatable(0f) }
    val rubberBandPx = with(density) { 4.dp.toPx() }
    val panelOffset by remember(rubberBandPx) {
        derivedStateOf {
            if (totalWidthPx == 0f) {
                0f
            } else {
                val fraction = (offsetAnimation.value / totalWidthPx).coerceIn(-1f, 1f)
                rubberBandPx * fraction.sign * EaseOut.transform(abs(fraction))
            }
        }
    }

    var currentIndex by remember { mutableIntStateOf(selectedIndex) }

    class DampedDragHolder {
        var instance: DampedDragAnimation? = null
    }

    val holder = remember { DampedDragHolder() }

    val dampedDrag = remember(animationScope, tabsCount, density, isLtr) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = selectedIndex.toFloat(),
            valueRange = 0f..(tabsCount - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 72f / 52f,
            canDrag = { true },
            onDragStarted = {},
            onDragStopped = {
                val targetIndex = targetValue.roundToInt().coerceIn(0, tabsCount - 1)
                if (currentIndex != targetIndex) {
                    currentIndex = targetIndex
                } else {
                    animateToValue(targetIndex.toFloat())
                }
                animationScope.launch {
                    offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                }
            },
            onDrag = { _, dragAmount ->
                if (tabWidthPx > 0f) {
                    updateValue(
                        (targetValue + dragAmount.x / tabWidthPx * if (isLtr) 1f else -1f)
                            .coerceIn(0f, (tabsCount - 1).toFloat())
    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                }
            }
    ).also { holder.instance = it }
    }

    LaunchedEffect(selectedIndex) {
        if (currentIndex != selectedIndex) currentIndex = selectedIndex
    }
    val onItemClickUpdated by rememberUpdatedState(onItemClick)
    LaunchedEffect(dampedDrag) {
        snapshotFlow { currentIndex }.drop(1).collectLatest { index ->
            dampedDrag.animateToValue(index.toFloat())
            onItemClickUpdated(index)
        }
    }

    val interactiveHighlight = remember(animationScope, isLtr) {
        InteractiveHighlight(
            animationScope = animationScope,
            position = { layerSize, _ ->
                val padPx = with(density) { 4.dp.toPx() }
                Offset(
                    x = if (isLtr) {
                        padPx + (dampedDrag.value + 0.5f) * tabWidthPx + panelOffset
                    } else {
                        layerSize.width - padPx - (dampedDrag.value + 0.5f) * tabWidthPx + panelOffset
                    },
                    y = layerSize.height / 2f
    )
            }
    )
    }

    val baseHighlight = rememberGravityRotatedHighlight(iosIndicatorSpecular, extraDegrees = -45f)
    val pillHighlight = rememberGravityRotatedHighlight(iosIndicatorSpecular, extraDegrees = 90f)

    val navBarBottomPadding = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
    val bottomPaddingValue = if (navBarBottomPadding != 0.dp) 4.dp + navBarBottomPadding else 24.dp
    val padPx = with(density) { 4.dp.toPx() }

    val navigationGestureModifier = Modifier.pointerInput(tabsCount, isLtr) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val downX = down.position.x
            val viewConfig = viewConfiguration

            dampedDrag.press()
            interactiveHighlight.press(down.position)

            var isDragging = false
            var lastX = downX

            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.fastFirstOrNull { it.id == down.id } ?: break

                    if (change.changedToUpIgnoreConsumed()) {
                        change.consume()
                        if (!isDragging) {
                            val touchX = if (isLtr) change.position.x - padPx else (size.width - padPx) - change.position.x
                            if (tabWidthPx > 0f) {
                                val tappedIndex = (touchX / tabWidthPx).toInt().coerceIn(0, tabsCount - 1)
                                if (currentIndex != tappedIndex) {
                                    currentIndex = tappedIndex
                                } else {
                                    dampedDrag.animateToValue(tappedIndex.toFloat())
                                }
                            }
                        } else {
                            val targetIndex = dampedDrag.targetValue.roundToInt().coerceIn(0, tabsCount - 1)
                            if (currentIndex != targetIndex) {
                                currentIndex = targetIndex
                            } else {
                                dampedDrag.animateToValue(targetIndex.toFloat())
                            }
                            animationScope.launch {
                                offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                            }
                        }
                        break
                    }

                    val newX = change.position.x
                    val totalDeltaX = newX - downX
                    if (!isDragging && abs(totalDeltaX) > viewConfig.touchSlop) {
                        isDragging = true
                    }

                    if (isDragging && tabWidthPx > 0f) {
                        change.consume()
                        val dragAmountX = newX - lastX
                        val delta = dragAmountX / tabWidthPx * if (isLtr) 1f else -1f
                        dampedDrag.updateValue(
                            (dampedDrag.targetValue + delta).coerceIn(0f, (tabsCount - 1).toFloat())
    )
                        animationScope.launch {
                            offsetAnimation.snapTo(offsetAnimation.value + dragAmountX)
                        }
                    }
                    lastX = newX
                }
            } finally {
                dampedDrag.release()
                interactiveHighlight.release()
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .padding(bottom = bottomPaddingValue, start = 44.dp, end = 44.dp)
                .fillMaxWidth(),
            contentAlignment = Alignment.CenterStart
    ) {
            // Layer 1: Background Pill Container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .onSizeChanged { coords ->
                        totalWidthPx = coords.width.toFloat()
                        val contentWidthPx = (totalWidthPx - padPx * 2f).coerceAtLeast(0f)
                        tabWidthPx = (contentWidthPx / tabsCount).coerceAtLeast(0f)
                    }
                    .graphicsLayer { translationX = panelOffset }
                    .dropShadow(
                        shape = pillShape,
                        shadow = Shadow(
                            radius = 10.dp,
                            color = Color.Black,
                            alpha = 0.2f
    )
    )
                    .then(
                        if (isBlurActive && backdrop != null) {
                            Modifier.drawBackdrop(
                                backdrop = backdrop,
                                    shape = { pillShape },
                                    effects = {
                                        vibrancy()
                                        blur(12.dp.toPx())
                                        lens(
                                            refractionHeight = 24.dp.toPx(),
                                            refractionAmount = 32.dp.toPx()
    )
                                    },
                                    highlight = { baseHighlight.copy(alpha = 0.75f) },
                                    layerBlock = {
                                        val width = size.width.coerceAtLeast(1f)
                                        val s = floatLerp(1f, 1f + 16.dp.toPx() / width, dampedDrag.pressProgress)
                                        scaleX = s
                                        scaleY = s
                                    },
                                    onDrawSurface = { drawRect(containerColor) }
    )
                        } else {
                            Modifier.background(containerColor, pillShape)
                        }
    )
                    .then(if (isBlurActive) interactiveHighlight.modifier else Modifier)
    )

            // Layer 2: glass chip samples the page. Rest = frosted; press/drag = less blur, more lens + highlight.
            if (tabWidthPx > 0f) {
                val tabWidthDp = with(density) { tabWidthPx.toDp() }
                val indicatorX = if (isLtr) {
                    padPx + dampedDrag.value * tabWidthPx + panelOffset
                } else {
                    totalWidthPx - padPx - tabWidthPx - dampedDrag.value * tabWidthPx + panelOffset
                }
                Box(
                    modifier = Modifier
                        .graphicsLayer { translationX = indicatorX }
                        .then(
                            if (isBlurActive && backdrop != null) {
                                Modifier
                                    .drawBackdrop(
                                        backdrop = backdrop,
                                        shape = { pillShape },
                                        effects = {
                                            val progress = dampedDrag.pressProgress
                                            blur(12.dp.toPx() * (1f - progress))
                                            lens(
                                                refractionHeight = 24.dp.toPx() * progress,
                                                refractionAmount = 32.dp.toPx() * progress,
                                                depthEffect = true,
                                                chromaticAberration = 0.25f
    )
                                        },
                                        highlight = {
                                            pillHighlight.copy(alpha = 0.55f + 0.45f * dampedDrag.pressProgress)
                                        },
                                        layerBlock = {
                                            scaleX = dampedDrag.scaleX
                                            scaleY = dampedDrag.scaleY
                                            val v = dampedDrag.velocity / 10f
                                            scaleX /= 1f - (v * 0.75f).coerceIn(-0.2f, 0.2f)
                                            scaleY *= 1f - (v * 0.25f).coerceIn(-0.2f, 0.2f)
                                        },
                                        onDrawSurface = {
                                            val progress = dampedDrag.pressProgress
                                            drawRect(
                                                color = if (!isDark) {
                                                    Color.Black.copy(alpha = 0.14f - 0.04f * progress)
                                                } else {
                                                    Color.White.copy(alpha = 0.20f - 0.04f * progress)
                                                }
    )
                                        }
    )
                                    .innerShadow(shape = pillShape) {
                                        InnerShadow(
                                            radius = 8.dp * dampedDrag.pressProgress,
                                            color = Color.Black.copy(alpha = 0.15f),
                                            alpha = dampedDrag.pressProgress
    )
                                    }
                            } else {
                                Modifier
                                    .clip(pillShape)
                                    .background(accentColor.copy(alpha = 0.22f), pillShape)
                                    .graphicsLayer {
                                        scaleX = dampedDrag.scaleX
                                        scaleY = dampedDrag.scaleY
                                        val v = dampedDrag.velocity / 10f
                                        scaleX /= 1f - (v * 0.75f).coerceIn(-0.2f, 0.2f)
                                        scaleY *= 1f - (v * 0.25f).coerceIn(-0.2f, 0.2f)
                                    }
                            }
    )
                        .height(44.dp)
                        .width(tabWidthDp)
    )
            }

            // Layer 3: Single Tab Content Row (Icons and Labels rendered once, color & scale smoothly interpolate)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .graphicsLayer { translationX = panelOffset }
                    .then(navigationGestureModifier)
                    .padding(horizontal = 4.dp)
                    .selectableGroup(),
                verticalAlignment = Alignment.CenterVertically
    ) {
                items.forEachIndexed { index, item ->
                    val dist = abs(dampedDrag.value - index)
                    val activeFraction = (1f - dist).coerceIn(0f, 1f)
                    val itemColor = colorLerp(tabContentColor, accentColor, activeFraction)
                    val itemScale = floatLerp(1f, 1.15f, activeFraction * dampedDrag.pressProgress)

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .graphicsLayer {
                                scaleX = itemScale
                                scaleY = itemScale
                            }
                            .semantics {
                                role = Role.Tab
                                selected = index == currentIndex
                                onClick {
                                    if (currentIndex != index) {
                                        currentIndex = index
                                    }
                                    true
                                }
                            },
                        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
                        horizontalAlignment = CenterHorizontally
    ) {
                        BadgedBox(badge = { badge(index)?.invoke() }) {
                            Icon(
                                modifier = Modifier.size(20.dp),
                                imageVector = item.icon,
                                tint = itemColor,
                                contentDescription = null
    )
                        }
                        Text(
                            text = item.label,
                            color = itemColor,
                            fontSize = 10.sp,
                            fontWeight = if (activeFraction > 0.5f) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
    )
                    }
                }
            }
        }
    }
}
