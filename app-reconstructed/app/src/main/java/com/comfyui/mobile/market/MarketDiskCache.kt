package com.comfyui.mobile.market

import com.google.gson.Gson
import java.io.File

/** Small, bounded, account-partitioned metadata cache under Android's non-backed-up cacheDir. */
internal class MarketDiskCache(
    private val directory: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = 60,
    private val maxBytes: Long = 16L * 1024 * 1024
) {
    data class Entry(val savedAt: Long, val body: String) {
        fun fresh(now: Long, ttl: Long) = now >= savedAt && now - savedAt < ttl
    }

    private val gson = Gson()

    @Synchronized
    fun get(key: String): Entry? = runCatching {
        val file = File(directory, marketHash(key) + ".json")
        if (!file.isFile || file.length() > maxBytes) return null
        gson.fromJson(file.readText(), Entry::class.java)
            ?.takeIf { it.savedAt > 0 && it.body.isNotBlank() }
    }.getOrNull()

    @Synchronized
    fun put(key: String, body: String) {
        // Cache I/O is best effort; a full disk must not turn a successful search into an error.
        runCatching {
            val json = gson.toJson(Entry(now(), body))
            if (json.toByteArray(Charsets.UTF_8).size > maxBytes) return
            directory.mkdirs()
            val file = File(directory, marketHash(key) + ".json")
            val temp = File(directory, file.name + ".tmp")
            temp.writeText(json)
            if (!temp.renameTo(file)) {
                file.writeText(json)
                temp.delete()
            }
            val entries = directory.listFiles { f -> f.extension == "json" }
                .orEmpty().sortedByDescending { it.lastModified() }
            var used = 0L
            entries.forEachIndexed { index, entry ->
                used += entry.length()
                if (index >= maxEntries || used > maxBytes) entry.delete()
            }
        }
    }
}
