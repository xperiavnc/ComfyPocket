package com.comfyui.mobile.market

import coil.ImageLoader
import com.comfyui.mobile.diagnostics.NavigationDiagnostics
import okhttp3.OkHttpClient
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * TLS socket.close() can write close_notify. Never cancel/evict clients on the UI thread.
 *
 * This process-owned IO worker deliberately outlives ViewModel.onCleared/viewModelScope.
 * Only finite resource-release jobs are submitted; they hold no Activity or ViewModel.
 * FIFO ordering also drains clients retired by settings changes before final close completes.
 */
internal object MarketResourceCleanup {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ComfyPocket-market-cleanup").apply { isDaemon = true }
    }

    fun release(
        client: OkHttpClient,
        imageLoader: ImageLoader? = null,
        previous: CompletableFuture<Boolean>? = null,
        closingRepository: Boolean = false
    ): CompletableFuture<Boolean> = CompletableFuture.supplyAsync({
        var successful = true
        fun attempt(action: () -> Unit) {
            try {
                action()
            } catch (error: Exception) {
                // One failed resource must not skip the remaining cleanup operations.
                // Do not record messages: they may include URLs or credentials.
                successful = false
                if (error is InterruptedException) Thread.currentThread().interrupt()
            }
        }
        attempt { if (previous?.join() == false) successful = false }
        attempt { imageLoader?.shutdown() }
        attempt { client.dispatcher.cancelAll() }
        attempt { client.dispatcher.executorService.shutdown() }
        attempt {
            check(client.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS))
        }
        attempt { client.connectionPool.evictAll() }
        if (!successful) {
            NavigationDiagnostics.record(NavigationDiagnostics.Event.MARKET_RESOURCE_CLEANUP_FAILED)
        }
        if (closingRepository) {
            NavigationDiagnostics.record(if (successful)
                NavigationDiagnostics.Event.MARKET_REPOSITORY_CLOSED
            else NavigationDiagnostics.Event.MARKET_REPOSITORY_CLOSE_FAILED)
        }
        successful
    }, worker)
}
