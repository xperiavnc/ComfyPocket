package com.comfyui.mobile.danbooru

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DanbooruVideoNetworkTest {
    @Test fun media3RangeReadsUseTheConfiguredIndependentHttpProxy() {
        MockWebServer().use { proxy ->
            val bytes = "fixture-video-range".toByteArray()
            proxy.enqueue(MockResponse().setResponseCode(206)
                .addHeader("Content-Type", "video/mp4")
                .addHeader("Content-Range", "bytes 2-9/${bytes.size}")
                .setBody(Buffer().write(bytes.copyOfRange(2, 10))))
            val calls = DanbooruHttp.client(
                DanbooruSettings("fixture-user", "fixture-key", proxyEnabled = true,
                    proxyAddress = "http://127.0.0.1:${proxy.port}"), api = false
            ) { "ComfyPocket/fixture" }
            val source = OkHttpDataSource.Factory(calls).createDataSource()
            try {
                assertEquals(8L, source.open(DataSpec.Builder()
                    .setUri(Uri.parse("http://cdn.fixture.invalid/video.mp4"))
                    .setPosition(2).setLength(8).build()))
                val actual = ByteArray(8)
                var offset = 0
                while (offset < actual.size) offset += source.read(actual, offset, actual.size - offset)
                assertArrayEquals(bytes.copyOfRange(2, 10), actual)
                val request = proxy.takeRequest()
                assertEquals("bytes=2-9", request.getHeader("Range"))
                assertEquals("ComfyPocket/fixture", request.getHeader("User-Agent"))
                assertNull(request.getHeader("Authorization"))
            } finally {
                source.close()
                calls.dispatcher.executorService.shutdown()
                calls.connectionPool.evictAll()
            }
        }
    }
    @Test fun runtimeVideoCallsDoNotUseTheImageWholeCallTimeout() {
        val runtime = DanbooruRuntime.get(RuntimeEnvironment.getApplication())
        val request = Request.Builder().url("https://cdn.fixture.invalid/video.mp4").build()
        assertEquals(0L, runtime.videoCalls.newCall(request).timeout().timeoutNanos())
        assertEquals(60_000_000_000L, runtime.mediaCalls.newCall(request).timeout().timeoutNanos())
    }
}
