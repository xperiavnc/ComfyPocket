package com.comfyui.mobile.danbooru

import com.google.gson.Gson
import java.io.File
import java.security.MessageDigest

/** Lives only in cacheDir; the normal App cache cleanup also removes this cache. */
class DanbooruCache(
    private val directory: File, private val now: () -> Long = System::currentTimeMillis,
    private val maxBytes: Long = 2L * 1024 * 1024, private val maxEntries: Int = 200
) {
    private val gson = Gson()
    private fun file(scope: String, type: DanbooruApiType, query: String): File {
        val key = "$scope\u0000${type.name}\u0000${normalizeTag(query)}"
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$digest.json")
    }
    @Synchronized fun get(scope: String, type: DanbooruApiType, query: String): DanbooruApiResult? {
        val target = file(scope, type, query)
        return runCatching {
            if (!target.isFile || target.length() > 128 * 1024) return null
            val result = gson.fromJson(target.readText(), DanbooruApiResult::class.java) ?: return null
            val age = now() - result.fetchedAt
            if (age !in 0 until type.ttlMillis || result.status !in cacheable) return null
            result.copy(fromCache = true)
        }.getOrNull()
    }
    @Synchronized fun put(scope: String, type: DanbooruApiType, query: String, value: DanbooruApiResult) {
        if (value.status !in cacheable) return
        runCatching {
            directory.mkdirs()
            val target = file(scope, type, query)
            val temporary = File.createTempFile("entry-", ".tmp", directory)
            try {
                temporary.writeText(gson.toJson(value.copy(fromCache = false)))
                if (!temporary.renameTo(target)) {
                    target.delete()
                    check(temporary.renameTo(target))
                }
            } finally { temporary.delete() }
            val files = directory.listFiles()?.filter { it.extension == "json" }?.sortedBy { it.lastModified() }.orEmpty()
            var bytes = files.sumOf { it.length() }
            var count = files.size
            for (entry in files) {
                if (bytes <= maxBytes && count <= maxEntries) break
                val size = entry.length()
                if (entry.delete()) { bytes -= size; count-- }
            }
        }
    }
    companion object {
        private val cacheable = setOf(LookupStatus.FOUND, LookupStatus.NOT_FOUND, LookupStatus.NO_WIKI)
    }
}
