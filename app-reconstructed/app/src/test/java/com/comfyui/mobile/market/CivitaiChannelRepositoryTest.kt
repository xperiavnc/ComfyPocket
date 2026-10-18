package com.comfyui.mobile.market

import com.comfyui.mobile.data.AppModule
import com.comfyui.mobile.model.CivitaiContentChannel
import com.comfyui.mobile.model.CivitaiModelItem
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CivitaiChannelRepositoryTest {
    private fun repository(server: MockWebServer, allowMature: Boolean = true): CivitaiRepository {
        val repo = CivitaiRepository(RuntimeEnvironment.getApplication(),
            CivitaiSettings(apiKey = UUID.randomUUID().toString(), includeMatureContent = allowMature),
            AppModule.provideRepository())
        val client = ReflectionHelpers.getField<OkHttpClient>(repo, "client")
        ReflectionHelpers.setField(repo, "client", client.newBuilder().addInterceptor { chain ->
            val url = chain.request().url
            assertEquals("https", url.scheme)
            assertEquals("civitai.com", url.host)
            assertTrue(url.encodedPath.startsWith("/api/v1/"))
            val fixture = server.url(url.encodedPath).newBuilder().encodedQuery(url.encodedQuery).build()
            chain.proceed(chain.request().newBuilder().url(fixture).build())
        }.build())
        return repo
    }

    private val ordinary = """{"id":2779381,"name":"fixture","type":"LORA","nsfw":false,
        "nsfwLevel":1,"minor":true,"sfwOnly":true,"modelVersions":[
        {"id":3130085,"baseModel":"Anima","images":[
            {"id":1,"url":"https://image.civitai.com/fixture/pg.png","nsfwLevel":1,
             "meta":{"prompt":"ordinary fixture"}},
            {"id":2,"url":"https://image.civitai.com/fixture/r.png","nsfwLevel":4,
             "meta":{"prompt":"filtered fixture"}}]}]}"""
    private val version = """{"id":3130085,"baseModel":"Anima","images":[
        {"id":1,"url":"https://image.civitai.com/fixture/pg.png","nsfwLevel":1,
         "meta":{"prompt":"ordinary fixture"}},
        {"id":2,"url":"https://image.civitai.com/fixture/r.png","nsfwLevel":4,
         "meta":{"prompt":"filtered fixture"}}]}"""

    @Test fun sameSearchUsesDistinctParametersAndDiskCachesForEachChannel() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"items":[$ordinary],"metadata":{}}"""))
            server.enqueue(MockResponse().setBody("""{"items":[{"id":20,"nsfw":true,"nsfwLevel":4}],"metadata":{}}"""))
            val repo = repository(server)
            try {
                val normal = MarketQuery(author = "fixture", channel = CivitaiContentChannel.STANDARD)
                val adult = normal.copy(channel = CivitaiContentChannel.MATURE)
                assertEquals(2779381L, repo.search(normal, 1, null, false).page.items.single().id)
                assertEquals(20L, repo.search(adult, 1, null, false).page.items.single().id)
                assertEquals("false", server.takeRequest().requestUrl!!.queryParameter("nsfw"))
                assertEquals("true", server.takeRequest().requestUrl!!.queryParameter("nsfw"))
                assertTrue(repo.search(normal, 1, null, false).fromCache)
                assertTrue(repo.search(adult, 1, null, false).fromCache)
                assertEquals(2, server.requestCount)
                println("MARKET_CHANNEL_CACHE ordinary=false mature=true repeat-network=0 PASS")
            } finally { repo.close() }
        }
    }

    @Test fun linkLookupUsesModelDetailNotListAndOrdinaryOnlyPreviewsStayOrdinary() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(ordinary))
            server.enqueue(MockResponse().setBody(version))
            val repo = repository(server)
            try {
                val reference = CivitaiJson.modelReference(
                    "https://civitai.red/models/2779381/sample?modelVersionId=3130085")!!
                val result = repo.openModel(reference)
                assertEquals(CivitaiContentChannel.STANDARD, result.model.contentChannel)
                assertEquals(true, result.model.minor)
                assertEquals(true, result.model.sfwOnly)
                assertEquals(listOf(1L), result.model.versions.single().images.map { it.id })
                assertEquals("ordinary fixture", result.model.versions.single().images.single().prompt)
                assertEquals("/api/v1/models/2779381", server.takeRequest().path)
                assertEquals("/api/v1/model-versions/3130085", server.takeRequest().path)
                assertEquals(2, server.requestCount)
                println("MARKET_LINK_LOOKUP id=2779381 globalMature=true detailScope=STANDARD previewIds=[1] PASS")
            } finally { repo.close() }
        }
    }

    @Test fun communityExamplesOfOrdinaryOnlyModelsAlsoRequestAndKeepOnlyOrdinaryContent() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(ordinary))
            server.enqueue(MockResponse().setBody("""{"id":3130085,"images":[]}"""))
            server.enqueue(MockResponse().setBody("""{"items":[
                {"id":4,"url":"https://image.civitai.com/fixture/pg.png","nsfwLevel":1},
                {"id":8,"url":"https://image.civitai.com/fixture/r.png","nsfwLevel":4}]}"""))
            val repo = repository(server)
            try {
                val result = repo.openModel(CivitaiModelReference(2779381))
                assertEquals(listOf(4L), result.model.versions.single().images.map { it.id })
                server.takeRequest(); server.takeRequest()
                val images = server.takeRequest()
                assertEquals("/api/v1/images", images.requestUrl!!.encodedPath)
                assertEquals("false", images.requestUrl!!.queryParameter("nsfw"))
            } finally { repo.close() }
        }
    }

    @Test fun deniedLookupNeverFallsBackToAPreviouslyCachedModel() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"id":2779381,"modelVersions":[]}"""))
            server.enqueue(MockResponse().setResponseCode(403))
            val repo = repository(server)
            try {
                repo.openModel(CivitaiModelReference(2779381))
                try { repo.openModel(CivitaiModelReference(2779381)); fail("Expected current permission") }
                catch (error: MarketAccessException) { assertEquals(403, error.status) }
                assertEquals(2, server.requestCount)
            } finally { repo.close() }
        }
    }

    @Test fun wrongVersionAndDisabledMatureBrowsingStopBeforeVersionOrImageRequests() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(ordinary))
            server.enqueue(MockResponse().setBody("""{"id":20,"nsfw":true,"nsfwLevel":4,"modelVersions":[]}"""))
            val repo = repository(server, allowMature = false)
            try {
                try { repo.openModel(CivitaiModelReference(2779381, 999)); fail("Expected exact version") }
                catch (error: MarketAccessException) { assertTrue(error.message!!.contains("版本")) }
                try { repo.openModel(CivitaiModelReference(20)); fail("Expected ordinary scope") }
                catch (error: MarketAccessException) { assertTrue(error.message!!.contains("内容范围")) }
                assertEquals(2, server.requestCount)
            } finally { repo.close() }
        }
    }
}
