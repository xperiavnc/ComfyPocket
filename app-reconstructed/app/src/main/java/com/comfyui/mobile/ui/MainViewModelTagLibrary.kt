package com.comfyui.mobile.ui

import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.comfyui.mobile.agent.AgentPromptSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal object MainViewModelTagLibrary {
    fun install(vm: MainViewModel, uri: Uri) {
        vm.viewModelScope.launch {
            try {
                vm.localTagLibrary.store.install {
                    requireNotNull(vm.getApplication<android.app.Application>().contentResolver.openInputStream(uri)) { "读取所选文件失败" }
                }
                val value = vm._agentPromptSettings.value.copy(useLocalLibrary = true)
                AgentPromptSettingsStore(vm.prefs).save(value)
                vm._agentPromptSettings.value = value
                vm.showUserMessage("词库已安装，已启用绘图前查词")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                vm.showUserMessage("词库导入失败：${error.message ?: "请重新选择资源包"}")
            }
        }
    }

    fun remove(vm: MainViewModel) {
        vm.viewModelScope.launch {
            try {
                vm.localTagLibrary.store.remove()
                val value = vm._agentPromptSettings.value.copy(useLocalLibrary = false)
                AgentPromptSettingsStore(vm.prefs).save(value)
                vm._agentPromptSettings.value = value
                vm.showUserMessage("已移除本地词库")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                vm.showUserMessage("词库移除失败：${error.message ?: "请重试"}")
            }
        }
    }
}
