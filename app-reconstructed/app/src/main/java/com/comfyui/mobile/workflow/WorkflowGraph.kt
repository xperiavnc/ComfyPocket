package com.comfyui.mobile.workflow

import com.comfyui.mobile.model.EditableNode
import com.comfyui.mobile.model.WorkflowOptionParser
import com.comfyui.mobile.model.isStrengthKey

/**
 * API-graph transformations and editable-node parsing, with no Android or ViewModel dependency.
 * Algorithms are extracted from MainViewModel; caller-owned maps are only mutated where the
 * original override-cleanup contract explicitly requires it.
 */
internal object WorkflowGraph {
    private val EASY_LORA_STACK_SLOT_KEY = Regex("^lora_(\\d+)_(name|strength|model_strength|clip_strength)$")

    internal fun sanitizeNumbers(value: Any?, keyHint: String? = null): Any? {
        val floatKeys = setOf(
            "cfg",
            "denoise",
            "guidance",
            "strength",
            "eta"
        )
        val intKeys = setOf(
            "width",
            "height",
            "steps",
            "seed",
            "batch_size",
            "batch",
            "clip_skip"
        )

        return when (value) {
            is Map<*, *> -> {
                value.entries.associate { (k, v) ->
                    val key = k?.toString() ?: ""
                    key to sanitizeNumbers(v, key)
                }
            }
            is List<*> -> {
                if (
                    value.size == 2 &&
                    value[0] is String &&
                    value[1] is Number
                ) {
                    val first = value[0] as String
                    val second = value[1]
                    val index = when (second) {
                        is Int, is Long -> second
                        is Float -> {
                            val d = second.toDouble()
                            if (d.isFinite() && d % 1.0 == 0.0) d.toInt() else second
                        }
                        is Double -> {
                            if (second.isFinite() && second % 1.0 == 0.0) {
                                val asLong = second.toLong()
                                if (asLong in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) asLong.toInt() else asLong
                            } else {
                                second
                            }
                        }
                        else -> second
                    }
                    listOf(first, index)
                } else {
                    value.map { sanitizeNumbers(it, null) }
                }
            }
            is Double -> {
                val key = keyHint?.lowercase()
                if (key != null && (floatKeys.contains(key) || isStrengthKey(key))) return value
                if (!value.isFinite() || value % 1.0 != 0.0) return value

                if (key != null && !intKeys.contains(key)) return value

                val asLong = value.toLong()
                if (asLong in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) asLong.toInt() else asLong
            }
            else -> value
        }
    }

    internal fun applyParamOverridesToWorkflow(
        workflow: Map<String, Any>,
        paramValues: MutableMap<String, Any>
    ): Pair<Map<String, Any>, List<String>> {
        if (paramValues.isEmpty()) return workflow to emptyList()

        val current = workflow.toMutableMap()
        val toRemove = mutableListOf<String>()
        for ((compound, rawValue) in paramValues) {
            val parts = compound.split(":", limit = 2)
            if (parts.size != 2) continue
            val nodeId = parts[0]
            val inputKey = parts[1]

            val nodeAny = current[nodeId] as? Map<*, *> ?: continue
            val node = nodeAny.entries.associate { (k, v) -> (k?.toString() ?: "") to v }.toMutableMap()
            val inputsAny = node["inputs"] as? Map<*, *> ?: continue
            val inputs = inputsAny.entries.associate { (k, v) -> (k?.toString() ?: "") to v }.toMutableMap()

            val expected = inputs[inputKey]
            if (expected is List<*> && expected.size == 2 && expected[0] is String && expected[1] is Number) {
                toRemove.add(compound)
                continue
            }

            val value = coerceToExpectedType(expected, rawValue) ?: continue
            inputs[inputKey] = value
            node["inputs"] = inputs
            current[nodeId] = node
        }

        if (toRemove.isNotEmpty()) {
            toRemove.forEach { paramValues.remove(it) }
        }

        return current to toRemove
    }

