package com.comfyui.mobile.ui

import android.content.Context
import android.net.Uri
import com.comfyui.mobile.model.EditableNode
import com.comfyui.mobile.model.MediaOutput
import com.comfyui.mobile.model.QueueDto
import com.comfyui.mobile.model.ServerProfile
import com.comfyui.mobile.network.RhResourcePageData
import java.io.File
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Narrow host for [com.comfyui.mobile.ui.screens.control.CloudModelBrowserDialog].
 * Control + AI App detail both open the dialog; keep this smaller than full [ControlUi].
 */
interface CloudModelBrowserHost {
    val servers: StateFlow<List<ServerProfile>>
    val currentServerId: StateFlow<String>

    suspend fun searchCloudModels(
        resourceType: String,
        keyword: String,
        page: Int,
        pageSize: Int,
        baseModels: List<String>? = null
    ): Result<RhResourcePageData>

    suspend fun searchLocalModels(
        resourceType: String,
        keyword: String,
        page: Int,
        pageSize: Int,
        baseModel: String? = null
    ): Result<RhResourcePageData>
}

/**
 * Narrow host for [com.comfyui.mobile.ui.screens.FullScreenPreview]
 * (preview URL, save/delete, text fetch, toast). Used by Control and other screens.
 */
interface MediaPreviewHost {
    fun previewUrl(media: MediaOutput): String
    fun saveMediaToAlbum(
        context: Context,
        media: MediaOutput,
        silent: Boolean = false,
        onComplete: ((Boolean) -> Unit)? = null
    )
    fun deleteLocalMedia(context: Context, mediaList: List<MediaOutput>)
    fun deleteMedia(mediaList: List<MediaOutput>)
    suspend fun fetchTextContent(media: MediaOutput): String
    fun showToast(msg: String)
}

/**
 * Control-screen facade over [MainViewModel] (Option A).
 * Implementation still delegates to the god ViewModel; Screen type-depends only on this.
 */
interface ControlUi : CloudModelBrowserHost, MediaPreviewHost {
    val queueList: StateFlow<List<QueueDto>>
    val queueSize: StateFlow<Int>
    val editableNodes: StateFlow<List<EditableNode>>
    val currentWorkflowName: StateFlow<String?>
    val latestGenerationList: StateFlow<List<MediaOutput>>
    val savedWorkflowFiles: StateFlow<List<File>>
    val baseUrl: StateFlow<String>
    val batchCount: StateFlow<Int>

    val rhAppStatus: StateFlow<String?>
    val rhAppRunning: StateFlow<Boolean>
    val rhOfficialBusy: StateFlow<Boolean>
    val currentRhWorkflowMeta: StateFlow<RhWorkflowMeta?>
    val rhWorkflowIdRequest: SharedFlow<Unit>

    fun uploadImageToNode(uri: Uri, nodeId: String, key: String? = null, forAiApp: Boolean = false)
    fun saveCurrentWorkflowAsAiApp(title: String, coverUri: Uri? = null)
    fun overwriteCurrentWorkflowToLocal()
    fun fetchQueue()
    fun refreshSavedWorkflows()
    fun setCurrentRhWorkflowId(input: String): String?
    fun loadWorkflow(uri: Uri)
    fun getSiteSavedWorkflows(): List<File>
    fun deleteSavedWorkflow(file: File): Boolean
    fun rhSiteName(siteId: String?): String
    /** Site id of a saved cloud workflow file name; replaces direct MainViewModelRhWorkflows.siteOf. */
    fun rhWorkflowSiteOf(fileName: String): String?
    fun loadLocalWorkflow(file: File, silent: Boolean = false)
    fun syncRunningHubResource(
        inputUrlOrId: String,
        customName: String? = null,
        onSuccess: (String) -> Unit = {},
        onError: (String) -> Unit = {}
    )
    fun setParamCustomName(nodeId: String, key: String, name: String)
    fun updateParamOrder(newOrderKeys: List<String>)
    fun toggleNodeVisibility(nodeId: String, key: String, visible: Boolean)
    fun updateNodeValue(nodeId: String, key: String, value: Any)
    fun getViewUrl(media: MediaOutput): String
    fun getViewUrl(filename: String, subfolder: String, type: String): String
    fun setBatchCount(value: Int)
    fun runWorkflow(batchCount: Int = 1)
    fun stopAllWorkflows()
}
