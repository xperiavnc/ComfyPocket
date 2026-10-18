package com.comfyui.mobile.modelcenter

import android.os.Looper
import androidx.lifecycle.ViewModelStore
import com.google.gson.Gson
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
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class ModelCenterServerSwitchTest {
    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("ViewModel result did not settle", condition())
    }

    @Test fun switchRemovesPreviousServerImmediatelyAndRestoresItsOwnPreferences() {
        val app = RuntimeEnvironment.getApplication()
        val directory = File(app.filesDir, "local-model-cache-v1")
        LocalModelCache(directory, "switch-A").save(ModelCacheSnapshot(
            items = listOf(LocalModel(id = "A", name = "same.safetensors",
                annotation = ModelAnnotation(useCases = listOf("character")))),
            query = LocalModelQuery(useCase = "character"), compact = true, scrollIndex = 7, scrollOffset = 50))
        LocalModelCache(directory, "switch-B").save(ModelCacheSnapshot(
            items = listOf(LocalModel(id = "B", name = "same.safetensors"))))
        val vm = ModelCenterViewModel(app)
        val store = ViewModelStore().apply { put("fixture", vm) }
        try {
            vm.context("switch-A", "A", null, false)
            await { vm.state.value.restored }
            assertEquals("A", vm.state.value.page.items.single().id)
            assertEquals(7, vm.state.value.scrollIndex)
            assertEquals(50, vm.state.value.scrollOffset)
            assertTrue(vm.state.value.compact)
            vm.context("switch-B", "B", null, false)
            assertTrue(vm.state.value.page.items.isEmpty())
            await { vm.state.value.restored }
            assertEquals("B", vm.state.value.page.items.single().id)
            assertFalse(vm.state.value.compact)
            assertEquals("", vm.state.value.query.useCase)
        } finally { store.clear() }
    }

    @Test fun delayedResponseCannotPublishIntoNewServerOrItsCache() {
        val app = RuntimeEnvironment.getApplication()
        val gson = Gson()
        fun dispatch(id: String, slow: Boolean) = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val data: Any = if (request.path!!.endsWith("/capabilities"))
                    ModelCapabilities(1, id, listOf("model-index"))
                else ModelPage(serverId = id, items = listOf(LocalModel(id = id, serverId = id)))
                return MockResponse().setHeader("Content-Type", "application/json")
                    .setBody(gson.toJson(ModelEnvelope(data = data)))
                    .apply { if (slow) setBodyDelay(500, TimeUnit.MILLISECONDS) }
            }
        }
        MockWebServer().use { a -> MockWebServer().use { b ->
            a.dispatcher = dispatch("late-A", true)
            b.dispatcher = dispatch("live-B", false)
            val vm = ModelCenterViewModel(app)
            val store = ViewModelStore().apply { put("fixture", vm) }
            try {
                vm.context("race-A", "A", a.url("/").toString(), true)
                await { a.requestCount > 0 }
                vm.context("race-B", "B", b.url("/").toString(), true)
                assertTrue(vm.state.value.page.items.isEmpty())
                await { !vm.state.value.fromCache && vm.state.value.page.items.isNotEmpty() }
                assertEquals("live-B", vm.state.value.page.items.single().id)
                Thread.sleep(600)
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals("live-B", vm.state.value.page.items.single().id)
                await {
                    LocalModelCache(File(app.filesDir, "local-model-cache-v1"), "race-B")
                        .load().items.singleOrNull()?.id == "live-B"
                }
            } finally { store.clear() }
        } }
    }
}
