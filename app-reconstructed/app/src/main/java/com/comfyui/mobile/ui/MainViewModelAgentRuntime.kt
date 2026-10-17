package com.comfyui.mobile.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.comfyui.mobile.agent.*
import com.comfyui.mobile.data.LlmClient
import com.comfyui.mobile.data.LlmErrorMapper
import com.comfyui.mobile.data.LlmException
import com.comfyui.mobile.model.AgentAttachment
import com.comfyui.mobile.model.AgentChatMessage
import com.comfyui.mobile.model.CharacterLore
import com.comfyui.mobile.model.EditableNode
import com.comfyui.mobile.model.LogType
import com.comfyui.mobile.network.ChatMessageDto
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Immutable per-turn settings. Not a data class: its diagnostic toString must not print the key. */
internal class AgentLlmSettings(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val effort: AgentReasoningEffort,
    val persona: String,
    val drawRules: String,
    val lores: List<CharacterLore>,
    val lastPrompt: String,
    val tagSettings: com.comfyui.mobile.danbooru.DanbooruSettings = com.comfyui.mobile.danbooru.DanbooruSettings()
) {
    companion object {
        fun capture(vm: MainViewModel) = AgentLlmSettings(
            vm._llmBaseUrl.value, vm._llmApiKey.value, vm._llmModel.value,
            vm._llmReasoningEffort.value, vm._agentSystemPrompt.value, vm._agentDrawPrompt.value,
            vm._characterLores.value.toList(),
            vm._agentLastExpandedPrompt.value.ifBlank {
                vm._agentMessages.value.asReversed().firstOrNull {
                    it.role == "assistant" && !it.expandedPrompt.isNullOrBlank()
                }?.expandedPrompt.orEmpty()
            },
            vm._agentDanbooruSettings.value
        )
    }
}

/** Per-ViewModel Android adapter. No process-global job, stop flag or active assistant identity. */
internal class MainViewModelAgentRuntime(private val vm: MainViewModel) {
    private val session get() = vm.agentSession
    private val tools = AgentTools()

    fun stop(reason: String = "已停止", invalidateContext: Boolean = false) {
        val submitting = session.state.value.turn?.phase == AgentPhase.SUBMITTING
        val turn = session.stop(reason, invalidateContext) ?: return
        MainViewModelAgent.updateAgentMessage(vm, turn.assistantId, persist = true) {
            val partial = it.content.takeIf { c -> c.isNotBlank() && c != "✨深度求索中..." }
            it.copy(
                content = partial ?: reason,
                isThinking = false,
                tagCheckingOrigin = null,
                errorMessage = if (submitting) {
                    "$reason；服务端已接收的任务可能继续运行"
                } else reason
            )
        }
    }

