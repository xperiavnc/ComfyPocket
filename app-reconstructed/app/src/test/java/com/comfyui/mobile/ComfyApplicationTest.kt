package com.comfyui.mobile

import android.view.ContextThemeWrapper
import coil.Coil
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComfyApplicationTest {
    @Test fun imageLoaderIsApplicationScopedBeforeOpeningAnyScreen() {
        val app = RuntimeEnvironment.getApplication()
        assertTrue(app is ComfyApplication)
        val first = Coil.imageLoader(app)
        val reopenedScreen = ContextThemeWrapper(app, android.R.style.Theme_Material)
        assertSame(first, Coil.imageLoader(reopenedScreen))
        assertNotNull(first.diskCache)
        assertNotNull(first.memoryCache)
    }
}
