package com.comfyui.mobile.taglib

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.AtomicFile
import com.google.gson.Gson
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class TagLibraryManifest(
    val schema: Int = 0,
    val revision: String = "",
    val counts: Map<String, Int> = emptyMap(),
    val translatedEntries: Int = 0,
    val untranslatedEntries: Int = 0,
    val bytes: Long = 0,
    val sha256: String = ""
) {
    val tags: Int get() = counts["tags"] ?: 0
    val characters: Int get() = counts["characters"] ?: 0

    internal fun validated(): TagLibraryManifest = apply {
        require(schema == 2 && revision.isNotBlank() && revision.length <= 160) { "词库版本不受支持" }
        require(bytes in 1..MAX_DATABASE_BYTES && sha256.matches(Regex("[a-f0-9]{64}"))) { "词库清单格式有误" }
        require(tags > 0 && characters >= 0 && tags.toLong() + characters <= 2_000_000) { "词库条目数有误" }
        require(translatedEntries >= 0 && untranslatedEntries >= 0 &&
            translatedEntries.toLong() + untranslatedEntries == tags.toLong() + characters) { "词库翻译统计有误" }
    }

    companion object {
        internal const val MAX_DATABASE_BYTES = 256L * 1024 * 1024
    }
}

data class LocalTagLibraryState(
    val installed: TagLibraryManifest? = null,
    val busy: Boolean = false,
    val checking: Boolean = true,
    val operation: String = ""
)

/** Owns optional data in private storage. Readers keep using the previous pack during import. */
class LocalTagLibraryStore(context: Context) {
    private val app = context.applicationContext
    private val directory = File(app.noBackupFilesDir, "tag-library")
    private val active get() = AtomicFile(File(directory, "active.json"))
    private val gson = Gson()
    private val lock = Any()
    private val operations = Mutex()
    private val mutableState = MutableStateFlow(LocalTagLibraryState())
    val state = mutableState.asStateFlow()

    private fun dataFile(info: TagLibraryManifest) = File(directory, "catalogue-${info.sha256.take(16)}.db")

    private fun readManifest(file: File): TagLibraryManifest =
        file.reader().use { gson.fromJson(it, TagLibraryManifest::class.java) }.validated()

    private fun current(): TagLibraryManifest? = runCatching {
        active.openRead().bufferedReader().use { gson.fromJson(it, TagLibraryManifest::class.java) }
            .validated().takeIf { dataFile(it).length() == it.bytes }
    }.getOrNull()

    private fun writeActive(info: TagLibraryManifest) {
        val output = active.startWrite()
        try {
            output.write(gson.toJson(info).toByteArray(Charsets.UTF_8))
            active.finishWrite(output)
        } catch (error: Throwable) {
            active.failWrite(output)
            throw error
        }
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        operations.withLock {
            synchronized(lock) {
                var info = current()
                // Earlier APKs copied this exact catalogue on first use but kept no local manifest.
                // Only metadata is bundled now; no database is extracted from the new APK.
                if (info == null && !File(directory, "removed").exists()) {
                    val legacy = app.assets.open("tag-library-legacy.json").bufferedReader().use {
                        gson.fromJson(it, TagLibraryManifest::class.java)
                    }.validated()
                    val existing = dataFile(legacy)
                    if (existing.isFile && existing.length() == legacy.bytes && sha256(existing) == legacy.sha256) {
                        writeActive(legacy)
                        info = legacy
                    }
                }
                mutableState.value = LocalTagLibraryState(installed = info, checking = false)
            }
        }
    }