    fun send(userText: String, mode: AgentMode) {
        val text = userText.trim()
        val attachments = vm._agentPendingAttachments.value.toList()
        if (text.isEmpty() && attachments.isEmpty()) return
        val version = session.contextVersion()
        val turn = session.begin("${MainViewModelAgent.scope(vm)}:$version") ?: return
        val settings = AgentLlmSettings.capture(vm)
        val prior = vm._agentMessages.value.toList()
        val user = AgentChatMessage(
            id = "u-${UUID.randomUUID()}", role = "user", content = text,
            attachments = attachments.ifEmpty { null }
        )
        MainViewModelAgent.appendAgentMessage(vm, user)
        MainViewModelAgent.appendAgentMessage(vm, AgentChatMessage(
            id = turn.assistantId, role = "assistant",
            content = if (mode == AgentMode.DRAW) "✨深度求索中..." else "", isThinking = true
        ))
        vm.clearAgentAttachments()
        val job = vm.viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                val source = AgentGenerationSource { progress ->
                    if (mode == AgentMode.CHAT) {
                        generateChat(turn, settings, prior + user, progress)
                    } else {
                        MainViewModelAgent.expandPromptWithLlm(
                            vm, text, prior,
                            attachments.filter { it.isImage }.mapNotNull(MainViewModelAgent::imageDataUrl),
                            settings = settings, requestId = turn.id,
                            onProgress = { _, _, reasoning, content -> progress(AgentProgress(reasoning, content)) },
                            checkCurrent = { session.requireCurrent(turn) },
                            onTagChecking = { origin ->
                                withContext(Dispatchers.Main) {
                                    session.requireCurrent(turn)
                                    MainViewModelAgent.updateAgentMessage(vm, turn.assistantId, persist = false) {
                                        it.copy(tagCheckingOrigin = origin)
                                    }
                                }
                            },
                            onTagReport = { report ->
                                withContext(Dispatchers.Main) {
                                    session.requireCurrent(turn)
                                    MainViewModelAgent.updateAgentMessage(vm, turn.assistantId, persist = false) {
                                        it.copy(tagCheck = report, tagCheckingOrigin = null)
                                    }
                                    vm.addLog("Agent ${report.origin.title}: ${report.summary}；HTTP ${report.networkRequests}/3", LogType.INFO)
                                }
                            }
                        ).getOrThrow()
                    }
                }
                var lastUi = 0L
                val observer = object : AgentConversationObserver {
                    override suspend fun progress(progress: AgentProgress) {
                        val now = System.nanoTime()
                        if (lastUi != 0L && now - lastUi < 40_000_000L) return
                        lastUi = now
                        withContext(Dispatchers.Main) {
                            session.requireCurrent(turn)
                            val content = if (mode == AgentMode.CHAT) progress.content
                                else MainViewModelAgent.extractStreamingReply(progress.content).orEmpty()
                            MainViewModelAgent.updateAgentMessage(vm, turn.assistantId, persist = false) {
                                it.copy(
                                    content = content.ifBlank { if (mode == AgentMode.DRAW) "✨深度求索中..." else "" },
                                    thinkingContent = progress.reasoning.takeIf(String::isNotBlank),
                                    isThinking = content.isBlank()
                                )
                            }
                        }
                    }

                    override suspend fun reply(reply: AgentReply) {
                        withContext(Dispatchers.Main) {
                            session.requireCurrent(turn)
                            MainViewModelAgent.updateAgentMessage(vm, turn.assistantId, persist = true) {
                                it.copy(
                                    content = if (mode == AgentMode.CHAT) reply.tip else MainViewModelAgent.sanitizeAgentReply(reply.tip),
                                    expandedPrompt = reply.prompt.takeIf(String::isNotBlank),
                                    thinkingContent = reply.thinking.takeIf(String::isNotBlank),
                                    isThinking = false, errorMessage = null, tagCheckingOrigin = null
                                )
                            }
                        }
                    }
                }
                AgentConversationRunner(session).run(turn, mode, source, observer, WorkflowPort(turn, version, attachments))
                withContext(Dispatchers.Main) {
                    session.requireCurrent(turn)
                    session.complete(turn)
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (session.isCurrent(turn)) stop("已停止")
                }
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (session.isCurrent(turn)) {
                        val message = when (e) {
                            is AgentExecutionException -> e.message.orEmpty()
                            is LlmException -> e.toDisplayString()
                            else -> LlmErrorMapper.map(e, endpoint = LlmClient.resolveEndpoint(settings.baseUrl)).toDisplayString()
                        }
                        MainViewModelAgent.updateAgentMessage(vm, turn.assistantId, persist = true) {
                            val hasReply = it.content.isNotBlank() && it.content != "✨深度求索中..."
                            it.copy(
                                content = if (hasReply) it.content else message,
                                errorMessage = message, isThinking = false, tagCheckingOrigin = null
                            )
                        }
                        vm.addLog("Agent 本轮失败: ${if (e is LlmException) e.errorType else e.javaClass.simpleName}", LogType.ERROR)
                        session.fail(turn, if (e is LlmException) e.errorType.name else e.javaClass.simpleName)
                    }
                }
            }
        }
        session.attach(turn, job)
        job.invokeOnCompletion { cause ->
            // Covers a scope already cancelled before a LAZY coroutine can enter its body.
            if (cause is CancellationException && session.isCurrent(turn)) session.stop("会话已关闭")
        }
        job.start()
    }

    private suspend fun generateChat(
        turn: AgentTurn,
        settings: AgentLlmSettings,
        allMessages: List<AgentChatMessage>,
        progress: suspend (AgentProgress) -> Unit
    ): AgentReply {
        val history = allMessages.filter {
            (it.role == "user" || it.role == "assistant") && !it.isThinking && it.errorMessage == null
        }.takeLast(20)
        val messages = listOf(ChatMessageDto("system", settings.persona.trim())) + history.map {
            ChatMessageDto(it.role, if (it.role == "user") MainViewModelAgent.chatText(it) else it.content)
        }
        val images = mutableMapOf<Int, List<String>>()
        var budget = 8
        for (i in history.indices.reversed()) {
            if (budget <= 0) break
            val urls = history[i].attachments.orEmpty().filter { it.isImage }.take(budget)
                .mapNotNull(MainViewModelAgent::imageDataUrl)
            if (urls.isNotEmpty()) { images[i + 1] = urls; budget -= urls.size }
        }
        val output = vm.llmClient.chatCompletionsStream(
            settings.baseUrl, settings.apiKey, settings.model, messages, messageImages = images,
            requestId = turn.id, reasoningEffort = settings.effort,
            onProgress = { _, _, reasoning, content -> progress(AgentProgress(reasoning, content)) }
        ).getOrThrow()
        return AgentReply(output.content, thinking = output.reasoning.orEmpty())
    }

    private inner class WorkflowPort(
        private val turn: AgentTurn,
        private val version: Long,
        private val attachments: List<AgentAttachment>
    ) : AgentWorkflowPort {
        private var uploadedImage: Pair<EditableNode.ImageInput, String>? = null

        override suspend fun prepare() {
            session.requireCurrent(turn)
            if (!MainViewModelAgent.ensureAgentWorkflowLoaded(vm, silent = true, expectedVersion = version)) {
                session.requireCurrent(turn)
                throw AgentExecutionException("默认工作流未加载，请在设置里重新选择模板。")
            }
            session.requireCurrent(turn)
            val image = withContext(Dispatchers.Main) {
                session.requireCurrent(turn)
                vm._agentEditableNodes.value.filterIsInstance<EditableNode.ImageInput>().firstOrNull()
            }
            val attachment = attachments.firstOrNull { it.isImage }
            if (image != null && attachment != null) {
                uploadedImage = image to uploadImage(attachment)
            } else if (attachment != null) {
                vm.addLog("Agent 附图：工作流无 Image 节点，已忽略上传", LogType.WARNING)
            }
        }

        override suspend fun patchPrompt(prompt: String) = withContext(Dispatchers.Main) {
            session.requireCurrent(turn)
            val target = MainViewModelAgent.findPrimaryPositivePromptTarget(vm)
                ?: throw AgentExecutionException("模板中找不到提示词输入，请在工作流设置选择注入节点。")
            val snapshot = workflowSnapshot(target)
            tools.inspectWorkflow.execute(snapshot).getOrElse {
                throw AgentExecutionException("工作流检查失败：${it.message}")
            }
            val changes = mutableListOf(AgentFieldChange(target.first, target.second, prompt))
            uploadedImage?.let { (node, value) -> changes.add(AgentFieldChange(node.nodeId, node.key, value)) }
            val patch = tools.patchWorkflow.execute(AgentPatchRequest(snapshot, changes)).getOrElse {
                throw AgentExecutionException("工作流修改未通过校验：${it.message}")
            }
            session.requireCurrent(turn)
            vm.agentWorkflow = patch.workflow
            patch.diff.forEach { vm.agentWorkflowParamValues["${it.nodeId}:${it.key}"] = it.after }
            MainViewModelAgent.parseAgentEditableNodes(vm, patch.workflow)
            MainViewModelAgent.persistAgentWorkflowParams(vm)
            vm.addLog("Agent patch_workflow 已校验并修改 ${patch.diff.size} 个字段", LogType.INFO)
        }

        private fun workflowSnapshot(target: Pair<String, String>): AgentWorkflowSnapshot {
            val graph = vm.agentWorkflow ?: throw AgentExecutionException("工作流为空")
            val allowed = vm._agentAuxNodeKeys.value + "${target.first}:${target.second}" +
                listOfNotNull(uploadedImage?.first?.let { "${it.nodeId}:${it.key}" })
            val fields = vm._agentEditableNodes.value.mapNotNull { node ->
                val key: String
                val kind: AgentFieldKind
                var options: List<String>? = null
                var min: Double? = null
                var max: Double? = null
                when (node) {
                    is EditableNode.TextInput -> { key = node.key; kind = AgentFieldKind.TEXT; options = node.options }
                    is EditableNode.ImageInput -> { key = node.key; kind = AgentFieldKind.IMAGE }
                    is EditableNode.BooleanInput -> { key = node.key; kind = AgentFieldKind.BOOLEAN }
                    is EditableNode.NumberInput -> {
                        key = node.key
                        kind = if (node.value is Int || node.value is Long) AgentFieldKind.INTEGER else AgentFieldKind.DECIMAL
                        min = node.min; max = node.max
                    }
                }
                if ("${node.nodeId}:$key" !in allowed) null else AgentFieldSpec(node.nodeId, key, node.classType, kind, options, min, max)
            }.toMutableList()
            // Preserve automatic prompt targeting for API nodes not exposed by object_info.
            if (fields.none { it.nodeId == target.first && it.key == target.second }) {
                val node = graph[target.first] as? Map<*, *>
                val inputs = node?.get("inputs") as? Map<*, *>
                if (inputs?.get(target.second) is String && node["class_type"] is String) {
                    fields.add(AgentFieldSpec(target.first, target.second, node["class_type"] as String, AgentFieldKind.TEXT))
                }
            }
            return AgentWorkflowSnapshot(graph, fields)
        }

        override suspend fun promptApplied(prompt: String) = withContext(Dispatchers.Main) {
            session.requireCurrent(turn)
            vm._agentLastExpandedPrompt.value = prompt
            vm.prefs.edit().putString(MainViewModelAgent.scopedPref("agent_last_prompt", vm), prompt).apply()
        }

        override suspend fun submit(): String = withContext(Dispatchers.Main) {
            session.requireCurrent(turn)
            vm.enqueueCurrentWorkflowAwait(1).getOrElse {
                throw AgentExecutionException("提交出图失败：${it.message ?: it.javaClass.simpleName}")
            }
        }

        private suspend fun uploadImage(attachment: AgentAttachment): String {
            session.requireCurrent(turn)
            val context = vm.getApplication<Application>()
            val uri = Uri.parse(attachment.uri)
            val mime = context.contentResolver.getType(uri)
            val name = UploadPreviewStore.uploadFileName(vm.getFileName(uri), mime)
            val dir = File(context.cacheDir, "agent_upload_${turn.id}").apply { mkdirs() }
            val file = File(dir, name)
            try {
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw AgentExecutionException("附图读取失败，未出图。")
                input.use { source -> file.outputStream().use { source.copyTo(it) } }
                currentCoroutineContext().ensureActive()
                session.requireCurrent(turn)
                val value = if (vm.currentServerProfile()?.isRunningHub == true) {
                    val backend = vm.currentRunningHubBackend() ?: throw AgentExecutionException("云端服务器已断开")
                    backend.client.uploadInput(file, mime, name).getOrThrow().also { uploaded ->
                        withContext(Dispatchers.Main) {
                            session.requireCurrent(turn)
                            MainViewModelRhWorkflows.recordUploadSite(vm, uploaded, backend.site.id)
                            vm.uploadPreviewStore.put(uploaded, file)
                        }
                    }
                } else {
                    val result = vm.repository.uploadImage(file) ?: throw AgentExecutionException("附图上传失败，未出图。")
                    if (result.subfolder.isNullOrBlank()) result.name else "${result.subfolder}/${result.name}"
                }
                session.requireCurrent(turn)
                return value
            } finally {
                dir.deleteRecursively()
            }
        }
    }
}
