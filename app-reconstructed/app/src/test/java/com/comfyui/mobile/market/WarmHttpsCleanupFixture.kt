package com.comfyui.mobile.market

import android.os.Looper
import android.os.NetworkOnMainThreadException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import java.io.Closeable
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket

/**
 * Real loopback HTTPS, including a second request reusing the same idle TLS connection.
 * Desktop TLS does not enforce Android's StrictMode. After warming the connection, replace
 * only its close handle with a guard reproducing Conscrypt's network-on-main check.
 * This reflection is deliberately confined to tests for the pinned OkHttp 4.12.0 version.
 */
internal class WarmHttpsCleanupFixture(blockClose: Boolean = false) : Closeable {
    private val server = MockWebServer()
    private val acquired = CopyOnWriteArrayList<Connection>()
    val closeThreads = CopyOnWriteArrayList<Thread>()
    val closeAttempts = AtomicInteger()
    val closeStarted = CountDownLatch(1)
    private val allowClose = CountDownLatch(if (blockClose) 1 else 0)
    private lateinit var tlsSocket: Socket
    val client: OkHttpClient

    init {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1").build()
        server.useHttps(HandshakeCertificates.Builder()
            .heldCertificate(certificate).build().sslSocketFactory(), false)
        server.start()
        val trust = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate).build()
        client = OkHttpClient.Builder()
            .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .protocols(listOf(Protocol.HTTP_1_1))
            .eventListener(object : EventListener() {
                override fun connectionAcquired(call: Call, connection: Connection) {
                    acquired += connection
                }
            }).build()
        runBlocking(Dispatchers.IO) {
            repeat(2) {
                server.enqueue(MockResponse().setBody("{}"))
                client.newCall(Request.Builder().url(server.url("/models")).build())
                    .execute().use { assertEquals("{}", it.body!!.string()) }
            }
            assertEquals(0, server.takeRequest().sequenceNumber)
            assertEquals("The fixture must reuse an HTTPS connection", 1,
                server.takeRequest().sequenceNumber)
        }
        assertSame(acquired[0], acquired[1])
        assertEquals(1, client.connectionPool.idleConnectionCount())
        tlsSocket = acquired.last().socket()
        assertTrue("The warmed connection must use TLS", tlsSocket is SSLSocket)
        val socketField = acquired.last().javaClass.getDeclaredField("socket").apply {
            isAccessible = true
        }
        socketField.set(acquired.last(), object : Socket() {
            override fun close() {
                closeAttempts.incrementAndGet()
                closeThreads += Thread.currentThread()
                closeStarted.countDown()
                if (Thread.currentThread() === Looper.getMainLooper().thread) {
                    throw NetworkOnMainThreadException()
                }
                check(allowClose.await(10, TimeUnit.SECONDS)) { "TLS cleanup test gate timed out" }
                tlsSocket.close()
            }
        })
    }

    fun installIn(repository: CivitaiRepository) {
        val field = CivitaiRepository::class.java.getDeclaredField("client").apply {
            isAccessible = true
        }
        val unused = field.get(repository) as OkHttpClient
        field.set(repository, client)
        runBlocking(Dispatchers.IO) {
            unused.dispatcher.cancelAll()
            unused.connectionPool.evictAll()
            unused.dispatcher.executorService.shutdown()
        }
    }

    fun releaseClose() = allowClose.countDown()

    fun assertClosedOffMain() {
        assertTrue(closeStarted.await(5, TimeUnit.SECONDS))
        assertTrue(closeThreads.isNotEmpty())
        assertTrue(closeThreads.all { it !== Looper.getMainLooper().thread })
    }

    override fun close() {
        releaseClose()
        runBlocking(Dispatchers.IO) {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            if (::tlsSocket.isInitialized) tlsSocket.close()
            client.dispatcher.executorService.shutdown()
            server.shutdown()
        }
    }
}
