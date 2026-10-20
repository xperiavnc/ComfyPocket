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
import com.comfyui.mobile.model.AgentClarificationAnswer
import com.comfyui.mobile.model.AgentQuestionAnswer
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
    val tagSettings: AgentPromptSettings = AgentPromptSettings(),
    val generationLimits: AgentGenerationLimits = AgentGenerationLimits()
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
            vm._agentPromptSettings.value,
            vm._llmProfiles.value.firstOrNull { it.id == vm._currentLlmProfileId.value }
                ?.generationLimits ?: AgentGenerationLimits()
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
                errorMessage = if (submitting) {
                    "$reason；服务端已接收的任务可能继续运行"
                } else reason
            )
        }
    }

    fun answer(messageId: String, answers: List<AgentQuestionAnswer>, mode: AgentMode): Boolean {
        val pending = AgentQuestionPolicy.pending(vm._agentMessages.value) ?: return false
        if (pending.id != messageId || session.busy.value) return false
        val questions = pending.clarification?.questions.orEmpty()
        val normalized = AgentQuestionPolicy.validate(questions, answers) ?: return false
        return send(AgentQuestionPolicy.answerText(questions, normalized), mode,
            AgentClarificationAnswer(messageId, normalized))
    }

    fun send(userText: String, mode: AgentMode, answer: AgentClarificationAnswer? = null): Boolean {
        if (!vm.agentWorkspace.requireWritable()) return false
        vm.agentWorkspace.referenceWarning()?.let { vm.showToast(it); return false }
        val text = userText.trim()
        val pickedAttachments = vm._agentPendingAttachments.value.toList()
        if (text.isEmpty() && pickedAttachments.isEmpty()) return false
        val prior = vm._agentMessages.value.toList()
        val pending = AgentQuestionPolicy.pending(prior)
        val continuing = AgentQuestionPolicy.continuation(text, prior)
        val answerLookupText = answer?.let {
            AgentQuestionPolicy.answerValues(pending?.clarification?.questions.orEmpty(), it.answers)
        }
        val inherited = continuing?.attachments.orEmpty().ifEmpty {
            if (continuing != null) prior.dropLast(1).lastOrNull { it.role == "user" }?.attachments.orEmpty()
            else emptyList()
        }
        val attachments = (pickedAttachments + inherited).distinctBy { it.id }.let { all ->
            var imageCount = 0
            all.filter { !it.isImage || ++imageCount <= 4 }
        }
        val cancelled = pending != null && AgentQuestionPolicy.isCancellation(text)
        val version = session.contextVersion()
        val turn = session.begin("${MainViewModelAgent.scope(vm)}:${vm.agentWorkspace.conversationId}:$version") ?: return false
        val settings = AgentLlmSettings.capture(vm)
        val user = AgentChatMessage(
            id = "u-${UUID.randomUUID()}", role = "user", content = text,
            attachments = pickedAttachments.ifEmpty { null }, clarificationAnswer = answer
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
                    if (cancelled) {
                        AgentReply("已取消本次提问，未修改提示词或提交出图。", action = AgentAction.RESPOND)
                    } else if (mode == AgentMode.CHAT) {
                        generateChat(turn, settings, prior + user, progress)
                    } else {
                        val workflowContext = AgentWorkflowPromptContext.capture(vm, version)
                        session.requireCurrent(turn)
                        MainViewModelAgent.expandPromptWithLlm(
                            vm, text, prior,
                            attachments.filter { it.isImage }.mapNotNull(MainViewModelAgent::imageDataUrl),
                             answerLookupText = answerLookupText,
                            settings = settings, requestId = turn.id,
                            currentNegativePrompt = workflowContext.negativePrompt,
                            workflowModelFacts = workflowContext.modelFacts,
                            onProgress = { _, _, reasoning, content -> progress(AgentProgress(reasoning, content)) },
                            checkCurrent = { session.requireCurrent(turn) },
                            onLocalTagReport = { report ->
                                withContext(Dispatchers.Main) {
                                    session.requireCurrent(turn)
                                    MainViewModelAgent.updateAgentMessage(vm, turn.assistantId, persist = false) {
                                        it.copy(localTagReport = report)
                                    }
                                    vm.addLog("Agent ${report.summary}", LogType.INFO)
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
                            val clarification = reply.clarification?.let {
                                AgentQuestionPolicy.bind(it, text, prior, answerLookupText).let { bound ->
                                    if (continuing == null && bound.sourceRequest == text)
                                        bound.copy(sourceRequest = MainViewModelAgent.chatText(user)) else bound
                                }
                            }
                            MainViewModelAgent.updateAgentMessage(vm, turn.assistantId, persist = true) {
                                it.copy(
                                    content = if (mode == AgentMode.CHAT) reply.tip else MainViewModelAgent.sanitizeAgentReply(reply.tip),
                                    expandedPrompt = reply.prompt.takeIf(String::isNotBlank),
                                    expandedNegativePrompt = reply.negativePrompt,
                                    thinkingContent = reply.thinking.takeIf(String::isNotBlank),
                                    characterSelections = reply.characterSelections,
                                    characterStatus = reply.characterStatus,
                                    clarification = clarification,
                                    // Also use the ordinary attachment store, so Room/export can relocate these URIs.
                                    attachments = if (clarification != null) attachments.ifEmpty { null } else null,
                                    isThinking = false, errorMessage = null
                                )
                            }
                        }
                    }
                }
                val result = AgentConversationRunner(session).run(turn, mode, source, observer, WorkflowPort(turn, version, attachments))
                withContext(Dispatchers.Main) {
                    session.requireCurrent(turn)
                    if (result.reply.clarification != null) session.waitForUser(turn) else session.complete(turn)
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
                                errorMessage = message, isThinking = false
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
        return true
    }

    private suspend fun generateChat(
        turn: AgentTurn,
        settings: AgentLlmSettings,
        allMessages: List<AgentChatMessage>,
        progress: suspend (AgentProgress) -> Unit
    ): AgentReply {
        val history = allMessages.filter {
            (it.role == "user" || it.role == "assistant") && !it.isThinking && it.errorMessage == null
        }.takeLast(201)
        val imagesById = mutableMapOf<String, List<String>>()
        var budget = 8
        for (i in history.indices.reversed()) {
            if (budget <= 0) break
            if (history[i].role != "user") continue
            val urls = history[i].attachments.orEmpty().filter { it.isImage }.take(budget)
                .mapNotNull(MainViewModelAgent::imageDataUrl)
            if (urls.isNotEmpty()) { imagesById[history[i].id] = urls; budget -= urls.size }
        }
        fun context(message: AgentChatMessage): AgentContextMessage {
            val content = when {
                message.role == "user" && message.id == history.last().id ->
                    AgentQuestionPolicy.requestText(MainViewModelAgent.chatText(message), history.dropLast(1))
                message.role == "user" -> MainViewModelAgent.chatText(message)
                message.clarification != null -> vm.gson.toJson(mapOf(
                    "action" to "ask_user", "reply" to message.content,
                    "questions" to message.clarification.questions,
                    "clarification_question" to message.clarification.question,
                    "clarification_options" to message.clarification.options))
                else -> message.content
            }
            return AgentContextMessage(ChatMessageDto(message.role, content), imagesById[message.id].orEmpty())
        }
        // A resumed chat still receives the original reference images after a restart/history trim.
        AgentQuestionPolicy.continuation(history.last().content, history.dropLast(1))?.attachments.orEmpty()
            .filter { it.isImage }.take(4).mapNotNull(MainViewModelAgent::imageDataUrl).takeIf { it.isNotEmpty() }
            ?.let { original ->
                imagesById[history.last().id] = (imagesById[history.last().id].orEmpty() + original).distinct().take(4)
            }
        val selected = AgentContextBudget.fit(
            AgentContextMessage(ChatMessageDto("system", AgentPromptPolicy.chat(settings.persona))),
            history.dropLast(1).map(::context), listOf(context(history.last())),
            settings.generationLimits, defaultHistoryMessages = 19)
        vm.addLog("Agent 上下文：保守估算 ${selected.estimatedInputTokens} tokens，裁剪 ${selected.removedMessages} 条历史", LogType.INFO)
        val messages = selected.messages.map { it.message }
        val images = selected.messages.mapIndexedNotNull { index, item ->
            item.images.takeIf { it.isNotEmpty() }?.let { index to it }
        }.toMap()
        val output = vm.llmClient.chatCompletionsStream(
            settings.baseUrl, settings.apiKey, settings.model, messages, messageImages = images,
            requestId = turn.id, reasoningEffort = settings.effort,
            generationLimits = settings.generationLimits,
            onProgress = { _, _, reasoning, content -> progress(AgentProgress(reasoning, content)) }
        ).getOrThrow()
        val parsed = com.comfyui.mobile.data.AgentReplyParser.parse(output.content, preserveNaturalLanguage = true)
        return if (parsed.clarification != null) {
            AgentReply(parsed.reply.ifBlank { parsed.clarification.question }, thinking = output.reasoning.orEmpty(),
                clarification = parsed.clarification, action = AgentAction.ASK_USER)
        } else AgentReply(
            if (parsed.action == AgentAction.RESPOND) parsed.reply else output.content,
            thinking = output.reasoning.orEmpty(), action = AgentAction.RESPOND)
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

        override suspend fun patchPrompt(prompt: String, negativePrompt: String?) = withContext(Dispatchers.Main) {
            session.requireCurrent(turn)
            val (target, configuredNegative) = try {
                val positive = MainViewModelAgent.findPrimaryPositivePromptTarget(vm)
                    ?: throw AgentExecutionException("模板中找不到提示词输入，请在工作流设置选择注入节点。")
                // Validate the binding even when an old model response omits negative_prompt.
                positive to MainViewModelAgent.negativePromptTargets(vm, positive)
            } catch (e: IllegalArgumentException) {
                throw AgentExecutionException(e.message ?: "提示词绑定已失效，请重新设置")
            }
            val negativeTargets = if (negativePrompt != null) configuredNegative else emptyList()
            if (negativeTargets.isNotEmpty()) {
                val conflicts = AgentNegativePromptPolicy.conflicts(prompt, negativePrompt)
                if (conflicts.isNotEmpty()) {
                    throw AgentExecutionException(
                        "正反向提示词包含相同标签（${conflicts.take(5).joinToString("、")}），" +
                            "请调整后重试；本轮未改写提示词或提交出图。"
                    )
                }
            }
            val snapshot = workflowSnapshot(listOf(target) + negativeTargets)
            tools.inspectWorkflow.execute(snapshot).getOrElse {
                throw AgentExecutionException("工作流检查失败：${it.message}")
            }
            val changes = mutableListOf(AgentFieldChange(target.first, target.second, prompt))
            negativeTargets.forEach { (id, key) -> changes.add(AgentFieldChange(id, key, negativePrompt!!)) }
            uploadedImage?.let { (node, value) -> changes.add(AgentFieldChange(node.nodeId, node.key, value)) }
            val patch = tools.patchWorkflow.execute(AgentPatchRequest(snapshot, changes)).getOrElse {
                throw AgentExecutionException("工作流修改未通过校验：${it.message}")
            }
            session.requireCurrent(turn)
            vm.agentWorkflow = patch.workflow
            patch.diff.forEach { vm.agentWorkflowParamValues["${it.nodeId}:${it.key}"] = it.after }
            MainViewModelAgent.parseAgentEditableNodes(vm, patch.workflow)
            MainViewModelAgent.persistAgentWorkflowParams(vm)
            val negativeStatus = when {
                vm._agentNegativeBinding.value.mode == PromptBindingMode.KEEP ->
                    "按设置保留工作流反向原值；生成的反向仅供查看"
                negativePrompt == null -> "模型未返回反向提示词，已保留工作流原值"
                negativeTargets.isEmpty() -> "未写入：工作流没有可识别的独立反向输入，已保留原配置"
                negativePrompt.isEmpty() -> "已清空工作流反向输入（${negativeTargets.size} 项）"
                else -> "已写入工作流反向输入（${negativeTargets.size} 项）"
            }
            MainViewModelAgent.updateAgentMessage(vm, turn.assistantId, persist = true) {
                it.copy(negativePromptStatus = negativeStatus)
            }
            val effectiveTargets = if (vm._agentNegativeBinding.value.mode == PromptBindingMode.KEEP) {
                AgentPromptTargets.negative(patch.workflow, target)
            } else configuredNegative
            MainViewModelAgent.savePromptOutput(vm, AgentPromptOutput(
                prompt, negativePrompt ?: AgentPromptBindings.text(patch.workflow, effectiveTargets), negativeStatus))
            vm.addLog("Agent 反向提示词：$negativeStatus", LogType.INFO)
            vm.addLog("Agent patch_workflow 已校验并修改 ${patch.diff.size} 个字段", LogType.INFO)
        }

        private fun workflowSnapshot(targets: List<Pair<String, String>>): AgentWorkflowSnapshot {
            val graph = vm.agentWorkflow ?: throw AgentExecutionException("工作流为空")
            val allowed = vm._agentAuxNodeKeys.value + targets.map { "${it.first}:${it.second}" } +
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
            for (target in targets) {
                if (fields.none { it.nodeId == target.first && it.key == target.second }) {
                    val node = graph[target.first] as? Map<*, *>
                    val inputs = node?.get("inputs") as? Map<*, *>
                    if (inputs?.get(target.second) is String && node["class_type"] is String) {
                        fields.add(AgentFieldSpec(target.first, target.second, node["class_type"] as String, AgentFieldKind.TEXT))
                    }
                }
            }
            return AgentWorkflowSnapshot(graph, fields)
        }

        override suspend fun promptApplied(prompt: String) = withContext(Dispatchers.Main) {
            session.requireCurrent(turn)
            // patchPrompt saved both fields atomically; never overwrite that pair with positive only.
            check(vm._agentPromptOutput.value.positive == prompt)
        }

        override suspend fun submit(): String = withContext(Dispatchers.Main) {
            session.requireCurrent(turn)
            vm.agentWorkspace.flush()
            session.requireCurrent(turn)
            vm.enqueueCurrentWorkflowAwait(1, turn.id).getOrElse {
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
