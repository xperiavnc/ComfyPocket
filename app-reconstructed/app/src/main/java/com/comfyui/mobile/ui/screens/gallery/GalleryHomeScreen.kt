package com.comfyui.mobile.ui.screens.gallery

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavController
import com.comfyui.mobile.danbooru.DanbooruRuntime
import com.comfyui.mobile.danbooru.DanbooruViewModel
import com.comfyui.mobile.ui.GalleryUi
import com.comfyui.mobile.ui.screens.CategoryChip
import com.comfyui.mobile.ui.screens.GalleryScreen
import com.comfyui.mobile.ui.screens.danbooru.DanbooruScreen

enum class GalleryHomeSource(val title: String, val legacy: String?) {
    SERVER("服务器", GalleryUi.GALLERY_SERVER), LOCAL("本地", GalleryUi.GALLERY_LOCAL), DANBOORU("Danbooru", null)
}

class GalleryHomeSourceStore(private val prefs: SharedPreferences) {
    fun load(): GalleryHomeSource {
        val stored = prefs.getString("gallery_home_source", null)
        GalleryHomeSource.entries.firstOrNull { it.name == stored }?.let { return it }
        val migrated = if (prefs.getString("gallery_source", "server") == "local") GalleryHomeSource.LOCAL else GalleryHomeSource.SERVER
        save(migrated)
        return migrated
    }
    fun save(value: GalleryHomeSource) {
        prefs.edit().putString("gallery_home_source", value.name).apply()
    }
}

/** Only a source host. No extra Scaffold and no online business in the existing god ViewModel. */
@Composable
fun GalleryHomeScreen(navController: NavController, legacy: GalleryUi) {
    val app = LocalContext.current.applicationContext
    val store = remember(app) { GalleryHomeSourceStore(app.getSharedPreferences("comfy_prefs", Context.MODE_PRIVATE)) }
    var source by rememberSaveable { mutableStateOf(store.load()) }
    val runtime = remember(app) { DanbooruRuntime.get(app) }
    val online: DanbooruViewModel = viewModel(factory = viewModelFactory {
        initializer { DanbooruViewModel(createSavedStateHandle(), runtime) }
    })
    val holder = rememberSaveableStateHolder()
    val legacySource by legacy.gallerySource.collectAsState()
    fun select(next: GalleryHomeSource) {
        source = next
        store.save(next)
        next.legacy?.let { if (legacySource != it) legacy.setGallerySource(it) }
    }
    val picker: @Composable () -> Unit = {
        GallerySourcePicker(source, ::select)
    }
    holder.SaveableStateProvider(source.name) {
        if (source == GalleryHomeSource.DANBOORU) {
            DanbooruScreen(online, runtime, picker)
        } else {
            androidx.compose.runtime.LaunchedEffect(source) {
                source.legacy?.let { if (legacy.gallerySource.value != it) legacy.setGallerySource(it) }
            }
            GalleryScreen(navController, legacy, picker)
        }
    }
}

@Composable
fun GallerySourcePicker(selected: GalleryHomeSource, onSelect: (GalleryHomeSource) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GalleryHomeSource.entries.forEach { source -> CategoryChip(source.title, selected == source) { onSelect(source) } }
    }
}
