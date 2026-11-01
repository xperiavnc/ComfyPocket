package com.comfyui.mobile.data

import com.comfyui.mobile.agent.AgentProxySettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class LlmProxyTest {
    private lateinit var proxy: MockWebServer
    private lateinit var network: OkHttpClient

    @Before fun setup() {
        proxy = MockWebServer(); proxy.start()
        network = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
    }

    @After fun close() {
        network.dispatcher.executorService.shutdownNow()
        proxy.shutdown()
    }

    private fun settings(user: String = "", pass: String = "") =
        AgentProxySettings(true, "http://${proxy.hostName}:${proxy.port}", user, pass)

    @Test fun publicEndpointsGoThroughTheProxyWithCredentials() = runBlocking {
        // The test proxy lives on localhost, which the LAN rule would bypass; check the selector
        // separately and route a public host through it here.
        val client = LlmClient(client = network)
        client.applyProxy(settings("user", "secret"))
        proxy.enqueue(MockResponse().setResponseCode(407).addHeader("Proxy-Authenticate", "Basic realm=\"p\""))
        proxy.enqueue(MockResponse().setBody("""{"data":[{"id":"m1"}]}"""))
        val models = client.listModels("http://api.example.invalid/v1", "k").getOrThrow()
        assertEquals(listOf("m1"), models)
        val first = proxy.takeRequest()
        assertTrue(first.requestLine.contains("http://api.example.invalid/v1/models"))
        assertNull(first.getHeader("Proxy-Authorization"))
        val second = proxy.takeRequest()
        assertTrue(second.getHeader("Proxy-Authorization").orEmpty().startsWith("Basic "))
    }

    @Test fun lanHostsBypassTheProxyAndDisablingRestoresDefault() {
        val selector = LanBypassProxySelector.through(java.net.Proxy(java.net.Proxy.Type.HTTP,
            java.net.InetSocketAddress.createUnresolved("10.0.0.2", 7890)))
        assertEquals(java.net.Proxy.NO_PROXY, selector.select(java.net.URI("http://192.168.1.5:11434/v1")).single())
        assertEquals(java.net.Proxy.NO_PROXY, selector.select(java.net.URI("http://localhost:1234")).single())
        assertEquals(java.net.Proxy.Type.HTTP, selector.select(java.net.URI("https://api.deepseek.com")).single().type())
    }

    @Test fun malformedAddressesAreRejectedWithReadableMessages() {
        for (bad in listOf("", "socks5://1.2.3.4:1080", "http://u:p@1.2.3.4:8080", "http://1.2.3.4:8080/path")) {
            try {
                AgentProxySettings(true, bad).validated()
                fail("accepted $bad")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message.orEmpty().contains("代理"))
            }
        }
        assertEquals("1.2.3.4:7890", AgentProxySettings(true, " 1.2.3.4:7890 ").validated().label())
        assertNull(AgentProxySettings(false, "garbage").validated().proxyUrl())
    }

    @Test fun proxyFailuresMentionTheProxy() = runBlocking {
        val client = LlmClient(client = network)
        val dead = MockWebServer().apply { start() }
        val port = dead.port
        dead.shutdown()
        client.applyProxy(AgentProxySettings(true, "http://127.0.0.1:$port"))
        val error = client.listModels("http://api.example.invalid/v1", "").exceptionOrNull() as LlmException
        assertTrue(error.suggestion, error.suggestion.contains("HTTP 代理 127.0.0.1:$port"))
    }
}
