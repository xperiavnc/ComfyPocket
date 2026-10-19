package com.comfyui.mobile.taglib

import android.database.sqlite.SQLiteDatabase
import com.google.gson.Gson
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalTagLibraryStoreTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private fun fixture(revision: String = "fixture-v1", badHash: Boolean = false, badCount: Boolean = false): ByteArray {
        val file = File(app.cacheDir, "$revision.db")
        file.delete()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE entries(id INTEGER PRIMARY KEY,name TEXT,zh TEXT,category TEXT,section TEXT,kind TEXT,trigger TEXT,detail TEXT,post_count INTEGER)")
            db.execSQL("CREATE TABLE lookup(term TEXT,entry_id INTEGER)")
            db.execSQL("INSERT INTO entries VALUES(1,'blue hair','蓝发','hair','','tag','','',42)")
            db.execSQL("INSERT INTO entries VALUES(2,'hatsune_miku','初音未来','character_names','','character','hatsune_miku','',42)")
            db.execSQL("INSERT INTO lookup VALUES('蓝发',1),('blue hair',1),('初音未来',2)")
        }
        val bytes = file.readBytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val info = TagLibraryManifest(2, revision, mapOf("tags" to if (badCount) 2 else 1, "characters" to 1),
            if (badCount) 3 else 2, 0, bytes.size.toLong(), if (badHash) "0".repeat(64) else hash)
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, data) in listOf("manifest.json" to Gson().toJson(info).toByteArray(), "catalogue-v1.db" to bytes)) {
                zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    @Test fun freshInstallHasNoCatalogueAndPackSurvivesNewInstancesUntilRemoved() = runBlocking {
        val library = LocalTagLibrary(app)
        library.store.refresh()
        assertNull(library.store.state.value.installed)
        assertFalse(library.forRequest("蓝发").available)
        assertTrue(library.search("蓝发").isEmpty())
        assertFalse(app.assets.list("")!!.contains("tag-library"))
        library.store.install { ByteArrayInputStream(fixture()) }
        assertEquals("蓝发", library.search("blue_hair").single().translation)
        assertEquals("hatsune_miku", library.search("初音未来", "character").single().trigger)
        val reopened = LocalTagLibrary(app)
        reopened.store.refresh()
        assertEquals(1 to 1, reopened.counts)
        reopened.store.remove()
        reopened.store.refresh()
        assertNull(reopened.store.state.value.installed)
        assertTrue(reopened.search("蓝发").isEmpty())
        assertTrue(File(app.noBackupFilesDir, "tag-library").listFiles()!!.none { it.extension == "db" })
    }

    @Test fun failedUpdateOrCancellationKeepsTheInstalledPackAndCleansTemporaryFiles() = runBlocking {
        val library = LocalTagLibrary(app)
        library.store.install { ByteArrayInputStream(fixture()) }
        for (pack in listOf(fixture("bad-hash", badHash = true), fixture("bad-count", badCount = true), byteArrayOf(1, 2))) {
            assertTrue(runCatching { library.store.install { ByteArrayInputStream(pack) } }.isFailure)
            assertEquals("fixture-v1", library.store.state.value.installed!!.revision)
            assertEquals("blue hair", library.search("蓝发").single().name)
            assertFalse(library.store.state.value.busy)
        }
        assertTrue(runCatching { library.store.install { throw CancellationException("cancelled") } }.exceptionOrNull() is CancellationException)
        assertEquals("fixture-v1", library.store.state.value.installed!!.revision)
        assertFalse(app.cacheDir.listFiles()!!.any { it.name.startsWith("tag-import-") })
        library.store.install { ByteArrayInputStream(fixture("fixture-v2")) }
        assertEquals("fixture-v2", library.store.state.value.installed!!.revision)
        assertEquals(1, File(app.noBackupFilesDir, "tag-library").listFiles()!!.count { it.extension == "db" })
    }

    @Test fun existingLegacyDatabaseMigratesAndDeliberateRemovalStaysRemoved() = runBlocking {
        val source = File(TagLibraryTestData.source, "catalogue-v1.db")
        val info = Gson().fromJson(File(TagLibraryTestData.source, "manifest.json").readText(), TagLibraryManifest::class.java)
        val dir = File(app.noBackupFilesDir, "tag-library").apply { mkdirs() }
        source.copyTo(File(dir, "catalogue-${info.sha256.take(16)}.db"), overwrite = true)
        val library = LocalTagLibrary(app)
        library.store.refresh()
        assertEquals(info.revision, library.revision)
        assertTrue(File(dir, "active.json").exists())
        assertEquals("blue hair", library.search("蓝发").first().name)
        library.store.remove()
        library.store.refresh()
        assertNull(library.store.state.value.installed)
    }

    @Test fun completeRepositoryPackImportsAllChineseAndCharacterData() = runBlocking {
        val archive = File(app.cacheDir, "full-pack.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            TagLibraryTestData.source.walkTopDown().filter { it.isFile }.forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(TagLibraryTestData.source).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        val library = LocalTagLibrary(app)
        library.store.install { archive.inputStream() }
        assertEquals(141007 to 101952, library.counts)
        assertEquals(240851, library.translatedCount)
        assertEquals("blue hair", library.search("蓝发").first().name)
        assertEquals("hatsune_miku", library.search("初音未来", "character").first().name)
        assertTrue(library.forRequest("机械魔法少女，超长辫子").entries.isNotEmpty())
        assertTrue(File(app.noBackupFilesDir, "tag-library").listFiles()!!.none { it.name == "toolbox" })
        println("FULL_OPTIONAL_PACK_INSTALL: ${library.counts}, revision=${library.revision}")
    }
}
