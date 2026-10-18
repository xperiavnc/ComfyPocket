package com.comfyui.mobile.ui

import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import com.comfyui.mobile.ui.theme.BottomBarStyle
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class BottomBarStyleTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("comfy_prefs", Context.MODE_PRIVATE)
    private val stores = mutableListOf<ViewModelStore>()
    private fun viewModel() = MainViewModel(app).also { vm ->
        stores += ViewModelStore().apply { put("appearance", vm) }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Before fun clear() { prefs.edit().clear().commit() }
    @After fun close() { stores.forEach { it.clear() } }

    @Test fun onlyTwoStableStylesAndUnknownValuesKeepTheCurrentBar() {
        assertEquals(2, BottomBarStyle.entries.size)
        assertEquals(BottomBarStyle.MATERIAL, BottomBarStyle.fromId(null))
        assertEquals(BottomBarStyle.MATERIAL, BottomBarStyle.fromId("future-or-invalid"))
        assertEquals(BottomBarStyle.CLASSIC, BottomBarStyle.fromId("classic"))
    }

    @Test fun upgradingDoesNotReviveAnOldGlassPreferenceOrChangeOtherSettings() {
        prefs.edit().putBoolean("liquid_glass_enabled", true).putInt("theme_mode", 2)
            .putFloat("ui_density_scale", 1.15f).commit()
        val vm = viewModel()
        assertEquals(BottomBarStyle.MATERIAL, vm.bottomBarStyle.value)
        vm.setBottomBarStyle(BottomBarStyle.CLASSIC)
        assertEquals(2, prefs.getInt("theme_mode", -1))
        assertEquals(1.15f, prefs.getFloat("ui_density_scale", 0f))
        assertTrue(prefs.getBoolean("liquid_glass_enabled", false))
    }

    @Test fun switchingBothWaysPersistsAcrossFreshViewModels() {
        val first = viewModel()
        first.setBottomBarStyle(BottomBarStyle.CLASSIC)
        assertEquals("classic", prefs.getString(BottomBarStyle.PREFERENCE_KEY, null))
        assertEquals(BottomBarStyle.CLASSIC, viewModel().bottomBarStyle.value)
        first.setBottomBarStyle(BottomBarStyle.MATERIAL)
        assertEquals("material", prefs.getString(BottomBarStyle.PREFERENCE_KEY, null))
        assertEquals(BottomBarStyle.MATERIAL, viewModel().bottomBarStyle.value)
    }
}
