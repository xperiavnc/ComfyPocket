package com.comfyui.mobile.modelcenter

import android.os.Looper
import androidx.lifecycle.ViewModelStore
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class ModelFileViewModelTest {
    private val gson = Gson()
    private val original = LocalModel(id = "original", serverId = "files-server", rootId = "root",
        name = "original.safetensors", relativePath = "original.safetensors", category = "LORA",
        size = 123, mtimeNs = 123_456_789, present = true)
    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Model file operation did not settle", condition())
    }

    @Test fun committedRenameAndDeletionPersistIntoOfflineCacheOnlyAfterServerSuccess() {
        MockWebServer().use { server ->
            var active: LocalModel? = original
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val data: Any = when {
                        request.path!!.endsWith("/capabilities") ->
                            ModelCapabilities(1, original.serverId, listOf("model-index", "model-file-management"))
                        request.path!!.endsWith("/file-action") -> {
                            val operation = gson.fromJson(request.body.readUtf8(), ModelFileWrite::class.java)
                            val old = active!!
                            active = if (operation.action == "delete") null else old.copy(id = "renamed",
                                name = operation.name!!, relativePath = operation.name)
                            ModelFileResult(original.serverId, operation.action, old.id, active)
                        }
                        request.path!!.contains("/models?") -> ModelPage(serverId = original.serverId,
                            items = listOfNotNull(active), total = if (active == null) 0 else 1)
                        else -> active!!
                    }
                    return MockResponse().setHeader("Content-Type", "application/json")
                        .setBody(gson.toJson(ModelEnvelope(data = data)))
                }
            }
            val app = RuntimeEnvironment.getApplication()
            val identity = "files-" + UUID.randomUUID()
            val vm = ModelCenterViewModel(app)
            val store = ViewModelStore().apply { put("fixture", vm) }
            try {
                vm.context(identity, "fixture", server.url("/").toString(), true)
                await { !vm.state.value.loading && !vm.state.value.fromCache }
                vm.openDetail(original.id)
                await { !vm.state.value.detailLoading }
                var result: Result<ModelFileResult>? = null
                CoroutineScope(Dispatchers.Main).launch {
                    result = vm.manageFile(original, ModelFileWrite.forItem(original, "rename", name = "新名.safetensors"))
                }
                await { result != null && !vm.state.value.loading }
                assertTrue(result!!.isSuccess)
                assertEquals("renamed", vm.state.value.detail!!.id)
                assertEquals("新名.safetensors", vm.state.value.page.items.single().name)
                val cache = LocalModelCache(File(app.filesDir, "local-model-cache-v1"), identity)
                await { cache.load().items.singleOrNull()?.id == "renamed" }
                val renamed = vm.state.value.detail!!
                result = null
                CoroutineScope(Dispatchers.Main).launch {
                    result = vm.manageFile(renamed, ModelFileWrite.forItem(renamed, "delete"))
                }
                await { result != null && !vm.state.value.loading }
                assertTrue(result!!.isSuccess)
                assertNull(vm.state.value.detail)
                assertTrue(vm.state.value.page.items.isEmpty())
                await { cache.load().items.isEmpty() }
                vm.context(identity, "fixture", server.url("/").toString(), false)
                await { vm.state.value.restored }
                assertTrue(vm.state.value.page.items.isEmpty())
            } finally { store.clear() }
        }
    }

    @Test fun delayedOldServerMutationDoesNotPolluteNewServerCache() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val mutation = request.path!!.endsWith("/file-action")
                    val data: Any = when {
                        mutation -> ModelFileResult(original.serverId, "rename", original.id, original.copy(id = "renamed"))
                        request.path!!.endsWith("/capabilities") ->
                            ModelCapabilities(1, original.serverId, listOf("model-index", "model-file-management"))
                        request.path!!.contains("/models?") -> ModelPage(serverId = original.serverId, items = listOf(original))
                        else -> original
                    }
                    return MockResponse().setHeader("Content-Type", "application/json")
                        .setBody(gson.toJson(ModelEnvelope(data = data)))
                        .apply { if (mutation) setBodyDelay(400, TimeUnit.MILLISECONDS) }
                }
            }
            val vm = ModelCenterViewModel(RuntimeEnvironment.getApplication())
            val store = ViewModelStore().apply { put("fixture", vm) }
            val originalIdentity = "late-file-" + UUID.randomUUID()
            try {
                vm.context(originalIdentity, "A", server.url("/").toString(), true)
                await { !vm.state.value.loading && !vm.state.value.fromCache }
                vm.openDetail(original.id)
                await { !vm.state.value.detailLoading }
                var result: Result<ModelFileResult>? = null
                val before = server.requestCount
                CoroutineScope(Dispatchers.Main).launch {
                    result = vm.manageFile(original, ModelFileWrite.forItem(original, "rename", name = "new.safetensors"))
                }
                await { server.requestCount > before }
                vm.context("other-file-" + UUID.randomUUID(), "B", null, false)
                await { vm.state.value.restored && result != null }
                assertTrue(result!!.isFailure)
                assertEquals("B", vm.state.value.serverLabel)
                assertTrue(vm.state.value.page.items.isEmpty())
                assertNull(vm.state.value.detail)
                val originalCache = LocalModelCache(File(RuntimeEnvironment.getApplication().filesDir,
                    "local-model-cache-v1"), originalIdentity).load()
                assertEquals("renamed", originalCache.items.single().id)
            } finally { store.clear() }
        }
    }
}
