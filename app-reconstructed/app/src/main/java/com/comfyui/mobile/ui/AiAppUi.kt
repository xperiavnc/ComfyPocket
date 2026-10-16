package com.comfyui.mobile.ui

import android.net.Uri
import com.comfyui.mobile.model.AiApp
import com.comfyui.mobile.model.EditableNode
import com.comfyui.mobile.model.MediaOutput
import java.io.File
import kotlinx.coroutines.flow.StateFlow

/**
 * AI App detail facade over [MainViewModel] (Option A, same pattern as [ControlUi]).
 * Implementation still delegates to the god ViewModel; Screen type-depends only on this.
 * Reuses [CloudModelBrowserHost] / [MediaPreviewHost] for cloud model picker and result preview.
 */
interface AiAppUi : CloudModelBrowserHost, MediaPreviewHost {
    val aiApps: StateFlow<List<AiApp>>
    val aiAppEditableNodes: StateFlow<List<EditableNode>>
    val aiAppLatestResults: StateFlow<List<MediaOutput>>
    val aiAppNeedsReimport: StateFlow<Boolean>
    val aiAppExecutingNodeName: StateFlow<String>
    val batchCount: StateFlow<Int>
    val isRunning: StateFlow<Boolean>
    val executionStatus: StateFlow<String>
    val progress: StateFlow<Float>
    val rhAppStatus: StateFlow<String?>
    val rhAppRunning: StateFlow<Boolean>

    fun openAiAppSession(appId: String)
    fun getAiAppCoverFile(app: AiApp): File?
    fun updateAiAppTitle(appId: String, title: String)
    fun updateAiAppCover(appId: String, uri: Uri)
    fun deleteAiApp(appId: String)
    fun aiAppUpdateNodeValue(nodeId: String, key: String, value: Any)
    fun aiAppToggleNodeVisibility(nodeId: String, key: String, visible: Boolean)
    fun aiAppSetParamCustomName(nodeId: String, key: String, name: String)
    fun aiAppUpdateParamOrder(newOrderKeys: List<String>)
    fun setBatchCount(value: Int)
    fun runAiApp(batchCount: Int = 1)
    fun cancelRhAppRun()
    fun uploadImageToNode(uri: Uri, nodeId: String, key: String?, forAiApp: Boolean)
    fun getViewUrl(media: MediaOutput): String
    fun getViewUrl(filename: String, subfolder: String, type: String): String
}
