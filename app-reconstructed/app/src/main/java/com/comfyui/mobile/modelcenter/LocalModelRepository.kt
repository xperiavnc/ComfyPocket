package com.comfyui.mobile.modelcenter

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File

/** Pinned API, never reads a mutable "current server" after starting a request. */
class LocalModelRepository(
    directory: File, identity: String, val baseUrl: String, callFactory: Call.Factory
) {
    private val gson = Gson()
    val cache = LocalModelCache(directory, identity)
    val api: ModelCenterApi = Retrofit.Builder().baseUrl(baseUrl.trimEnd('/') + "/")
        .callFactory(callFactory).addConverterFactory(GsonConverterFactory.create(gson))
        .build().create(ModelCenterApi::class.java)

    suspend fun capabilities(): ModelCapabilities {
        val data = checked(api.capabilities(), capability = true)
        check(data.supported) { "电脑 Bridge 缺少模型中心 v1 能力，请安装本批 Bridge 更新包" }
        return data
    }
    suspend fun models(query: LocalModelQuery, page: Int) = checked(api.models(query.params(page)))
    suspend fun detail(id: String) = checked(api.detail(id))
    suspend fun installation(modelId: Long, versionId: Long, fileId: Long) =
        checked(api.installation(modelId, versionId, fileId))
    suspend fun annotate(item: LocalModel, annotation: ModelAnnotation, undoRevision: Int? = null) =
        checked(api.annotate(item.id, AnnotationWrite(item.revision, annotation, undoRevision)))
    suspend fun scan(action: String) = checked(api.scan(ModelAction(action)))
    suspend fun tasks() = checked(api.tasks())
    suspend fun enrich(ids: List<String>, refresh: Boolean) = checked(api.enrich(EnrichmentWrite(ids, refresh)))
    suspend fun taskAction(id: String, action: String) = checked(api.taskAction(ModelAction(action, id)))
    suspend fun directories(id: String, rootId: String, path: String) = checked(api.directories(id, rootId, path))
    suspend fun fileAction(id: String, action: ModelFileWrite) = checked(api.fileAction(id, action))
    fun preview(item: LocalModel, index: Int = 0): String? {
        if (item.preview?.source == "none") return null
        return (baseUrl.trimEnd('/') + "/api/mobile/model-center/models/${item.id}/preview").toHttpUrl()
            .newBuilder().addQueryParameter("index", index.toString())
            .addQueryParameter("v", item.preview?.version?.takeIf { it.isNotBlank() }
                ?: "${item.sha256 ?: item.mtime}:${item.provider?.fetchedAt ?: 0}").build().toString()
    }

    private suspend fun <T> checked(reply: Response<ModelEnvelope<T>>, capability: Boolean = false): T =
        withContext(Dispatchers.IO) {
            if (reply.isSuccessful) {
                val body = reply.body()
                if (body?.code == 0 && body.data != null) return@withContext body.data
                error(body?.msg?.take(500) ?: "模型中心未返回有效数据")
            }
            if (capability && reply.code() == 404)
                error("电脑上的 Bridge 尚未支持模型中心，请安装本批 Bridge 更新包并重启 ComfyUI")
            val message = reply.errorBody()?.use { body ->
                runCatching { gson.fromJson(body.string(), ModelEnvelope::class.java)?.msg }.getOrNull()
            }
            error(message?.take(500)?.takeIf { it.isNotBlank() } ?: "模型中心 HTTP ${reply.code()}")
        }
}
