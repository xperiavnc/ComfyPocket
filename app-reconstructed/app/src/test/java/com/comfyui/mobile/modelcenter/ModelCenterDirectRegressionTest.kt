package com.comfyui.mobile.modelcenter

import android.os.Looper
import androidx.lifecycle.ViewModelStore
import com.comfyui.mobile.data.AppModule
import com.comfyui.mobile.market.*
import com.comfyui.mobile.model.CivitaiModelItem
import com.comfyui.mobile.model.CivitaiVersionItem
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class ModelCenterDirectRegressionTest {
    @Test fun providerCoverDoesNotGoThroughComputerPreviewEndpoint() {
        val vm = ModelCenterViewModel(RuntimeEnvironment.getApplication())
        val store = ViewModelStore().apply { put("fixture", vm) }
        try {
            vm.context("direct-preview", "电脑", "http://127.0.0.1:8188/", false)
            val deadline = System.currentTimeMillis() + 5000
            while (!vm.state.value.restored && System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            val url = "https://image.civitai.com/fixture/width=450/preview.jpeg"
            val model = LocalModel(id = "exact-file", provider = ModelProvider(images = listOf(ModelPreview(url))),
                preview = ModelPreviewInfo("provider"))
            println("MODEL_DIRECT_PREVIEW ${vm.preview(model)}")
            assertEquals(url, vm.preview(model))
        } finally { store.clear() }
    }

    @Test fun bridgeCatalogStillFetchesActualModelIntroductionOnPhone() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"id":818,"name":"模型","description":"真正的模型简介",
                "modelVersions":[{"id":819,"name":"v1","images":[{"url":"https://image.civitai.com/a.jpeg","meta":{"prompt":"fixture"}}]}]}"""))
            server.enqueue(MockResponse().setBody("""{"id":819,"name":"v1","description":"这是版本说明",
                "images":[{"url":"https://image.civitai.com/a.jpeg","meta":{"prompt":"fixture"}}]}"""))
            val repo = CivitaiRepository(RuntimeEnvironment.getApplication(),
                CivitaiSettings(mode = MarketConnectionMode.BRIDGE), AppModule.provideRepository())
            val client = ReflectionHelpers.getField<OkHttpClient>(repo, "client")
            ReflectionHelpers.setField(repo, "client", client.newBuilder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build())
            try {
                val result = repo.details(CivitaiModelItem(id = 818, name = "模型",
                    versions = listOf(CivitaiVersionItem(id = 819))), 819, true)
                println("MODEL_DIRECT_INTRO ${result.model.description}")
                assertEquals("真正的模型简介", result.model.description)
                assertEquals("这是版本说明", result.model.versions.single().description)
                assertEquals(2, server.requestCount)
            } finally { repo.close() }
        }
    }
}
