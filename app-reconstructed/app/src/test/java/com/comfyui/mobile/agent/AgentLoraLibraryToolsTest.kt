package com.comfyui.mobile.agent

import com.comfyui.mobile.modelcenter.LocalModel
import com.comfyui.mobile.modelcenter.ModelAnnotation
import com.comfyui.mobile.modelcenter.ModelProvider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AgentLoraLibraryToolsTest {
    private fun args(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    /** A workflow without any LoRA loader: the Agent can still browse and annotate LoRAs. */
    private val plain = AgentWorkflowSnapshot(
        mapOf("3" to mutableMapOf<String, Any>("class_type" to "KSampler", "inputs" to mutableMapOf<String, Any>("steps" to 20))),
        listOf(AgentFieldSpec("3", "steps", "KSampler", AgentFieldKind.INTEGER)))

    private val source = ModelProvider(provider = "Civitai", matchedHash = "b".repeat(64), name = "Kawaii Style", version = "v2",
        baseModel = "Illustrious", trainedWords = listOf("kawaii style"),
        modelDescription = "<p>Cute pastel style.</p><p>Use <b>0.6-0.8</b>.</p>", description = "Version notes")

    private fun kawaii(notes: String = "") = LocalModel(id = "k", serverId = "pc", category = "LORA", present = true,
        name = "KAWAII STYLE_XL", nodeModelName = "style\\KAWAII STYLE_XL.safetensors", sha256 = "b".repeat(64),
        revision = 1, provider = source, annotation = ModelAnnotation(notes = notes))

    private class FakeLibrary(var models: List<LocalModel>) : AgentLoraLibrary {
        var refreshes = 0
        var failure: Exception? = null
        val notes = mutableListOf<AgentLoraNote>()

        override suspend fun refresh(rescan: Boolean): AgentLoraRefresh {
            refreshes++
            failure?.let { throw it }
            return AgentLoraRefresh(AgentLoraCatalog.from(models), scanned = rescan,
                note = if (rescan) "已重新扫描电脑上的模型文件夹" else "")
        }

        override suspend fun detail(item: AgentLoraInfo): AgentLoraDetail {
            failure?.let { throw it }
            return AgentLoraDetail(AgentLoraCatalog.info(models.first { it.id == item.id }))
        }

        override suspend fun annotate(item: AgentLoraInfo, note: AgentLoraNote): AgentLoraAnnotated {
            failure?.let { throw it }
            notes += note
            val model = models.first { it.id == item.id }
            val merge = AgentLoraCatalog.merge(model.annotation, note,
                model.provider?.trainedWords.orEmpty(), model.provider?.baseModel.orEmpty())
            val saved = model.copy(annotation = merge.annotation, revision = model.revision + 1)
            models = models.map { if (it.id == saved.id) saved else it }
            return AgentLoraAnnotated(AgentLoraCatalog.info(saved), merge.applied, merge.skipped)
        }
    }

    @Test fun catalogSplitsTheAgentBlockFromTheUserNotesAndKeepsParagraphs() {
        val info = AgentLoraCatalog.info(kawaii("我常用来画头像\n\n${AgentLoraCatalog.AGENT_MARK}\n粉彩画风，0.7 最稳"))
        assertEquals("我常用来画头像", info.notes)
        assertEquals("粉彩画风，0.7 最稳", info.agentNotes)
        assertEquals("Cute pastel style.\nUse 0.6-0.8 .\n\nVersion notes", info.fullDescription)
        assertEquals("Version notes", info.description)
        assertEquals(listOf("kawaii style"), info.sourceTriggerWords)
        assertEquals("Civitai · Kawaii Style · v2", info.source)
        assertEquals("style\\KAWAII STYLE_XL.safetensors", info.file)
        assertEquals(listOf("KAWAII STYLE_XL"), AgentLoraCatalog.search(listOf(info), "粉彩").map { it.name })
    }

    @Test fun mergeOnlyReplacesTheAgentBlockAndFillsEmptyUnlockedFields() {
        val current = ModelAnnotation(notes = "用户备注\n\n${AgentLoraCatalog.AGENT_MARK}\n旧笔记", tags = listOf("cute"),
            outfit = "school uniform", fieldLocks = listOf("character"))
        val merge = AgentLoraCatalog.merge(current, AgentLoraNote(notes = "新笔记", tags = listOf("pastel", "Cute"),
            useCases = listOf("画风"), character = "Miku", outfit = "dress", baseModel = "SDXL",
            triggerWords = listOf("kawaii"), preferredStrength = 0.7),
            sourceTriggerWords = listOf("kawaii style"), sourceBaseModel = "Illustrious")
        val next = merge.annotation
        assertEquals("用户备注\n\n${AgentLoraCatalog.AGENT_MARK}\n新笔记", next.notes)
        assertEquals(listOf("cute", "pastel"), next.tags)
        assertEquals(listOf("style"), next.useCases)
        assertEquals("", next.character)
        assertEquals("school uniform", next.outfit)
        assertEquals("", next.baseModel)
        assertEquals(emptyList<String>(), next.trainedWords)
        assertEquals(0.7, next.preferredStrength!!, 0.0)
        assertEquals(listOf("character"), next.fieldLocks)
        // Fields beyond the notes show up in the Model Center as "AI 建议待确认".
        assertEquals("ai-suggestion", next.classificationSource)
        assertEquals("suggested", next.classificationStatus)
        // Locked character, user outfit, source base model and source trigger words are all left alone.
        assertEquals(4, merge.skipped.size)
        assertEquals("用户备注", AgentLoraCatalog.merge(next, AgentLoraNote(notes = "")).annotation.notes)
    }

    @Test fun confirmedClassificationOnlyLetsTheAgentChangeItsOwnNotes() {
        val current = ModelAnnotation(notes = "用户备注", useCases = listOf("style"), classificationStatus = "confirmed")
        val merge = AgentLoraCatalog.merge(current, AgentLoraNote(notes = "画风", tags = listOf("pastel"), character = "Miku"))
        assertEquals(current.copy(notes = "用户备注\n\n${AgentLoraCatalog.AGENT_MARK}\n画风"), merge.annotation)
        assertEquals(listOf("Agent 笔记"), merge.applied)
        assertEquals(listOf("其他标注（用户已确认分类，未改动）"), merge.skipped)
        val notesOnly = AgentLoraCatalog.merge(ModelAnnotation(), AgentLoraNote(notes = "画风")).annotation
        assertEquals("user" to "unclassified", notesOnly.classificationSource to notesOnly.classificationStatus)
        val full = AgentLoraCatalog.merge(ModelAnnotation(notes = "长".repeat(7990)), AgentLoraNote(notes = "超出上限的笔记"))
        assertTrue(full.applied.isEmpty())
        assertTrue(full.skipped.single().contains("${AgentLoraCatalog.MAX_NOTES}"))
    }

    @Test fun browsingToolsWorkWithoutAWorkflowSlotAndRefreshOncePerTurn() = runBlocking {
        val library = FakeLibrary(listOf(kawaii()))
        val box = AgentDrawToolbox(plain, loraLibrary = library)
        val names = box.definitions.map { it.function.name }
        assertTrue(names.containsAll(listOf(AgentDrawToolbox.LIST_LORAS, AgentDrawToolbox.READ_LORA,
            AgentDrawToolbox.REFRESH_LORAS, AgentDrawToolbox.ANNOTATE_LORA)))
        assertFalse(AgentDrawToolbox.SET_LORA in names)
        val result = box.call(AgentDrawToolbox.LIST_LORAS, args("{}"))
        assertTrue(result.ok)
        val data = result.data as Map<*, *>
        assertTrue((data["summary"] as String).startsWith("当前工作流没有 LoRA 槽位"))
        val listed = (data["loras"] as List<*>).single() as Map<*, *>
        assertEquals("style\\KAWAII STYLE_XL.safetensors", listed["file"])
        assertEquals(listOf("kawaii style"), listed["trigger_words"])
        box.call(AgentDrawToolbox.LIST_LORAS, args("""{"query":"kawaii"}"""))
        assertEquals(1, library.refreshes)
    }

    @Test fun readLoraGivesTheFullDescriptionAndBothTriggerListsAndFallsBackToTheCache() = runBlocking {
        val model = kawaii().copy(annotation = ModelAnnotation(
            notes = "${AgentLoraCatalog.AGENT_MARK}\n适合粉彩头像", trainedWords = listOf("kawaii")))
        val library = FakeLibrary(listOf(model))
        val box = AgentDrawToolbox(plain, loraLibrary = library)
        val read = box.call(AgentDrawToolbox.READ_LORA, args("""{"lora":"KAWAII STYLE_XL"}"""))
        assertTrue(read.error, read.ok)
        val data = read.data as Map<*, *>
        assertEquals(listOf("kawaii"), data["trigger_words"])
        assertEquals(listOf("kawaii style"), data["source_trigger_words"])
        assertTrue((data["description"] as String).startsWith("Cute pastel style.\nUse 0.6-0.8"))
        assertEquals("适合粉彩头像", data["agent_notes"])
        assertEquals(false, data["loadable"])
        library.failure = IllegalStateException("连接超时")
        val cached = box.call(AgentDrawToolbox.READ_LORA, args("""{"lora":"style/kawaii style_xl.safetensors"}"""))
        assertTrue(cached.ok)
        assertTrue(((cached.data as Map<*, *>)["notice"] as String).contains("连接超时"))
        assertFalse(box.call(AgentDrawToolbox.READ_LORA, args("""{"lora":"nope"}""")).ok)
    }

    @Test fun annotateLoraWritesThroughTheLibraryAndShowsUpInLaterListings() = runBlocking {
        val library = FakeLibrary(listOf(kawaii("用户备注")))
        val box = AgentDrawToolbox(plain, loraLibrary = library)
        val written = box.call(AgentDrawToolbox.ANNOTATE_LORA, args(
            """{"lora":"KAWAII STYLE_XL","notes":"粉彩画风，头像 0.7 最稳","tags":["pastel"],"preferred_strength":0.7}"""))
        assertTrue(written.error, written.ok)
        assertTrue(((written.data as Map<*, *>)["summary"] as String).startsWith("已写入"))
        val saved = library.models.single().annotation
        assertEquals("用户备注\n\n${AgentLoraCatalog.AGENT_MARK}\n粉彩画风，头像 0.7 最稳", saved.notes)
        assertEquals(0.7, saved.preferredStrength!!, 0.0)
        val listed = ((box.call(AgentDrawToolbox.LIST_LORAS, args("{}")).data as Map<*, *>)["loras"] as List<*>).single() as Map<*, *>
        assertEquals("用户备注", listed["notes"])
        assertEquals("粉彩画风，头像 0.7 最稳", listed["agent_notes"])
        val tooLong = box.call(AgentDrawToolbox.ANNOTATE_LORA,
            args("""{"lora":"KAWAII STYLE_XL","notes":"${"长".repeat(801)}"}"""))
        assertTrue(tooLong.error!!.contains("过长"))
        assertFalse(box.call(AgentDrawToolbox.ANNOTATE_LORA, args("""{"lora":"KAWAII STYLE_XL"}""")).ok)
        assertEquals(1, library.notes.size)
    }

    @Test fun refreshReportsAddedAndRemovedFilesAndKeepsTheCacheWhenOffline() = runBlocking {
        val old = LocalModel(id = "o", category = "LORA", present = true, name = "old", nodeModelName = "old.safetensors")
        val library = FakeLibrary(listOf(kawaii()))
        val box = AgentDrawToolbox(plain, loras = AgentLoraCatalog.from(listOf(old)), loraLibrary = library)
        val result = box.call(AgentDrawToolbox.REFRESH_LORAS, args("""{"rescan":true}"""))
        assertTrue(result.error, result.ok)
        val data = result.data as Map<*, *>
        assertEquals(listOf("style\\KAWAII STYLE_XL.safetensors"), data["added"])
        assertEquals(listOf("old.safetensors"), data["removed"])
        assertTrue((data["summary"] as String).contains("已重新扫描"))
        library.failure = IllegalStateException("电脑离线")
        val offline = box.call(AgentDrawToolbox.REFRESH_LORAS, args("{}"))
        assertTrue(offline.error!!.contains("电脑离线"))
        assertEquals(1, ((box.call(AgentDrawToolbox.LIST_LORAS, args("{}")).data as Map<*, *>)["loras"] as List<*>).size)
    }

    @Test fun listFallsBackToTheCachedLorasWhenTheComputerDoesNotAnswer() = runBlocking {
        val library = FakeLibrary(emptyList()).apply { failure = IllegalStateException("连接超时") }
        val box = AgentDrawToolbox(plain, loras = AgentLoraCatalog.from(listOf(kawaii())), loraLibrary = library)
        val data = box.call(AgentDrawToolbox.LIST_LORAS, args("{}")).data as Map<*, *>
        assertTrue((data["summary"] as String).contains("手机缓存"))
        assertEquals(1, (data["loras"] as List<*>).size)
    }
}
