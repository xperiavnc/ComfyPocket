package com.comfyui.mobile.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.disk.DiskCache
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.comfyui.mobile.model.MediaOutput
import com.comfyui.mobile.model.MediaType
import com.comfyui.mobile.ui.MediaPreviewHost
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import java.util.concurrent.ConcurrentHashMap

// AndroidX FileProvider assumes "/" as the host file separator. Keep its URI boundary
// as a fixture on Windows; all source bytes, Coil cache I/O and HTTP requests stay real.
@Implements(FileProvider::class, isInAndroidSdk = false)
class ShareFileProviderShadow {
    companion object {
        val files = ConcurrentHashMap<Uri, File>()
        @JvmStatic
        @Implementation
        fun getUriForFile(context: Context, authority: String, file: File): Uri =
            Uri.Builder().scheme("content").authority(authority).appendPath(file.name).build()
                .also { files[it] = file }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ShareFileProviderShadow::class], instrumentedPackages = ["androidx.core.content"])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@OptIn(ExperimentalCoroutinesApi::class)
class MediaCacheReuseTest {
    private lateinit var app: Context
    private lateinit var server: MockWebServer
    private lateinit var loader: ImageLoader
    private lateinit var cacheDir: File
    private lateinit var diskCache: DiskCache
    private lateinit var original: ByteArray
    private val client = OkHttpClient()

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ShareFileProviderShadow.files.clear()
        File(app.cacheDir, "share_cache").deleteRecursively()
        cacheDir = File(app.cacheDir, "test-originals-${UUID.randomUUID()}")
        diskCache = DiskCache.Builder().directory(cacheDir).maxSizeBytes(8L * 1024 * 1024).build()
        server = MockWebServer().apply { start() }
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        original = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        bitmap.recycle()
        loader = newLoader()
        Coil.setImageLoader(loader)
    }

    private fun newLoader(): ImageLoader = ImageLoader.Builder(app)
        .callFactory(client)
        .diskCache(diskCache)
        .respectCacheHeaders(false)
        .build()

    @After fun close() {
        if (::loader.isInitialized) loader.shutdown()
        Dispatchers.resetMain()
        if (::server.isInitialized) server.shutdown()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun enqueueImage() {
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png")
            .setHeader("Cache-Control", "no-store").setBody(Buffer().write(original)))
    }

    private fun sharedBytes(uri: Uri) = ShareFileProviderShadow.files.getValue(uri).readBytes()

    private fun media(filename: String = "image.png") =
        MediaOutput(filename, "agent", "output", MediaType.IMAGE, promptId = "generation")

    private fun host(url: String) = object : MediaPreviewHost {
        override fun previewUrl(media: MediaOutput) = url
        override fun saveMediaToAlbum(context: Context, media: MediaOutput, silent: Boolean, onComplete: ((Boolean) -> Unit)?) = Unit
        override fun deleteLocalMedia(context: Context, mediaList: List<MediaOutput>) = Unit
        override fun deleteMedia(mediaList: List<MediaOutput>) = Unit
        override suspend fun fetchTextContent(media: MediaOutput) = ""
        override fun showToast(msg: String) = Unit
    }

    private suspend fun preview(url: String, size: Int = 8): SuccessResult {
        val result = loader.execute(ImageRequest.Builder(app).data(url).size(size).allowHardware(false).build())
        assertTrue("Preview failed: $result", result is SuccessResult)
        return result as SuccessResult
    }

    @Test fun previewShareGalleryReopenAndLoaderRestartReuseOneOriginalDownload() = runBlocking {
        val url = server.url("/view?filename=image.png&subfolder=agent&type=output").toString()
        repeat(6) { enqueueImage() }
        assertEquals(DataSource.NETWORK, preview(url).dataSource)
        val first = server.requestCount
        val share = prepareShareUri(app, host(url), media(), client)
        assertNotNull(share)
        assertArrayEquals(original, sharedBytes(share!!))
        val afterShare = server.requestCount
        val repeated = prepareShareUri(app, host(url), media(), client)
        loader.memoryCache?.clear()
        assertEquals(DataSource.DISK, preview(url, 32).dataSource)
        val afterGallery = server.requestCount
        loader.shutdown()
        loader = newLoader()
        Coil.setImageLoader(loader)
        assertEquals(DataSource.DISK, preview(url).dataSource)
        println("imageRequests: preview=$first, share=$afterShare, gallery=$afterGallery, restart=${server.requestCount}")
        assertEquals(share, repeated)
        assertEquals(1, server.requestCount)
    }

    @Test fun sharingBeforePreviewDownloadsOnceAndWarmsGalleryCache() = runBlocking {
        val url = server.url("/view?filename=share-first.png").toString()
        repeat(3) { enqueueImage() }
        val uri = prepareShareUri(app, host(url), media("share-first.png"), client)
        assertNotNull(uri)
        assertArrayEquals(original, sharedBytes(uri!!))
        assertEquals(DataSource.DISK, preview(url).dataSource)
        prepareShareUri(app, host(url), media("share-first.png"), client)
        assertEquals(1, server.requestCount)
    }

    @Test fun concurrentSharesOfOneImageCoalesceIntoOneDownload() = runBlocking {
        val url = server.url("/same.png").toString()
        repeat(3) { enqueueImage() }
        val uris = listOf(async { prepareShareUri(app, host(url), media(), client) },
            async { prepareShareUri(app, host(url), media(), client) }).awaitAll()
        assertTrue(uris.all { it != null })
        assertEquals(uris[0], uris[1])
        assertEquals(1, server.requestCount)
    }

    @Test fun sameFilenameAtDifferentUrlsKeepsDistinctOriginals() = runBlocking {
        val firstUrl = server.url("/one/view?filename=image.png").toString()
        val secondUrl = server.url("/two/view?filename=image.png").toString()
        enqueueImage()
        server.enqueue(MockResponse().setBody("second original"))
        val first = prepareShareUri(app, host(firstUrl), media(), client)!!
        val second = prepareShareUri(app, host(secondUrl), media(), client)!!
        assertNotEquals(first, second)
        assertArrayEquals(original, sharedBytes(first))
        assertEquals("second original", String(sharedBytes(second)))
        assertEquals(2, server.requestCount)
    }

    @Test fun downloadedLocalOutputSharesWithNoServerRequest() = runBlocking {
        val file = File(app.filesDir, "local-output.png").apply { writeBytes(original) }
        val media = media().copy(localUri = android.net.Uri.fromFile(file).toString())
        val uri = prepareShareUri(app, host(server.url("/offline.png").toString()), media, client)
        assertNotNull(uri)
        assertArrayEquals(original, sharedBytes(uri!!))
        assertEquals(0, server.requestCount)
    }

    @Test fun failedDownloadLeavesNoPartialShareAndRetrySucceeds() = runBlocking {
        val url = server.url("/retry.png").toString()
        server.enqueue(MockResponse().setResponseCode(503).setBody("try later"))
        assertNull(prepareShareUri(app, host(url), media(), client))
        assertTrue(File(app.cacheDir, "share_cache").listFiles().orEmpty().isEmpty())
        enqueueImage()
        assertNotNull(prepareShareUri(app, host(url), media(), client))
        assertEquals(2, server.requestCount)
    }
}
