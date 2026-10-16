package com.comfyui.mobile.network

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Turns a `/prompt` response WITHOUT a prompt_id into a readable Chinese message. Handles
 * - ComfyUI: `{"error":{type,message,details},"node_errors":{"<id>":{"errors":[{type,message,details,extra_info}],"class_type":".."}}}`
 *   (local ComfyUI answers HTTP 400; the RunningHub proxy may answer HTTP 200 with the same body),
 * - RunningHub envelopes `{code,msg,data}`, whose data/msg may wrap the ComfyUI error (object or JSON string),
 * - empty `{}` bodies (the RH proxy answers `{}` e.g. for an invalid key).
 * Every output is redacted (no API keys).
 */
object PromptErrorParser {

    data class Parsed(val promptId: String?, val message: String?)

    fun parse(body: String?, httpCode: Int): Parsed {
        val raw = body?.trim().orEmpty()
        if (raw.isEmpty()) return Parsed(null, "服务器返回空响应，未返回 prompt_id (HTTP $httpCode)")
        val root = runCatching { JsonParser.parseString(raw) }.getOrNull()
        if (root == null || !root.isJsonObject) {
            return Parsed(null, "服务器返回了无法解析的内容 (HTTP $httpCode): ${SecretRedactor.redact(raw.take(200))}")
        }
        val o = root.asJsonObject
        val pid = str(o.get("prompt_id"))?.trim()?.takeIf { it.isNotBlank() }
        if (pid != null) return Parsed(pid, null)

        val comfy = findComfyError(o, 0)
        val rhCode = intOf(o.get("code"))
        val rhMsg = (str(o.get("msg")) ?: str(o.get("message")))?.trim()?.takeIf { it.isNotBlank() && !it.startsWith("{") }
        val parts = mutableListOf<String>()
        if (rhCode != null && rhCode != 0 && rhCode != 200) {
            parts += RhError.message(rhCode, if (comfy != null) null else rhMsg)
        }
        if (comfy != null) {
            parts += comfy
        } else if (parts.isEmpty() && rhMsg != null && !rhMsg.equals("success", true)) {
            parts += rhMsg
        }
        if (parts.isEmpty()) {
            val hint = if (o.size() == 0) {
                "服务器返回 {}，未返回 prompt_id（若为 RunningHub，请检查 API Key 是否有效）"
            } else {
                "服务器未返回 prompt_id: ${SecretRedactor.redact(raw.take(200))}"
            }
            return Parsed(null, "$hint (HTTP $httpCode)")
        }
        return Parsed(null, SecretRedactor.redact(parts.joinToString("；")))
    }

    private fun findComfyError(o: JsonObject, depth: Int): String? {
        if (depth > 3) return null
        describeComfy(o)?.let { return it }
        for (key in listOf("data", "msg", "message", "error", "detail", "details", "errorMessages")) {
            val el = o.get(key) ?: continue
            val obj = asObject(el) ?: continue
            if (obj === o) continue
            findComfyError(obj, depth + 1)?.let { return it }
        }
        return null
    }

