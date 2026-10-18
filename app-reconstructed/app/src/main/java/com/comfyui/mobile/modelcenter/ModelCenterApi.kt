package com.comfyui.mobile.modelcenter

import retrofit2.Response
import retrofit2.http.*

/** Version negotiation precedes any mutation. There is intentionally no /prompt method. */
interface ModelCenterApi {
    @GET("api/mobile/model-center/capabilities")
    suspend fun capabilities(): Response<ModelEnvelope<ModelCapabilities>>
    @GET("api/mobile/model-center/models")
    suspend fun models(@QueryMap query: Map<String, String>): Response<ModelEnvelope<ModelPage>>
    @GET("api/mobile/model-center/models/{id}")
    suspend fun detail(@Path("id") id: String): Response<ModelEnvelope<LocalModel>>
    @GET("api/mobile/model-center/installation")
    suspend fun installation(@Query("modelId") modelId: Long, @Query("versionId") versionId: Long,
                             @Query("fileId") fileId: Long): Response<ModelEnvelope<ModelInstallation>>
    @POST("api/mobile/model-center/models/{id}/annotation")
    suspend fun annotate(@Path("id") id: String, @Body body: AnnotationWrite): Response<ModelEnvelope<LocalModel>>
    @POST("api/mobile/model-center/scan")
    suspend fun scan(@Body action: ModelAction): Response<ModelEnvelope<ModelScan>>
    @GET("api/mobile/model-center/tasks")
    suspend fun tasks(): Response<ModelEnvelope<ModelTasks>>
    @POST("api/mobile/model-center/enrichment")
    suspend fun enrich(@Body body: EnrichmentWrite): Response<ModelEnvelope<ModelEnrichmentResult>>
    @POST("api/mobile/model-center/task-action")
    suspend fun taskAction(@Body action: ModelAction): Response<ModelEnvelope<ModelEnrichmentTask>>
}
data class AnnotationWrite(val expectedRevision: Int, val annotation: ModelAnnotation, val undoRevision: Int? = null)
data class EnrichmentWrite(val locationIds: List<String>, val refresh: Boolean = false)
data class ModelAction(val action: String, val id: String? = null)
