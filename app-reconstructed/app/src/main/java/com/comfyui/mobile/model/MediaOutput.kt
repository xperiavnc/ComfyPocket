package com.comfyui.mobile.model

import androidx.compose.runtime.Immutable

enum class MediaType {
    IMAGE, VIDEO, AUDIO, TEXT,
    /** Any other output file (zip, safetensors, json, ...): shown as a file card, saved to Download/ComfyUI. */
    FILE
}

@Immutable
data class MediaOutput(
    val filename: String,
    val subfolder: String,
    val type: String,
    val mediaType: MediaType,
    val timestamp: Long = System.currentTimeMillis(),
    val promptId: String? = null,
    /** content/file URI when this item is from local save folder */
    val localUri: String? = null
    )
