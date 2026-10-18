package com.comfyui.mobile.ui.screens.danbooru

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.comfyui.mobile.danbooru.*
import okhttp3.Call
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** One user-started player; source fallback and cleanup are shared by the UI and tests. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class DanbooruVideoSession(
    private val player: Player,
    private val sources: List<DanbooruMediaSource>,
    private val onError: (String?) -> Unit
) : Player.Listener {
    private var index = 0
    private var released = false
    private var foreground = true
    init { player.addListener(this) }

    fun start() = load(autoplay = true)
    private fun load(autoplay: Boolean) {
        if (released) return
        val source = sources.getOrNull(index)
        if (source == null) { onError("此帖子暂未提供可播放的视频地址"); return }
        onError(null)
        player.setMediaItem(MediaItem.Builder().setUri(source.url).setMimeType(source.videoMimeType()).build())
        player.prepare()
        if (foreground && autoplay) player.play() else player.pause()
    }
    fun retry() { if (!released) { index = 0; start() } }
    fun pause() { if (!released) player.pause() }
    fun setForeground(value: Boolean) {
        foreground = value
        if (!value) pause()
    }
    override fun onPlayerError(error: PlaybackException) {
        if (released) return
        if (index + 1 < sources.size) {
            val autoplay = player.playWhenReady
            index++
            load(autoplay)
        } else {
            pause()
            val http = generateSequence(error.cause) { it.cause }
                .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
            onError(when {
                http != null -> DanbooruHttp.statusMessage(http.responseCode)
                error.errorCode in 3000..4999 -> "设备暂未能解析或解码此视频，可重试或打开原帖"
                else -> "视频读取失败，请检查画廊网络或代理后重试"
            })
        }
    }
    fun release() {
        if (!released) {
            released = true
            player.removeListener(this)
            player.release()
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun danbooruVideoPlayer(context: Context, calls: Call.Factory): ExoPlayer {
    val http = OkHttpDataSource.Factory(calls)
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
        .setAudioAttributes(AudioAttributes.DEFAULT, true)
        .setHandleAudioBecomingNoisy(true)
        .build()
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun DanbooruVideoPlayer(
    post: DanbooruPost, calls: Call.Factory, namespace: String,
    fullscreen: Boolean, onFullscreenChange: (Boolean) -> Unit, modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val sources = remember(post) { post.playbackCandidates() }
    key(post.id, post.md5, namespace, sources) {
        var error by remember { mutableStateOf<String?>(null) }
        val changeFullscreen by rememberUpdatedState(onFullscreenChange)
        val player = remember(context, calls) { danbooruVideoPlayer(context, calls) }
        val session = remember(player, sources) { DanbooruVideoSession(player, sources) { error = it } }
        val view = remember(player) {
            PlayerView(context).apply {
                useController = true
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                setFullscreenButtonClickListener { changeFullscreen(it) }
                tag = "danbooru-inline-player"
            }
        }
        val fullView = remember(player) {
            PlayerView(context).apply {
                useController = true
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                setFullscreenButtonClickListener { changeFullscreen(it) }
                tag = "danbooru-fullscreen-player"
            }
        }
        DisposableEffect(player, fullscreen) {
            // Move only the render target: keep the same session, position, source and pause state.
            PlayerView.switchTargetView(player, if (fullscreen) view else fullView, if (fullscreen) fullView else view)
            view.setFullscreenButtonState(fullscreen)
            fullView.setFullscreenButtonState(fullscreen)
            onDispose { }
        }
        DisposableEffect(session, lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                // Includes outgoing navigation destinations: never keep audio in the background.
                if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) session.setForeground(false)
                if (event == Lifecycle.Event.ON_RESUME) session.setForeground(true)
            }
            lifecycle.addObserver(observer)
            session.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            session.start()
            onDispose {
                lifecycle.removeObserver(observer)
                view.player = null
                fullView.player = null
                session.release()
            }
        }
        Box(modifier.testTag("danbooru-video-player"), contentAlignment = Alignment.Center) {
            AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
            if (error != null) {
                Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f)).padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error!!)
                    DanbooruSmallAction("重试视频", primary = true) { session.retry() }
                }
            }
        }
        if (fullscreen) DanbooruFullscreen("danbooru-video-fullscreen", { changeFullscreen(false) }) {
            AndroidView(factory = { fullView }, modifier = Modifier.fillMaxSize().safeDrawingPadding())
            Row(Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(8.dp)) {
                FullscreenClose { changeFullscreen(false) }
            }
            if (error != null) {
                Column(Modifier.align(Alignment.BottomCenter).safeDrawingPadding()
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f)).padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error!!)
                    DanbooruSmallAction("重试视频", primary = true) { session.retry() }
                }
            }
        }
    }
}
