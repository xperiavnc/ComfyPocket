package com.comfyui.mobile.modelcenter

import android.app.Application
import com.comfyui.mobile.data.ComfyRepository
import com.comfyui.mobile.market.CivitaiRepository
import com.comfyui.mobile.market.CivitaiSettingsStore
import com.comfyui.mobile.model.ServerProfile
import com.comfyui.mobile.network.RhResourceItem
import com.comfyui.mobile.network.RhResourcePageData
import com.comfyui.mobile.network.RhResourceVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A thin adapter over model-center data, NOT another independent model library. */
internal class ModelPickerData(application: Application, private val bridge: ComfyRepository) {
    private val directory = File(application.filesDir, "local-model-cache-v1")
    private val settingsStore = CivitaiSettingsStore(application)
    private var settings = settingsStore.load()
    private val phone = CivitaiRepository(application, settings, bridge)

    private fun updateTransport() {
        val current = settingsStore.load()
        if (current != settings) {
            settings = current
            phone.updateSettings(current)
        }
    }

    fun imageLoader(): coil.ImageLoader {
        updateTransport()
        return phone.imageLoader
    }
    fun imageRequest(url: String): Any = phone.imageRequest(url)
    fun close() = phone.close()
    private fun repository(server: ServerProfile) = LocalModelRepository(
        directory, "${server.effectiveId}|${server.baseUrl}", server.baseUrl, bridge.hostAwareCallFactory)

    suspend fun decorate(server: ServerProfile, page: RhResourcePageData): RhResourcePageData =
        withContext(Dispatchers.IO) {
            updateTransport()
            val repo = repository(server)
            val saved = repo.cache.load()
            page.copy(records = page.records.orEmpty().map { resource ->
                val item = resource.localModel ?: return@map resource
                val resolved = if (saved.bridgeServerId == item.serverId)
                    item.withPhoneSource(saved.phoneSources, phone.sourceScope) else item
                modelPickerResource(resource, resolved) { repo.preview(it) }
            })
        }

    suspend fun detail(server: ServerProfile, resource: RhResourceItem): Result<RhResourceItem> = try {
        updateTransport()
        val original = resource.localModel
        if (original == null) {
            Result.success(resource) // A newly discovered native file may not be indexed yet.
        } else {
            val repo = repository(server)
            val cap = repo.capabilities()
            check(cap.serverId == original.serverId) { "模型资料库已更换，请返回并刷新模型列表" }
            val latest = repo.detail(original.id)
            check(latest.present && latest.relativePath.replace('\\', '/') ==
                resource.effectiveModelName.replace('\\', '/')) { "模型位置已变更，请返回并刷新模型列表" }
            val saved = withContext(Dispatchers.IO) { repo.cache.load() }
            var resolved = if (saved.bridgeServerId == latest.serverId)
                latest.withPhoneSource(saved.phoneSources, phone.sourceScope) else latest
            val hash = modelHash(resolved.sha256)
            val existing = resolved.provider
            if (hash != null && (existing?.modelDescriptionFetched != true ||
                    System.currentTimeMillis() - existing.fetchedAt >= 24 * 60 * 60_000L)) {
                val namespace = phone.sourceScope
                val transport = phone.transportScope
                // This is the same direct client, proxy/key and disk cache used by model-center.
                // Missing network metadata does not erase the index's annotation or sidecar text.
                val fetched = try { phone.fetchSource(hash) } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { null }
                if (fetched != null && phone.sourceScope == namespace && phone.transportScope == transport &&
                    modelHash(fetched.provider.matchedHash) == hash) {
                    val provider = fetched.provider.copy(fetchedBy = "phone", cacheScope = namespace)
                    resolved = latest.copy(provider = provider, matchBasis = "sha256")
                    withContext(Dispatchers.IO) { repo.cache.savePhoneSource(latest.serverId, provider) }
                }
            }
            Result.success(modelPickerResource(resource, resolved) { repo.preview(it) })
        }
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (error: Exception) { Result.failure(error) }
}

internal fun modelPickerResource(
    resource: RhResourceItem, model: LocalModel, localPreview: (LocalModel) -> String?
): RhResourceItem {
    val provider = model.provider
    val descriptions = listOf(provider?.modelDescription.orEmpty(), provider?.description.orEmpty())
        .filter(String::isNotBlank).distinct()
    val preview = if (model.preview?.source == "local") localPreview(model) else remoteModelPreview(model)
    return resource.copy(
        // Native file names stay visible and exact node options are never replaced by an alias.
        localModel = model,
        thumbnailUrl = preview ?: resource.thumbnailUrl,
        posterUrl = preview ?: resource.posterUrl,
        desc = descriptions.takeIf { it.isNotEmpty() }?.joinToString("\n\n") ?: resource.desc,
        versions = listOf(RhResourceVersion(baseModel = model.baseModel,
            triggerWords = model.triggerWords.joinToString(", "), versionResourceName = resource.effectiveModelName))
    )
}

internal val RhResourceItem.modelAnnotationName: String
    get() = localModel?.annotation?.let { annotation ->
        annotation.alias.trim().ifBlank {
            annotation.aliases.firstOrNull { value -> value.any { it in '\u3400'..'\u9fff' } }?.trim().orEmpty()
        }
    }.orEmpty()
