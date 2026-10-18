package com.comfyui.mobile.ui.screens.danbooru

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.comfyui.mobile.danbooru.DanbooruMediaSource
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DanbooruVideoSessionTest {
    private class RecordingPlayer {
        val events = mutableListOf<String>()
        var playWhenReady = false
        var listener: Player.Listener? = null
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "addListener" -> { listener = args!![0] as Player.Listener; null }
                "removeListener" -> { listener = null; null }
                "setMediaItem" -> {
                    events += "load:${(args!![0] as MediaItem).localConfiguration!!.uri}"
                    null
                }
                "prepare", "release" -> { events += method.name; null }
                "play" -> { events += "play"; playWhenReady = true; null }
                "pause" -> { events += "pause"; playWhenReady = false; null }
                "getPlayWhenReady" -> playWhenReady
                "toString" -> "RecordingPlayer"
                else -> throw AssertionError("Unexpected player API: ${method.name}")
            }
        } as Player
        fun fail() = listener!!.onPlayerError(
            PlaybackException("fixture", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
    }
    private val sources = listOf(
        DanbooruMediaSource("https://cdn.invalid/sample.mp4", "video-sample", "mp4"),
        DanbooruMediaSource("https://cdn.invalid/original.webm", "video-original", "webm"))

    @Test fun userStartPreparesAndPlaysThenAnErrorTriesTheNextSource() {
        val recording = RecordingPlayer()
        val errors = mutableListOf<String?>()
        val session = DanbooruVideoSession(recording.player, sources) { errors += it }
        session.start()
        assertEquals(listOf("load:${sources[0].url}", "prepare", "play"), recording.events)
        recording.fail()
        assertEquals(listOf("load:${sources[1].url}", "prepare", "play"), recording.events.takeLast(3))
        recording.fail()
        assertNotNull(errors.last())
        assertFalse(recording.playWhenReady)
        session.retry()
        assertEquals("load:${sources[0].url}", recording.events.takeLast(3).first())
        session.release()
    }
    @Test fun backgroundAndOutgoingDestinationNeverRestartAudioDuringFallback() {
        val recording = RecordingPlayer()
        val session = DanbooruVideoSession(recording.player, sources) {}
        session.start()
        session.setForeground(false)
        recording.events.clear()
        recording.fail()
        assertEquals(listOf("load:${sources[1].url}", "prepare", "pause"), recording.events)
        session.setForeground(true)
        assertFalse("Returning to the app must not silently autoplay", recording.playWhenReady)
        session.release()
    }
    @Test fun userPausedPlaybackStaysPausedWhenTheSourceChanges() {
        val recording = RecordingPlayer()
        val session = DanbooruVideoSession(recording.player, sources) {}
        session.start()
        recording.player.pause()
        recording.fail()
        assertFalse(recording.playWhenReady)
        session.release()
    }
    @Test fun notYetResumedScreenPreparesWithoutAutoplayAndReleaseIsIdempotent() {
        val recording = RecordingPlayer()
        val session = DanbooruVideoSession(recording.player, sources) {}
        session.setForeground(false)
        session.start()
        assertFalse(recording.events.contains("play"))
        session.release()
        session.release()
        assertEquals(1, recording.events.count { it == "release" })
        val snapshot = recording.events.toList()
        session.start()
        session.retry()
        session.pause()
        assertEquals(snapshot, recording.events)
    }
    @Test fun missingMediaAddressHasAUsefulMessageWithoutPreparingAPlayer() {
        val recording = RecordingPlayer()
        var error: String? = null
        val session = DanbooruVideoSession(recording.player, emptyList()) { error = it }
        session.start()
        assertNotNull(error)
        assertTrue(recording.events.isEmpty())
        session.release()
    }
}
