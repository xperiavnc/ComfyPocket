package com.comfyui.mobile.modelcenter

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

interface LocalModelSource {
    /** Account/content scope, never the key itself. Independent of the Bridge browsing mode. */
    val sourceScope: String
    /** Changing a proxy must restart requests without throwing away account-scoped metadata. */
    val transportScope: String get() = sourceScope
    suspend fun fetchSource(sha256: String, refresh: Boolean = false): ModelSourceResult
}

data class ModelSourceResult(val provider: ModelProvider, val warning: String? = null)
class ModelSourceException(message: String, val code: Int? = null) : Exception(message)
data class ModelSourceTask(val status: String = "", val message: String = "", val retryAt: Long = 0) {
    val active: Boolean get() = status == "queued" || status == "loading"
}

internal fun modelHash(value: String?): String? =
    value?.takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) }?.lowercase(java.util.Locale.ROOT)

internal fun LocalModel.withPhoneSource(sources: Map<String, ModelProvider>, scope: String): LocalModel {
    val hash = modelHash(sha256) ?: return this
    val source = sources[hash]?.takeIf {
        scope.isNotBlank() && it.cacheScope == scope && modelHash(it.matchedHash) == hash
    } ?: return this
    // In particular, annotation, revision and file presence always remain the Bridge's values.
    return copy(provider = source, matchBasis = "sha256")
}

internal fun ModelCacheSnapshot.withPhoneSources(scope: String): ModelCacheSnapshot =
    copy(items = items.map { it.withPhoneSource(phoneSources, scope) })

internal fun remoteModelPreview(item: LocalModel, index: Int = 0): String? =
    remoteModelPreviews(item).getOrNull(index)

private val VideoPreview = Regex("""(?i)\.(mp4|webm|mov|m4v|mkv)(\?|#|$)""")

/**
 * Every provider image the phone may fetch itself, in provider order. Video entries are dropped:
 * cover loaders only decode stills, and Civitai often lists a clip first.
 */
internal fun remoteModelPreviews(item: LocalModel): List<String> =
    item.provider?.images.orEmpty().mapNotNull { image ->
        image.url.toHttpUrlOrNull()?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }
            ?.toString()?.takeUnless { VideoPreview.containsMatchIn(it) }
    }.distinct()

/**
 * Visible-item, hash-deduplicated phone requests. Binding changes cancel queued/in-flight
 * work, and the epoch check also rejects sources that finish despite cancellation.
 * At most two hashes are fetched concurrently; failures never retry in a composition loop.
 */
internal class ModelSourceLoader(
    private val scope: CoroutineScope,
    private val onStatus: (String, ModelSourceTask) -> Unit,
    private val onResult: (String, ModelProvider) -> Unit,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val permits = Semaphore(2)
    private val jobs = mutableMapOf<String, Job>()
    private val retryAt = mutableMapOf<String, Long>()
    private var epoch = 0L
    private var binding = ""
    private var source: LocalModelSource? = null

    fun bind(identity: String, value: LocalModelSource?) {
        val key = "$identity|${value?.sourceScope.orEmpty()}|${value?.transportScope.orEmpty()}"
        if (key == binding && source === value) return
        binding = key
        epoch++
        jobs.values.forEach(Job::cancel)
        jobs.clear()
        retryAt.clear()
        source = value
    }

    fun request(rawHash: String?, refresh: Boolean = false): Boolean {
        val hash = modelHash(rawHash) ?: return false
        val current = source ?: return false
        if (jobs[hash]?.isActive == true || (!refresh && (retryAt[hash] ?: 0) > now())) return false
        val token = epoch
        val namespace = current.sourceScope
        val transport = current.transportScope
        onStatus(hash, ModelSourceTask("queued", "等待手机直连查询"))
        // LAZY lets the job enter the map before an immediate/cached source can complete.
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                permits.withPermit {
                    if (token != epoch) return@withPermit
                    onStatus(hash, ModelSourceTask("loading", "手机正在获取简介与预览"))
                    val result = current.fetchSource(hash, refresh)
                    currentCoroutineContext().ensureActive()
                    if (token != epoch || namespace != current.sourceScope || transport != current.transportScope) return@withPermit
                    if (modelHash(result.provider.matchedHash) != hash)
                        throw ModelSourceException("来源文件指纹与本地文件不一致，请重试")
                    val complete = result.provider.modelDescriptionFetched && result.warning == null
                    val next = now() + if (complete) 24 * 60 * 60_000L else 30_000L
                    retryAt[hash] = next
                    onResult(hash, result.provider.copy(fetchedBy = "phone", cacheScope = namespace))
                    onStatus(hash, ModelSourceTask(if (complete) "ready" else "partial",
                        result.warning ?: "简介与预览资料已保存到手机", next))
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                if (token == epoch) {
                    val missing = (error as? ModelSourceException)?.code == 404
                    val next = now() + if (missing) 30 * 60_000L else 30_000L
                    retryAt[hash] = next
                    onStatus(hash, ModelSourceTask(if (missing) "not-found" else "error",
                        if (missing) "Civitai 未收录此文件，或当前账号不可见" else
                            error.message ?: "手机直连查询失败，请检查代理后重试", next))
                }
            } finally {
                if (token == epoch) jobs.remove(hash)
            }
        }
        jobs[hash] = job
        job.start()
        return true
    }
}
