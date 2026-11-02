package com.comfyui.mobile.modelcenter

import com.comfyui.mobile.agent.AgentLoraCatalog
import com.comfyui.mobile.agent.AgentLoraCompletion
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

    /** Phone-side Civitai stand-in: finds [found] by hash and answers 404 for the rest, or fails like a dead network. */
    private class FakeSource(private val found: Set<String>, private val offline: Boolean = false) : LocalModelSource {
        val asked = mutableListOf<String>()
        override val sourceScope = "phone-test"
        override suspend fun fetchSource(sha256: String, refresh: Boolean): ModelSourceResult {
            asked += sha256
            if (offline) throw java.io.IOException("连接 civitai.com 超时")
            if (sha256 !in found) throw ModelSourceException("Civitai 上没有这个文件", 404)
            return ModelSourceResult(ModelProvider(provider = "civitai", matchedHash = sha256, name = "Known Style",
                trainedWords = listOf("known style"), baseModel = "Illustrious"))
        }
    }

    private fun hashed(id: String, hash: String?, provider: ModelProvider? = null) = lora(id).copy(sha256 = hash, provider = provider)

    private fun listed(directory: java.io.File, identity: String, items: List<LocalModel>, savedAt: Long) =
        LocalModelCache(directory, ModelCenterLoraLibrary.listIdentity(identity))
            .save(ModelCacheSnapshot(bridgeServerId = "pc", items = items, savedAt = savedAt, lastSync = savedAt))

    @Test fun completeAsksForAScanOfUnknownFilesAndLooksUpMissingSourcesOnce() = runBlocking {
        MockWebServer().use { server ->
            val directory = temporary.newFolder()
            val identity = "pc|" + server.url("/").toString()
            // Hashes are unique per test: the retry times live for the whole process.
            val known = "1".repeat(64)
            val lost = "2".repeat(64)
            val bridge = "3".repeat(64)
            listed(directory, identity, listOf(hashed("known", known), hashed("lost", lost),
                hashed("sourced", bridge, ModelProvider(provider = "civitai", matchedHash = bridge)), hashed("plain", null)),
                System.currentTimeMillis() - 1_000)
            server.enqueue(json(ModelCapabilities(1, "pc", listOf("model-index"))))
            server.enqueue(json(ModelScan(status = "scanning")))
            val source = FakeSource(found = setOf(known))
            val library = ModelCenterLoraLibrary(directory, identity, server.url("/").toString(), OkHttpClient()) { source }
            val loadable = listOf("known.safetensors", "lost.safetensors", "sourced.safetensors", "plain.safetensors", "new.safetensors")

            // new.safetensors is not in the computer's index yet; a 404 from Civitai is an answer, not a failure.
            assertEquals(AgentLoraCompletion(scanStarted = true, fetched = 1, failed = 0), library.complete(loadable))
            assertEquals("/api/mobile/model-center/capabilities", server.takeRequest().path)
            val scan = server.takeRequest()
            assertEquals("POST", scan.method)
            assertEquals("/api/mobile/model-center/scan", scan.path)
            assertTrue(scan.body.readUtf8().contains("start"))
            assertEquals(listOf(known, lost), source.asked)
            val saved = LocalModelCache(directory, identity).load().phoneSources.getValue(known)
            assertEquals("phone", saved.fetchedBy)
            assertEquals("phone-test", saved.cacheScope)
            assertEquals(listOf("known style"), ModelCenterLoraLibrary.cached(directory, identity)
                .single { it.file == "known.safetensors" }.triggerWords)

            // The next turn neither asks for another scan within half an hour nor repeats the lookups.
            assertEquals(AgentLoraCompletion(), library.complete(loadable))
            assertEquals(2, server.requestCount)
            assertEquals(listOf(known, lost), source.asked)

            // The list saved before the scan request is re-read once, then counts as fresh again.
            server.enqueue(json(ModelCapabilities(1, "pc", listOf("model-index"))))
            server.enqueue(json(ModelPage(serverId = "pc", items = listOf(hashed("known", known), lora("new")))))
            assertEquals(listOf("known.safetensors", "new.safetensors"), library.refreshIfStale()?.loras?.map { it.file })
            assertNull(library.refreshIfStale())
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun refreshIfStaleOnlyReReadsAMissingOrOldList() = runBlocking {
        MockWebServer().use { server ->
            val directory = temporary.newFolder()
            val identity = "pc|" + server.url("/").toString()
            val library = ModelCenterLoraLibrary(directory, identity, server.url("/").toString(), OkHttpClient())
            // Never listed: read it now.
            server.enqueue(json(ModelCapabilities(1, "pc", listOf("model-index"))))
            server.enqueue(json(ModelPage(serverId = "pc", items = listOf(lora("a")))))
            assertEquals(listOf("a.safetensors"), library.refreshIfStale()?.loras?.map { it.file })
            // Just listed: the turn uses the phone's copy without asking the computer.
            assertNull(library.refreshIfStale())
            assertEquals(2, server.requestCount)
            // Older than ten minutes: read again.
            listed(directory, identity, listOf(lora("a")), System.currentTimeMillis() - 11 * 60_000L)
            server.enqueue(json(ModelCapabilities(1, "pc", listOf("model-index"))))
            server.enqueue(json(ModelPage(serverId = "pc", items = listOf(lora("a"), lora("b")))))
            assertEquals(listOf("a.safetensors", "b.safetensors"), library.refreshIfStale()?.loras?.map { it.file })
            // A computer that cannot answer leaves the turn on the cache instead of failing it.
            listed(directory, identity, listOf(lora("a")), System.currentTimeMillis() - 11 * 60_000L)
            server.enqueue(MockResponse().setResponseCode(500))
            assertNull(library.refreshIfStale())
        }
    }

    @Test fun completeStopsAfterTwoNetworkFailuresAndWaitsBeforeRetrying() = runBlocking {
        MockWebServer().use { server ->
            val directory = temporary.newFolder()
            val identity = "pc|" + server.url("/").toString()
            val hashes = listOf("4", "5", "6").map { it.repeat(64) }
            listed(directory, identity, hashes.mapIndexed { index, hash -> hashed("l$index", hash) }, System.currentTimeMillis())
            val source = FakeSource(found = hashes.toSet(), offline = true)
            val library = ModelCenterLoraLibrary(directory, identity, server.url("/").toString(), OkHttpClient()) { source }
            val loadable = listOf("l0.safetensors", "l1.safetensors", "l2.safetensors")
            // Civitai unreachable from this network: two failures in a row end the run instead of waiting out all three.
            assertEquals(AgentLoraCompletion(failed = 2), library.complete(loadable))
            assertEquals(hashes.take(2), source.asked)
            // Every file is indexed, so the computer is not asked to scan.
            assertEquals(0, server.requestCount)
            // The failed ones wait half an hour; only the one never tried is looked up on the next turn.
            assertEquals(AgentLoraCompletion(failed = 1), library.complete(loadable))
            assertEquals(hashes, source.asked)
        }
    }
}
