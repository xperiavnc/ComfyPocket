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
    tag: String, onDismiss: () -> Unit, content: @Composable BoxScope.() -> Unit
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
        Box(Modifier.fillMaxSize().background(Color.Black).testTag(tag), content = content)
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
    DanbooruFullscreen("danbooru-image-fullscreen", onDismiss) {
        Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged(zoom::resize)
            .testTag("danbooru-zoom-surface")
            .semantics { stateDescription = "${(zoom.scale * 100).roundToInt()}%" }
            .pointerInput(zoom) {
                detectTapGestures(onTap = { chrome = !chrome }, onDoubleTap = zoom::doubleTap)
            }
            .pointerInput(zoom) {
                detectTransformGestures { centroid, pan, factor, _ -> zoom.transform(centroid, pan, factor) }
            }) {
            DanbooruMediaPreview(post, imageLoader, namespace, Modifier.fillMaxSize(),
                originalOnly = original, decodeSize = 4096,
                imageModifier = Modifier.graphicsLayer {
                    scaleX = zoom.scale; scaleY = zoom.scale
                    translationX = zoom.offset.x; translationY = zoom.offset.y
                })
        }
        if (chrome) {
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
            Text("双指缩放 · 双击放大 / 复位",
                Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(16.dp),
                color = Color.White.copy(alpha = 0.8f), style = MiuixTheme.textStyles.footnote1)
        }
    }
}
