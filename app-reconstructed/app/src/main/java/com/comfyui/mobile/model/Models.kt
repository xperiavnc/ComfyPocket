package com.comfyui.mobile.model

import androidx.compose.runtime.Immutable

// --- Upload ---
data class UploadResponse(
    val name: String,
    val subfolder: String,
    val type: String
)

// --- Editable Nodes ---
@Immutable
sealed class EditableNode {
    abstract val nodeId: String
    abstract val title: String
    abstract val classType: String
    abstract val isVisible: Boolean

    @Immutable
    data class TextInput(
        override val nodeId: String, 
        override val title: String, 
        override val classType: String,
        val text: String,
        val options: List<String>? = null,
        val key: String, // The key in inputs map, e.g., "text" or "text_g"
        override val isVisible: Boolean = true
    ) : EditableNode()
    
    @Immutable
    data class NumberInput(
        override val nodeId: String,
        override val title: String,
        override val classType: String,
        val value: Number,
        val key: String,
        val min: Double? = null,
        val max: Double? = null,
        override val isVisible: Boolean = true
    ) : EditableNode()

    @Immutable
    data class BooleanInput(
        override val nodeId: String,
        override val title: String,
        override val classType: String,
        val value: Boolean,
        val key: String,
        override val isVisible: Boolean = true
    ) : EditableNode()
    
    @Immutable
    data class ImageInput(
        override val nodeId: String,
        override val title: String,
        override val classType: String,
        val filename: String,
        val key: String = "image",
        val subfolder: String = "",
        val type: String = "input",
        override val isVisible: Boolean = true
    ) : EditableNode()
}

object WorkflowOptionParser {
    private val SCALAR_TYPES = setOf(
        "INT", "FLOAT", "STRING", "BOOLEAN", "BOOL", "IMAGE", "LATENT", "MODEL",
        "CLIP", "VAE", "CONDITIONING", "MASK", "AUDIO", "VIDEO", "CONTROL_NET",
        "UPSCALE_MODEL", "STYLE_MODEL", "GLIGEN", "CLIP_VISION", "NOISE", "GUIDER",
        "SAMPLER", "SIGMAS", "TAESD", "WEBCAM", "POINT", "COMBO"
    )

    private fun extractFromList(rawList: List<*>): List<String>? {
        val opts = rawList.mapNotNull { it?.toString()?.trim() }.filter { it.isNotBlank() }
        return opts.ifEmpty { null }
    }

    private fun extractOptions(spec: Any?): List<String>? {
        if (spec == null) return null
        if (spec is Map<*, *>) {
            val rawOpts = (spec["values"] ?: spec["options"] ?: spec["choices"]) as? List<*>
            return rawOpts?.let { extractFromList(it) }
        }
        val arr = spec as? List<*> ?: return null
        if (arr.isEmpty()) return null

        // Format 1: Standard ComfyUI COMBO: [ [ "option1", "option2" ], { "default": "..." } ]
        val first = arr[0]
        if (first is List<*>) {
            val opts = extractFromList(first)
            if (opts != null) return opts
        }

        // Format 2: Dict options in second element: [ "COMBO" / "STRING", { "values" / "options": [...] } ]
        if (arr.size >= 2 && arr[1] is Map<*, *>) {
            val config = arr[1] as Map<*, *>
            val rawOpts = (config["values"] ?: config["options"] ?: config["choices"]) as? List<*>
            if (rawOpts != null) {
                val opts = extractFromList(rawOpts)
                if (opts != null) return opts
            }
        }

        // Format 3: Direct flat list of options: [ "1024x1024", "1280x720", ... ]
        // Exclude scalar type declarations like [ "INT", { "default": 512 } ]
        val isScalarSpec = arr.size == 2 && arr[1] is Map<*, *> && first is String && first.uppercase() in SCALAR_TYPES
        if (!isScalarSpec) {
            val allScalarsOrStrings = arr.all { it is String || it is Number }
            if (allScalarsOrStrings) {
                val opts = extractFromList(arr)
                if (opts != null && opts.none { it.uppercase() in SCALAR_TYPES }) {
                    return opts
                }
            }
        }

        return null
    }

    fun getComboOptions(objectInfo: Map<String, Any>, classType: String, inputKey: String): List<String>? {
        val classEntry = objectInfo[classType] as? Map<*, *> ?: return null
        val input = classEntry["input"] as? Map<*, *> ?: return null
        val required = input["required"] as? Map<*, *>
        val optional = input["optional"] as? Map<*, *>

        return extractOptions(required?.get(inputKey)) ?: extractOptions(optional?.get(inputKey))
    }

    /** min/max of a numeric input from /object_info (e.g. ["FLOAT", {"min": -10.0, "max": 10.0}]), or null. */
    fun getNumberRange(objectInfo: Map<String, Any>, classType: String, inputKey: String): Pair<Double, Double>? {
        val input = (objectInfo[classType] as? Map<*, *>)?.get("input") as? Map<*, *> ?: return null
        val spec = ((input["required"] as? Map<*, *>)?.get(inputKey) ?: (input["optional"] as? Map<*, *>)?.get(inputKey)) as? List<*>
        val cfg = spec?.getOrNull(1) as? Map<*, *> ?: return null
        val min = (cfg["min"] as? Number)?.toDouble() ?: return null
        val max = (cfg["max"] as? Number)?.toDouble() ?: return null
        return if (max > min) min to max else null
    }}