    private fun asObject(el: JsonElement): JsonObject? = when {
        el.isJsonObject -> el.asJsonObject
        el.isJsonPrimitive && el.asJsonPrimitive.isString -> el.asString.trim().takeIf { it.startsWith("{") }?.let { s ->
            runCatching { JsonParser.parseString(s) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
        }
        else -> null
    }

    /** Describes a ComfyUI {error, node_errors} object; null when it has neither. */
    private fun describeComfy(o: JsonObject): String? {
        val errEl = o.get("error")
        val errObj = errEl?.takeIf { it.isJsonObject }?.asJsonObject
        val errStr = errEl?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotBlank() && !it.startsWith("{") }
        val nodeErrs = o.get("node_errors")?.takeIf { it.isJsonObject }?.asJsonObject
        val hasNodeErrors = nodeErrs != null && nodeErrs.size() > 0
        if (errObj == null && errStr == null && !hasNodeErrors) return null

        val sb = StringBuilder()
        when {
            errObj != null -> {
                val type = str(errObj.get("type"))
                val msg = str(errObj.get("message"))?.trim()
                val det = str(errObj.get("details"))?.trim()
                val label = typeLabel(type)
                sb.append(label ?: msg?.takeIf { it.isNotBlank() } ?: "工作流校验失败")
                if (!hasNodeErrors) {
                    val extra = listOfNotNull(
                        msg?.takeIf { label != null && it.isNotBlank() },
                        det?.takeIf { it.isNotBlank() }
    ).joinToString(": ")
                    if (extra.isNotBlank()) sb.append("（").append(shorten(extra)).append("）")
                }
            }
            errStr != null -> sb.append(errStr.take(300))
            else -> sb.append("工作流校验失败")
        }
        if (hasNodeErrors) {
            val entries = nodeErrs!!.entrySet().toList()
            val items = entries.take(3).map { (nodeId, v) ->
                val n = v.takeIf { it.isJsonObject }?.asJsonObject
                val cls = str(n?.get("class_type"))?.takeIf { it.isNotBlank() }
                val errs = n?.get("errors")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
                    .orEmpty()
                val text = errs.take(2).joinToString("；") { e -> describeNodeError(e) }.ifBlank { "参数错误" }
                "节点 #$nodeId" + (cls?.let { " $it" } ?: "") + ": " + text
            }
            sb.append("：").append(items.joinToString("；"))
            if (entries.size > 3) sb.append(" 等 ${entries.size} 个节点")
        }
        return sb.toString()
    }

    private fun describeNodeError(e: JsonObject): String {
        val type = str(e.get("type"))
        val msg = str(e.get("message"))?.trim()
        val det = str(e.get("details"))?.trim()
        val input = e.get("extra_info")?.takeIf { it.isJsonObject }?.asJsonObject?.get("input_name")?.let { str(it) }?.trim()
        val label = typeLabel(type)
        return buildString {
            val detail = det?.takeIf { it.isNotBlank() } ?: msg?.takeIf { label != null && it.isNotBlank() }
            if (!input.isNullOrBlank() && detail?.startsWith(input) != true) append(input).append(" ")
            append(label ?: msg?.takeIf { it.isNotBlank() } ?: "参数错误")
            if (!detail.isNullOrBlank()) append("（").append(shorten(detail)).append("）")
        }
    }

    /** "lora_name: 'x.safetensors' not in ['a', 'b', ...]" -> drop the (huge) option list. */
    private fun shorten(s: String): String {
        val t = s.trim()
        val idx = t.indexOf(" not in [")
        val cut = if (idx > 0) t.substring(0, idx) + " 不在可选列表中" else t
        return cut.take(240)
    }

    private fun typeLabel(type: String?): String? = when (type?.trim()) {
        "prompt_outputs_failed_validation" -> "工作流校验失败"
        "invalid_prompt" -> "工作流格式无效"
        "prompt_no_outputs" -> "工作流没有输出节点"
        "value_not_in_list" -> "取值不在可选列表中"
        "required_input_missing" -> "缺少必填输入"
        "value_smaller_than_min" -> "数值小于允许的最小值"
        "value_bigger_than_max" -> "数值大于允许的最大值"
        "invalid_input_type" -> "输入类型错误"
        "return_type_mismatch" -> "节点连接类型不匹配"
        "bad_linked_input" -> "节点连接无效"
        "exception_during_validation", "exception_during_inner_validation" -> "节点校验时出现异常"
        "custom_validation_failed" -> "节点自定义校验失败"
        "missing_node_type" -> "缺少节点类型（服务器未安装该节点）"
        else -> null
    }

    private fun str(el: JsonElement?): String? = when {
        el == null || el.isJsonNull -> null
        el.isJsonPrimitive -> el.asString
        else -> el.toString()
    }

    private fun intOf(el: JsonElement?): Int? = str(el)?.trim()?.toDoubleOrNull()?.toInt()
}