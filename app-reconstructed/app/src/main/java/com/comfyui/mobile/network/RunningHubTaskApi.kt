package com.comfyui.mobile.network

import com.google.gson.JsonElement
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query

/**
 * RunningHub official OpenAPI (isolated from the local ComfyUI stack).
 * Auth: `Authorization: Bearer <key>` header is added by [RhHttpClient]; v1 endpoints additionally
 * require `apiKey` in the JSON / multipart body (accountStatus uses lowercase `apikey`).
 * The only exception is [webAppApiCallDemo]: the docs specify `apiKey` as a required QUERY parameter
 * (Bearer-only returns code 500 UNKNOWN_ERROR). Every log line redacts it (SecretRedactor).
 */
interface RunningHubTaskApi {
    @POST("task/openapi/create")
    suspend fun createTask(@Body body: RhCreateTaskRequest): Response<RhEnvelope>

    @POST("task/openapi/ai-app/run")
    suspend fun runAiApp(@Body body: RhAiAppRunRequest): Response<RhEnvelope>

    @POST("task/openapi/status")
    suspend fun taskStatus(@Body body: RhTaskIdRequest): Response<RhEnvelope>

    @POST("task/openapi/outputs")
    suspend fun taskOutputs(@Body body: RhTaskIdRequest): Response<RhEnvelope>

    @POST("task/openapi/cancel")
    suspend fun cancelTask(@Body body: RhTaskIdRequest): Response<RhEnvelope>

    /** Legacy upload: returns data.fileName (e.g. "api/xxx.png"), documented as LoadImage fieldValue. */
    @Multipart
    @POST("task/openapi/upload")
    suspend fun uploadInput(
        @Part("apiKey") apiKey: RequestBody,
        @Part("fileType") fileType: RequestBody,
        @Part file: MultipartBody.Part
    ): Response<RhEnvelope>

    /** v2 binary upload (Bearer only): returns data.download_url / data.filename. Not used as fieldValue (undocumented). */
    @Multipart
    @POST("openapi/v2/media/upload/binary")
    suspend fun uploadBinaryV2(@Part file: MultipartBody.Part): Response<RhEnvelope>

    /** Official model / LoRA library (resourceType UNET / CHECKPOINT / LORA / GGUF); key in the Authorization header only. */
    @POST("openapi/v2/resource/list")
    suspend fun resourceList(@Body body: RhResourceListRequest): Response<RhEnvelope>

    @POST("uc/openapi/accountStatus")
    suspend fun accountStatus(@Body body: RhAccountStatusRequest): Response<RhEnvelope>

    @GET("openapi/v2/queue/status")
    suspend fun queueStatus(): Response<RhEnvelope>

    /** AI app template, exactly as documented: GET ?apiKey=..&webappId=.. plus the Bearer header. */
    @GET("api/webapp/apiCallDemo")
    suspend fun webAppApiCallDemo(
        @Query("apiKey") apiKey: String,
        @Query("webappId") webappId: String
    ): Response<RhEnvelope>

    @POST("api/openapi/getJsonApiFormat")
    suspend fun workflowJsonApiFormat(@Body body: RhWorkflowJsonRequest): Response<RhEnvelope>

    /** Public workflow metadata (name / cover). Website API; used because getJsonApiFormat has no title. */
    @POST("api/portal/workflow/detail")
    suspend fun portalWorkflowDetail(@Body body: RhPortalWorkflowDetailRequest): Response<RhEnvelope>
}

data class RhEnvelope(
    val code: Int? = null,
    val msg: String? = null,
    val message: String? = null,
    val errorMessages: JsonElement? = null,
    val data: JsonElement? = null
    ) {
    val isOk: Boolean get() = code == RhError.OK || code == RhError.OK_V2
    val text: String? get() = msg ?: message
}

data class RhNodeInfo(
    val nodeId: String,
    val fieldName: String,
    val fieldValue: String
    )

data class RhAiAppRunRequest(
    val apiKey: String,
    val webappId: String,
    val nodeInfoList: List<RhNodeInfo>,
    val instanceType: String? = null
    )

/**
 * /task/openapi/create. [workflow] = full API-format graph as a JSON string (doc: optional, overrides the
 * saved graph). Nulls are omitted by the plain Gson serializer, so an absent workflowId is not sent.
 */
data class RhCreateTaskRequest(
    val apiKey: String,
    val workflowId: String? = null,
    val workflow: String? = null,
    val nodeInfoList: List<RhNodeInfo>? = null,
    val addMetadata: Boolean? = null,
    val instanceType: String? = null
    )

data class RhTaskIdRequest(
    val apiKey: String,
    val taskId: String
    )

data class RhAccountStatusRequest(
    val apikey: String
    )

data class RhWorkflowJsonRequest(
    val apiKey: String,
    val workflowId: String
    )

data class RhPortalWorkflowDetailRequest(
    val workflowId: String
    )

/** Result of ai-app/run or create. */
data class RhSubmitResult(
    val taskId: String,
    val taskStatus: String?,
    val promptTips: String?
    )

data class RhOutputFile(
    val fileUrl: String,
    val fileType: String?,
    val nodeId: String?,
    val taskCostTime: String?,
    val consumeCoins: String?,
    val consumeMoney: String?,
    val thirdPartyConsumeMoney: String?
    )

data class RhFailedReason(
    val exceptionType: String?,
    val exceptionMessage: String?,
    val nodeName: String?,
    val nodeId: String?
    ) {
    val display: String
        get() = buildString {
            val node = listOfNotNull(nodeName?.takeIf { it.isNotBlank() }, nodeId?.takeIf { it.isNotBlank() }?.let { "#$it" })
                .joinToString(" ")
            if (node.isNotBlank()) append("节点 ").append(node).append(": ")
            append(exceptionMessage?.trim()?.take(300)?.ifBlank { null } ?: exceptionType ?: "任务运行失败")
        }
}

sealed class RhOutputsResult {
    data class Success(val files: List<RhOutputFile>) : RhOutputsResult()
    data class Running(val queued: Boolean) : RhOutputsResult()
    data class Failed(val reason: RhFailedReason?, val message: String) : RhOutputsResult()
}

data class RhQueueStatus(
    val apiKeyType: String?,
    val concurrentLimit: Int?,
    val runningCount: Int?,
    val queuedCount: Int?,
    val totalCurrentTasks: Int?
    )