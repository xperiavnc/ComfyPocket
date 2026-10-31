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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** The reported 2 checkpoints / 48 LoRAs, with deliberately slow phone responses. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class ModelCenterQueryRegressionTest {
    private val gson = Gson()
    private val items = listOf("CHECKPOINT" to 2, "LORA" to 48).flatMap { (category, count) ->
        (1..count).map { LocalModel(id = "$category-$it", name = "$category-$it.safetensors",
            serverId = "query-fixture", category = category, present = true) }
    }

    private fun pump(millis: Long) {
        val until = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun await(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 8_000
        while (!condition() && System.currentTimeMillis() < until) pump(20)
        assertTrue("Query did not settle", condition())
    }

    private fun fixture(block: (ModelCenterViewModel, List<String>) -> Unit) {
        MockWebServer().use { server ->
            val queries = CopyOnWriteArrayList<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = requireNotNull(request.requestUrl)
                    val category = url.queryParameter("category").orEmpty()
                    val data: Any = if (url.encodedPath.endsWith("/capabilities"))
                        ModelCapabilities(1, "query-fixture", listOf("model-index"), scanComplete = true)
                    else {
                        queries.add(category)
                        val filtered = items.filter { category.isBlank() || it.category == category }
                        val page = url.queryParameter("page")?.toInt() ?: 1
                        ModelPage(serverId = "query-fixture", total = filtered.size, page = page,
                            hasMore = page * 40 < filtered.size, scanComplete = true,
                            items = filtered.drop((page - 1) * 40).take(40))
                    }
                    return MockResponse().setHeader("Content-Type", "application/json")
                        .setBody(gson.toJson(ModelEnvelope(data = data)))
                        .apply {
                            if (url.encodedPath.endsWith("/models") && category == "LORA")
                                setBodyDelay(900, TimeUnit.MILLISECONDS)
                        }
                }
            }
            val app = RuntimeEnvironment.getApplication()
            val vm = ModelCenterViewModel(app)
            val store = ViewModelStore().apply { put("fixture", vm) }
            try {
                vm.context("query-regression-${server.port}", "Fixture", server.url("/").toString(), true)
                await { !vm.state.value.fromCache && !vm.state.value.loading }
                vm.query(LocalModelQuery(category = "CHECKPOINT"))
                await { !vm.state.value.loading && vm.state.value.page.total == 2 }
                block(vm, queries)
            } finally { store.clear() }
        }
    }

    @Test fun selectingLoraImmediatelyRemovesCheckpointRowsAndTheirTotal() = fixture { vm, _ ->
        vm.query(LocalModelQuery(category = "LORA"))
        val pending = vm.state.value
        println("MODEL_QUERY_SWITCH category=${pending.query.category} loading=${pending.loading} " +
            "total=${pending.page.total} visible=${pending.page.items.map { it.category }}")
        assertTrue(pending.loading)
        assertTrue("Old category cards must disappear at selection, not after the network reply",
            pending.page.items.all { it.category == "LORA" })
        // Cached LoRA rows show at once (no blank list while the network answers); the
        // total counts only rows matching the new chip, never the previous checkpoint total.
        assertTrue("Cached rows for the selected chip must show immediately", pending.page.items.isNotEmpty())
        assertEquals(pending.page.items.size, pending.page.total)
        assertFalse(pending.page.hasMore)
        await { !vm.state.value.loading && vm.state.value.page.total == 48 }
        assertEquals(40, vm.state.value.page.items.size)
        assertTrue(vm.state.value.page.items.all { it.category == "LORA" })
        vm.refresh(append = true)
        await { !vm.state.value.loading && vm.state.value.page.page == 2 }
        assertEquals(48, vm.state.value.page.items.size)
        assertFalse(vm.state.value.page.hasMore)
    }

    @Test fun repeatedRefreshesDoNotStarveASlowCategoryRequest() = fixture { vm, queries ->
        vm.query(LocalModelQuery(category = "LORA"))
        repeat(7) {
            pump(80)
            vm.refresh()
        }
        pump(600)
        println("MODEL_QUERY_POLL loading=${vm.state.value.loading} total=${vm.state.value.page.total} " +
            "loraRequests=${queries.count { it == "LORA" }}")
        assertEquals("Identical refreshes during a request must join it instead of cancelling it",
            1, queries.count { it == "LORA" })
        assertFalse(vm.state.value.loading)
        assertEquals(48, vm.state.value.page.total)
    }
}
