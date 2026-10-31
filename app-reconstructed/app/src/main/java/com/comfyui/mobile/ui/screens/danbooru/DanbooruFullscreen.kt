package com.comfyui.mobile.ui.screens.danbooru

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.ImageLoader
import com.comfyui.mobile.danbooru.DanbooruPost
import kotlin.math.max
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** A separate window owns Back and covers both navigation styles. The Activity's bars are untouched. */
@Composable
internal fun DanbooruFullscreen(
    tag: String, onDismiss: () -> Unit, backdropAlpha: () -> Float = { 1f },
    content: @Composable BoxScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
        dismissOnBackPress = true, dismissOnClickOutside = false
    )) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            val bars = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
            bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars?.hide(WindowInsetsCompat.Type.systemBars())
            onDispose { /* Only the dismissed dialog window was changed, never the Activity window. */ }
        }
        Box(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black.copy(alpha = backdropAlpha())) }
            .testTag(tag), content = content)
    }
}

@Composable
internal fun FullscreenClose(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp),
        backgroundColor = Color.Black.copy(alpha = 0.5f)) {
        Icon(MiuixIcons.Close, contentDescription = "退出全屏", tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

@Stable
internal class DanbooruZoomState(private val aspectRatio: Float) {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    private var viewport = IntSize.Zero
    fun resize(size: IntSize) {
        viewport = size
        offset = clamp(offset)
    }
    fun reset() { scale = 1f; offset = Offset.Zero }
    fun doubleTap(at: Offset) {
        if (scale > 1.05f) reset() else transform(at, Offset.Zero, 2.5f)
    }
    fun transform(centroid: Offset, pan: Offset, zoom: Float) {
        if (!zoom.isFinite() || zoom <= 0 || viewport.width <= 0 || viewport.height <= 0) return
        val next = (scale * zoom).coerceIn(1f, 5f)
        val ratio = next / scale
        val pivot = centroid - Offset(viewport.width / 2f, viewport.height / 2f)
        scale = next
        offset = if (next <= 1.001f) Offset.Zero else clamp((offset - pivot) * ratio + pivot + pan)
    }
    private fun clamp(candidate: Offset): Offset {
        val width = viewport.width.toFloat()
        val height = viewport.height.toFloat()
        val aspect = aspectRatio.takeIf { it.isFinite() && it > 0 } ?: (width / height).takeIf { it.isFinite() } ?: 1f
        // Bound the fitted picture, not its letterboxed container.
        val fittedWidth = minOf(width, height * aspect)
        val fittedHeight = minOf(height, width / aspect)
        val x = max(0f, (fittedWidth * scale - width) / 2)
        val y = max(0f, (fittedHeight * scale - height) / 2)
        return Offset(candidate.x.coerceIn(-x, x), candidate.y.coerceIn(-y, y))
    }
}

@Composable
internal fun DanbooruImageFullscreen(
    post: DanbooruPost, imageLoader: ImageLoader, namespace: String,
    initiallyOriginal: Boolean, onDismiss: () -> Unit
) {
    var original by rememberSaveable(post.id, namespace) { mutableStateOf(initiallyOriginal) }
    var chrome by remember { mutableStateOf(true) }
    val zoom = remember(post.id) {
        DanbooruZoomState(if ((post.width ?: 0) > 0 && (post.height ?: 0) > 0)
            post.width!!.toFloat() / post.height!! else 0f)
    }
    // Vertical swipe at 100% closes; the picture follows the finger and the black fades.
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val dismissPx = with(density) { 120.dp.toPx() }
    val fadePx = with(density) { 360.dp.toPx() }
    val dragFraction = { (kotlin.math.abs(drag.value) / fadePx).coerceIn(0f, 1f) }
    DanbooruFullscreen("danbooru-image-fullscreen", onDismiss, backdropAlpha = { 1f - dragFraction() * 0.85f }) {
        Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged(zoom::resize)
            .testTag("danbooru-zoom-surface")
            .semantics { stateDescription = "${(zoom.scale * 100).roundToInt()}%" }
            .pointerInput(zoom) {
                detectTapGestures(onTap = { chrome = !chrome }, onDoubleTap = zoom::doubleTap)
            }
            .pointerInput(zoom) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    // 0 = undecided, 1 = pinch/pan, 2 = dismiss drag.
                    var mode = 0
                    var slop = Offset.Zero
                    var total = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) break
                        if (mode == 0) {
                            if (pressed > 1 || zoom.scale > 1.05f) mode = 1
                            else {
                                slop += event.calculatePan()
                                if (slop.getDistance() > viewConfiguration.touchSlop)
                                    mode = if (kotlin.math.abs(slop.y) > kotlin.math.abs(slop.x) * 1.2f) 2 else 1
                            }
                        }
                        if (mode == 2 && pressed > 1) {
                            // A second finger turns the swipe back into a pinch.
                            mode = 1
                            scope.launch { drag.animateTo(0f) }
                        }
                        if (mode == 1) {
                            val factor = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (factor != 1f || pan != Offset.Zero) {
                                zoom.transform(event.calculateCentroid(useCurrent = false), pan, factor)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } else if (mode == 2) {
                            total += event.calculatePan().y
                            val now = total
                            scope.launch { drag.snapTo(now) }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    }
                    if (mode == 2) {
                        if (kotlin.math.abs(total) > dismissPx) onDismiss()
                        else scope.launch { drag.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = 500f)) }
                    }
                }
            }) {
            DanbooruMediaPreview(post, imageLoader, namespace, Modifier.fillMaxSize(),
                originalOnly = original, decodeSize = 4096,
                imageModifier = Modifier.graphicsLayer {
                    val shrink = 1f - dragFraction() * 0.12f
                    scaleX = zoom.scale * shrink; scaleY = zoom.scale * shrink
                    translationX = zoom.offset.x; translationY = zoom.offset.y + drag.value
                })
        }
        AnimatedVisibility(chrome && drag.value == 0f, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize()) {
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().safeDrawingPadding().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FullscreenClose(onDismiss)
                Spacer(Modifier.weight(1f))
                DanbooruSmallAction("复位") { zoom.reset() }
                if (post.fileUrl != null) {
                    DanbooruSmallAction(if (original) "显示预览" else "查看原图") {
                        original = !original
                        zoom.reset()
                    }
                }
            }
            Text("双指缩放 · 双击放大 · 上下滑动关闭",
                Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(16.dp),
                color = Color.White.copy(alpha = 0.7f), style = MiuixTheme.textStyles.footnote1)
        }
        }
    }
}