    internal fun coerceToExpectedType(expected: Any?, incoming: Any?): Any? {
        if (incoming == null) return null

        return when (expected) {
            is Int -> (incoming as? Number)?.toInt()
            is Long -> (incoming as? Number)?.toLong()
            is Float -> (incoming as? Number)?.toFloat()
            is Double -> (incoming as? Number)?.toDouble()
            is Number -> {
                val num = incoming as? Number ?: return null
                if (expected is Int) num.toInt()
                else if (expected is Long) num.toLong()
                else if (expected is Float) num.toFloat()
                else if (expected is Double) num.toDouble()
                else {
                    val d = num.toDouble()
                    if (d.isFinite() && d % 1.0 == 0.0) {
                        val asLong = d.toLong()
                        if (asLong in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) asLong.toInt() else asLong
                    } else {
                        d
                    }
                }
            }
            is Boolean -> {
                when (incoming) {
                    is Boolean -> incoming
                    is String -> incoming.trim().lowercase().let { it == "true" || it == "1" || it == "yes" }
                    is Number -> incoming.toInt() != 0
                    else -> null
                }
            }
            is String -> incoming.toString()
            else -> incoming
        }
    }

    internal fun compoundKeyOf(node: EditableNode): String {
        val key = when (node) {
            is EditableNode.TextInput -> node.key
            is EditableNode.NumberInput -> node.key
            is EditableNode.BooleanInput -> node.key
            is EditableNode.ImageInput -> node.key
        }
        return "${node.nodeId}:$key"
    }

    private fun isInactiveLoraStackSlotKey(classType: String, inputs: Map<String, Any>, key: String): Boolean {
        if (classType != "easy loraStack") return false
        val m = EASY_LORA_STACK_SLOT_KEY.matchEntire(key) ?: return false
        val count = (inputs["num_loras"] as? Number)?.toInt() ?: 1
        if (m.groupValues[1].toInt() > count) return true
        val advanced = inputs["mode"] as? String == "advanced"
        return when (m.groupValues[2]) {
            "strength" -> advanced
            "model_strength", "clip_strength" -> !advanced
            else -> false
        }
    }

