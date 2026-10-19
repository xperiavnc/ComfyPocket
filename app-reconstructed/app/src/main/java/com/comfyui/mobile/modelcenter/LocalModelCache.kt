package com.comfyui.mobile.modelcenter

import com.google.gson.Gson
import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

data class ModelCacheSnapshot(
    val bridgeServerId: String = "", val capabilities: ModelCapabilities? = null,
    val items: List<LocalModel> = emptyList(), val savedAt: Long = 0, val lastSync: Long = 0,
    val query: LocalModelQuery = LocalModelQuery(), val compact: Boolean = false,
    val scrollIndex: Int = 0, val scrollOffset: Int = 0,
    // Phone metadata is an overlay, not a replacement for the computer's file/index record.
    val phoneSources: Map<String, ModelProvider> = emptyMap()
)

/** Caller supplies an IO dispatcher. Each profile AND normalized endpoint owns a separate file. */
class LocalModelCache(private val directory: File, identity: String, private val gson: Gson = Gson()) {
    private val file = File(directory, cacheKey(identity) + ".json")
    private val lock = locks.computeIfAbsent(file.absolutePath) { Any() }

    fun load(): ModelCacheSnapshot = synchronized(lock) { read() }

    private fun read(): ModelCacheSnapshot = try {
        if (file.isFile) gson.fromJson(file.readText(), ModelCacheSnapshot::class.java) ?: ModelCacheSnapshot()
        else ModelCacheSnapshot()
    } catch (_: Exception) { ModelCacheSnapshot() }

    fun save(snapshot: ModelCacheSnapshot) = synchronized(lock) {
        val previous = read()
        // A selector and a retained model-center VM may save at the same time.
        // Preserve the newest phone source without overwriting file/annotation state.
        val sources = if (previous.bridgeServerId == snapshot.bridgeServerId) {
            (previous.phoneSources.keys + snapshot.phoneSources.keys).associateWith { hash ->
                listOfNotNull(previous.phoneSources[hash], snapshot.phoneSources[hash])
                    .maxBy { it.fetchedAt }
            }
        } else snapshot.phoneSources
        write(snapshot.copy(phoneSources = sources))
    }

    fun savePhoneSource(serverId: String, provider: ModelProvider) = synchronized(lock) {
        val hash = modelHash(provider.matchedHash) ?: return@synchronized
        if (provider.cacheScope.isBlank()) return@synchronized
        val current = read()
        if (current.bridgeServerId.isNotBlank() && current.bridgeServerId != serverId) return@synchronized
        save(current.copy(bridgeServerId = serverId,
            phoneSources = current.phoneSources + (hash to provider), savedAt = System.currentTimeMillis()))
    }

    private fun write(snapshot: ModelCacheSnapshot) {
        directory.mkdirs()
        val temp = File(directory, file.name + ".tmp")
        val raw = gson.toJson(snapshot)
        temp.outputStream().use { stream -> stream.write(raw.toByteArray(Charsets.UTF_8)); stream.fd.sync() }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
        fun cacheKey(identity: String): String = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        fun page(snapshot: ModelCacheSnapshot, query: LocalModelQuery, page: Int): ModelPage {
            val filtered = snapshot.items.filter(query::matches)
            val sorted = when (query.sort) {
                "size" -> filtered.sortedWith(compareByDescending<LocalModel> { it.size ?: Long.MIN_VALUE }.thenBy { it.id })
                "mtime" -> filtered.sortedWith(compareByDescending<LocalModel> { it.mtime ?: Long.MIN_VALUE }.thenBy { it.id })
                "recent" -> filtered.sortedWith(compareByDescending<LocalModel> { it.lastUsed ?: Long.MIN_VALUE }.thenBy { it.id })
                else -> filtered.sortedWith(compareBy<LocalModel> { it.displayName.lowercase(Locale.ROOT) }.thenBy { it.id })
            }
            val present = snapshot.items.filter { it.present }
            return ModelPage(serverId = snapshot.bridgeServerId,
                items = sorted.drop((page-1)*LocalModelQuery.PAGE_SIZE).take(LocalModelQuery.PAGE_SIZE),
                page = page, total = sorted.size, hasMore = page*LocalModelQuery.PAGE_SIZE < sorted.size,
                stats = ModelStats(present.size, snapshot.items.size-present.size,
                    present.distinctBy { it.rootPath + "/" + it.relativePath }.sumOf { it.size ?: 0 }, present.count { it.sha256 != null },
                    present.mapNotNull { it.sha256 }.distinct().size), lastSync = snapshot.lastSync,
                scanComplete = false)
        }
    }
}
