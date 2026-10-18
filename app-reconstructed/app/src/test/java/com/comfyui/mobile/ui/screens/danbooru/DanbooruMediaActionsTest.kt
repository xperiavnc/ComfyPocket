package com.comfyui.mobile.ui.screens.danbooru

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DanbooruMediaActionsTest {
    private class Album(val output: File, val failWrite: Boolean = false) : ContentProvider() {
        var inserted: Uri? = null
        var values: ContentValues? = null
        var published = false
        var deleted = false
        override fun onCreate() = true
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            inserted = uri; this.values = ContentValues(values)
            return Uri.withAppendedPath(uri, "42")
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
            published = values?.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 0
            return 1
        }
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int { deleted = true; return 1 }
        override fun getType(uri: Uri) = "application/octet-stream"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? = null
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (failWrite) throw IOException("fixture album write failure")
            return ParcelFileDescriptor.open(output,
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY)
        }
    }

    private fun album(fail: Boolean = false): Album {
        val app = RuntimeEnvironment.getApplication()
        return Album(File(app.cacheDir, "album-${System.nanoTime()}"), fail).apply {
            attachInfo(app, ProviderInfo().apply { authority = "media" })
            ShadowContentResolver.registerProviderInternal("media", this)
        }
    }

    @Test fun completeVideoIsPublishedToMoviesAsAVideoWithoutChangingItsBytes() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val album = album()
        val file = File(app.cacheDir, "video.mp4").apply { writeBytes(ByteArray(120_001) { (it % 250).toByte() }) }
        val result = saveDanbooruMedia(app, file, 701, "mp4", "video/mp4", video = true)
        assertEquals(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, album.inserted)
        assertEquals("Movies/ComfyPocket", album.values!!.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        assertEquals("video/mp4", album.values!!.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals("danbooru_701.mp4", album.values!!.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
        assertEquals(1, album.values!!.getAsInteger(MediaStore.MediaColumns.IS_PENDING).toInt())
        assertArrayEquals(file.readBytes(), album.output.readBytes())
        assertEquals(Uri.withAppendedPath(album.inserted!!, "42"), result)
        assertTrue(album.published)
        assertFalse(album.deleted)
    }

    @Test fun existingImageSaveStillPublishesToPicturesAndFailedVideoCopyDeletesThePendingRow() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val file = File(app.cacheDir, "image.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val images = album()
        saveDanbooruMedia(app, file, 702, "png", "image/png", video = false)
        assertEquals(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, images.inserted)
        assertEquals("Pictures/ComfyPocket", images.values!!.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        assertArrayEquals(file.readBytes(), images.output.readBytes())
        val failed = album(fail = true)
        try { saveDanbooruMedia(app, file, 703, "mp4", "video/mp4", video = true); fail() }
        catch (_: IOException) { }
        assertTrue(failed.deleted)
        assertFalse(failed.published)
    }
}
