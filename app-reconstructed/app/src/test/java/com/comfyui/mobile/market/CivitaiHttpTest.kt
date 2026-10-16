package com.comfyui.mobile.market

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.net.Proxy
import java.util.concurrent.TimeUnit

class CivitaiHttpTest {
    private val auth = CivitaiAuthInterceptor("fixture-personal-key")
    private fun headers(url: String) = auth.authenticated(Request.Builder().url(url)
        .header("Authorization", "must-be-removed").build())

    @Test fun personalKeyOnlyGoesToOfficialHttpsApi() {
        assertEquals("Bearer fixture-personal-key",
            headers("https://civitai.com/api/v1/models").header("Authorization"))
        listOf(
            "http://civitai.com/api/v1/models", "https://civitai.com:8443/api/v1/me",
            "https://image.civitai.com/api/v1/models", "https://civitai.com.evil.invalid/api/v1/me",
            "https://evil.invalid/api/v1/me", "https://civitai.com/other",
            "https://civitai.com/api/download/models/1"
        ).forEach { assertNull(it, headers(it).header("Authorization")) }
    }

    @Test fun blankKeyDoesNotCreateAnAuthorizationHeader() {
        val request = CivitaiAuthInterceptor("").authenticated(
            Request.Builder().url("https://civitai.com/api/v1/me").build())
        assertNull(request.header("Authorization"))
    }

    @Test fun usesIndependentNormalTlsAndAnExplicitDirectRoute() {
        val client = CivitaiHttp.client(CivitaiSettings())
        assertEquals(Proxy.NO_PROXY, client.proxy)
        assertFalse(client.followSslRedirects)
        assertNotNull(client.sslSocketFactory)
    }

    @Test fun anonymousFixtureRequestNeverCarriesTheKey() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            val client = CivitaiHttp.client(CivitaiSettings(apiKey = "private-key"))
            assertEquals("{}", CivitaiHttp.text(client, CivitaiHttp.request(server.url("/api/v1/models"))))
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertNull(request.getHeader("Authorization"))
            assertFalse(request.path.orEmpty().contains("private-key"))
        }
    }

    @Test fun customHttpProxyIsUsedAndBasicAuthenticationRetriesOnlyOnce() = runBlocking {
        MockWebServer().use { proxy ->
            proxy.enqueue(MockResponse().setResponseCode(407)
                .addHeader("Proxy-Authenticate", "Basic realm=\"fixture\""))
            proxy.enqueue(MockResponse().setBody("{}"))
            val client = CivitaiHttp.client(CivitaiSettings(
                proxyEnabled = true, proxyAddress = proxy.url("/").toString(),
                proxyUsername = "user", proxyPassword = "password", apiKey = "private-key"
            ))
            assertEquals("{}", CivitaiHttp.text(client,
                CivitaiHttp.request(okhttp3.HttpUrl.Builder().scheme("http")
                    .host("unresolvable.invalid").addPathSegment("fixture").build())))
            val initial = proxy.takeRequest(2, TimeUnit.SECONDS)!!
            val retry = proxy.takeRequest(2, TimeUnit.SECONDS)!!
            assertTrue(initial.requestLine.startsWith("GET http://unresolvable.invalid/fixture"))
            assertNull(initial.getHeader("Authorization"))
            assertEquals(Credentials.basic("user", "password", Charsets.UTF_8),
                retry.getHeader("Proxy-Authorization"))
            assertNull(retry.getHeader("Authorization"))
        }
    }

    @Test fun wrongProxyCredentialsStopInsteadOfLooping() = runBlocking {
        MockWebServer().use { proxy ->
            repeat(2) {
                proxy.enqueue(MockResponse().setResponseCode(407)
                    .addHeader("Proxy-Authenticate", "Basic realm=\"fixture\""))
            }
            val client = CivitaiHttp.client(CivitaiSettings(proxyEnabled = true,
                proxyAddress = proxy.url("/").toString(), proxyUsername = "user", proxyPassword = "bad"))
            try {
                CivitaiHttp.text(client, CivitaiHttp.request(okhttp3.HttpUrl.Builder()
                    .scheme("http").host("unresolvable.invalid").build()))
                fail("Expected proxy authentication failure")
            } catch (error: MarketAccessException) {
                assertEquals(407, error.status)
            }
            assertEquals(2, proxy.requestCount)
        }
    }

    @Test fun upstreamErrorBodiesNeverAppearInUserErrors() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("body-with-a-private-key"))
            try {
                CivitaiHttp.text(CivitaiHttp.client(CivitaiSettings()),
                    CivitaiHttp.request(server.url("/models")))
                fail("Expected forbidden")
            } catch (error: MarketAccessException) {
                assertEquals(403, error.status)
                assertFalse(error.toString().contains("body-with-a-private-key"))
            }
        }
    }

    @Test fun untrustedHttpsCertificatesAreRejected() = runBlocking {
        MockWebServer().use { server ->
            val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build()
                .sslSocketFactory(), false)
            server.enqueue(MockResponse().setBody("{}"))
            try {
                CivitaiHttp.text(CivitaiHttp.client(CivitaiSettings()),
                    CivitaiHttp.request(server.url("/")))
                fail("Expected certificate rejection")
            } catch (error: MarketAccessException) {
                assertTrue(error.message.orEmpty().contains("TLS"))
            }
        }
    }

    @Test fun cancellingCoroutineCancelsItsNetworkCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = CivitaiHttp.client(CivitaiSettings())
            val job = launch(Dispatchers.IO) {
                CivitaiHttp.text(client, CivitaiHttp.request(server.url("/slow")))
            }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            client.dispatcher.cancelAll()
        }
    }

    @Test fun errorsDistinguishCredentialsPermissionAndRateLimit() {
        assertTrue(CivitaiHttp.statusMessage(401).contains("Key"))
        assertTrue(CivitaiHttp.statusMessage(403).contains("权限"))
        assertTrue(CivitaiHttp.statusMessage(407).contains("代理"))
        assertTrue(CivitaiHttp.statusMessage(429).contains("频率"))
    }
}
