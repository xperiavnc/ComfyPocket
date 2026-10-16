package com.comfyui.mobile.ui

import android.content.Context
import com.comfyui.mobile.model.MediaOutput
import kotlinx.coroutines.flow.StateFlow

/**
 * Gallery category shown in the local-album filter sheet.
 * Moved out of [MainViewModel] so [GalleryScreen] need not type-depend on the god VM.
 */
data class GalleryCategory(val id: String, val name: String)

/**
 * Gallery-screen facade over [MainViewModel] (Option A, same pattern as [ControlUi] / [AiAppUi]).
 * Implementation still delegates to the god ViewModel; Screen type-depends only on this.
 * Reuses [MediaPreviewHost] for FullScreenPreview (preview URL, save/delete, text, toast).
 */
interface GalleryUi : MediaPreviewHost {
    companion object {
        const val GALLERY_SERVER = "server"
        const val GALLERY_LOCAL = "local"
    }

    val sessionMediaList: StateFlow<List<MediaOutput>>
    val localMediaList: StateFlow<List<MediaOutput>>
    val gallerySource: StateFlow<String>
    val baseUrl: StateFlow<String>
    val galleryCategories: StateFlow<List<GalleryCategory>>
    val galleryCategoryAssignments: StateFlow<Map<String, String>>

    fun mediaKey(media: MediaOutput): String
    fun setGallerySource(source: String)
    fun refreshLocalMedia(context: Context)
    fun checkLatestHistory()
    fun saveBatchMediaToAlbum(
        context: Context,
        mediaList: List<MediaOutput>,
        onComplete: (() -> Unit)? = null
    )
    fun createGalleryCategory(name: String)
    fun renameGalleryCategory(id: String, name: String)
    fun deleteGalleryCategory(id: String)
    fun unassignMediaFromGalleryCategory(keys: Set<String>)
    fun assignMediaToGalleryCategoryAndSave(mediaList: List<MediaOutput>, categoryId: String)
}
