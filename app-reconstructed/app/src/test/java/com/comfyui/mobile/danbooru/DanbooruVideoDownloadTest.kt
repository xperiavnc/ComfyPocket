package com.comfyui.mobile.danbooru

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DanbooruVideoDownloadTest {
    @Test fun originalVideoStreamsThroughTheProxyReportsProgressAndReusesTheCompletedFile() = runBlocking {
        MockWebServer().use { proxy ->
            val bytes = ByteArray(150_000) { (it % 251).toByte() }
            proxy.enqueue(MockResponse().setBody(Buffer().write(bytes)).setHeader("Content-Type", "video/webm"))
            val app = RuntimeEnvironment.getApplication()
            val settings = DanbooruSettings(proxyEnabled = true, proxyAddress = "http://127.0.0.1:${proxy.port}")
            val calls = DanbooruHttp.client(settings, false) { "download-fixture" }
                .newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).build()
            val post = DanbooruPost(601, emptyMap(), extension = "webm",
                sampleUrl = "http://video.fixture/sample.mp4", fileUrl = "http://video.fixture/original.webm")
            val progress = mutableListOf<Pair<Long, Long?>>()
            val scope = "proxy-${System.nanoTime()}"
            try {
                val file = DanbooruVideoDownloads.get(app, post, calls, scope) { done, total -> progress += done to total }
                assertArrayEquals(bytes, file.file.readBytes())
                assertEquals("webm", file.extension)
                assertEquals("video/webm", file.mimeType)
                val request = proxy.takeRequest(2, TimeUnit.SECONDS)!!
                assertTrue(request.requestLine.startsWith("GET http://video.fixture/original.webm "))
                assertEquals("download-fixture", request.getHeader("User-Agent"))
                assertEquals(0L, calls.callTimeoutMillis.toLong())
                assertEquals(30_000, calls.readTimeoutMillis)
                assertEquals(0L to bytes.size.toLong(), progress.first())
                assertEquals(bytes.size.toLong() to bytes.size.toLong(), progress.last())
                assertEquals(file, DanbooruVideoDownloads.get(app, post, calls, scope))
                assertEquals("Saving again must not download again", 1, proxy.requestCount)
            } finally { calls.dispatcher.executorService.shutdown(); calls.connectionPool.evictAll() }
        }
    }

    @Test fun absentOriginalUsesAPlayableVideoWithItsActualExtensionAndUnknownLengthIsSupported() = runBlocking {
        MockWebServer().use { server ->
            val bytes = ByteArray(10_000) { 42 }
            server.enqueue(MockResponse().setChunkedBody(Buffer().write(bytes), 137).setHeader("Content-Type", "video/mp4"))
            val app = RuntimeEnvironment.getApplication()
            val post = DanbooruPost(602, emptyMap(), extension = "webm", sampleUrl = server.url("/sample.mp4").toString())
            val progress = mutableListOf<Pair<Long, Long?>>()
            val file = DanbooruVideoDownloads.get(app, post, OkHttpClient(), "chunked") { done, total -> progress += done to total }
            assertEquals("mp4", file.extension)
            assertEquals("video/mp4", file.mimeType)
            assertArrayEquals(bytes, file.file.readBytes())
            assertNull(progress.first().second)
            assertEquals(bytes.size.toLong(), progress.last().first)
            assertEquals(bytes.size.toLong(), progress.last().second)
        }
    }

    @Test fun failedOrInterruptedDownloadsLeaveNoCompletedFileAndCanBeRetried() = runBlocking {
        MockWebServer().use { server ->
            val bytes = ByteArray(12_000) { 33 }
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)).setHeader("Content-Length", bytes.size + 100)
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END))
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            val app = RuntimeEnvironment.getApplication()
            val post = DanbooruPost(603, emptyMap(), extension = "mp4", fileUrl = server.url("/original.mp4").toString())
            val calls = OkHttpClient()
            for (attempt in 0..1) {
                try { DanbooruVideoDownloads.get(app, post, calls, "failed"); fail("Download must fail") }
                catch (_: DanbooruException) { }
                val files = File(app.cacheDir, "share_cache/danbooru/video").listFiles().orEmpty()
                assertTrue("No partial video should become a completed file", files.isEmpty())
            }
            val completed = DanbooruVideoDownloads.get(app, post, calls, "failed")
            assertArrayEquals(bytes, completed.file.readBytes())
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun cancellingAnInFlightDownloadCancelsTheCallAndCleansItsTemporaryFile() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(200_000) { 44 }))
                .throttleBody(1024, 50, TimeUnit.MILLISECONDS))
            val app = RuntimeEnvironment.getApplication()
            val calls = OkHttpClient()
            val post = DanbooruPost(604, emptyMap(), extension = "mp4", fileUrl = server.url("/slow.mp4").toString())
            val work = async { DanbooruVideoDownloads.get(app, post, calls, "cancelled") }
            var received = false
            repeat(100) {
                if (server.requestCount > 0) received = true
                if (!received) delay(20)
            }
            assertTrue(received)
            work.cancel()
            work.join()
            val folder = File(app.cacheDir, "share_cache/danbooru/video")
            repeat(100) { if (folder.listFiles().orEmpty().isNotEmpty()) delay(20) }
            assertTrue(work.isCancelled)
            assertTrue(folder.listFiles().orEmpty().isEmpty())
            assertEquals(0, calls.dispatcher.runningCallsCount())
        }
    }
}
