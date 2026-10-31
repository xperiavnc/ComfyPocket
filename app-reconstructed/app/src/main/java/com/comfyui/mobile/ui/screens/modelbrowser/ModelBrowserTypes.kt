package com.comfyui.mobile.ui.screens.modelbrowser

import androidx.compose.foundation.background
import com.comfyui.mobile.ui.designsystem.PocketTokens
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Pinned entry text of cloud model pickers (dropdown top entry / row under a text field). */
const val CLOUD_MODEL_BROWSE_TEXT = "浏览云端模型库"
const val LOCAL_MODEL_BROWSE_TEXT = "浏览本地模型库"

/**
 * Official resourceType tabs for RunningHub openapi/v2/resource/list and Civitai type filter.
 * Pair: API key → Chinese / display label.
 */
val MODEL_RESOURCE_TYPE_TABS = listOf(
    "CHECKPOINT" to "大模型",
    "UNET" to "UNet",
    "GGUF" to "GGUF",
    "LORA" to "LoRA"
)

private val LORA_STACK_KEY = Regex("(?i)^lora(_name)?_?\\d+(_name)?$")
private val CLIP_NAME_KEY = Regex("^clip_name\\d*$")

/**
 * Official resourceType (CHECKPOINT / UNET / GGUF / LORA) of a model-file input, or null when the input is not
 * a model file. VAE / CLIP (text encoder, incl. DualCLIP clip_name1/2 and GGUF CLIP loaders) / ControlNet /
 * upscale-model loader inputs are listed under 大模型 (CHECKPOINT) in the RunningHub model library, so they
 * open that tab. Only these specific loader inputs count - a generic model_name on other nodes does not.
 */
fun cloudModelResourceTypeFor(classType: String, key: String): String? {
    val cls = classType.lowercase()
    val k = key.lowercase()
    return when {
        k == "lora_name" || LORA_STACK_KEY.matches(k) || (k == "lora" && cls.contains("lora")) -> "LORA"
        cls.contains("gguf") && (k == "unet_name" || k == "gguf_name" || k == "model_name" || k == "ckpt_name") -> "GGUF"
        k == "unet_name" || (cls.contains("unetloader") && k == "model_name") -> "UNET"
        k == "ckpt_name" -> "CHECKPOINT"
        k == "vae_name" && (cls.contains("vae") || cls.contains("loader")) -> "CHECKPOINT"
        CLIP_NAME_KEY.matches(k) && (cls.contains("clip") || cls.contains("loader")) -> "CHECKPOINT"
        k == "control_net_name" && (cls.contains("controlnet") || cls.contains("loader")) -> "CHECKPOINT"
        k == "model_name" && cls.contains("upscalemodelloader") -> "CHECKPOINT"
        else -> null
    }
}

/**
 * The single "浏览模型库" entry, shown directly under a recognized model field,
 * whether or not the field has dropdown options.
 */
@Composable
fun CloudModelBrowseRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isCloud: Boolean = true
) {
    val scheme = MiuixTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(PocketTokens.Shape.Medium)
            .background(scheme.primary.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = if (isCloud) CLOUD_MODEL_BROWSE_TEXT else LOCAL_MODEL_BROWSE_TEXT,
            style = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.SemiBold),
            color = scheme.primary
        )
    }
}
