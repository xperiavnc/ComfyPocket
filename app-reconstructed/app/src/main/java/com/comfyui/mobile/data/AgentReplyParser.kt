package com.comfyui.mobile.data

import com.comfyui.mobile.model.AgentClarification
import com.comfyui.mobile.model.AgentQuestionOption
import com.comfyui.mobile.model.AgentUserQuestion
import com.comfyui.mobile.agent.AgentAction
import com.comfyui.mobile.agent.AgentPromptAlias
import com.comfyui.mobile.agent.AgentPromptAliases
import com.comfyui.mobile.model.AgentCharacterSelection
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import java.io.StringReader

/**
 * Parses the Draw-mode reply including separate positive and negative prompt fields.
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
        val error: String? = null,
        /** Missing / invalid = preserve existing; explicit "" = clear existing. */
        val negativePrompt: String? = null,
        val clarification: AgentClarification? = null,
        val action: AgentAction? = null,
        val outfitSelections: List<AgentCharacterSelection>? = null,
        val positiveAliases: List<AgentPromptAlias>? = null,
        val negativeAliases: List<AgentPromptAlias>? = null
    )

    private val PROMPT_KEYS = listOf("prompt", "tags", "positive")
    private val NEGATIVE_KEYS = listOf("negative_prompt", "negativePrompt", "negative", "反向提示词", "负向提示词")
    private val REPLY_KEYS = listOf("reply", "tip", "message")
    private val DRAW_KEYS = listOf("should_draw", "draw", "shouldDraw")
    private val CLARIFICATION_KEYS = listOf("needs_clarification", "needsClarification", "ask_user")
    private val CLARIFICATION_QUESTION_KEYS = listOf("clarification_question", "clarificationQuestion", "question")
    private val CLARIFICATION_OPTION_KEYS = listOf("clarification_options", "clarificationOptions", "options")

    fun parse(raw: String, preserveNaturalLanguage: Boolean = false): Result {
        val text = stripFences(raw).trim()
        if (text.isEmpty()) return Result("", "", null, "空响应")

        // 1. An interaction wins over stray prompt objects. Inspect decoded fields, not quoted text.
        val candidates = balancedObjects(text)
        val ordered = candidates.mapNotNull { parseObject(it) ?: parseObject(repairJson(it)) }
            .sortedByDescending { obj ->
                if (actionField(stringField(obj, listOf("action"))) == AgentAction.ASK_USER ||
                    boolField(obj, CLARIFICATION_KEYS) == true) 2
                else if (PROMPT_KEYS.any(obj::has)) 1 else 0
            }
        for (obj in ordered) {
            val reply = cleanReply(stringField(obj, REPLY_KEYS))
            val prompt = sanitizePrompt(stringField(obj, PROMPT_KEYS), preserveNaturalLanguage)
            val negative = optionalPromptField(obj, NEGATIVE_KEYS, preserveNaturalLanguage)
            val draw = boolField(obj, DRAW_KEYS)
            val action = actionField(stringField(obj, listOf("action")))
            val clarification = clarificationField(obj, action)
            if (clarification != null) return questionResult(reply, clarification)
            if (action == AgentAction.RESPOND) return Result(reply, "", false, action = action)
            if (prompt.isNotEmpty() || reply.isNotEmpty() || negative != null || action != null) {
                return Result(reply, prompt, draw,
                    if (prompt.isEmpty() && draw != false) "JSON 中没有有效的 prompt" else null,
                    negative, action = action, outfitSelections = outfitSelectionsField(obj),
                    positiveAliases = AgentPromptAliases.parse(obj["prompt_aliases"]),
                    negativeAliases = AgentPromptAliases.parse(obj["negative_prompt_aliases"]))
            }
        }

        // 2. Truncated / malformed JSON: salvage complete string fields only.
        val hasLabelledPrompt = Regex(
            """(?im)^\s*(?:prompt|tags|positive|正向提示词|正面提示词|提示词)\s*[:：]""").containsMatchIn(text)
        if ((text.contains('{') || text.contains("\"prompt\"")) && !hasLabelledPrompt) {
            val reply = cleanReply(salvageString(text, REPLY_KEYS))
            val prompt = sanitizePrompt(salvageString(text, PROMPT_KEYS), preserveNaturalLanguage)
            val negative = salvageOptionalString(text, NEGATIVE_KEYS)?.let { sanitizeOptionalPrompt(it, preserveNaturalLanguage) }
            val draw = salvageBool(text, DRAW_KEYS)
            val action = actionField(salvageString(text, listOf("action")))
            val asks = action == AgentAction.ASK_USER || salvageBool(text, CLARIFICATION_KEYS) == true ||
                Regex(""""action"\s*:\s*"ask_user(?:_question)?(?:["\s,}]|$)""").containsMatchIn(text)
            if (asks) {
                // Truncation must not turn a question into a stale-prompt render.
                val question = salvageString(text, CLARIFICATION_QUESTION_KEYS).ifBlank { reply }
                return questionResult(reply, freeQuestion(question))
            }
            if (action == AgentAction.RESPOND) return Result(reply, "", false, action = action)
            return Result(reply, prompt, draw, if (prompt.isEmpty() && draw != false) "模型返回的 JSON 无法解析" else null, negative, action = action)
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

    private fun optionalPromptField(obj: JsonObject, keys: List<String>, natural: Boolean): String? {
        for (key in keys) {
            val value = obj[key] ?: continue
            val raw = when {
                value.isJsonPrimitive && value.asJsonPrimitive.isString -> value.asString
                value.isJsonArray && value.asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isString } ->
                    value.asJsonArray.joinToString(", ") { it.asString }
                else -> return null
            }
            return sanitizeOptionalPrompt(raw, natural)
        }
        return null
    }

    private fun sanitizeOptionalPrompt(raw: String, natural: Boolean): String? =
        sanitizePrompt(raw, natural).takeIf { raw.isBlank() || it.isNotBlank() }

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

    private fun stringArrayField(obj: JsonObject, keys: List<String>): List<String> {
        for (key in keys) {
            val value = obj[key] ?: continue
            if (!value.isJsonArray) return emptyList()
            return value.asJsonArray.mapNotNull { item ->
                item.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.asString?.trim()?.takeIf(String::isNotBlank)
            }.distinct().take(5)
        }
        return emptyList()
    }

    private fun outfitSelectionsField(obj: JsonObject): List<AgentCharacterSelection>? {
        val array = obj["outfit_selections"]?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        return array.take(20).mapNotNull { value ->
            if (!value.isJsonObject) return@mapNotNull null
            val item = value.asJsonObject
            val character = stringField(item, listOf("character_id")).trim()
            val outfit = stringField(item, listOf("outfit_id")).trim().ifBlank { null }
            val custom = boolField(item, listOf("custom_outfit")) == true
            if (character.isBlank() || (outfit == null && !custom) || (outfit != null && custom)) null
            else AgentCharacterSelection(character, outfit, custom)
        }.ifEmpty { null }
    }

    private fun actionField(value: String): AgentAction? = when (value.trim().lowercase()) {
        "ask_user", "ask_user_question" -> AgentAction.ASK_USER
        "respond", "reply" -> AgentAction.RESPOND
        "apply_prompt", "prompt_only" -> AgentAction.APPLY_PROMPT
        "draw" -> AgentAction.DRAW
        else -> null
    }

    private fun questionResult(reply: String, clarification: AgentClarification) = Result(
        reply.ifBlank { clarification.question }, "", false, clarification = clarification, action = AgentAction.ASK_USER)

    private fun freeQuestion(question: String) = AgentClarification(
        question.ifBlank { "请补充本轮任务需要确认的信息。" }.take(8000),
        emptyList(), purpose = "agent_question",
        questions = listOf(AgentUserQuestion("q1",
            question.ifBlank { "请补充本轮任务需要确认的信息。" }.take(8000), "free_text"))
    )

    private fun questionsField(obj: JsonObject): List<AgentUserQuestion> {
        val values = obj["questions"]?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        val ids = mutableSetOf<String>()
        return values.take(6).mapIndexedNotNull { index, value ->
            if (!value.isJsonObject) return@mapIndexedNotNull null
            val item = value.asJsonObject
            val prompt = stringField(item, listOf("prompt", "question")).trim().take(2000)
            if (prompt.isBlank()) return@mapIndexedNotNull null
            val proposed = stringField(item, listOf("id")).trim().take(80).ifBlank { "q${index + 1}" }
            var id = proposed
            while (!ids.add(id)) id += "_${index + 1}"
            val optionIds = mutableSetOf<String>()
            val options = item["options"]?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()
                .take(8).mapIndexedNotNull option@ { optionIndex, option ->
                    val label = when {
                        option.isJsonObject -> stringField(option.asJsonObject, listOf("label")).trim()
                        option.isJsonPrimitive && option.asJsonPrimitive.isString -> option.asString.trim()
                        else -> ""
                    }.take(1000)
                    if (label.isBlank()) return@option null
                    val optionId = if (option.isJsonObject) stringField(option.asJsonObject, listOf("id")).trim().take(80)
                        .ifBlank { "o${optionIndex + 1}" } else "o${optionIndex + 1}"
                    if (!optionIds.add(optionId)) return@option null
                    val description = if (option.isJsonObject)
                        stringField(option.asJsonObject, listOf("description")).trim().take(2000).ifBlank { null }
                    else null
                    AgentQuestionOption(optionId, label, description)
                }
            val free = stringField(item, listOf("kind", "type")) == "free_text" || options.isEmpty()
            AgentUserQuestion(
                id, prompt, if (free) "free_text" else "multiple_choice",
                if (free) null else options, !free && boolField(item, listOf("allow_multiple", "allowMultiple", "multi_select")) == true,
                stringField(item, listOf("placeholder")).trim().take(200).ifBlank { null }
            )
        }
    }

    private fun clarificationField(obj: JsonObject, action: AgentAction?): AgentClarification? {
        if (action == AgentAction.ASK_USER) {
            val questions = questionsField(obj)
            return if (questions.isNotEmpty()) AgentClarification(
                "请确认以下问题", emptyList(), purpose = "agent_question", questions = questions)
            else freeQuestion(stringField(obj, CLARIFICATION_QUESTION_KEYS).ifBlank { stringField(obj, REPLY_KEYS) })
        }
        val requested = boolField(obj, CLARIFICATION_KEYS)
        if (requested == false) return null
        val explicitQuestion = stringField(obj, listOf("clarification_question", "clarificationQuestion")).trim()
        val question = explicitQuestion.ifBlank {
            if (requested == true) stringField(obj, CLARIFICATION_QUESTION_KEYS).trim() else ""
        }
        if (question.isBlank() && requested != true) return null
        return AgentClarification(
            question.ifBlank { "请先确认本轮要采用的提示词。" }.take(400),
            stringArrayField(obj, CLARIFICATION_OPTION_KEYS)
        )
    }

    fun parseBoolString(s: String): Boolean? = when (s.trim().lowercase()) {
        "true", "1", "yes", "y", "是", "要", "画", "出图" -> true
        "false", "0", "no", "n", "否", "不", "别画", "不出图" -> false
        else -> null
    }

    /** `"key" : "value"` with a fully closed string. */
    private fun salvageString(text: String, keys: List<String>): String =
        salvageOptionalString(text, keys).orEmpty()

    private fun salvageOptionalString(text: String, keys: List<String>): String? {
        for (k in keys) {
            val m = Regex(""""${Regex.escape(k)}"\s*:\s*"((?:\\.|[^"\\])*)"""").find(text) ?: continue
            return unescape(m.groupValues[1])
        }
        return null
    }

    private fun salvageBool(text: String, keys: List<String>): Boolean? {
        for (k in keys) {
            val m = Regex(""""${Regex.escape(k)}"\s*:\s*"?(true|false|1|0)"?""", RegexOption.IGNORE_CASE).find(text) ?: continue
            return parseBoolString(m.groupValues[1])
        }
        return null
    }

    private fun parseLabelled(text: String, preserveNaturalLanguage: Boolean): Result {
        val labels = Regex("""(?im)^[ \t]*[\*#\-\[\(]*(negative_prompt_aliases|prompt_aliases|outfit_selections|正向中文别名|反向中文别名|negative_prompt|negativePrompt|negative prompt|negative|反向提示词|负向提示词|负面提示词|prompt|tags|positive|正向提示词|正面提示词|提示词|tip|reply|回复|should_draw)[\*\]\)]*[ \t]*[:：][ \t]*""")
        val matches = labels.findAll(text).toList()
        val sections = matches.mapIndexed { index, match ->
            match.groupValues[1].lowercase() to text.substring(
                match.range.last + 1, matches.getOrNull(index + 1)?.range?.first ?: text.length
            ).trim()
        }
        val prompt = sections.firstOrNull { it.first in setOf("prompt", "tags", "positive", "正向提示词", "正面提示词", "提示词") }
            ?.second?.let { sanitizePrompt(it, preserveNaturalLanguage) }.orEmpty()
        val negative = sections.firstOrNull {
            it.first in setOf("negative_prompt", "negativeprompt", "negative prompt", "negative", "反向提示词", "负向提示词", "负面提示词")
        }?.second?.let { sanitizeOptionalPrompt(it, preserveNaturalLanguage) }
        val before = sections.firstOrNull { it.first in setOf("tip", "reply", "回复") }?.second
            ?: text.substring(0, matches.firstOrNull()?.range?.first ?: text.length)
        val reply = cleanReply(before.replace(
            Regex("""(?im)^\s*[\*#\-_\[\(]*(?:tip|reply|回复|提示)[\*\]\)]*\s*[:：]\s*"""), ""))
        val draw = sections.firstOrNull { it.first == "should_draw" }?.second?.let(::parseBoolString)
        fun aliases(vararg keys: String) = sections.firstOrNull { it.first in keys }?.second?.let {
            runCatching { AgentPromptAliases.parse(JsonParser.parseString(it)) }.getOrNull()
        }
        return Result(reply, prompt, draw,
            if (prompt.isEmpty() && reply.isEmpty()) "未找到 prompt 字段" else null, negative,
            positiveAliases = aliases("prompt_aliases", "正向中文别名"),
            negativeAliases = aliases("negative_prompt_aliases", "反向中文别名"))
    }

    // ---- sanitizers ----

    private val KEY_PREFIX = Regex("""(?i)^["'\s]*(negative_prompt|negativePrompt|negative|prompt|reply|tip|tags|positive|should_draw|shoulddraw|draw|message)["'\s]*[:：]\s*""")
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
        return t
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
