package com.comfyui.mobile.agent

import android.content.SharedPreferences
import com.google.gson.Gson
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

enum class AgentRuleKind(val title: String) {
    DIALOGUE("对话规则"), DRAWING("绘图规则")
}

/** A durable copy of the imported text; no dependency on the external document URI. */
data class AgentRuleDocument(val name: String, val content: String, val importedAt: Long = System.currentTimeMillis())
/**
 * [dialogue]/[drawing] are the active files sent to the model. The libraries keep every imported
 * file so the user can switch between them; null (not empty) when unused so old JSON and
 * `AgentRuleFiles()` compare equal after a round trip through Gson.
 */
data class AgentRuleFiles(
    val dialogue: AgentRuleDocument? = null,
    val drawing: AgentRuleDocument? = null,
    val dialogueLibrary: List<AgentRuleDocument>? = null,
    val drawingLibrary: List<AgentRuleDocument>? = null
) {
    companion object { const val MAX_LIBRARY = 8 }

    fun document(kind: AgentRuleKind) = if (kind == AgentRuleKind.DIALOGUE) dialogue else drawing

    /** Every file of this kind, the active one included even if it predates the library. */
    fun library(kind: AgentRuleKind): List<AgentRuleDocument> {
        val stored = (if (kind == AgentRuleKind.DIALOGUE) dialogueLibrary else drawingLibrary).orEmpty()
        val active = document(kind) ?: return stored
        return if (stored.any { it.name == active.name }) stored.map { if (it.name == active.name) active else it }
            else listOf(active) + stored
    }

    private fun with(kind: AgentRuleKind, active: AgentRuleDocument?, library: List<AgentRuleDocument>): AgentRuleFiles {
        val lib = library.takeIf { it.isNotEmpty() }
        return if (kind == AgentRuleKind.DIALOGUE) copy(dialogue = active, dialogueLibrary = lib)
            else copy(drawing = active, drawingLibrary = lib)
    }

    /** Non-null: add (same name replaces) and activate. Null: delete the active file from the library. */
    fun replace(kind: AgentRuleKind, document: AgentRuleDocument?): AgentRuleFiles {
        val current = library(kind)
        if (document == null) {
            val active = document(kind) ?: return this
            return with(kind, null, current.filterNot { it.name == active.name })
        }
        val rest = current.filterNot { it.name == document.name }
        require(rest.size < MAX_LIBRARY) { "每类规则最多保存 $MAX_LIBRARY 个文件，请先移除不用的" }
        return with(kind, document, listOf(document) + rest)
    }

    /** Switch the active file; null keeps the library but sends no rule of this kind. */
    fun select(kind: AgentRuleKind, name: String?): AgentRuleFiles {
        val current = library(kind)
        val target = name?.let { n -> current.firstOrNull { it.name == n } ?: error("规则文件不存在：$n") }
        return with(kind, target, current)
    }
}

object AgentRuleFileReader {
    const val MAX_BYTES = 512 * 1024

    fun read(name: String, input: InputStream): AgentRuleDocument {
        require(name.substringAfterLast('.', "").lowercase() in setOf("txt", "md", "markdown")) {
            "请选择 TXT 或 Markdown 规则文件"
        }
        val bytes = input.readNBytes(MAX_BYTES + 1)
        require(bytes.size <= MAX_BYTES) { "规则文件请控制在 512 KB 以内" }
        fun decode(charset: Charset, offset: Int = 0): String = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
        val content = when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                decode(Charsets.UTF_16LE, 2)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                decode(Charsets.UTF_16BE, 2)
            else -> try { decode(Charsets.UTF_8).removePrefix("\uFEFF") }
                catch (_: CharacterCodingException) { decode(Charset.forName("GB18030")) }
        }
        require(content.isNotBlank()) { "规则文件内容为空" }
        require(content.none { it.isISOControl() && it !in "\n\r\t" }) { "请选择纯文本规则文件" }
        return AgentRuleDocument(name, content)
    }
}

class AgentRuleFilesStore(private val prefs: SharedPreferences, private val gson: Gson = Gson()) {
    companion object {
        private const val KEY = "agent_imported_rule_files_v1"
        private const val MIGRATED = "agent_imported_rules_migrated"
    }

    fun load(): AgentRuleFiles {
        if (!prefs.getBoolean(MIGRATED, false)) {
            val editor = prefs.edit()
            for (key in listOf("agent_system_prompt", "agent_draw_prompt")) {
                prefs.getString(key, null)?.let { editor.putString("agent_rules_retired_$key", it) }
                editor.remove(key)
            }
            check(editor.putBoolean(MIGRATED, true).commit()) { "旧规则迁移保存失败" }
        }
        return prefs.getString(KEY, null)?.let { gson.fromJson(it, AgentRuleFiles::class.java) } ?: AgentRuleFiles()
    }

    fun save(files: AgentRuleFiles) {
        // Validate the complete replacement before changing any persisted value.
        (AgentRuleKind.entries.flatMap { files.library(it) }).forEach {
            require(it.name.isNotBlank() && it.content.isNotBlank() &&
                it.content.toByteArray(Charsets.UTF_8).size <= AgentRuleFileReader.MAX_BYTES) { "规则文件内容无效或过长" }
        }
        check(prefs.edit().putString(KEY, gson.toJson(files)).putBoolean(MIGRATED, true).commit()) { "规则保存失败，请重试" }
    }
}
