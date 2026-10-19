package com.comfyui.mobile.modelcenter

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelFileHttpTest {
    @get:Rule val temporary = TemporaryFolder()
    private val gson = Gson()
    private fun json(data: Any) = MockResponse().setHeader("Content-Type", "application/json")
        .setBody(gson.toJson(ModelEnvelope(data = data)))
    private fun repository(server: MockWebServer) =
        LocalModelRepository(temporary.newFolder(), "files", server.url("/comfy/").toString(), OkHttpClient())
    private val item = LocalModel(id = "location", serverId = "server", rootId = "root", name = "原名.safetensors",
        relativePath = "角色/原名.safetensors", mtimeNs = 1_792_000_000_123_456_700, size = 123, present = true)

    @Test fun actualFilenameOperationCarriesExactSnapshotRatherThanChangingAlias() = runBlocking {
        MockWebServer().use { server ->
            val modified = item.copy(id = "new-location", name = "新名.safetensors", relativePath = "角色/新名.safetensors")
            server.enqueue(json(ModelFileResult("server", "rename", item.id, modified, 2, message = "服务器文件已重命名")))
            val repo = repository(server)
            val result = repo.fileAction(item.id, ModelFileWrite.forItem(item, "rename", name = "新名.safetensors"))
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/comfy/api/mobile/model-center/models/location/file-action", request.path)
            val body = gson.fromJson(request.body.readUtf8(), ModelFileWrite::class.java)
            assertEquals(item.mtimeNs, body.expectedMtimeNs)
            assertEquals(item.relativePath, body.expectedPath)
            assertEquals(item.size, body.expectedSize)
            assertEquals("新名.safetensors", body.name)
            assertEquals("new-location", result.item!!.id)
            assertEquals(2, result.relatedFiles)
        }
    }

    @Test fun movingUsesServerRootAndRelativeFolderAndDeleteConfirmsFilename() = runBlocking {
        MockWebServer().use { server ->
            val repo = repository(server)
            server.enqueue(json(ModelFileResult("server", "move", item.id, item)))
            repo.fileAction(item.id, ModelFileWrite.forItem(item, "move", rootId = "root-2", directory = "作者/角色"))
            val move = gson.fromJson(server.takeRequest().body.readUtf8(), ModelFileWrite::class.java)
            assertEquals("root-2", move.rootId)
            assertEquals("作者/角色", move.directory)
            server.enqueue(json(ModelFileResult("server", "delete", item.id)))
            repo.fileAction(item.id, ModelFileWrite.forItem(item, "delete"))
            val delete = gson.fromJson(server.takeRequest().body.readUtf8(), ModelFileWrite::class.java)
            assertEquals(item.name, delete.confirmName)
        }
    }

    @Test fun folderListingDoesNotTransmitPhonePathsOrCivitaiKey() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json(ModelDirectories("server", "root-2", "作者", listOf("角色", "画风"))))
            val result = repository(server).directories(item.id, "root-2", "作者")
            val request = server.takeRequest()
            assertEquals("/comfy/api/mobile/model-center/models/location/directories", request.requestUrl!!.encodedPath)
            assertEquals("root-2", request.requestUrl!!.queryParameter("rootId"))
            assertEquals("作者", request.requestUrl!!.queryParameter("path"))
            assertNull(request.getHeader("Authorization"))
            assertEquals(listOf("角色", "画风"), result.directories)
        }
    }

    @Test fun conflictingTargetReturnsFailureAndCapabilityDoesNotGuessSupport() = runBlocking {
        assertFalse(ModelCapabilities(1, "server", listOf("model-index")).fileManagement)
        assertTrue(ModelCapabilities(1, "server", listOf("model-index", "model-file-management")).fileManagement)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(409).setBody(
                """{"code":409,"msg":"目标已存在同名文件，未覆盖任何文件","data":null}"""))
            val result = runCatching { repository(server).fileAction(item.id,
                ModelFileWrite.forItem(item, "rename", name = "taken.safetensors")) }
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("未覆盖"))
        }
    }
}
