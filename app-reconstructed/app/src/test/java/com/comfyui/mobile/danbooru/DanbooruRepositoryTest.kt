package com.comfyui.mobile.danbooru

import java.io.File
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DanbooruRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var directory: File
    private lateinit var repository: DanbooruRepository
    private val settings = DanbooruSettings()
    private fun response(body: String) = MockResponse().setBody(body)
    @Before fun setup() {
        server = MockWebServer().apply { start() }
        directory = kotlin.io.path.createTempDirectory("danbooru-repo").toFile()
        repository = DanbooruRepository(DanbooruCache(directory), server.url("/"),
            clientFor = { OkHttpClient.Builder().proxy(Proxy.NO_PROXY).readTimeout(200, TimeUnit.MILLISECONDS).build() },
            intervalMillis = 0)
    }
    @After fun close() { server.shutdown(); directory.deleteRecursively() }

    @Test fun exactMatchIsRankedBeforePopularityAndMissingWikiDoesNotEraseTag() = runBlocking {
        server.enqueue(response("""[{"name":"smile_extra","category":0,"post_count":9999},{"name":"smile","category":0,"post_count":3}]"""))
        server.enqueue(MockResponse().setResponseCode(404))
        val item = repository.lookup(listOf("smile"), 3, TagCheckOrigin.APP_COMPAT, settings).items.single()
        assertEquals("smile", item.tags.first().name)
        assertEquals(MatchKind.EXACT, item.tags.first().matchKind)
        assertEquals(LookupStatus.NO_WIKI, item.wiki!!.status)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("smile", request.requestUrl!!.queryParameter("search[name_or_alias_matches]"))
        assertEquals("count", request.requestUrl!!.queryParameter("search[order]"))
        assertNull(request.requestUrl!!.queryParameter("search[post_count]"))
        assertTrue(request.getHeader("User-Agent")!!.startsWith("ComfyPocket/"))
        assertNull(request.getHeader("Authorization"))
    }

    @Test fun aliasDirectionIsConfirmedNotInferredFromFuzzyResults() = runBlocking {
        server.enqueue(response("""[{"name":"blonde_hair","category":0,"post_count":12}]"""))
        server.enqueue(response("""[{"antecedent_name":"blond_hair","consequent_name":"blonde_hair","status":"active"}]"""))
        server.enqueue(response("""{"title":"blonde_hair","body":"Light yellow [[hair]] color."}"""))
        val report = repository.lookup(listOf("blond hair"), 3, TagCheckOrigin.MODEL_TOOL, settings)
        val item = report.items.single()
        assertEquals(3, report.networkRequests)
        assertEquals(MatchKind.ALIAS, item.tags.first().matchKind)
        assertEquals("blonde_hair", item.tags.first().aliasTarget)
        assertEquals("Light yellow hair color.", item.tags.first().wikiSummary)
        server.takeRequest()
        val aliasRequest = server.takeRequest()
        assertEquals("blond_hair", aliasRequest.requestUrl!!.queryParameter("search[antecedent_name_matches]"))
        assertEquals("active", aliasRequest.requestUrl!!.queryParameter("search[status]"))
    }

    @Test fun unknownWordsStayUnverifiedAndThreeRequestBudgetIsShared() = runBlocking {
        repeat(3) { server.enqueue(response("[]")) }
        val report = repository.lookup(listOf("my_trigger", "other_term", "third_term"), 3, TagCheckOrigin.APP_COMPAT, settings)
        assertEquals(3, report.networkRequests)
        assertEquals(3, server.requestCount)
        assertEquals(LookupStatus.NOT_FOUND, report.items.first().search.status)
        assertTrue(report.items.first().tags.isEmpty())
        assertEquals(LookupStatus.BUDGET_EXHAUSTED, report.items.last().search.status)
        assertTrue(report.incomplete)
    }

    @Test fun cacheSurvivesRepositoryRecreationAndRefreshMakesNewRequests() = runBlocking {
        server.enqueue(response("""[{"name":"cat","category":0,"post_count":1}]"""))
        server.enqueue(response("""{"title":"cat","body":"A cat."}"""))
        repository.lookup(listOf("cat"), 3, TagCheckOrigin.APP_COMPAT, settings)
        val next = DanbooruRepository(DanbooruCache(directory), server.url("/"), intervalMillis = 0)
        assertEquals(0, next.lookup(listOf("cat"), 3, TagCheckOrigin.APP_COMPAT, settings).networkRequests)
        server.enqueue(response("""[{"name":"cat","category":0,"post_count":2}]"""))
        server.enqueue(response("""{"title":"cat","body":"A domestic cat."}"""))
        assertEquals(2, next.lookup(listOf("cat"), 3, TagCheckOrigin.APP_COMPAT, settings, refresh = true).networkRequests)
        assertEquals(4, server.requestCount)
    }

    @Test fun rateLimitHonorsRetryAfterAndDoesNotRetryOrPretendMissing() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "90"))
        val first = repository.lookup(listOf("smile", "cat"), 3, TagCheckOrigin.APP_COMPAT, settings)
        assertEquals(1, first.networkRequests)
        assertEquals(LookupStatus.RATE_LIMITED, first.items.first().search.status)
        assertEquals(0, repository.lookup(listOf("smile"), 3, TagCheckOrigin.APP_COMPAT, settings).networkRequests)
        assertEquals(1, server.requestCount)
    }

    @Test fun httpParseAndTimeoutHaveDistinctResults() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(LookupStatus.HTTP_ERROR, repository.lookup(listOf("a"), 3, TagCheckOrigin.APP_COMPAT, settings).items[0].search.status)
        server.enqueue(response("<html>not json</html>"))
        assertEquals(LookupStatus.PARSE_ERROR, repository.lookup(listOf("b"), 3, TagCheckOrigin.APP_COMPAT, settings).items[0].search.status)
        server.enqueue(response("[]").setBodyDelay(1, TimeUnit.SECONDS))
        assertEquals(LookupStatus.TIMEOUT, repository.lookup(listOf("c"), 3, TagCheckOrigin.APP_COMPAT, settings).items[0].search.status)
    }

    @Test fun parametersAndWikiPathAreStructurallyEncoded() {
        val tag = repository.url(DanbooruApiType.TAG, "blue hair*", 99)
        assertEquals("blue_hair*", tag.queryParameter("search[name_or_alias_matches]"))
        assertEquals("5", tag.queryParameter("limit"))
        assertTrue(tag.encodedQuery!!.contains("%5B"))
        val wiki = repository.url(DanbooruApiType.WIKI, "a/b (tag)", 3)
        assertTrue(wiki.encodedPath.contains("a%2Fb"))
        assertEquals("a/b_(tag).json", wiki.pathSegments.last())
    }

    @Test fun parsesActualReadOnlyApiSamplesCapturedOn20261017() {
        val rows = com.google.gson.JsonParser.parseString(
            javaClass.getResource("/danbooru/live-api-sample-20261017.json")!!.readText()).asJsonArray
        val tag = repository.parse(DanbooruApiType.TAG, "blonde_hair", rows[0].asJsonObject["body"].toString())
        assertEquals("blonde_hair", tag.tags.single().name)
        assertEquals(0, tag.tags.single().category)
        assertTrue(tag.tags.single().postCount!! > 0)
        val alias = repository.parse(DanbooruApiType.ALIAS, "blond_hair", rows[1].asJsonObject["body"].toString())
        assertEquals(DanbooruAlias("blond_hair", "blonde_hair"), alias.aliases.single())
        val wiki = repository.parse(DanbooruApiType.WIKI, "blonde_hair", rows[2].asJsonObject["body"].toString())
        assertEquals("Hair that is colored blonde or yellow.", wiki.wikiSummary)
    }

    @Test fun uncachedRequestsWaitAtLeastOneSecondAndUseInjectedClientOnly() = runBlocking {
        var clock = 10_000L
        val waits = mutableListOf<Long>()
        val seen = mutableListOf<DanbooruSettings>()
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()
        val repo = DanbooruRepository(DanbooruCache(directory), server.url("/"), clientFor = {
            seen += it; client
        }, now = { clock }, pause = { waits += it; clock += it })
        server.enqueue(response("""[{"name":"cat","category":0,"post_count":1}]"""))
        server.enqueue(MockResponse().setResponseCode(404))
        val proxySettings = settings.copy(proxyEnabled = true, proxyAddress = "http://127.0.0.1:9080")
        repo.lookup(listOf("cat"), 3, TagCheckOrigin.APP_COMPAT, proxySettings)
        assertEquals(listOf(1_000L), waits)
        assertEquals(listOf(proxySettings, proxySettings), seen)
        assertEquals(2, server.requestCount)
    }
}
