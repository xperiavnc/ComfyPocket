package com.comfyui.mobile.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.comfyui.mobile.agent.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The file is read off-main, then committed once. A late import cannot undo a newer remove/import. */
internal class MainViewModelAgentRules(private val vm: MainViewModel) {
    private val versions = AgentRuleKind.entries.associateWith { AtomicLong() }
    private val mutableImporting = MutableStateFlow<Set<AgentRuleKind>>(emptySet())
    val importing = mutableImporting.asStateFlow()

    fun import(kind: AgentRuleKind, uri: Uri) {
        val ticket = versions.getValue(kind).incrementAndGet()
        mutableImporting.value += kind
        vm.viewModelScope.launch {
            try {
                val document = withContext(Dispatchers.IO) {
                    val resolver = vm.getApplication<Application>().contentResolver
                    val name = (vm.getFileName(uri) ?: uri.lastPathSegment ?: "rules.txt")
                        .substringAfterLast('/').substringAfterLast('\\')
                    (resolver.openInputStream(uri) ?: error("规则文件读取失败")).use { AgentRuleFileReader.read(name, it) }
                }
                if (versions.getValue(kind).get() != ticket) return@launch
                replace(kind, document)
                vm.showToast("已导入${kind.title}：${document.name}，下一轮生效")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (versions.getValue(kind).get() == ticket) vm.showToast("导入失败：${e.message ?: "文件读取错误"}")
            } finally {
                if (versions.getValue(kind).get() == ticket) mutableImporting.value -= kind
            }
        }
    }

    fun replace(kind: AgentRuleKind, document: AgentRuleDocument?) =
        apply(vm._agentRuleFiles.value.replace(kind, document), setOf(kind))

    fun restore(files: AgentRuleFiles) = apply(files, AgentRuleKind.entries.toSet())

    private fun apply(files: AgentRuleFiles, changed: Set<AgentRuleKind>) {
        AgentRuleFilesStore(vm.prefs, vm.gson).save(files)
        changed.forEach { versions.getValue(it).incrementAndGet() }
        mutableImporting.value -= changed
        vm._agentRuleFiles.value = files
        vm._agentSystemPrompt.value = files.dialogue?.content.orEmpty()
        vm._agentDrawPrompt.value = files.drawing?.content.orEmpty()
        // AgentLlmSettings already freezes both fields at send; a new file applies next turn.
    }
}
