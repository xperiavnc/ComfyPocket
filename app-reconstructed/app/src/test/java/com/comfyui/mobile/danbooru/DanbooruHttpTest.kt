package com.comfyui.mobile.danbooru

import java.io.File
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.*
import okhttp3.Credentials
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class DanbooruHttpTest {
    private val agent = { "ComfyPocket/fixture (Danbooru)" }
    private fun client(settings: DanbooruSettings = DanbooruSettings(), api: Boolean = true) = DanbooruHttp.client(settings, api, agent)
    private suspend fun read(server: MockWebServer, queue: DanbooruApiQueue = DanbooruApiQueue(0),
        pause: suspend (Long) -> Unit = {}): String =
        DanbooruHttp.text(client(), Request.Builder().url(server.url("/posts.json")).build(), queue, pause)

    @Test fun strictIndependentClientHasExplicitProxyRouteAndNoSslDowngrade() {
        val direct = client()
        assertEquals(Proxy.NO_PROXY, direct.proxy)
        assertFalse(direct.followSslRedirects)
        val routed = client(DanbooruSettings(proxyEnabled = true, proxyAddress = "http://localhost:1234"))
        assertEquals(Proxy.Type.HTTP, routed.proxy?.type())
    }
    @Test fun basicAuthOnlyMatchesTheExactOfficialHttpsApi() {
        val auth = DanbooruAuthInterceptor(DanbooruSettings("user", "fixture-key"))
        for (url in listOf("https://danbooru.donmai.us/posts.json?tags=blue_sky", "https://danbooru.donmai.us/posts/123.json",
            "https://danbooru.donmai.us/profile.json")) {
            val req = auth.authenticated(Request.Builder().url(url).build())
            assertEquals(Credentials.basic("user", "fixture-key"), req.header("Authorization"))
            assertFalse(req.url.toString().contains("fixture-key"))
        }
        for (url in listOf("http://danbooru.donmai.us/posts.json", "https://danbooru.donmai.us:8443/posts.json",
            "https://cdn.donmai.us/posts.json", "https://danbooru.donmai.us.evil.invalid/posts.json",
            "https://danbooru.donmai.us/posts/1", "https://danbooru.donmai.us/other.json")) {
            assertNull(url, auth.authenticated(Request.Builder().url(url).header("Authorization", "remove").build()).header("Authorization"))
        }
    }
    @Test fun retry429HonorsRetryAfterAndStopsAfterTwoRetries() = runBlocking {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "7").setBody("{}")) }
            val waits = mutableListOf<Long>()
            try { read(server, pause = { waits += it }); fail() } catch (e: DanbooruException) { assertEquals(429, e.status) }
            assertEquals(listOf(7000L, 7000L), waits)
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun retry429CanSucceedAndMissingHeaderUsesFiniteBackoff() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setBody("{}"))
            server.enqueue(MockResponse().setBody("[]"))
            val waits = mutableListOf<Long>()
            assertEquals("[]", read(server, pause = { waits += it }))
            assertEquals(listOf(1000L), waits)
        }
    }
    @Test fun credentialPermissionAndPaginationErrorsAreNotRetriedOrReportedAsEmptyPages() = runBlocking {
        MockWebServer().use { server ->
            for (status in listOf(401, 403, 404, 407, 410)) {
                server.enqueue(MockResponse().setResponseCode(status).setBody("fixture-secret-body"))
                try { read(server); fail() } catch (e: DanbooruException) {
                    assertEquals(status, e.status)
                    assertFalse(e.message.orEmpty().contains("fixture-secret-body"))
                }
            }
            assertEquals(5, server.requestCount)
        }
    }
    @Test fun proxyCarriesApiAndEveryMediaRepresentationIncludingShareOriginal() = runBlocking {
        MockWebServer().use { proxy ->
            val config = DanbooruSettings("user", "fixture-key", true, proxy.url("/").toString())
            val api = client(config)
            val media = client(config, api = false)
            for ((index, path) in listOf("/posts.json", "/thumbnail.jpg", "/sample.jpg", "/original.jpg").withIndex()) {
                proxy.enqueue(MockResponse().setBody(if (index == 0) "[]" else "encoded-fixture"))
                val request = Request.Builder().url("http://unresolvable.invalid$path").build()
                val text = if (index == 0) DanbooruHttp.text(api, request, DanbooruApiQueue(0))
                    else media.newCall(request).execute().use { it.body!!.string() }
                assertTrue(text.isNotBlank())
                val received = proxy.takeRequest(2, TimeUnit.SECONDS)!!
                assertTrue(received.requestLine.startsWith("GET http://unresolvable.invalid$path"))
                assertNull(received.getHeader("Authorization"))
                assertEquals("ComfyPocket/fixture (Danbooru)", received.getHeader("User-Agent"))
            }
        }
    }
    @Test fun proxyAuthenticationIsSeparateFromSiteAuthenticationAndWrongPasswordStops() = runBlocking {
        MockWebServer().use { proxy ->
            repeat(2) { proxy.enqueue(MockResponse().setResponseCode(407).addHeader("Proxy-Authenticate", "Basic realm=\"fixture\"").setBody("{}")) }
            val calls = client(DanbooruSettings("user", "fixture-key", true, proxy.url("/").toString(), "proxy-user", "proxy-password"))
            try { DanbooruHttp.text(calls, Request.Builder().url("http://unresolvable.invalid/posts.json").build(), DanbooruApiQueue(0)); fail() }
            catch (e: DanbooruException) { assertEquals(407, e.status) }
            assertNull(proxy.takeRequest().getHeader("Proxy-Authorization"))
            val retried = proxy.takeRequest()
            assertEquals(Credentials.basic("proxy-user", "proxy-password"), retried.getHeader("Proxy-Authorization"))
            assertNull(retried.getHeader("Authorization")); assertEquals(2, proxy.requestCount)
        }
    }
    @Test fun cancellationStopsTheNetworkCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val calls = client()
            val job = launch(Dispatchers.IO) {
                DanbooruHttp.text(calls, Request.Builder().url(server.url("/posts.json")).build(), DanbooruApiQueue(0))
            }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertTrue(job.isCancelled); calls.dispatcher.cancelAll()
        }
    }
    @Test fun profile200AnonymousOrWrongIdentityIsNotLoginSuccess() {
        for (json in listOf("""{"id":null,"name":"Anonymous"}""", """{"id":0,"name":"user"}""", """{"id":4,"name":"other"}""")) {
            try { DanbooruJson.identity(json, "user"); fail() } catch (e: DanbooruException) { assertEquals(401, e.status) }
        }
        assertEquals(DanbooruIdentity(4, "User", "Member"), DanbooruJson.identity("""{"id":4,"name":"User","level_string":"Member"}""", "user"))
    }
    @Test fun verificationChecksProfileThenIndexThenShowWithoutUsingPageCache() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"id":4,"name":"user","level":20}"""))
            server.enqueue(MockResponse().setBody("""[{"id":3}]"""))
            server.enqueue(MockResponse().setBody("""{"id":3}"""))
            val repo = DanbooruRepository(client(), DanbooruApiQueue(0), Files.createTempDirectory("dbverify").toFile(), server.url("/"))
            assertEquals(4L, repo.verify(DanbooruSettings("user", "key"))?.id)
            assertEquals("/profile.json", server.takeRequest().path)
            assertTrue(server.takeRequest().path!!.startsWith("/posts.json?"))
            assertEquals("/posts/3.json", server.takeRequest().path)
        }
    }
    @Test fun fiveNetworkPagesEncodeQueryAndCachedPagesDoNotRepeatRequests() = runBlocking {
        MockWebServer().use { server ->
            val cache = Files.createTempDirectory("dbpages").toFile()
            val repo = DanbooruRepository(client(), DanbooruApiQueue(0), cache, server.url("/"))
            val query = DanbooruQuery.resolve("blue_sky -cloud rating:g", ContentFilter.GENERAL)
            var cursor: String? = null
            repeat(5) { number ->
                val top = 1000 - number * 40
                val body = (0..39).joinToString(",", "[", "]") { """{"id":${top - it}}""" }
                server.enqueue(MockResponse().setBody(body))
                val posts = repo.posts(query, cursor)
                assertEquals(40, posts.size)
                val request = server.takeRequest()
                assertEquals(query.tags, request.requestUrl!!.queryParameter("tags"))
                assertEquals(cursor, request.requestUrl!!.queryParameter("page"))
                assertEquals("40", request.requestUrl!!.queryParameter("limit"))
                assertEquals(posts, repo.posts(query, cursor)) // same page from the disk block
                cursor = "b${posts.minOf { it.id }}"
            }
            assertEquals(5, server.requestCount)
            assertEquals(5, cache.listFiles()!!.size)
        }
    }
    @Test fun readableNetworkErrorsAreDistinct() {
        assertTrue(DanbooruHttp.networkMessage(UnknownHostException()).contains("域名"))
        assertTrue(DanbooruHttp.networkMessage(SocketTimeoutException()).contains("超时"))
        assertTrue(DanbooruHttp.networkMessage(SSLException("fixture")).contains("TLS"))
        assertTrue(DanbooruHttp.statusMessage(410).contains("分页边界"))
    }
}
