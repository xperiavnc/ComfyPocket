package com.comfyui.mobile.market

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import com.comfyui.mobile.data.AppModule
import com.comfyui.mobile.diagnostics.NavigationDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class MarketResourceCleanupTest {
    private lateinit var app: Application

    @Before
    fun start() {
        assertSame(Looper.getMainLooper().thread, Thread.currentThread())
        // Drain any earlier test's finite cleanup before starting this journal session.
        assertTrue(MarketResourceCleanup.release(OkHttpClient()).get(10, TimeUnit.SECONDS))
        app = RuntimeEnvironment.getApplication()
        NavigationDiagnostics.start(app)
    }

    private fun repository() = CivitaiRepository(app, CivitaiSettings(), AppModule.provideRepository())

    private fun currentClient(repository: CivitaiRepository): OkHttpClient =
        CivitaiRepository::class.java.getDeclaredField("client").let {
            it.isAccessible = true
            it.get(repository) as OkHttpClient
        }

    private fun trace() = NavigationDiagnostics.report(app).substringAfterLast("SESSION_STARTED")

    private fun assertReleased(client: OkHttpClient) {
        assertTrue(client.dispatcher.executorService.isShutdown)
        assertTrue(client.dispatcher.executorService.isTerminated)
        assertEquals(0, client.dispatcher.runningCallsCount())
        assertEquals(0, client.dispatcher.queuedCallsCount())
        assertEquals(0, client.connectionPool.connectionCount())
    }

    @Test
    fun viewModelClearClosesWarmTlsAfterItsScopeIsCancelledWithoutPrematureSuccess() {
        WarmHttpsCleanupFixture(blockClose = true).use { https ->
            val viewModel = MarketViewModel(app)
            val store = ViewModelStore().apply { put("market", viewModel) }
            val scopeJob = viewModel.viewModelScope.coroutineContext[Job]!!
            https.installIn(viewModel.repository)
            try {
                store.clear()
                assertTrue("ViewModel scope must already be cancelled", scopeJob.isCancelled)
                val completion = viewModel.repository.close()
                assertTrue(https.closeStarted.await(5, TimeUnit.SECONDS))
                assertFalse(completion.isDone)
                assertTrue("MARKET_REPOSITORY_CLOSING" in trace())
                assertFalse("Must not mark success just after scheduling", "MARKET_REPOSITORY_CLOSED" in trace())
                https.releaseClose()
                assertTrue(completion.get(10, TimeUnit.SECONDS))
                https.assertClosedOffMain()
                assertReleased(https.client)
                assertTrue("MARKET_REPOSITORY_CLOSED" in trace())
                assertFalse("MARKET_REPOSITORY_CLOSE_FAILED" in trace())
                assertFalse("JAVA_CRASH" in trace())
            } finally {
                https.releaseClose()
                viewModel.repository.close().get(10, TimeUnit.SECONDS)
                store.clear()
            }
        }
    }

    @Test
    fun repeatedCloseSharesOneCompletionAndOneTlsRelease() {
        WarmHttpsCleanupFixture(blockClose = true).use { https ->
            val repository = repository()
            https.installIn(repository)
            try {
                val first = repository.close()
                repeat(25) { assertSame(first, repository.close()) }
                assertTrue(https.closeStarted.await(5, TimeUnit.SECONDS))
                assertFalse(first.isDone)
                https.releaseClose()
                assertTrue(first.get(10, TimeUnit.SECONDS))
                assertSame(first, repository.close())
                assertEquals(1, https.closeAttempts.get())
                assertReleased(https.client)
                assertEquals(1, trace().lineSequence().count { " MARKET_REPOSITORY_CLOSING " in it })
                assertEquals(1, trace().lineSequence().count { " MARKET_REPOSITORY_CLOSED " in it })
            } finally {
                https.releaseClose()
                repository.close().get(10, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun rotatingSettingsKeepsReplacementUsableAndFinalCloseDrainsBothClients() {
        WarmHttpsCleanupFixture(blockClose = true).use { https ->
            val repository = repository()
            https.installIn(repository)
            try {
                repository.updateSettings(CivitaiSettings(apiKey = "fixture-key"))
                assertTrue(https.closeStarted.await(5, TimeUnit.SECONDS))
                val replacement = currentClient(repository)
                assertNotSame(https.client, replacement)
                assertFalse(replacement.dispatcher.executorService.isShutdown)
                // The old client's blocked TLS close must not stop new settings taking effect.
                MockWebServer().use { server ->
                    server.enqueue(MockResponse().setBody("{}"))
                    assertEquals("{}", runBlocking(Dispatchers.IO) {
                        CivitaiHttp.text(replacement, CivitaiHttp.request(server.url("/models")))
                    })
                    val completion = repository.close()
                    assertFalse("Final close must include retired clients", completion.isDone)
                    assertFalse("MARKET_REPOSITORY_CLOSED" in trace())
                    https.releaseClose()
                    assertTrue(completion.get(10, TimeUnit.SECONDS))
                    assertReleased(https.client)
                    assertReleased(replacement)
                }
                https.assertClosedOffMain()
            } finally {
                https.releaseClose()
                repository.close().get(10, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun rapidSettingsChangesDoNotLeakRetiredDispatchers() {
        WarmHttpsCleanupFixture(blockClose = true).use { https ->
            val repository = repository()
            https.installIn(repository)
            val owned = mutableListOf(https.client)
            try {
                repeat(5) {
                    repository.updateSettings(CivitaiSettings(apiKey = "fixture-$it"))
                    owned += currentClient(repository)
                }
                val completion = repository.close()
                https.releaseClose()
                assertTrue(completion.get(10, TimeUnit.SECONDS))
                owned.forEach(::assertReleased)
                assertEquals(1, https.closeAttempts.get())
            } finally {
                https.releaseClose()
                repository.close().get(10, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun settingsAfterCloseFailWithoutCreatingAnotherClient() {
        val repository = repository()
        val original = currentClient(repository)
        assertTrue(repository.close().get(10, TimeUnit.SECONDS))
        assertThrows(IllegalStateException::class.java) {
            repository.updateSettings(CivitaiSettings(apiKey = "not-to-be-created"))
        }
        assertSame(original, currentClient(repository))
        assertReleased(original)
    }

    @Test
    fun aFailedImageShutdownStillReleasesTlsAndReportsFailureWithoutMessages() {
        WarmHttpsCleanupFixture().use { https ->
            val loader = ImageLoader.Builder(app).diskCache(null).build()
            val throwingLoader = object : ImageLoader by loader {
                override fun shutdown() {
                    assertNotSame(Looper.getMainLooper().thread, Thread.currentThread())
                    throw IllegalStateException("PRIVATE-key-and-url-must-not-be-logged")
                }
            }
            try {
                assertFalse(MarketResourceCleanup.release(
                    https.client, throwingLoader, closingRepository = true
                ).get(10, TimeUnit.SECONDS))
                https.assertClosedOffMain()
                assertReleased(https.client)
                assertTrue("MARKET_RESOURCE_CLEANUP_FAILED" in trace())
                assertTrue("MARKET_REPOSITORY_CLOSE_FAILED" in trace())
                assertFalse("MARKET_REPOSITORY_CLOSED" in trace())
                assertFalse("PRIVATE-key" in trace())
            } finally {
                runBlocking(Dispatchers.IO) { loader.shutdown() }
            }
        }
    }

    @Test
    fun aRetiredClientFailureDoesNotSkipCurrentCleanupOrMarkOverallSuccess() {
        WarmHttpsCleanupFixture().use { https ->
            assertFalse(MarketResourceCleanup.release(
                https.client, previous = CompletableFuture.completedFuture(false),
                closingRepository = true
            ).get(10, TimeUnit.SECONDS))
            https.assertClosedOffMain()
            assertReleased(https.client)
            assertTrue("MARKET_REPOSITORY_CLOSE_FAILED" in trace())
            assertFalse("MARKET_REPOSITORY_CLOSED" in trace())
        }
    }

    @Test
    fun queuedAndRunningCallsAreCancelledOffMainBeforeCleanupCompletes() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val cancellationThreads = CopyOnWriteArrayList<Thread>()
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun canceled(call: Call) {
                    cancellationThreads += Thread.currentThread()
                }
            }).build()
            client.dispatcher.maxRequests = 1
            client.dispatcher.maxRequestsPerHost = 1
            val failed = CountDownLatch(2)
            val callback = object : Callback {
                override fun onFailure(call: Call, e: IOException) { failed.countDown() }
                override fun onResponse(call: Call, response: Response) { response.close() }
            }
            try {
                repeat(2) {
                    client.newCall(Request.Builder().url(server.url("/pending")).build()).enqueue(callback)
                }
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                assertEquals(1, client.dispatcher.runningCallsCount())
                assertEquals(1, client.dispatcher.queuedCallsCount())
                assertTrue(MarketResourceCleanup.release(client).get(10, TimeUnit.SECONDS))
                assertTrue(failed.await(5, TimeUnit.SECONDS))
                assertEquals(2, cancellationThreads.size)
                assertTrue(cancellationThreads.all { it !== Looper.getMainLooper().thread })
                assertReleased(client)
            } finally {
                MarketResourceCleanup.release(client).get(10, TimeUnit.SECONDS)
            }
        }
    }
}
