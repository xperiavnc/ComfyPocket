package com.comfyui.mobile.data

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import java.io.StringReader

/**
 * Parses the Draw-mode LLM reply `{"reply": ..., "prompt": ..., "should_draw": ...}`.
 * Pure Kotlin (no Android) so it is unit-testable.
 *
 * Extracts only the prompt field, never the serialized reply envelope. Anima/natural mode keeps
 * visual sentences and quoted image text. When no valid prompt is found it is "".
 */
object AgentReplyParser {

    data class Result(
        val reply: String,
        val prompt: String,
        /** null = field missing / unreadable. */
        val shouldDraw: Boolean?,
        /** Short reason when no usable prompt was extracted. */
        val error: String? = null
    )

    private val PROMPT_KEYS = listOf("prompt", "tags", "positive")
    private val REPLY_KEYS = listOf("reply", "tip", "message")
    private val DRAW_KEYS = listOf("should_draw", "draw", "shouldDraw")

    fun parse(raw: String, preserveNaturalLanguage: Boolean = false): Result {
        val text = stripFences(raw).trim()
        if (text.isEmpty()) return Result("", "", null, "空响应")

        // 1. Balanced JSON objects (string/escape aware), prefer the one carrying a prompt key.
        val candidates = balancedObjects(text)
        val ordered = candidates.sortedByDescending { c -> PROMPT_KEYS.any { c.contains("\"$it\"") } }
        for (candidate in ordered) {
            val obj = parseObject(candidate) ?: parseObject(repairJson(candidate)) ?: continue
            val reply = cleanReply(stringField(obj, REPLY_KEYS))
            val prompt = sanitizePrompt(stringField(obj, PROMPT_KEYS), preserveNaturalLanguage)
            val draw = boolField(obj, DRAW_KEYS)
            if (prompt.isNotEmpty() || reply.isNotEmpty()) {
                return Result(reply, prompt, draw, if (prompt.isEmpty()) "JSON 中没有有效的 prompt" else null)
            }
        }

        // 2. Truncated / malformed JSON: salvage complete string fields only.
        if (text.contains('{') || text.contains("\"prompt\"")) {
            val reply = cleanReply(salvageString(text, REPLY_KEYS))
            val prompt = sanitizePrompt(salvageString(text, PROMPT_KEYS), preserveNaturalLanguage)
            val draw = salvageBool(text, DRAW_KEYS)
            return Result(reply, prompt, draw, if (prompt.isEmpty()) "模型返回的 JSON 无法解析" else null)
        }

        // 3. Plain-text labelled format ("回复：… / prompt: …"); never dump the whole reply.
        return parseLabelled(text, preserveNaturalLanguage)
    }

    // ---- extraction ----

    fun stripFences(raw: String): String =
        raw.replace(Regex("""(?im)^\s*```[a-zA-Z0-9_-]*\s*$"""), "")
            .replace("```", "")

    /** Top-level `{…}` spans, tracking quotes and escapes so braces inside strings don't count. */
    fun balancedObjects(text: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var start = -1
        var inString = false
        var quote = '"'
        var escape = false
        for ((i, c) in text.withIndex()) {
            if (inString) {
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == quote -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> { inString = true; quote = '"' }
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> if (depth > 0) {
                    depth--
                    if (depth == 0 && start >= 0) { out.add(text.substring(start, i + 1)); start = -1 }
                }
            }
        }
        return out
    }

    private fun parseObject(json: String): JsonObject? = runCatching {
        val reader = JsonReader(StringReader(json)).apply { isLenient = true }
        val el: JsonElement = JsonParser.parseReader(reader)
        if (el.isJsonObject) el.asJsonObject else null
    }.getOrNull()

    /** Smart quotes → ASCII, trailing commas removed. */
    fun repairJson(json: String): String =
        json.replace('\u201c', '"').replace('\u201d', '"')
            .replace('\u2018', '\'').replace('\u2019', '\'')
            .replace('\uff02', '"')
            .replace(Regex(""",\s*([}\]])"""), "$1")

    private fun stringField(obj: JsonObject, keys: List<String>): String {
        for (k in keys) {
            val el = obj.get(k) ?: continue
            if (el.isJsonNull) continue
            return when {
                el.isJsonPrimitive -> el.asString
                el.isJsonArray -> el.asJsonArray.joinToString(", ") { if (it.isJsonPrimitive) it.asString else "" }
                else -> ""
            }
        }
        return ""
    }

    private fun boolField(obj: JsonObject, keys: List<String>): Boolean? {
        for (k in keys) {
            val el = obj.get(k) ?: continue
            if (!el.isJsonPrimitive) return null
            val p = el.asJsonPrimitive
            return when {
                p.isBoolean -> p.asBoolean
                p.isNumber -> p.asDouble != 0.0
                else -> parseBoolString(p.asString)
            }
        }
        return null
    }

