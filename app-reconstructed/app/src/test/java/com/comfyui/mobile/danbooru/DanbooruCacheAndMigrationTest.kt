package com.comfyui.mobile.danbooru

import android.content.Context
import android.graphics.Bitmap
import android.view.ContextThemeWrapper
import coil.ImageLoader
import coil.disk.DiskCache
import com.comfyui.mobile.data.MediaFileCache
import com.comfyui.mobile.ui.screens.gallery.GalleryHomeSource
import com.comfyui.mobile.ui.screens.gallery.GalleryHomeSourceStore
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
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
@Config(sdk = [35])
class DanbooruCacheAndMigrationTest {
    @Test fun migratesBothLegacySourcesAndPersistsOnlineWithoutPassingItToTheLegacySetter() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("source-test", Context.MODE_PRIVATE)
        for ((old, expected) in listOf("server" to GalleryHomeSource.SERVER, "local" to GalleryHomeSource.LOCAL)) {
            prefs.edit().clear().putString("gallery_source", old).commit()
            val store = GalleryHomeSourceStore(prefs)
            assertEquals(expected, store.load())
            store.save(GalleryHomeSource.DANBOORU)
            assertEquals(GalleryHomeSource.DANBOORU, store.load())
            assertEquals(old, prefs.getString("gallery_source", null))
            assertNull(GalleryHomeSource.DANBOORU.legacy)
        }
    }
    @Test fun unknownOldSourceDefaultsToServerWithoutLosingTheOriginalValue() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("bad-source-test", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("gallery_source", "unexpected").commit()
        assertEquals(GalleryHomeSource.SERVER, GalleryHomeSourceStore(prefs).load())
        assertEquals("unexpected", prefs.getString("gallery_source", null))
    }
    @Test fun runtimeAndLoaderHaveApplicationLifetimeAcrossScreenContexts() {
        val app = RuntimeEnvironment.getApplication()
        val first = DanbooruRuntime.get(app)
        val another = DanbooruRuntime.get(ContextThemeWrapper(app, android.R.style.Theme_Material))
        assertSame(first, another)
        assertSame(first.imageLoader, another.imageLoader)
        assertNotNull(first.imageLoader.diskCache)
    }
    @Test fun concurrentShareSaveAndReopenedOriginalReuseEncodedBytesWithoutAnotherDownload() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val folder = File(app.cacheDir, "danbooru-test-${System.nanoTime()}")
        val loader = ImageLoader.Builder(app).diskCache {
            DiskCache.Builder().directory(File(folder, "images")).maxSizeBytes(4L * 1024 * 1024).build()
        }.build()
        try {
            MockWebServer().use { server ->
                val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                val bytes = ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
                bitmap.recycle()
                server.enqueue(MockResponse().addHeader("Content-Type", "image/png").setBody(Buffer().write(bytes)))
                val calls = OkHttpClient()
                val url = server.url("/original.png").toString()
                val key = "danbooru:fixture:1:version:original"
                suspend fun original(context: Context) = MediaFileCache.originalForShare(context, url, "fixture.png", calls,
                    loader, key, folder.name + "/share")
                val files = listOf(async { original(app) }, async { original(app) }).awaitAll()
                assertEquals(1, server.requestCount)
                assertArrayEquals(bytes, files[0].readBytes())
                assertEquals(files[0], files[1])
                // A lost temporary share file is recovered from the encoded Coil original, not a bitmap.
                assertTrue(files[0].delete())
                val reopened = original(ContextThemeWrapper(app, android.R.style.Theme_Material))
                assertArrayEquals(bytes, reopened.readBytes())
                assertEquals(1, server.requestCount)
            }
        } finally {
            loader.shutdown()
            folder.deleteRecursively()
        }
    }
    @Test fun settingsValidatePairAndProxyWithoutLeakingTheirValuesInDiagnostics() {
        for (config in listOf(DanbooruSettings(username = "user"), DanbooruSettings(apiKey = "key"),
            DanbooruSettings(proxyEnabled = true, proxyAddress = "http://proxy.invalid"),
            DanbooruSettings(proxyEnabled = true, proxyAddress = "https://proxy.invalid:8080"),
            DanbooruSettings(proxyEnabled = true, proxyAddress = "http://user:pass@proxy.invalid:8080"))) {
            try { config.validated(); fail() } catch (_: IllegalArgumentException) { }
        }
        val config = DanbooruSettings("user", "private-key", true, "http://proxy.invalid:8080", "u", "private-password")
        assertEquals(8080, config.validated().proxyUrl()?.port)
        assertFalse(config.toString().contains("private"))
        assertNotEquals(config.namespace(), config.copy(apiKey = "other").namespace())
        assertNotEquals(config.namespace(), config.copy(proxyAddress = "http://other.invalid:8080").namespace())
    }
    @Test fun mediaKeysSeparateScopeVersionAndRepresentation() {
        val post = DanbooruPost(1, emptyMap(), md5 = "version")
        val original = post.mediaKey("account1", "original", "https://cdn.donmai.us/a.png")
        assertNotEquals(original, post.mediaKey("account1", "sample", "https://cdn.donmai.us/a.png"))
        assertNotEquals(original, post.mediaKey("account2", "original", "https://cdn.donmai.us/a.png"))
        assertNotEquals(original, post.copy(md5 = "changed").mediaKey("account1", "original", "https://cdn.donmai.us/a.png"))
    }
}
