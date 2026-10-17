package com.comfyui.mobile.market

import android.os.Looper
import com.comfyui.mobile.data.AppModule
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class CivitaiCleanupRegressionTest {
    private fun repository() = CivitaiRepository(
        RuntimeEnvironment.getApplication(), CivitaiSettings(), AppModule.provideRepository()
    )

    @Test
    fun warmHttpsRepositoryCloseRunsOffMain() {
        assertSame(Looper.getMainLooper().thread, Thread.currentThread())
        WarmHttpsCleanupFixture().use { https ->
            val repository = repository()
            https.installIn(repository)
            // onCleared is called on main after the return transition completes.
            repository.close()
            https.assertClosedOffMain()
        }
    }

    @Test
    fun savingSettingsRetiresWarmHttpsClientOffMain() {
        assertSame(Looper.getMainLooper().thread, Thread.currentThread())
        WarmHttpsCleanupFixture().use { https ->
            val repository = repository()
            https.installIn(repository)
            try {
                // The settings screen also calls this method from main.
                repository.updateSettings(CivitaiSettings(apiKey = "fixture-key"))
                https.assertClosedOffMain()
            } finally {
                repository.close()
            }
        }
    }
}