    fun parseBoolString(s: String): Boolean? = when (s.trim().lowercase()) {
        "true", "1", "yes", "y", "是", "要", "画", "出图" -> true
        "false", "0", "no", "n", "否", "不", "别画", "不出图" -> false
        else -> null
    }

    /** `"key" : "value"` with a fully closed string. */
    private fun salvageString(text: String, keys: List<String>): String {
        for (k in keys) {
            val m = Regex(""""${Regex.escape(k)}"\s*:\s*"((?:\\.|[^"\\])*)"""").find(text) ?: continue
            return unescape(m.groupValues[1])
        }
        return ""
    }

    private fun salvageBool(text: String, keys: List<String>): Boolean? {
        for (k in keys) {
            val m = Regex(""""${Regex.escape(k)}"\s*:\s*"?(true|false|1|0)"?""", RegexOption.IGNORE_CASE).find(text) ?: continue
            return parseBoolString(m.groupValues[1])
        }
        return null
    }

    private fun parseLabelled(text: String, preserveNaturalLanguage: Boolean): Result {
        val promptLabel = Regex("""(?im)^\s*[\*#\-_\[\(]*(?:prompt|tags|positive|正面提示词|提示词)[\*\]\)]*\s*[:：]\s*""")
        val m = promptLabel.find(text)
        val prompt = if (m != null) sanitizePrompt(text.substring(m.range.last + 1), preserveNaturalLanguage) else ""
        val before = if (m != null) text.substring(0, m.range.first) else text
        val reply = cleanReply(
            before.replace(Regex("""(?im)^\s*[\*#\-_\[\(]*(?:tip|reply|回复|提示)[\*\]\)]*\s*[:：]\s*"""), "")
                .lines().firstOrNull { line -> line.any { it in '\u4e00'..'\u9fa5' } }.orEmpty()
        )
        return Result(reply, prompt, null, if (prompt.isEmpty()) "未找到 prompt 字段" else null)
    }

    // ---- sanitizers ----

    private val KEY_PREFIX = Regex("""(?i)^["'\s]*(prompt|reply|tip|tags|positive|should_draw|shoulddraw|draw|message)["'\s]*[:：]\s*""")
    private val JSONISH = Regex("""(?i)("\s*:|:\s*"|\bshould_draw\b)""")

    /**
     * Tag mode strips leaked envelope keys and conversational filler without damaging model syntax.
     * Mixed/natural mode preserves the already-decoded prompt, including punctuation and image text.
     */
    fun sanitizePrompt(raw: String, preserveNaturalLanguage: Boolean = false): String {
        if (raw.isBlank()) return ""
        // Parsed JSON strings are already unescaped; a second decoding would destroy \(...\).
        var text = stripFences(raw).trim()
        if (preserveNaturalLanguage) {
            text = text.replace(KEY_PREFIX, "").trim()
            if (JSONISH.containsMatchIn(text) || text.startsWith("{\"")) return ""
            return text
        }
        text = text.lines().filterNot { it.trim().startsWith("---") }.joinToString(",")
        val tags = text.split(',', '\n', '，').mapNotNull { piece ->
            var t = piece.trim()
            repeat(3) { t = t.replace(KEY_PREFIX, "").trim() }
            t = t.trim('"', '\'', '`', ' ', '*', '#', ';', '.')
                .trim()
            when {
                t.isEmpty() -> null
                t.any { it in '\u4e00'..'\u9fa5' } -> null
                JSONISH.containsMatchIn(t) -> null
                t.contains('"') || t.none { it.isLetterOrDigit() } -> null
                (t.contains('{') || t.contains('}')) && !Regex("""\{[^{}"]+}""").matches(t) -> null
                t.lowercase().let { l -> l in setOf("true", "false", "null") } -> null
                t.lowercase().startsWith("here is") || t.lowercase().startsWith("here are") ||
                    t.lowercase().startsWith("sure") || t.lowercase().startsWith("okay") ||
                    t.lowercase().startsWith("certainly") -> null
                else -> t
            }
        }
        return tags.distinct().joinToString(", ")
    }

    private fun cleanReply(raw: String): String {
        val t = unescape(raw).trim().removeSurrounding("\"").trim()
        // A reply that is itself JSON is a parse leak, not a reply.
        if (t.startsWith("{") || t.contains("\"prompt\"") || t.contains("should_draw")) return ""
        return t.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    }

    fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'u' -> if (i + 5 < s.length && s.substring(i + 2, i + 6).toIntOrNull(16) != null) {
                        sb.append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 4
                    } else sb.append('u')
                    '"', '\\', '/' -> sb.append(n)
                    else -> sb.append('\\').append(n)
                }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }
}
