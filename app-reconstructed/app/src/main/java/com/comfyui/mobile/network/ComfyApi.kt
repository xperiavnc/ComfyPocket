package com.comfyui.mobile.network

import com.comfyui.mobile.model.PromptRequest
import com.comfyui.mobile.model.PromptResponse
import com.comfyui.mobile.model.QueueResponse
import com.comfyui.mobile.model.QueueActionRequest
import com.comfyui.mobile.model.HistoryActionRequest
import com.comfyui.mobile.model.UploadResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Multipart
import retrofit2.http.Part
import okhttp3.MultipartBody
import okhttp3.ResponseBody

interface ComfyApi {

    @POST("prompt")
    suspend fun queuePrompt(@Body request: PromptRequest): Response<PromptResponse>

    /** Same endpoint, raw body: error / node_errors / RH envelopes are parsed by PromptErrorParser. */
    @POST("prompt")
    suspend fun queuePromptRaw(@Body request: PromptRequest): Response<ResponseBody>

    @POST("interrupt")
    suspend fun interrupt(): Response<Unit>

    @retrofit2.http.GET("history")
    suspend fun getHistory(): Response<Map<String, com.comfyui.mobile.model.HistoryData>>

    @retrofit2.http.GET("history/{prompt_id}")
    suspend fun getHistoryForPrompt(
        @retrofit2.http.Path("prompt_id") promptId: String
    ): Response<Map<String, com.comfyui.mobile.model.HistoryData>>

    @retrofit2.http.GET("queue")
    suspend fun getQueue(): Response<QueueResponse>

    @POST("queue")
    suspend fun controlQueue(@Body request: QueueActionRequest): Response<Unit>
    
    @Multipart
    @POST("upload/image")
    suspend fun uploadImage(@Part image: MultipartBody.Part): Response<UploadResponse>
    
    @retrofit2.http.GET("view")
    suspend fun getView(
        @retrofit2.http.Query("filename") filename: String,
        @retrofit2.http.Query("subfolder") subfolder: String,
        @retrofit2.http.Query("type") type: String
    ): Response<ResponseBody>

    @retrofit2.http.GET("object_info")
    suspend fun getObjectInfo(): Response<Map<String, Any>>
    
    @POST("history")
    suspend fun controlHistory(@Body request: HistoryActionRequest): Response<Unit>
    
    @retrofit2.http.GET("system_stats")
    suspend fun getSystemStats(): Response<com.comfyui.mobile.model.SystemStatsResponse>

    @retrofit2.http.GET("api/mobile/system/monitor")
    suspend fun getMobileSystemMonitor(): Response<com.comfyui.mobile.model.MobileSystemMonitorResponse>

    @retrofit2.http.GET("api/mobile/resource/list")
    suspend fun getMobileResourceList(
        @retrofit2.http.Query("resourceType") resourceType: String,
        @retrofit2.http.Query("keyword") keyword: String,
        @retrofit2.http.Query("page") page: Int,
        @retrofit2.http.Query("pageSize") pageSize: Int,
        @retrofit2.http.Query("baseModel") baseModel: String? = null,
        @retrofit2.http.Query("refresh") refresh: Boolean = false
    ): Response<RhResourceListResponse>

    @retrofit2.http.GET("api/mobile/system/assets_summary")
    suspend fun getAssetsSummary(): Response<com.comfyui.mobile.model.AssetsSummaryResponse>

    @retrofit2.http.GET("api/mobile/civitai/search")
    suspend fun searchCivitai(
        @retrofit2.http.Query("query") query: String,
        @retrofit2.http.Query("type") type: String? = null,
        @retrofit2.http.Query("baseModel") baseModel: String? = null,
        @retrofit2.http.Query("page") page: Int = 1,
        @retrofit2.http.Query("cursor") cursor: String? = null,
        @retrofit2.http.Query("limit") limit: Int = 20,
        @retrofit2.http.Query("sort") sort: String? = null,
        @retrofit2.http.Query("username") username: String? = null,
        @retrofit2.http.Query("period") period: String? = null,
        @retrofit2.http.Query("nsfw") nsfw: Boolean? = null,
        @retrofit2.http.Query("ids") ids: String? = null
    ): Response<com.comfyui.mobile.model.CivitaiSearchResponse>

    @retrofit2.http.GET("api/mobile/model-folders")
    suspend fun getModelFolders(
        @retrofit2.http.Query("type") type: String
    ): Response<com.comfyui.mobile.model.ModelFoldersResponse>

    @POST("api/mobile/civitai/native-download")
    suspend fun downloadCivitaiModel(
        @Body request: com.comfyui.mobile.model.CivitaiDownloadRequest
    ): Response<com.comfyui.mobile.model.SimpleActionResponse>

    @retrofit2.http.GET("api/mobile/civitai/native-tasks")
    suspend fun getCivitaiDownloadTasks(): Response<com.comfyui.mobile.model.CivitaiTasksResponse>

    @POST("api/mobile/civitai/native-task-action")
    suspend fun civitaiTaskAction(
        @Body request: com.comfyui.mobile.model.CivitaiTaskActionRequest
    ): Response<com.comfyui.mobile.model.SimpleActionResponse>
}
