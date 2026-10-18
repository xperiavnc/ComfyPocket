package com.comfyui.mobile.market

import com.comfyui.mobile.data.AppModule
import com.comfyui.mobile.modelcenter.ModelSourceException
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
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CivitaiLocalModelSourceTest {
    private val hash = "a".repeat(64)
    private fun version(fileHash: String = hash, modelId: Long = 31) = """
        {"id":32,"modelId":$modelId,"name":"v2","baseModel":"Illustrious","model":{"name":"精确模型"},
         "description":"<p>版本使用说明</p>","trainedWords":["fixture_trigger"],
         "files":[{"id":1,"primary":true,"hashes":{"SHA256":"${"b".repeat(64)}"}},
                  {"id":33,"primary":false,"hashes":{"SHA256":"${fileHash.uppercase()}"}}],
         "images":[{"url":"https://image.civitai.com/fixture/width=800/a.jpeg"},
                   {"url":"http://invalid.fixture/a.jpg"},{"type":"video","url":"https://image.civitai.com/v.mp4"}]}
    """.trimIndent()
    private val model = """{"id":31,"name":"精确模型","creator":{"username":"作者"},
        "description":"<p>模型总简介，不是版本说明</p>","modelVersions":[{"id":32}]}"""

    /**
     * Exercise the configured HTTP proxy without public traffic/TLS interception:
     * only this fixture rewrites https to http before the real phone client sends to its proxy.
     */
    private fun repository(proxy: MockWebServer): CivitaiRepository {
        val settings = CivitaiSettings(mode = MarketConnectionMode.BRIDGE,
            proxyEnabled = true, proxyAddress = proxy.url("/").toString(), apiKey = UUID.randomUUID().toString())
        val repo = CivitaiRepository(RuntimeEnvironment.getApplication(), settings, AppModule.provideRepository())
        val client = ReflectionHelpers.getField<OkHttpClient>(repo, "client")
        assertEquals(Proxy.Type.HTTP, client.proxy!!.type())
        assertEquals(proxy.port, (client.proxy!!.address() as InetSocketAddress).port)
        assertEquals(settings.copy(mode = MarketConnectionMode.DIRECT).cacheNamespace(), repo.sourceScope)
        ReflectionHelpers.setField(repo, "client", client.newBuilder().addInterceptor { chain ->
            val url = chain.request().url
            assertEquals("https", url.scheme)
            assertEquals("civitai.com", url.host)
            chain.proceed(chain.request().newBuilder().url(url.newBuilder().scheme("http").port(80).build()).build())
        }.build())
        return repo
    }

    @Test fun exactHashFindsNonPrimaryFileAndKeepsVersionAndModelIntroductionsSeparate() {
        val result = CivitaiLocalModelJson.match(version(), hash)
        assertEquals(33L, result.fileId)
        assertEquals(32L, result.versionId)
        assertEquals(31L, result.modelId)
        assertEquals(hash, result.matchedHash)
        assertEquals(listOf("fixture_trigger"), result.trainedWords)
        assertEquals("<p>版本使用说明</p>", result.description)
        assertFalse(result.modelDescriptionFetched)
        assertTrue(result.modelDescription.isEmpty())
        assertEquals(1, result.images.size)
    }

    @Test fun wrongHashAndMissingModelIdNeverGuessIdentityFromFilenameOrPrimaryFile() {
        listOf(version("c".repeat(64)), version(modelId = 0)).forEach { body ->
            assertThrows(IllegalArgumentException::class.java) { CivitaiLocalModelJson.match(body, hash) }
        }
        assertThrows(IllegalArgumentException::class.java) { CivitaiLocalModelJson.match(version(), "short") }
    }

    @Test fun aFileWithoutHashesDoesNotHideTheSubsequentExactMatch() {
        val body = version().replace("\"hashes\":{\"SHA256\":\"${"b".repeat(64)}\"}", "\"hashes\":null")
        assertEquals(33L, CivitaiLocalModelJson.match(body, hash).fileId)
    }

    @Test fun phoneFetchUsesHttpProxyEvenWithBridgeCatalogAndPersistsApiCache() = runBlocking {
        MockWebServer().use { proxy ->
            proxy.enqueue(MockResponse().setBody(version()))
            proxy.enqueue(MockResponse().setBody(model))
            val repo = repository(proxy)
            try {
                val result = repo.fetchSource(hash)
                assertEquals("作者", result.provider.author)
                assertEquals("<p>模型总简介，不是版本说明</p>", result.provider.modelDescription)
                assertEquals("<p>版本使用说明</p>", result.provider.description)
                assertTrue(result.provider.modelDescriptionFetched)
                assertEquals("phone", result.provider.fetchedBy)
                assertEquals(repo.sourceScope, result.provider.cacheScope)
                assertNull(result.warning)
                assertTrue(proxy.takeRequest().requestLine.contains("http://civitai.com/api/v1/model-versions/by-hash/$hash"))
                assertTrue(proxy.takeRequest().requestLine.contains("http://civitai.com/api/v1/models/31"))
                repo.fetchSource(hash)
                assertEquals("Two endpoint responses should now be reused from disk", 2, proxy.requestCount)
                println("MODEL_SOURCE_PROXY by-hash + model: 2 phone requests; repeat fetch: 0 additional requests")
            } finally { repo.close() }
        }
    }

    @Test fun partialIntroductionFailureKeepsVersionCoverAndCanRetryOnlyMissingModel() = runBlocking {
        MockWebServer().use { proxy ->
            proxy.enqueue(MockResponse().setBody(version()))
            proxy.enqueue(MockResponse().setResponseCode(503))
            proxy.enqueue(MockResponse().setBody(model))
            val repo = repository(proxy)
            try {
                val partial = repo.fetchSource(hash)
                assertFalse(partial.provider.modelDescriptionFetched)
                assertEquals(1, partial.provider.images.size)
                assertEquals(listOf("fixture_trigger"), partial.provider.trainedWords)
                assertNotNull(partial.warning)
                val retried = repo.fetchSource(hash)
                assertTrue(retried.provider.modelDescriptionFetched)
                assertNull(retried.warning)
                assertEquals(3, proxy.requestCount)
            } finally { repo.close() }
        }
    }

    @Test fun missingHashStopsBeforeNetworkAnd404KeepsItsMeaning() = runBlocking {
        MockWebServer().use { proxy ->
            proxy.enqueue(MockResponse().setResponseCode(404))
            val repo = repository(proxy)
            try {
                try { repo.fetchSource("not-a-sha"); fail("Expected a missing fingerprint") }
                catch (error: ModelSourceException) { assertTrue(error.message!!.contains("SHA-256")) }
                assertEquals(0, proxy.requestCount)
                try { repo.fetchSource(hash); fail("Expected no match") }
                catch (error: ModelSourceException) { assertEquals(404, error.code) }
                assertEquals(1, proxy.requestCount)
            } finally { repo.close() }
        }
    }

    @Test fun incorrectHashResponseNeverRequestsOrSavesAnotherModelsIntroduction() = runBlocking {
        MockWebServer().use { proxy ->
            proxy.enqueue(MockResponse().setBody(version("c".repeat(64))))
            val repo = repository(proxy)
            try {
                try { repo.fetchSource(hash); fail("Expected exact-hash rejection") }
                catch (error: ModelSourceException) { assertTrue(error.message!!.contains("SHA-256")) }
                assertEquals(1, proxy.requestCount)
            } finally { repo.close() }
        }
    }
}
