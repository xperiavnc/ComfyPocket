package com.comfyui.mobile.modelcenter

import com.comfyui.mobile.agent.AgentLoraCatalog
import com.comfyui.mobile.agent.AgentLoraNote
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelCenterLoraLibraryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val gson = Gson()
    private fun json(data: Any) = MockResponse().setHeader("Content-Type", "application/json")
        .setBody(gson.toJson(ModelEnvelope(code = 0, data = data)))
    private fun lora(id: String, revision: Int = 1, notes: String = "") = LocalModel(id = id, serverId = "pc",
        category = "LORA", present = true, name = id, relativePath = "$id.safetensors", revision = revision,
        annotation = ModelAnnotation(notes = notes))

    @Test fun refreshPagesThroughEveryLoraAndKeepsTheModelCenterSnapshot() = runBlocking {
        MockWebServer().use { server ->
            val directory = temporary.newFolder()
            val identity = "pc|" + server.url("/").toString()
            // The Model Center's own (filtered) snapshot must survive the Agent's full listing.
            LocalModelCache(directory, identity).save(ModelCacheSnapshot(bridgeServerId = "pc", items = listOf(lora("a"),
                LocalModel(id = "ckpt", serverId = "pc", category = "CHECKPOINT", present = true, name = "base"))))
            server.enqueue(json(ModelCapabilities(1, "pc", listOf("model-index"))))
            server.enqueue(json(ModelPage(serverId = "pc", items = listOf(lora("a", revision = 2), lora("b")), hasMore = true)))
            server.enqueue(json(ModelPage(serverId = "pc", items = listOf(lora("c")), page = 2)))
            val library = ModelCenterLoraLibrary(directory, identity, server.url("/").toString(), OkHttpClient())
            val result = library.refresh()
            assertEquals(listOf("a.safetensors", "b.safetensors", "c.safetensors"), result.loras.map { it.file })
            assertEquals("/api/mobile/model-center/capabilities", server.takeRequest().path)
            assertEquals("LORA", server.takeRequest().requestUrl!!.queryParameter("category"))
            assertEquals("2", server.takeRequest().requestUrl!!.queryParameter("page"))
            assertEquals(listOf("a", "ckpt"), LocalModelCache(directory, identity).load().items.map { it.id })
            val cached = ModelCenterLoraLibrary.cached(directory, identity)
            assertEquals(listOf("a.safetensors", "b.safetensors", "c.safetensors"), cached.map { it.file })
            assertEquals("a", cached.first().id)
        }
    }

    @Test fun annotateSendsTheLatestRevisionKeepsUserNotesAndUpdatesTheCache() = runBlocking {
        MockWebServer().use { server ->
            val directory = temporary.newFolder()
            val identity = "pc|" + server.url("/").toString()
            LocalModelCache(directory, identity).save(ModelCacheSnapshot(bridgeServerId = "pc", items = listOf(lora("a"))))
            server.enqueue(json(ModelCapabilities(1, "pc", listOf("model-index"))))
            server.enqueue(json(lora("a", revision = 3, notes = "我的备注")))
            server.enqueue(json(lora("a", revision = 4, notes = "我的备注\n\n${AgentLoraCatalog.AGENT_MARK}\n画风 LoRA")))
            val library = ModelCenterLoraLibrary(directory, identity, server.url("/").toString(), OkHttpClient())
            val item = ModelCenterLoraLibrary.cached(directory, identity).single()
            val result = library.annotate(item, AgentLoraNote(notes = "画风 LoRA"))
            server.takeRequest()
            assertEquals("/api/mobile/model-center/models/a", server.takeRequest().path)
            val write = server.takeRequest()
            assertEquals("POST", write.method)
            assertEquals("/api/mobile/model-center/models/a/annotation", write.path)
            val raw = write.body.readUtf8()
            val body = gson.fromJson(raw, AnnotationWrite::class.java)
            // Every key has to pass the Bridge's validate_annotation (ComfyUI-Mobile-Bridge/model_index.py).
            val allowed = setOf("alias", "notes", "character", "outfit", "baseModel", "classificationSource",
                "classificationStatus", "aliases", "tags", "useCases", "trainedWords", "fieldLocks", "favorite", "preferredStrength")
            val sent = com.google.gson.JsonParser.parseString(raw).asJsonObject.getAsJsonObject("annotation").keySet()
            assertTrue(sent.toString(), allowed.containsAll(sent))
            // A notes-only write leaves the user's classification as it was.
            assertEquals("user", body.annotation.classificationSource)
            assertEquals("unclassified", body.annotation.classificationStatus)
            assertEquals(3, body.expectedRevision)
            assertEquals("我的备注\n\n${AgentLoraCatalog.AGENT_MARK}\n画风 LoRA", body.annotation.notes)
            assertEquals(listOf("Agent 笔记"), result.applied)
            assertEquals("画风 LoRA", result.info.agentNotes)
            assertEquals("我的备注", result.info.notes)
            assertEquals(4, LocalModelCache(directory, identity).load().items.single().revision)
        }
    }

    @Test fun aMovedFileTellsTheAgentToRefreshInsteadOfFailingWithTheRawError() = runBlocking {
        MockWebServer().use { server ->
            val directory = temporary.newFolder()
            val identity = "pc|" + server.url("/").toString()
            LocalModelCache(directory, identity).save(ModelCacheSnapshot(bridgeServerId = "pc", items = listOf(lora("a"))))
            server.enqueue(json(ModelCapabilities(1, "pc", listOf("model-index"))))
            server.enqueue(MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json")
                .setBody(gson.toJson(ModelEnvelope<Any>(code = 404, msg = "模型位置不存在"))))
            val library = ModelCenterLoraLibrary(directory, identity, server.url("/").toString(), OkHttpClient())
            val item = ModelCenterLoraLibrary.cached(directory, identity).single()
            val failure = runCatching { library.annotate(item, AgentLoraNote(notes = "画风 LoRA")) }.exceptionOrNull()
            assertTrue(failure?.message, failure?.message.orEmpty().contains("refresh_loras"))
            assertEquals(2, server.requestCount)
        }
    }
}