/** LoRA / ControlNet weight inputs only — not count fields like num_loras. */
fun isStrengthKey(key: String): Boolean {
    val k = key.trim().lowercase()
    if (k.isEmpty()) return false
    if (k == "strength" || k == "strength_model" || k == "strength_clip") return true
    if (k.startsWith("strength_")) return true
    if (k.endsWith("_strength") || k.endsWith("_model_strength") || k.endsWith("_clip_strength")) return true
    return false
}

/** When /object_info is missing, known INT count fields still get a usable slider. */
fun fallbackNumberRange(key: String): Pair<Double, Double>? = when (key.trim().lowercase()) {
    "num_loras" -> 1.0 to 10.0
    else -> null
}

const val STRENGTH_SLIDER_MAX = 5.0
const val STRENGTH_SLIDER_MIN = 0.0
/** @deprecated Use [STRENGTH_SLIDER_MIN]; kept so older call sites compile. */
const val STRENGTH_SLIDER_MIN_FLOOR = STRENGTH_SLIDER_MIN
const val STRENGTH_SLIDER_STEP = 0.01

/** Slider range of a strength input: fixed 0 .. 5 (object_info ignored for bounds). */
fun strengthSliderRange(objectInfoRange: Pair<Double, Double>?): Pair<Double, Double> =
    STRENGTH_SLIDER_MIN to STRENGTH_SLIDER_MAX

/** Snap a slider value to the 0.01 strength step. */
fun snapStrength(v: Float): Double {
    val stepped = Math.round(v / STRENGTH_SLIDER_STEP) * STRENGTH_SLIDER_STEP
    return stepped.coerceIn(STRENGTH_SLIDER_MIN, STRENGTH_SLIDER_MAX)
}
enum class LogType {
    INFO,
    SUCCESS,
    WARNING,
    ERROR
}

@Immutable
data class ConsoleMessage(
    val timestamp: Long,
    val text: String,
    val type: LogType
)

@Immutable
data class QueueDto(
    val promptId: String,
    val id: String
)

data class HistoryItem(
    val promptId: String,
    val workflow: Map<String, Any>,
    val outputs: Map<String, Any>?,
    val name: String? = null,
    /** ComfyUI history status_str ("success" / "error"), null if absent. */
    val statusStr: String? = null,
    val errorMessage: String? = null,
    val interrupted: Boolean = false
)

data class HistoryData(
    val prompt: List<Any?>? = null,
    val outputs: Map<String, Any>? = null,
    val status: Map<String, Any?>? = null
)

data class PromptRequest(
    val prompt: Map<String, Any>,
    val client_id: String? = null
)

data class PromptResponse(
    val prompt_id: String? = null,
    /** RunningHub proxy may answer 200 with its own {code,msg} envelope instead of a prompt_id. */
    val code: Any? = null,
    val msg: String? = null
)

data class QueueActionRequest(
    val clear: Boolean = false
)

data class HistoryActionRequest(
    val clear: Boolean? = null,
    val delete: List<String>? = null
)

data class QueueResponse(
    val queue_running: List<List<Any?>>? = null,
    val queue_pending: List<List<Any?>>? = null
)

@Immutable
data class SystemMonitorData(
    val gpuUtil: Float = 0f,
    val vramUsed: Float = 0f,
    val vramTotal: Float = 1f,
    val cpuUtil: Float = 0f,
    val ramUsed: Float = 0f,
    val ramTotal: Float = 1f,
    val gpuTemp: Float = 0f,
    val deviceName: String? = null,
    val isCrystools: Boolean = true
    )

data class SystemStatsResponse(
    val system: SystemInfo? = null,
    val devices: List<DeviceStats>? = null
    )

data class SystemInfo(
    val os: String? = null,
    val python_version: String? = null,
    val embedded_python: Boolean? = null
    )

data class DeviceStats(
    val name: String? = null,
    val type: String? = null,
    val index: Int? = null,
    val vram_total: Long? = null,
    val vram_free: Long? = null,
    val torch_vram_total: Long? = null,
    val torch_vram_free: Long? = null
    )

@Immutable
data class AiApp(
    val id: String,
    val title: String,
    val workflowKey: String,
    val workflowFileName: String,
    val coverFileName: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    /** Where the app comes from. null = saved before Phase 2a (derived from its storage folder). */
    val source: AiAppSource? = null,
    /** RunningHub webappId (official API AI app). */
    val rhWebappId: String? = null,
    /** RunningHubSite.id ("cn" / "ai") the app was imported from. */
    val rhSite: String? = null,
    /** Full apiCallDemo nodeInfoList (nodeId, nodeName, fieldName, fieldValue, fieldType, fieldData, description...). */
    val rhTemplateJson: String? = null
    ) {
    /** A RunningHub AI app that can run through the official API. */
    val isRhWebApp: Boolean
        get() = source == AiAppSource.RUNNINGHUB && !rhWebappId.isNullOrBlank() && !rhTemplateJson.isNullOrBlank()
}

enum class AiAppSource {
    LOCAL,
    RUNNINGHUB,
}

@Immutable
data class CharacterLore(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val triggerWords: String,
    val enabled: Boolean? = true
    ) {
    val isEnabled: Boolean get() = enabled != false
}