    suspend fun install(source: () -> InputStream) = withContext(Dispatchers.IO) {
        operations.withLock {
            mutableState.value = mutableState.value.copy(busy = true, operation = "正在导入词库…")
            val staging = File(app.cacheDir, "tag-import-${UUID.randomUUID()}").apply { mkdirs() }
            try {
                val archive = File(staging, "pack.zip")
                source().use { input -> archive.outputStream().use { copyLimited(input, it, 128L * 1024 * 1024) } }
                currentCoroutineContext().ensureActive()
                val info: TagLibraryManifest
                val database = File(staging, "catalogue.db")
                ZipFile(archive).use { zip ->
                    require(zip.entries().asSequence().count { it.name == "manifest.json" } == 1 &&
                        zip.entries().asSequence().count { it.name == "catalogue-v1.db" } == 1) { "请选择 ComfyPocket 词库 ZIP 包" }
                    val manifestFile = File(staging, "manifest.json")
                    zip.getInputStream(zip.getEntry("manifest.json")).use { input ->
                        manifestFile.outputStream().use { copyLimited(input, it, 512L * 1024) }
                    }
                    info = readManifest(manifestFile)
                    zip.getInputStream(zip.getEntry("catalogue-v1.db")).use { input ->
                        database.outputStream().use { copyLimited(input, it, info.bytes) }
                    }
                }
                mutableState.value = mutableState.value.copy(operation = "正在校验词库…")
                require(database.length() == info.bytes && sha256(database) == info.sha256) { "词库文件校验失败，请重新获取资源包" }
                validateDatabase(database, info)
                currentCoroutineContext().ensureActive()
                synchronized(lock) {
                    directory.mkdirs()
                    // Copy onto the same filesystem before atomically replacing the active data.
                    val temporary = File(directory, "incoming.db")
                    try {
                        database.copyTo(temporary, overwrite = true)
                        Files.move(temporary.toPath(), dataFile(info).toPath(),
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                        writeActive(info)
                        File(directory, "removed").delete()
                        mutableState.value = LocalTagLibraryState(installed = info, checking = false)
                        directory.listFiles()?.filter { it.extension == "db" && it != dataFile(info) }?.forEach { it.delete() }
                    } finally {
                        temporary.delete()
                    }
                }
            } finally {
                staging.deleteRecursively()
                mutableState.value = mutableState.value.copy(busy = false, checking = false, operation = "")
            }
        }
    }

    suspend fun remove() = withContext(Dispatchers.IO) {
        operations.withLock {
            synchronized(lock) {
                directory.mkdirs()
                // A deliberate removal must not be undone by the legacy migration.
                File(directory, "removed").writeText("1")
                active.delete()
                directory.listFiles()?.filter { it.name != "removed" }?.forEach {
                    check(it.delete() || !it.exists()) { "词库移除未完成，请重试" }
                }
                mutableState.value = LocalTagLibraryState(checking = false)
            }
        }
    }

    internal fun <T> read(block: (SQLiteDatabase, TagLibraryManifest) -> T): T? = synchronized(lock) {
        val info = current() ?: return@synchronized null
        SQLiteDatabase.openDatabase(dataFile(info).path, null, SQLiteDatabase.OPEN_READONLY).use { block(it, info) }
    }

    private fun validateDatabase(file: File, info: TagLibraryManifest) {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA quick_check", null).use { require(it.moveToFirst() && it.getString(0) == "ok") { "词库数据库损坏" } }
            db.rawQuery("SELECT id,name,zh,category,section,kind,trigger,detail,post_count FROM entries LIMIT 1", null).use {
                require(it.moveToFirst()) { "词库内容为空" }
            }
            db.rawQuery("SELECT term,entry_id FROM lookup LIMIT 1", null).use {
                require(it.moveToFirst()) { "词库检索索引为空" }
            }
            val counts = mutableMapOf<String, Int>()
            db.rawQuery("SELECT kind,COUNT(*) FROM entries GROUP BY kind", null).use {
                while (it.moveToNext()) counts[it.getString(0)] = it.getInt(1)
            }
            require(counts == mapOf("tag" to info.tags, "character" to info.characters).filterValues { it > 0 }) { "词库条目数与清单不一致" }
        }
    }

    private suspend fun copyLimited(input: InputStream, output: java.io.OutputStream, limit: Long) {
        val buffer = ByteArray(65536)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) return
            total += count
            require(total <= limit) { "词库资源包大小超出限制" }
            output.write(buffer, 0, count)
        }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().use { input ->
            val bytes = ByteArray(65536)
            while (true) {
                val size = input.read(bytes)
                if (size < 0) break
                digest.update(bytes, 0, size)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
