package com.comfyui.mobile.ui

import android.os.Looper
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf

/** Let production migration finish before exercising the writable settings controls. */
internal fun awaitAgentStorage(vm: MainViewModel) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (!vm.agentStorageStatus.value.writable && System.nanoTime() < deadline) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
        Thread.sleep(5)
    }
    assertTrue(vm.agentStorageStatus.value.toString(), vm.agentStorageStatus.value.writable)
}