    internal fun parseWorkflowNodes(
        workflow: Map<String, Any>,
        objectInfo: Map<String, Any>,
        visibility: Map<String, Boolean>,
        labels: Map<String, String>
    ): List<EditableNode> {
        val list = mutableListOf<EditableNode>()

        fun getComboOptions(classType: String, inputKey: String): List<String>? =
            WorkflowOptionParser.getComboOptions(objectInfo, classType, inputKey)

        fun isVisible(nodeId: String, classType: String, key: String): Boolean {
            val override = visibility["$nodeId:$key"]
            if (override != null) return override
            return when (classType) {
                "LoadImage", "LoadImageMask" -> key == "image"
                "CLIPTextEncode" -> key == "text"
                "EmptyLatentImage" -> key == "width" || key == "height"
                else -> false
            }
        }

        fun resolveTitle(nodeId: String, key: String, defaultTitle: String): String {
            val mapKey = "$nodeId:$key"
            val custom = labels[mapKey]
            return if (custom.isNullOrBlank()) defaultTitle else custom
        }

        for ((nodeId, nodeData) in workflow) {
             if (nodeData is Map<*, *>) {
                 val classType = nodeData["class_type"] as? String ?: ""
                 val meta = nodeData["_meta"] as? Map<String, Any>
                 val title = meta?.get("title") as? String ?: classType
                 val inputs = nodeData["inputs"] as? Map<String, Any> ?: continue

                 val consumedKeys = mutableSetOf<String>()
                 
                 when (classType) {
                     "LoadImage" -> {
                         val rawImg = inputs["image"]
                         if (rawImg is List<*> && rawImg.size == 2 && rawImg[0] is String && rawImg[1] is Number) {
                             consumedKeys.add("image")
                         } else {
                             val filename = rawImg as? String ?: ""
                             val subfolder = inputs["subfolder"] as? String ?: ""
                             val type = inputs["type"] as? String ?: "input"
                             list.add(EditableNode.ImageInput(nodeId, resolveTitle(nodeId, "image", title), classType, filename, "image", subfolder, type, isVisible(nodeId, classType, "image")))
                             consumedKeys.add("image")
                         }
                     }
                     "LoadImageMask" -> {
                         val rawImg = inputs["image"]
                         if (rawImg is List<*> && rawImg.size == 2 && rawImg[0] is String && rawImg[1] is Number) {
                             consumedKeys.add("image")
                         } else {
                             val filename = rawImg as? String ?: ""
                             val subfolder = inputs["subfolder"] as? String ?: ""
                             val type = inputs["type"] as? String ?: "input"
                             list.add(EditableNode.ImageInput(nodeId, resolveTitle(nodeId, "image", title), classType, filename, "image", subfolder, type, isVisible(nodeId, classType, "image")))
                             consumedKeys.add("image")
                         }
                     }
                     "VHS_LoadVideo", "LoadVideo" -> {
                         val rawVideo = inputs["video"]
                         if (rawVideo is List<*> && rawVideo.size == 2 && rawVideo[0] is String && rawVideo[1] is Number) {
                             consumedKeys.add("video")
                         } else {
                             val filename = rawVideo as? String ?: ""
                             val subfolder = inputs["subfolder"] as? String ?: ""
                             val type = inputs["type"] as? String ?: "input"
                             list.add(EditableNode.ImageInput(nodeId, resolveTitle(nodeId, "video", "$title Video"), classType, filename, "video", subfolder, type, isVisible(nodeId, classType, "video")))
                             consumedKeys.add("video")
                         }
                     }
                     "LoadAudio" -> {
                         val rawAudio = inputs["audio"]
                         if (rawAudio is List<*> && rawAudio.size == 2 && rawAudio[0] is String && rawAudio[1] is Number) {
                             consumedKeys.add("audio")
                         } else {
                             val filename = rawAudio as? String ?: ""
                             val subfolder = inputs["subfolder"] as? String ?: ""
                             val type = inputs["type"] as? String ?: "input"
                             list.add(EditableNode.ImageInput(nodeId, resolveTitle(nodeId, "audio", "$title Audio"), classType, filename, "audio", subfolder, type, isVisible(nodeId, classType, "audio")))
                             consumedKeys.add("audio")
                         }
                     }
                     "CLIPTextEncode" -> {
                         val rawText = inputs["text"]
                         if (rawText is List<*> && rawText.size == 2 && rawText[0] is String && rawText[1] is Number) {
                             // text 输入端口已被上游节点 (如组合器) 连线，属于拓扑连线而非可编辑字面量
                             consumedKeys.add("text")
                         } else {
                             val text = rawText as? String ?: ""
                             list.add(EditableNode.TextInput(nodeId, resolveTitle(nodeId, "text", title), classType, text, null, "text", isVisible(nodeId, classType, "text")))
                             consumedKeys.add("text")
                         }
                     }
                     "EmptyLatentImage" -> {
                         val w = (inputs["width"] as? Number) ?: 512
                         val h = (inputs["height"] as? Number) ?: 512
                        list.add(EditableNode.NumberInput(nodeId, resolveTitle(nodeId, "width", "$title Width"), classType, w, "width", 64.0, 4096.0, isVisible(nodeId, classType, "width")))
                        list.add(EditableNode.NumberInput(nodeId, resolveTitle(nodeId, "height", "$title Height"), classType, h, "height", 64.0, 4096.0, isVisible(nodeId, classType, "height")))
                        consumedKeys.add("width")
                        consumedKeys.add("height")
                     }
                     "KSampler" -> {
                         val seed = (inputs["seed"] as? Number) ?: 0
                        list.add(EditableNode.NumberInput(nodeId, resolveTitle(nodeId, "seed", "$title Seed"), classType, seed, "seed", isVisible = isVisible(nodeId, classType, "seed")))
                        val steps = (inputs["steps"] as? Number) ?: 20
                        list.add(EditableNode.NumberInput(nodeId, resolveTitle(nodeId, "steps", "$title Steps"), classType, steps, "steps", 1.0, 100.0, isVisible(nodeId, classType, "steps")))
                        val cfg = (inputs["cfg"] as? Number) ?: 8.0
                        list.add(EditableNode.NumberInput(nodeId, resolveTitle(nodeId, "cfg", "$title CFG"), classType, cfg, "cfg", 1.0, 30.0, isVisible(nodeId, classType, "cfg")))
                        consumedKeys.add("seed")
                        consumedKeys.add("steps")
                        consumedKeys.add("cfg")
                     }
                 }

                 for ((key, value) in inputs) {
                     if (consumedKeys.contains(key)) continue
                     if (isInactiveLoraStackSlotKey(classType, inputs, key)) continue
                     if (value is List<*> && value.size == 2 && value[0] is String && value[1] is Number) continue

                    when (value) {
                        is String -> {
                           val options = getComboOptions(classType, key)?.distinct()
                           val mergedOptions = if (!options.isNullOrEmpty()) {
                               val currentValue = value.trim()
                               if (currentValue.isNotBlank() && !options.contains(currentValue)) {
                                   listOf(currentValue) + options
                               } else {
                                   options
                               }
                           } else {
                               null
                           }
                           list.add(EditableNode.TextInput(nodeId, resolveTitle(nodeId, key, "$title $key"), classType, value, mergedOptions, key, isVisible(nodeId, classType, key)))
                        }
                        is Number -> {
                           val objectRange = com.comfyui.mobile.model.WorkflowOptionParser.getNumberRange(objectInfo, classType, key)
                               ?: com.comfyui.mobile.model.fallbackNumberRange(key)
                           val range = if (isStrengthKey(key)) com.comfyui.mobile.model.strengthSliderRange(objectRange) else objectRange
                           list.add(EditableNode.NumberInput(nodeId, resolveTitle(nodeId, key, "$title $key"), classType, value, key, range?.first, range?.second, isVisible(nodeId, classType, key)))
                        }
                        is Boolean -> {
                           list.add(EditableNode.BooleanInput(nodeId, resolveTitle(nodeId, key, "$title $key"), classType, value, key, isVisible(nodeId, classType, key)))
                        }
                    }
                }
            }
        }
        return list
    }

