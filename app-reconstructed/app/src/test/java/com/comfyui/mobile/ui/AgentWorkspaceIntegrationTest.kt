package com.comfyui.mobile.ui

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import com.comfyui.mobile.agent.workspace.*
import com.comfyui.mobile.model.*
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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

/** Real ViewModel + generated Room/SQLite + files + .cui.bak; only remote services are loopback fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class AgentWorkspaceIntegrationTest {
    private lateinit var app: Application
    private lateinit var server: MockWebServer
    private lateinit var vm: MainViewModel
    private lateinit var holder: ViewModelStore
    private val gson = Gson()

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("comfy_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(
                if (request.path?.startsWith("/queue") == true) """{"queue_running":[],"queue_pending":[]}""" else "{}")
        }
        server.start()
        start()
    }

    private fun start() {
        vm = MainViewModel(app)
        holder = ViewModelStore().apply { put("vm", vm) }
        pump { vm.agentWorkspace.ready }
        vm._servers.value = emptyList()
        vm._currentServerId.value = ""
        vm.repository.setBaseUrlOnly(server.url("/").toString())
        vm._savedIp.value = "127.0.0.1"
        vm._savedPort.value = server.port.toString()
        vm._savedScheme.value = "http"
    }

    private fun stop() {
        holder.clear()
        runBlocking { vm.agentWorkspace.awaitClosed() }
        vm.repository.disconnect()
    }

    @After fun close() {
        stop()
        server.shutdown()
    }

    private fun pump(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        do {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            if (condition()) return
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        fail("workspace timed out: ${vm.agentStorageStatus.value}")
    }

    private fun <T> io(block: suspend () -> T): T {
        val job = CoroutineScope(SupervisorJob() + Dispatchers.IO).async { block() }
        pump { job.isCompleted }
        return runBlocking { job.await() }
    }

    private fun flush() { io { vm.agentWorkspace.flush() } }

    private fun <T> database(block: suspend (AgentWorkspaceRepository, AgentWorkspaceDao) -> T): T = io {
        val db = AgentWorkspaceDatabase.open(app)
        try { block(AgentWorkspaceRepository(db, File(app.filesDir, "agent_workspace"),
            File(app.filesDir, "agent_attachments")), db.workspace()) } finally { db.close() }
    }

    private fun message(id: String, content: String) =
        MainViewModelAgent.appendAgentMessage(vm, AgentChatMessage(id, "user", content))

    private fun fresh(title: String): String {
        val previous = vm.agentWorkspace.conversationId
        vm.newAgentConversation(title)
        pump { vm.agentStorageStatus.value.mode == AgentStorageMode.ROOM &&
            vm.agentWorkspace.conversationId != previous }
        return vm.agentWorkspace.conversationId!!
    }

    private fun open(id: String) {
        vm.openAgentConversation(id)
        pump { vm.agentStorageStatus.value.mode == AgentStorageMode.ROOM && vm.agentWorkspace.conversationId == id }
    }

    private fun delete(id: String) {
        vm.requestDeleteAgentConversation(id)
        pump { vm.agentConversationDeleteInfo.value?.conversation?.id == id }
        vm.deleteAgentConversation(id)
        pump { vm.agentStorageStatus.value.mode == AgentStorageMode.ROOM && vm.agentConversationDeleteInfo.value == null }
        flush()
    }

    @Test fun newEmptySessionsStayTransientAndPersistOnlyAfterAMeaningfulEdit() {
        val first = fresh("空白一")
        val second = fresh("空白二")
        assertNotEquals(first, second)
        vm.updateAgentDraft(AgentInputDraft(scrollMessageId = null))
        flush()
        assertTrue(database { _, dao -> dao.allConversations().isEmpty() })
        vm.updateAgentDraft(AgentInputDraft("保留草稿"))
        flush()
        assertEquals(second, database { _, dao -> dao.allConversations().single().id })
        stop()
        start()
        assertEquals(second, vm.activeAgentConversation.value!!.id)
        assertEquals("保留草稿", vm.agentInputDraft.value.text)
    }

    @Test fun switchingRestoresIndependentDraftsParametersPromptsModesAndDoesNotTouchActivityTime() {
        val a = vm.agentWorkspace.conversationId!!
        message("a", "A 内容")
        vm._agentWorkflowSource.value = "saved"
        vm._agentWorkflowId.value = "same.json"
        vm.agentWorkflowParamValues["9:width"] = 768
        vm._agentPromptOutput.value = com.comfyui.mobile.agent.AgentPromptOutput("positive A", "negative A")
        vm.setAgentInteractionMode("chat")
        vm.updateAgentDraft(AgentInputDraft("A 草稿", 1, 3, "a", 7))
        flush()
        val before = database { repo, _ -> repo.conversation(a)!!.updatedAt }
        val b = fresh("会话 B")
        assertTrue(vm.agentMessages.value.isEmpty())
        assertTrue(vm.agentWorkflowParamValues.isEmpty())
        assertEquals("", vm.agentPromptOutput.value.positive)
        assertEquals("", vm.agentInputDraft.value.text)
        message("b", "B 内容")
        vm._agentWorkflowSource.value = "saved"
        vm._agentWorkflowId.value = "same.json"
        vm.agentWorkflowParamValues["9:width"] = 1024
        vm._agentPromptOutput.value = com.comfyui.mobile.agent.AgentPromptOutput("positive B", "negative B")
        vm.agentWorkspace.saveConfiguration()
        vm.updateAgentDraft(AgentInputDraft("B 草稿"))
        flush()
        open(a)
        assertEquals(listOf("A 内容"), vm.agentMessages.value.map { it.content })
        assertEquals("A 草稿", vm.agentInputDraft.value.text)
        assertEquals("chat", vm.agentInteractionMode.value)
        assertEquals("negative A", vm.agentPromptOutput.value.negative)
        assertEquals(768, (vm.agentWorkflowParamValues["9:width"] as Number).toInt())
        flush()
        assertEquals(before, database { repo, _ -> repo.conversation(a)!!.updatedAt })
        open(b)
        assertEquals("B 草稿", vm.agentInputDraft.value.text)
        assertEquals(1024, (vm.agentWorkflowParamValues["9:width"] as Number).toInt())
        assertEquals("negative B", vm.agentPromptOutput.value.negative)
        stop()
        start()
        assertEquals(b, vm.agentWorkspace.conversationId)
    }

    @Test fun llmProfileSelectionStaysWithItsSessionAndNewSessionUsesTheGlobalDefault() {
        val default = LlmProfile(id = "default", name = "默认配置",
            baseUrl = server.url("/default").toString(), model = "default-model")
        val custom = LlmProfile(id = "custom", name = "会话专用",
            baseUrl = server.url("/custom").toString(), model = "custom-model")
        vm.persistLlmProfiles(listOf(default, custom))
        vm._llmProfiles.value = listOf(default, custom)
        vm.prefs.edit().putString("current_llm_profile_id", default.id).commit()
        vm.agentWorkspace.reload()
        pump { vm.agentStorageStatus.value.mode == AgentStorageMode.ROOM }
        val a = vm.agentWorkspace.conversationId!!
        assertEquals(default.id, vm.activeAgentConversation.value!!.llmProfileId)
        vm.selectLlmProfile(custom.id)
        flush()
        val b = fresh("使用默认 AI 的会话")
        assertEquals(default.id, vm.currentLlmProfileId.value)
        assertEquals(default.model, vm.llmModel.value)
        message("b", "保留默认配置")
        flush()
        open(a)
        assertEquals(custom.id, vm.currentLlmProfileId.value)
        assertEquals(custom.model, vm.llmModel.value)
        open(b)
        assertEquals(default.id, vm.currentLlmProfileId.value)
        assertEquals(default.id, vm.prefs.getString("current_llm_profile_id", null))
        stop()
        start()
        assertEquals(b, vm.agentWorkspace.conversationId)
        assertEquals(default.id, vm.currentLlmProfileId.value)
        open(a)
        assertEquals(custom.id, vm.currentLlmProfileId.value)
    }

    @Test fun renameUpdatesObservedIndexButOpeningHistoryDoesNotReorderIt() {
        val a = vm.agentWorkspace.conversationId!!
        message("a", "first")
        flush()
        val b = fresh("second")
        message("b", "second")
        flush()
        pump { vm.agentConversations.value.size == 2 }
        val order = vm.agentConversations.value.map { it.id }
        open(a)
        flush()
        assertEquals(order, vm.agentConversations.value.map { it.id })
        vm.renameAgentConversation(a, "新名字")
        flush()
        pump { vm.agentConversations.value.firstOrNull()?.title == "新名字" }
        assertEquals("新名字", vm.activeAgentConversation.value!!.title)
        assertEquals(a, vm.agentConversations.value.first().id)
        assertEquals(b, vm.agentConversations.value.last().id)
    }

    @Test fun latePickerFromTransientOriginIsSavedToOriginAndDoesNotChangeActivePointer() {
        val a = vm.agentWorkspace.conversationId!!
        val owner = vm.captureAgentAttachmentOwner()!!
        val b = fresh("会话 B")
        vm.updateAgentDraft(AgentInputDraft("B 草稿"))
        flush()
        val source = File(vm.agentWorkspace.importsDirectory.apply { mkdirs() }, "late-A.txt").apply { writeText("A asset") }
        io { vm.agentWorkspace.addAttachments(owner,
            listOf(AgentAttachment("late-A", "file", source.toURI().toString(), source.name, 7))) }
        flush()
        assertTrue(vm.agentPendingAttachments.value.isEmpty())
        assertTrue(database { repo, _ -> repo.state(a)!!.draftJson.contains("late-A") })
        stop()
        start()
        assertEquals(b, vm.agentWorkspace.conversationId)
        open(a)
        pump { vm.agentPendingAttachments.value.singleOrNull()?.uri?.startsWith("file:") == true }
        assertEquals("A asset", File(java.net.URI(vm.agentPendingAttachments.value.single().uri)).readText())
    }

    @Test fun lateMediaStaysInOriginAndDeletingOriginDetachesWithoutRebuildingIt() {
        message("a", "A")
        val a = vm.agentWorkspace.conversationId!!
        val target = vm.agentWorkspace.currentTarget()
        val link = io { vm.agentWorkspace.reserve("turn-a", target, "{}")!! }
        io { vm.agentWorkspace.confirm(link, "a-remote") }
        val b = fresh("B")
        message("b", "B")
        flush()
        val media = MediaOutput("A.png", "", "output", MediaType.IMAGE, promptId = "a-remote",
            workspaceEpoch = vm.agentWorkspace.callbackEpoch)
        assertTrue(vm.agentWorkspace.record(media, target.key))
        flush()
        assertTrue(vm.agentLatestGenerationList.value.isEmpty())
        open(a)
        assertEquals("A.png", vm.agentLatestGenerationList.value.single().filename)
        delete(a)
        assertEquals(b, vm.agentWorkspace.conversationId)
        vm.agentWorkspace.record(media, target.key)
        flush()
        assertTrue(vm.agentLatestGenerationList.value.isEmpty())
        assertNull(database { repo, _ -> repo.conversation(a) })
        val detached = database { _, dao -> dao.execution(link.id)!! }
        assertNull(detached.conversationId)
        assertNotNull(detached.detachedAt)
        assertEquals("a-remote", detached.remoteId)
        assertTrue(vm.taskStore.tasks.value.any { it.agentExecutionId == link.id && it.agentConversationId == null })
    }

    @Test fun deletingLastSessionCreatesFreshTransientIdentityAndKeepsOtherScopes() {
        message("local", "local")
        val a = vm.agentWorkspace.conversationId!!
        val cloud = database { repo, _ -> repo.createConversation("cloud@ai", "cloud").id }
        delete(a)
        val empty = vm.agentWorkspace.conversationId!!
        assertNotEquals(a, empty)
        assertTrue(vm.agentMessages.value.isEmpty())
        assertEquals("", vm.agentInputDraft.value.text)
        flush()
        assertNull(database { repo, _ -> repo.conversation(empty) })
        assertNotNull(database { repo, _ -> repo.conversation(cloud) })
    }

    @Test fun switchingRestoresScrollAnchorBeyondRecentPageWithoutLosingOlderHistory() {
        val a = vm.agentWorkspace.conversationId!!
        repeat(435) { message("m-$it", "message $it") }
        vm.updateAgentDraft(AgentInputDraft("draft", scrollMessageId = "m-10", scrollOffset = 26))
        flush()
        fresh("B")
        open(a)
        assertTrue(vm.agentMessages.value.any { it.id == "m-10" })
        assertEquals("m-10", vm.agentInputDraft.value.scrollMessageId)
        assertEquals(26, vm.agentInputDraft.value.scrollOffset)
        assertEquals("m-434", vm.agentMessages.value.last().id)
    }

    @Test fun switchingInvalidatesInFlightIdentityAndPersistsThePartialReplyInItsOrigin() {
        val a = vm.agentWorkspace.conversationId!!
        val turn = vm.agentSession.begin("local:$a")!!
        MainViewModelAgent.appendAgentMessage(vm, AgentChatMessage(turn.assistantId, "assistant", "partial", isThinking = true))
        val oldVersion = vm.agentSession.contextVersion()
        fresh("B")
        assertFalse(vm.agentBusy.value)
        assertFalse(vm.agentSession.isCurrent(turn))
        assertTrue(vm.agentSession.contextVersion() > oldVersion)
        assertTrue(vm.agentMessages.value.isEmpty())
        open(a)
        assertEquals("partial", vm.agentMessages.value.single().content)
        assertFalse(vm.agentMessages.value.single().isThinking)
        assertEquals("已切换会话", vm.agentMessages.value.single().errorMessage)
    }

    @Test fun queuedOldTemplateReadCannotOverwriteTheNewConversation() {
        message("a", "A")
        val a = vm.agentWorkspace.conversationId!!
        val version = vm.agentSession.contextVersion()
        fresh("B")
        assertFalse(io { MainViewModelAgent.ensureAgentWorkflowLoaded(vm, expectedVersion = version) })
        assertNull(vm.agentWorkflow)
        assertTrue(vm.agentMessages.value.isEmpty())
        assertNotEquals(a, vm.agentWorkspace.conversationId)
    }

    @Test fun modelSelectorOwnerExpiresAcrossSessionSwitchOrTemplateChange() {
        message("a", "A")
        val a = vm.agentWorkspace.conversationId!!
        val owner = vm.captureAgentConfigurationOwner()
        assertTrue(vm.isCurrentAgentConfiguration(owner))
        fresh("B")
        assertFalse(vm.isCurrentAgentConfiguration(owner))
        open(a)
        assertFalse(vm.isCurrentAgentConfiguration(owner))
        val next = vm.captureAgentConfigurationOwner()
        assertTrue(vm.isCurrentAgentConfiguration(next))
        vm._agentWorkflowId.value = "different-template"
        assertFalse(vm.isCurrentAgentConfiguration(next))
    }

    @Test fun pickerCapturedBeforeFirstSaveDoesNotRecreateDeletedOrigin() {
        val owner = vm.captureAgentAttachmentOwner()!!
        assertTrue(owner.mayCreate)
        message("a", "persist A")
        flush()
        delete(owner.conversationId!!)
        val source = File(vm.agentWorkspace.importsDirectory.apply { mkdirs() }, "deleted-A.txt").apply { writeText("late") }
        io { vm.agentWorkspace.addAttachments(owner,
            listOf(AgentAttachment("late", "file", source.toURI().toString(), source.name, 4))) }
        flush()
        assertNull(database { repo, _ -> repo.conversation(owner.conversationId) })
        assertFalse(source.exists())
        assertTrue(vm.agentPendingAttachments.value.isEmpty())
    }

    @Test fun saveFailureQueuedJustBeforeNewSessionCannotBeHiddenByTheLoad() {
        message("a", "saved A")
        flush()
        val origin = vm.agentWorkspace.conversationId!!
        database { _, dao ->
            // The separate open helper shares the same on-disk database.
            val db = AgentWorkspaceDatabase.open(app)
            try { db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_p2 BEFORE INSERT ON agent_messages WHEN NEW.id = 'fail-p2' BEGIN SELECT RAISE(ABORT, 'fixture'); END")
            } finally { db.close() }
        }
        message("fail-p2", "unsaved A")
        vm.newAgentConversation("B")
        pump { vm.agentStorageStatus.value.mode == AgentStorageMode.BLOCKED }
        database { _, _ ->
            val db = AgentWorkspaceDatabase.open(app)
            try { db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_p2") } finally { db.close() }
        }
        vm.retryAgentStorage()
        pump { vm.agentStorageStatus.value.mode == AgentStorageMode.ROOM }
        assertEquals(origin, vm.agentWorkspace.conversationId)
        assertEquals(listOf("saved A", "unsaved A"), vm.agentMessages.value.map { it.content })
    }

    @Test fun reviewDeletingTheGlobalDefaultDoesNotBreakEveryNewSession() {
        val default = vm.llmProfiles.value.first()
        val replacement = default.copy(id = "replacement", name = "剩余配置")
        vm.addOrUpdateLlmProfile(replacement, selectAfterSave = false)
        vm.prefs.edit().putString("current_llm_profile_id", default.id).commit()
        vm.deleteLlmProfile(default.id)
        flush()
        fresh("新任务")
        assertNull(vm.agentReferenceWarning())
        assertEquals(replacement.id, vm.currentLlmProfileId.value)
        assertEquals(replacement.id, vm.activeAgentConversation.value!!.llmProfileId)
    }

    @Test fun reviewDeletingAnotherSessionKeepsTheActiveConfigurationAndRequestWritable() {
        message("a", "A")
        flush()
        val other = database { repo, _ -> repo.createConversation("local", "B").id }
        val owner = vm.captureAgentConfigurationOwner()
        val turn = vm.agentSession.begin("active-a")!!
        vm.requestDeleteAgentConversation(other)
        pump { vm.agentConversationDeleteInfo.value != null }
        vm.deleteAgentConversation(other)
        assertEquals(AgentStorageMode.ROOM, vm.agentStorageStatus.value.mode)
        flush()
        assertTrue(vm.isCurrentAgentConfiguration(owner))
        assertTrue(vm.agentSession.isCurrent(turn))
        assertNull(database { repo, _ -> repo.conversation(other) })
        vm.stopAgentGeneration()
    }

    @Test fun reviewResetChatRejectsAnOlderPickerEvenAfterReturningToTheSession() {
        message("a", "A")
        flush()
        val a = vm.agentWorkspace.conversationId!!
        val old = vm.captureAgentAttachmentOwner()!!
        vm.clearAgentChat()
        flush()
        fresh("B")
        vm.updateAgentDraft(AgentInputDraft("B 草稿"))
        flush()
        open(a)
        val source = File(vm.agentWorkspace.importsDirectory.apply { mkdirs() }, "before-reset.txt").apply {
            writeText("discard this old selection")
        }
        io { vm.agentWorkspace.addAttachments(old,
            listOf(AgentAttachment("old-picker", "file", source.toURI().toString(), source.name, source.length()))) }
        flush()
        assertTrue(vm.agentPendingAttachments.value.isEmpty())
        assertFalse(source.exists())
        stop()
        start()
        assertEquals("", vm.agentInputDraft.value.text)
        assertEquals("", vm.agentPromptOutput.value.positive)
        val afterRestart = File(vm.agentWorkspace.importsDirectory.apply { mkdirs() }, "old-after-restart.txt")
            .apply { writeText("stale callback") }
        io { vm.agentWorkspace.addAttachments(old, listOf(AgentAttachment(
            "stale-restored", "file", afterRestart.toURI().toString(), afterRestart.name))) }
        assertTrue(vm.agentPendingAttachments.value.isEmpty())
        assertFalse(afterRestart.exists())
        val current = vm.captureAgentAttachmentOwner()!!
        val valid = File(vm.agentWorkspace.importsDirectory, "new-after-reset.txt").apply { writeText("new selection") }
        io { vm.agentWorkspace.addAttachments(current, listOf(AgentAttachment(
            "fresh", "file", valid.toURI().toString(), valid.name))) }
        assertEquals(listOf("fresh"), vm.agentPendingAttachments.value.map { it.id })
    }

    private fun rewritePackage(file: File, transform: (String, ByteArray) -> ByteArray): ByteArray {
        val entries = linkedMapOf<String, ByteArray>()
        java.util.zip.GZIPInputStream(file.readBytes().drop(11).toByteArray().inputStream()).use { gzip ->
            java.util.zip.ZipInputStream(gzip).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) entries[entry.name] = transform(entry.name, zip.readBytes())
                    entry = zip.nextEntry
                }
            }
        }
        val bytes = java.io.ByteArrayOutputStream()
        bytes.write("COMFYUIBAK".toByteArray())
        bytes.write(1)
        java.util.zip.GZIPOutputStream(bytes).use { gzip ->
            java.util.zip.ZipOutputStream(gzip).use { zip ->
                entries.forEach { (name, content) ->
                    zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(content); zip.closeEntry()
                }
            }
        }
        return bytes.toByteArray()
    }

    @Test fun productionWritesOnlyRoomAndRestartRestoresMessagesPromptsAndWorkflowState() {
        message("u", "new message")
        vm._agentPromptOutput.value = com.comfyui.mobile.agent.AgentPromptOutput("cat", "blurry")
        vm._agentWorkflowSource.value = "saved"
        vm._agentWorkflowId.value = "fixture.json"
        vm.agentWorkflowParamValues["9:width"] = 768
        vm.agentWorkspace.saveConfiguration()
        flush()
        assertFalse(vm.prefs.contains("agent_messages__local"))
        stop()
        start()
        assertEquals("new message", vm.agentMessages.value.single().content)
        assertEquals("blurry", vm.agentPromptOutput.value.negative)
        assertEquals(768, (vm.agentWorkflowParamValues["9:width"] as Number).toInt())
    }

    @Test fun scopedMigrationKeepsOriginalSnapshotAndDoesNotLetOtherMalformedScopeBlockLocal() {
        stop()
        app.deleteDatabase("agent_workspace.db")
        val original = gson.toJson(listOf(AgentChatMessage("legacy", "user", "old message")))
        app.getSharedPreferences("comfy_prefs", Context.MODE_PRIVATE).edit()
            .putString("agent_messages__local", original)
            .putString("agent_messages__cloud@ai", "{broken").commit()
        start()
        assertEquals(AgentStorageMode.ROOM, vm.agentStorageStatus.value.mode)
        assertEquals("old message", vm.agentMessages.value.single().content)
        message("new", "new message")
        flush()
        assertEquals(original, vm.prefs.getString("agent_messages__local", null))
        assertEquals(2, database { repo, _ -> repo.messages(vm.agentWorkspace.conversationId!!).size })
    }

    @Test fun failedMigrationUsesLegacyAndRetryTakesFreshSnapshot() {
        stop()
        app.deleteDatabase("agent_workspace.db")
        app.getSharedPreferences("comfy_prefs", Context.MODE_PRIVATE).edit()
            .putString("agent_messages__local", "{broken").commit()
        start()
        assertEquals(AgentStorageMode.LEGACY, vm.agentStorageStatus.value.mode)
        vm.prefs.edit().putString("agent_messages__local",
            gson.toJson(listOf(AgentChatMessage("fixed", "user", "repaired")))).commit()
        vm.retryAgentStorage()
        pump { vm.agentStorageStatus.value.mode == AgentStorageMode.ROOM }
        assertEquals("repaired", vm.agentMessages.value.single().content)
    }

    @Test fun incrementalCheckpointRetainsOtherRowsAndRestartMarksInterruptedReply() {
        message("u", "first")
        MainViewModelAgent.appendAgentMessage(vm, AgentChatMessage("a", "assistant", "", isThinking = true))
        MainViewModelAgent.updateAgentMessage(vm, "a", persist = false) { it.copy(content = "partial") }
        flush()
        val before = database { _, dao -> dao.messages(vm.agentWorkspace.conversationId!!) }
        assertEquals(2, before.size)
        assertEquals(0L, before.first().sequence)
        stop()
        start()
        assertEquals("partial", vm.agentMessages.value.last().content)
        assertFalse(vm.agentMessages.value.last().isThinking)
        assertEquals("上次回复已中断", vm.agentMessages.value.last().errorMessage)
    }

    @Test fun last200MessagesLoadFirstAndEarlierPageKeepsOrder() {
        repeat(235) { message("m-$it", "message $it") }
        flush()
        stop()
        start()
        assertEquals(200, vm.agentMessages.value.size)
        assertEquals("m-35", vm.agentMessages.value.first().id)
        assertTrue(vm.agentHasEarlierMessages.value)
        vm.loadEarlierAgentMessages()
        pump { vm.agentMessages.value.size == 235 }
        assertEquals((0..234).map { "m-$it" }, vm.agentMessages.value.map { it.id })
        assertFalse(vm.agentHasEarlierMessages.value)
    }

    @Test fun draftSelectionScrollAndImportedAttachmentSurviveRestart() {
        vm.updateAgentDraft(AgentInputDraft("unfinished", 2, 7, "message", 12))
        val owner = vm.captureAgentAttachmentOwner()!!
        val source = File(vm.agentWorkspace.importsDirectory.apply { mkdirs() }, "draft.txt").apply { writeText("fixture") }
        io { vm.agentWorkspace.addAttachments(owner,
            listOf(AgentAttachment("att", "file", source.toURI().toString(), "draft.txt", 7, textContent = "fixture"))) }
        flush()
        stop()
        start()
        assertEquals(AgentInputDraft("unfinished", 2, 7, "message", 12), vm.agentInputDraft.value)
        pump { vm.agentPendingAttachments.value.singleOrNull()?.uri?.startsWith("file:") == true }
        val attachment = vm.agentPendingAttachments.value.single()
        assertEquals("fixture", File(java.net.URI(attachment.uri)).readText())
        val state = database { repo, _ -> repo.state(vm.agentWorkspace.conversationId!!)!! }
        assertTrue(state.draftJson.contains("agent-asset://"))
        assertFalse(state.draftJson.contains(app.filesDir.absolutePath))
    }

    @Test fun clearChatRemovesOnlyItsAssetRefsAndKeepsLegacyOriginalsAndOtherConversationAssets() {
        val legacy = File(app.filesDir, "agent_attachments/shared.txt").apply { parentFile!!.mkdirs(); writeText("shared") }
        val att = AgentAttachment("att", "file", legacy.toURI().toString(), legacy.name, 6)
        MainViewModelAgent.appendAgentMessage(vm, AgentChatMessage("a", "user", "one", attachments = listOf(att)))
        flush()
        val otherId = database { repo, _ ->
            val other = repo.createConversation("local", "other")
            repo.saveMessage(other.id, AgentChatMessage("b", "user", "two", attachments = listOf(att)))
            other.id
        }
        vm.clearAgentChat()
        flush()
        assertTrue(legacy.isFile)
        database { repo, _ ->
            val uri = repo.messages(otherId).single().attachments!!.single().uri
            assertEquals("shared", repo.resolveAttachment(uri)!!.readText())
            assertTrue(repo.messages(vm.agentWorkspace.conversationId!!).isEmpty())
        }
    }

    @Test fun callbacksWithSameRemoteIdOnDifferentServersCannotCrossConversationOwnership() {
        message("u", "first")
        flush()
        val firstId = vm.agentWorkspace.conversationId!!
        val targetA = vm.agentWorkspace.currentTarget()
        val a = io { vm.agentWorkspace.reserve("turn-a", targetA, "{}")!! }
        io { vm.agentWorkspace.confirm(a, "same-remote") }
        val b = database { repo, _ ->
            val other = repo.createConversation("local", "second")
            val link = repo.reserveExecution(other.id, "turn-b", AgentExecutionTarget("LOCAL_COMFY", "B"))
            repo.confirmExecution(link, "same-remote")
            other.id
        }
        vm.agentWorkspace.reload()
        pump { vm.agentStorageStatus.value.mode == AgentStorageMode.ROOM }
        val targetB = AgentExecutionTarget("LOCAL_COMFY", "B")
        val epoch = vm.agentWorkspace.callbackEpoch
        assertTrue(vm.agentWorkspace.record(MediaOutput("same.png", "", "output", MediaType.IMAGE,
            promptId = "same-remote", sourceUrl = server.url("/a").toString(), workspaceEpoch = epoch), targetA.key))
        assertTrue(vm.agentWorkspace.record(MediaOutput("same.png", "", "output", MediaType.IMAGE,
            promptId = "same-remote", sourceUrl = server.url("/b").toString(), workspaceEpoch = epoch), targetB.key))
        flush()
        database { repo, _ ->
            assertEquals(server.url("/a").toString(), repo.media(firstId).single().sourceUrl)
            assertEquals(server.url("/b").toString(), repo.media(b).single().sourceUrl)
        }
        pump { vm.agentLatestGenerationList.value.isNotEmpty() }
        assertEquals(server.url("/a").toString(), vm.agentLatestGenerationList.value.single().sourceUrl)
    }

    @Test fun unknownSubmissionHasDurableMirrorAfterRestartAndIsNeverResent() {
        message("u", "submit")
        val link = io { vm.agentWorkspace.reserve("turn", vm.agentWorkspace.currentTarget(), "{}")!! }
        io { vm.agentWorkspace.unknown(link) }
        stop()
        start()
        val mirror = vm.taskStore.tasks.value.single { it.agentExecutionId == link.id }
        assertEquals("LOST", mirror.state)
        assertNull(mirror.remoteId)
        assertEquals("unknown", database { _, dao -> dao.execution(link.id)!!.submissionState })
        assertEquals(0, server.requestCount)
    }

    @Test fun cuiBakRoundTripPreservesDraftAndMinimalExecutionWithoutTaskHistory() {
        message("u", "backed up")
        vm.updateAgentDraft(AgentInputDraft("draft", 1, 3))
        val link = io { vm.agentWorkspace.reserve("turn", vm.agentWorkspace.currentTarget(), "{}")!! }
        io { vm.agentWorkspace.confirm(link, "pid") }
        flush()
        val file = io { vm.exportBackupToFile(BackupExportOptions(includeKeys = false,
            includeWorkflows = false, includeAiApps = false, includeTaskHistory = false)) }
        val parsed = io { MainViewModelBackup.parseBackupBytes(vm, file.readBytes()) }
        assertEquals(1, parsed.workspaceConversationCount)
        vm.clearAgentChat()
        message("replacement", "later")
        flush()
        val oldEpoch = vm.agentWorkspace.callbackEpoch
        val result = io { vm.restoreParsedBackup(parsed, BackupRestoreOptions(
            restoreWorkflows = false, restoreAiApps = false, restoreTaskHistory = false, restoreAgentWorkspace = true)) }
        assertTrue(result.toString(), result.isSuccess)
        pump { vm.agentStorageStatus.value.mode == AgentStorageMode.ROOM }
        assertEquals("backed up", vm.agentMessages.value.single().content)
        assertEquals("draft", vm.agentInputDraft.value.text)
        assertTrue(vm.agentWorkspace.callbackEpoch > oldEpoch)
        assertFalse(vm.agentWorkspace.record(MediaOutput("late.png", "", "output", MediaType.IMAGE,
            promptId = "pid", workspaceEpoch = oldEpoch), link.targetKey))
        assertTrue(File(app.filesDir, "agent_workspace/restore-backups").listFiles().orEmpty().isNotEmpty())
        assertNotNull(database { _, dao -> dao.execution(link.id) })
    }

    @Test fun restoredEpochIsPublishedBeforeSocketReconnects() {
        message("u", "restore fixture")
        flush()
        val directory = File(app.cacheDir, "workspace-epoch-backup")
        io { vm.agentWorkspace.export(directory) }
        val previous = vm.agentWorkspace.callbackEpoch
        vm.agentWorkspace.beginRestore()
        try {
            io {
                vm.agentWorkspace.flush()
                vm.agentWorkspace.invalidateCallbacks()
                vm.agentWorkspace.restore(directory, confirmed = true)
                val expected = databaseEpoch()
                assertTrue(expected > previous)
                assertEquals(expected, vm.agentWorkspace.callbackEpoch)
            }
        } finally {
            vm.agentWorkspace.finishRestore()
        }
        pump { vm.agentStorageStatus.value.mode == AgentStorageMode.ROOM }
        assertEquals(vm.agentWorkspace.callbackEpoch, vm.repository.webSocketManager.mediaWorkspaceEpoch)

        // Simulate a socket established before the persisted workspace version is loaded on startup.
        val sockets = java.util.concurrent.ConcurrentLinkedQueue<okhttp3.WebSocket>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path?.startsWith("/ws?") == true) return MockResponse().withWebSocketUpgrade(
                    object : okhttp3.WebSocketListener() {
                        override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) { sockets.add(webSocket) }
                        override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                    })
                return MockResponse().setBody("{}")
            }
        }
        val target = vm.agentWorkspace.currentTarget()
        val link = io { vm.agentWorkspace.reserve("socket-turn", target, "{}")!! }
        io { vm.agentWorkspace.confirm(link, "socket-pid") }
        vm.repository.webSocketManager.mediaSourceKey = target.key
        vm.repository.updateConnection(server.url("/").toString(), force = true)
        pump { sockets.isNotEmpty() && vm.repository.webSocketManager.isSocketLive() }
        val connectionsBeforeLoad = sockets.size
        val persistedEpoch = database { repo, _ -> repo.advanceEpoch() }
        vm.agentWorkspace.reload()
        pump { vm.agentStorageStatus.value.mode == AgentStorageMode.ROOM &&
            vm.agentWorkspace.callbackEpoch == persistedEpoch }
        pump { sockets.size > connectionsBeforeLoad && vm.repository.webSocketManager.isSocketLive() }
        sockets.last().send("""{"type":"executed","data":{"prompt_id":"socket-pid","output":{"images":[{"filename":"epoch.png","subfolder":"","type":"output"}]}}}""")
        pump { vm.agentLatestGenerationList.value.any { it.filename == "epoch.png" } }
        assertEquals(persistedEpoch, vm.agentLatestGenerationList.value.single { it.filename == "epoch.png" }.workspaceEpoch)
    }

    private suspend fun databaseEpoch(): Long {
        val db = AgentWorkspaceDatabase.open(app)
        return try { AgentWorkspaceRepository(db, File(app.filesDir, "agent_workspace"),
            File(app.filesDir, "agent_attachments")).currentEpoch() } finally { db.close() }
    }

    @Test fun oldContainerWithoutWorkspaceDoesNotClearNewChat() {
        message("u", "keep")
        flush()
        val file = io { vm.exportBackupToFile(BackupExportOptions(includeAgentWorkspace = false,
            includeKeys = false, includeWorkflows = false, includeAiApps = false, includeTaskHistory = false)) }
        val bytes = rewritePackage(file) { name, content ->
            if (name != "manifest.json") content else JsonParser.parseString(String(content)).asJsonObject
                .apply { addProperty("version", 2); remove("workspaceVersion") }.toString().toByteArray()
        }
        val parsed = io { MainViewModelBackup.parseBackupBytes(vm, bytes) }
        assertEquals(2, parsed.dto.version)
        assertNull(parsed.workspaceConversationCount)
        assertTrue(io { vm.restoreParsedBackup(parsed) }.isSuccess)
        pump { vm.agentStorageStatus.value.mode == AgentStorageMode.ROOM }
        assertEquals("keep", vm.agentMessages.value.single().content)
    }

    @Test fun unknownWorkspaceBackupVersionFailsPreflightWithoutChangingCurrentConversation() {
        message("u", "keep")
        flush()
        val file = io { vm.exportBackupToFile(BackupExportOptions(includeTaskHistory = false)) }
        val bytes = rewritePackage(file) { name, content ->
            if (name != "agent_workspace/workspace.json") content else
                JsonParser.parseString(String(content)).asJsonObject.apply { addProperty("version", 99) }
                    .toString().toByteArray()
        }
        assertTrue(runCatching { io { MainViewModelBackup.parseBackupBytes(vm, bytes) } }.isFailure)
        assertEquals("keep", vm.agentMessages.value.single().content)
        assertEquals(AgentStorageMode.ROOM, vm.agentStorageStatus.value.mode)
    }

    @Test fun corruptedDraftAttachmentFailsBeforeWorkspaceReplacement() {
        vm.updateAgentDraft(AgentInputDraft("keep"))
        val source = File(vm.agentWorkspace.importsDirectory.apply { mkdirs() }, "draft.txt").apply { writeText("asset") }
        io { vm.agentWorkspace.addAttachments(vm.captureAgentAttachmentOwner()!!,
            listOf(AgentAttachment("asset", "file", source.toURI().toString(), "draft.txt", 5))) }
        flush()
        val file = io { vm.exportBackupToFile(BackupExportOptions(includeTaskHistory = false)) }
        val bytes = rewritePackage(file) { name, content ->
            if (name.startsWith("agent_workspace/assets/")) "changed".toByteArray() else content
        }
        assertTrue(runCatching { io { MainViewModelBackup.parseBackupBytes(vm, bytes) } }.isFailure)
        assertEquals("keep", vm.agentInputDraft.value.text)
        assertEquals(1, vm.agentPendingAttachments.value.size)
    }

    @Test fun attachmentReturningAfterOriginDeletionIsDiscardedWithoutRecreatingConversation() {
        message("u", "delete origin")
        flush()
        val owner = vm.captureAgentAttachmentOwner()!!
        val source = File(vm.agentWorkspace.importsDirectory.apply { mkdirs() }, "late.txt").apply { writeText("late") }
        database { repo, _ -> repo.deleteConversation(owner.conversationId!!) }
        io { vm.agentWorkspace.addAttachments(owner,
            listOf(AgentAttachment("late", "file", source.toURI().toString(), "late.txt", 4))) }
        flush()
        assertFalse(source.exists())
        assertTrue(vm.agentPendingAttachments.value.isEmpty())
        assertNull(database { repo, _ -> repo.conversation(owner.conversationId!!) })
    }

    @Test fun outputArrivingBeforeSubmitConfirmationIsBufferedAndArchivedOnce() {
        message("u", "fast")
        val target = vm.agentWorkspace.currentTarget()
        val link = io { vm.agentWorkspace.reserve("fast-turn", target, "{}")!! }
        val media = MediaOutput("fast.png", "", "output", MediaType.IMAGE, promptId = "fast-pid",
            sourceUrl = server.url("/view?filename=fast.png").toString(), workspaceEpoch = vm.agentWorkspace.callbackEpoch)
        assertFalse(vm.agentWorkspace.record(media, target.key))
        io { vm.agentWorkspace.confirm(link, "fast-pid") }
        vm.agentWorkspace.record(media, target.key)
        flush()
        assertEquals(1, database { repo, _ -> repo.media(link.conversationId!!).size })
    }

    @Test fun failedWriteRetryKeepsUnsavedMessageAndNeverRevertsToLegacyPrefs() {
        message("start", "saved")
        flush()
        io {
            val db = AgentWorkspaceDatabase.open(app)
            try { db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_message BEFORE INSERT ON agent_messages WHEN NEW.id = 'fail' BEGIN SELECT RAISE(ABORT, 'fixture'); END")
            } finally { db.close() }
        }
        message("fail", "retain unsaved")
        pump { vm.agentStorageStatus.value.mode == AgentStorageMode.BLOCKED }
        assertEquals("retain unsaved", vm.agentMessages.value.last().content)
        assertFalse(vm.prefs.contains("agent_messages__local"))
        io {
            val db = AgentWorkspaceDatabase.open(app)
            try { db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_message") } finally { db.close() }
        }
        vm.retryAgentStorage()
        pump { vm.agentStorageStatus.value.mode == AgentStorageMode.ROOM }
        flush()
        assertEquals(listOf("saved", "retain unsaved"),
            database { repo, _ -> repo.messages(vm.agentWorkspace.conversationId!!).map { it.content } })
    }

    @Test fun generatedMediaDownloadUsesStoredSourceEvenAfterConnectionChange() {
        val a = MockWebServer().apply { enqueue(MockResponse().setBody("original-file")); start() }
        try {
            val media = MediaOutput("same.png", "", "output", MediaType.IMAGE,
                sourceUrl = a.url("/view?filename=same.png").toString())
            val dest = File(app.cacheDir, "media-fixture")
            assertTrue(io { vm.repository.downloadViewToFile(media, dest) })
            assertEquals("original-file", dest.readText())
            assertEquals("/view?filename=same.png", a.takeRequest(1, TimeUnit.SECONDS)!!.path)
            assertFalse(io { vm.repository.downloadViewToFile(media.copy(sourceUnresolved = true), dest) })
            assertEquals(0, server.requestCount)
        } finally { a.shutdown() }
    }
}
