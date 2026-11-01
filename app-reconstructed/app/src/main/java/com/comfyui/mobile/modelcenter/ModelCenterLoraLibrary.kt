package com.comfyui.mobile.modelcenter

import com.comfyui.mobile.agent.AgentLoraAnnotated
import com.comfyui.mobile.agent.AgentLoraCatalog
import com.comfyui.mobile.agent.AgentLoraDetail
import com.comfyui.mobile.agent.AgentLoraInfo
import com.comfyui.mobile.agent.AgentLoraLibrary
import com.comfyui.mobile.agent.AgentLoraNote
import com.comfyui.mobile.agent.AgentLoraRefresh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import java.io.File

/**
 * The Agent's view of the computer's LoRAs, through the same model-center endpoints and cache files as the
 * Model Center screen. The full LoRA list goes into its own cache file so the Model Center's filtered page
 * snapshot is never replaced; single records the computer returns are written into both.
 */
internal class ModelCenterLoraLibrary(
    directory: File,
    identity: String,
    baseUrl: String,
    callFactory: Call.Factory,
    /** Phone-side Civitai lookup for descriptions the computer has not fetched. */
    private val phone: (() -> LocalModelSource)? = null
) : AgentLoraLibrary {
    private val repo = LocalModelRepository(directory, identity, baseUrl, callFactory)
    private val listCache = LocalModelCache(directory, listIdentity(identity))

    override suspend fun refresh(rescan: Boolean): AgentLoraRefresh {
        val capabilities = repo.capabilities()
        var note = ""
        if (rescan) {
            note = try {
                var scan = repo.scan("start")
                val started = System.currentTimeMillis()
                while (scan.active && System.currentTimeMillis() - started < SCAN_WAIT_MS) {
                    delay(SCAN_POLL_MS)
                    scan = repo.tasks().scan
                }
                if (scan.active) "电脑仍在扫描（已发现 ${scan.discovered} 个文件），稍后可再刷新一次"
                else "已重新扫描电脑上的模型文件夹"
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { "没能启动扫描（${error.message ?: "未知错误"}），下面是现有索引" }
        }
        val items = mutableListOf<LocalModel>()
        var page = 1
        while (page <= MAX_PAGES) {
            val result = repo.models(LocalModelQuery(category = LORA), page)
            check(result.serverId.isBlank() || result.serverId == capabilities.serverId) { "模型资料库已更换，请重新刷新" }
            items += result.items
            if (!result.hasMore || result.items.isEmpty()) break
            page++
        }
        val loras = items.filter { it.category.equals(LORA, ignoreCase = true) }.distinctBy { it.id.ifBlank { it.relativePath } }
        val sources = phoneSources(capabilities.serverId)
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            listCache.save(listCache.load().copy(bridgeServerId = capabilities.serverId, capabilities = capabilities,
                items = loras, savedAt = now, lastSync = now))
        }
        return AgentLoraRefresh(AgentLoraCatalog.from(loras, sources), scanned = rescan, note = note)
    }

    override suspend fun detail(item: AgentLoraInfo): AgentLoraDetail {
        val latest = fresh(item)
        val hash = modelHash(latest.sha256)
        var provider = hash?.let { phoneSources(latest.serverId)[it] } ?: latest.provider
        var notice = ""
        val missing = provider?.modelDescriptionFetched != true && provider?.modelDescription.isNullOrBlank()
        val client = if (hash != null && missing) phone?.invoke() else null
        if (hash != null && client != null) {
            // Same direct client, proxy and disk cache as the Model Center; a dead link only costs a note.
            val fetched = try {
                withTimeoutOrNull(SOURCE_TIMEOUT_MS) { client.fetchSource(hash) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { null }
            val found = fetched?.provider?.takeIf { modelHash(it.matchedHash) == hash }
            if (found != null) {
                val saved = found.copy(fetchedBy = "phone", cacheScope = client.sourceScope)
                provider = saved
                withContext(Dispatchers.IO) { repo.cache.savePhoneSource(latest.serverId, saved) }
            } else if (provider?.description.isNullOrBlank()) {
                notice = "电脑和手机都没取到这个 LoRA 的来源简介（可能不在 Civitai 上，或手机连不上 Civitai）"
            }
        }
        if (provider == null && notice.isEmpty()) notice = "这个 LoRA 还没有来源资料（简介、来源触发词）"
        remember(latest)
        return AgentLoraDetail(AgentLoraCatalog.info(latest, provider), notice)
    }

    override suspend fun annotate(item: AgentLoraInfo, note: AgentLoraNote): AgentLoraAnnotated {
        val latest = fresh(item)
        val provider = modelHash(latest.sha256)?.let { phoneSources(latest.serverId)[it] } ?: latest.provider
        val merge = AgentLoraCatalog.merge(latest.annotation, note,
            provider?.trainedWords.orEmpty(), provider?.baseModel.orEmpty())
        if (merge.annotation == latest.annotation) {
            return AgentLoraAnnotated(AgentLoraCatalog.info(latest, provider), emptyList(), merge.skipped)
        }
        // The fresh record carries the revision the computer expects, so a concurrent edit is rejected, not lost.
        val saved = repo.annotate(latest, merge.annotation)
        remember(saved)
        return AgentLoraAnnotated(AgentLoraCatalog.info(saved, provider), merge.applied, merge.skipped)
    }

    private suspend fun fresh(item: AgentLoraInfo): LocalModel {
        check(item.id.isNotBlank()) { "「${item.name}」还没有进入电脑的模型索引，先调用 refresh_loras" }
        val capabilities = repo.capabilities()
        val latest = repo.detail(item.id)
        check(latest.serverId.isBlank() || latest.serverId == capabilities.serverId) { "模型资料库已更换，请先调用 refresh_loras" }
        check(latest.present) { "电脑上已经没有「${item.name}」，请先调用 refresh_loras" }
        return latest
    }

    private suspend fun phoneSources(serverId: String): Map<String, ModelProvider> = withContext(Dispatchers.IO) {
        val saved = repo.cache.load()
        if (saved.bridgeServerId.isBlank() || saved.bridgeServerId == serverId) saved.phoneSources else emptyMap()
    }

    /** Puts a record the computer just returned into both caches so the Model Center and later turns see it. */
    private suspend fun remember(model: LocalModel) = withContext(Dispatchers.IO) {
        for (cache in listOf(listCache, repo.cache)) {
            val saved = cache.load()
            if (saved.bridgeServerId.isNotBlank() && saved.bridgeServerId != model.serverId) continue
            val index = saved.items.indexOfFirst { it.id == model.id }
            if (index < 0) continue
            cache.save(saved.copy(items = saved.items.toMutableList().also { it[index] = model }))
        }
    }

    companion object {
        const val LORA = "LORA"
        private const val MAX_PAGES = 50
        private const val SCAN_WAIT_MS = 45_000L
        private const val SCAN_POLL_MS = 1_500L
        private const val SOURCE_TIMEOUT_MS = 8_000L

        /** Cache identity of the Agent's full LoRA list, next to the Model Center snapshot of [identity]. */
        fun listIdentity(identity: String) = "$identity|agent-loras"

        /**
         * LoRAs known on the phone for [identity] without touching the network: the Agent's own full list
         * merged with whatever the Model Center last loaded, newer revisions winning. Call on an IO thread.
         */
        fun cached(directory: File, identity: String): List<AgentLoraInfo> {
            val main = LocalModelCache(directory, identity).load()
            val own = LocalModelCache(directory, listIdentity(identity)).load()
            val ownItems = own.items.filter { it.category.equals(LORA, ignoreCase = true) }
            val mainItems = main.items.filter { it.category.equals(LORA, ignoreCase = true) }
            val sameServer = own.bridgeServerId.isBlank() || main.bridgeServerId.isBlank() ||
                own.bridgeServerId == main.bridgeServerId
            val items = when {
                sameServer -> {
                    val merged = linkedMapOf<String, LocalModel>()
                    for (model in ownItems + mainItems) {
                        val key = model.id.ifBlank { model.relativePath }
                        val existing = merged[key]
                        if (existing == null || model.revision > existing.revision) merged[key] = model
                    }
                    merged.values.toList()
                }
                own.savedAt >= main.savedAt -> ownItems
                else -> mainItems
            }
            return AgentLoraCatalog.from(items, main.phoneSources)
        }
    }
}
