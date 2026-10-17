package com.comfyui.mobile.ui.material

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.view.WindowCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.ImageLoader
import com.comfyui.mobile.danbooru.*

class MaterialSampleSession(app: Application) : AndroidViewModel(app) {
    val fixture = MaterialFixtures(app)
    val images = ImageLoader.Builder(app).crossfade(false).build()
    override fun onCleared() { images.shutdown() }
}

/** Explicit opt-in, non-exported and separate from the production navigation/theme. */
class MaterialSampleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val session: MaterialSampleSession = viewModel()
            var online by rememberSaveable { mutableStateOf(false) }
            val runtime = if (online) remember { DanbooruRuntime.get(applicationContext) } else null
            val service = runtime ?: session.fixture
            val vm: DanbooruViewModel = viewModel(key = if (online) "material-online" else "material-offline",
                factory = viewModelFactory { initializer { DanbooruViewModel(createSavedStateHandle(), service) } })
            val editor: MaterialConnectionEditor = viewModel(key = if (online) "material-online-editor" else "material-offline-editor")
            MaterialSampleScreen(vm, editor, runtime?.imageLoader ?: session.images, online,
                onOnline = { online = it }, onScenario = session.fixture::scenario, onExit = ::finish,
                onCopy = {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("Danbooru Tag", it.text))
                },
                onWeb = { url ->
                    if (online && Uri.parse(url).scheme in setOf("https", "http"))
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                },
                onOriginal = { post, share -> materialOriginalAction(this, checkNotNull(runtime), post, share) },
                onDarkChanged = { dark ->
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                })
        }
    }
}
