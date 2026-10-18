package com.comfyui.mobile.market

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.comfyui.mobile.data.AppModule
import com.comfyui.mobile.model.CivitaiModelItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** The market owns its state; adding Civitai features does not enlarge MainViewModel. */
class MarketViewModel(application: Application) : AndroidViewModel(application) {
    private val store = CivitaiSettingsStore(application)
    private val mutableSettings = MutableStateFlow(store.load())
    val settings = mutableSettings.asStateFlow()
    val repository = CivitaiRepository(application, settings.value, AppModule.provideRepository())
    val catalog = MarketBrowseController(viewModelScope, settings.value.includeMatureContent, repository::search)
    val opener = MarketModelOpener(viewModelScope, repository::openModel)

    fun bridgeContext(identity: String, available: Boolean) {
        if (repository.bridgeContext(identity, available) &&
            settings.value.mode == MarketConnectionMode.BRIDGE) {
            catalog.invalidate()
            catalog.ensureLoaded()
        }
    }

    suspend fun saveSettings(value: CivitaiSettings): Result<Unit> = try {
        val checked = value.validated()
        withContext(Dispatchers.IO) { store.save(checked) }
        opener.invalidate()
        repository.updateSettings(checked)
        mutableSettings.value = checked
        catalog.invalidate(checked.includeMatureContent)
        catalog.ensureLoaded()
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(IllegalArgumentException(error.message ?: "保存集市设置失败，请重试"))
    }

    suspend fun details(model: CivitaiModelItem, versionId: Long?, refresh: Boolean) =
        repository.details(model, versionId, refresh)

    override fun onCleared() {
        // The repository's IO cleanup must survive the already-cancelled viewModelScope.
        repository.close()
        super.onCleared()
    }
}