    internal fun buildWorkflowNodeTitles(workflow: Map<String, Any>): Map<String, String> {
        val out = LinkedHashMap<String, String>(workflow.size)
        for ((nodeId, nodeAny) in workflow) {
            val node = nodeAny as? Map<*, *> ?: continue
            val classType = node["class_type"] as? String
            val meta = node["_meta"] as? Map<*, *>
            val title = (meta?.get("title") as? String)?.trim().orEmpty()
            val resolved = when {
                title.isNotBlank() -> title
                !classType.isNullOrBlank() -> classType
                else -> nodeId
            }
            out[nodeId] = resolved
        }
        return out
    }

    internal fun generatePromptSummary(nodes: List<EditableNode>): String {
        return nodes
            .filter { it.isVisible }
            .filterIsInstance<EditableNode.TextInput>()
            .filter { it.key == "text" || it.key == "text_g" || it.key == "text_l" }
            .joinToString("\n") { node ->
                node.text
            }
    }

    internal fun randomizeSeedsForSend(workflow: Map<String, Any>, nextSeed: () -> Int = { kotlin.random.Random.nextInt(1, Int.MAX_VALUE) }): Map<String, Any> {
        val out = LinkedHashMap<String, Any>(workflow.size)
        for ((nodeId, nodeAny) in workflow) {
            val node = nodeAny as? Map<*, *>
            if (node == null) {
                out[nodeId] = nodeAny
                continue
            }

            val mutableNode = LinkedHashMap<String, Any?>(node.size)
            for ((k, v) in node) mutableNode[k?.toString() ?: ""] = v

            val inputsAny = node["inputs"]
            val inputs = inputsAny as? Map<*, *>
            if (inputs != null) {
                val newInputs = LinkedHashMap<String, Any?>(inputs.size)
                for ((k, v) in inputs) newInputs[k?.toString() ?: ""] = v

                if (newInputs.containsKey("seed")) newInputs["seed"] = nextSeed()
                if (newInputs.containsKey("noise_seed")) newInputs["noise_seed"] = nextSeed()

                mutableNode["inputs"] = newInputs
            }

            out[nodeId] = mutableNode
        }
        @Suppress("UNCHECKED_CAST")
        return out as Map<String, Any>
    }
}
