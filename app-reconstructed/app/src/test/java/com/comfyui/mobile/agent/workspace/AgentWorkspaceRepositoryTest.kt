package com.comfyui.mobile.agent.workspace

import android.app.Application
import androidx.room.Room
import com.comfyui.mobile.agent.AgentPromptOutput
import com.comfyui.mobile.model.AgentAttachment
import com.comfyui.mobile.model.AgentChatMessage
import com.comfyui.mobile.model.AgentClarification
import com.comfyui.mobile.model.MediaOutput
import com.comfyui.mobile.model.MediaType
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real generated Room implementation + SQLite + owned files; no phone data or remote APIs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentWorkspaceRepositoryTest {
    private lateinit var app: Application
    private lateinit var db: AgentWorkspaceDatabase
    private lateinit var root: File
    private lateinit var legacy: File
    private lateinit var repo: AgentWorkspaceRepository
    private val gson = Gson()
    private val databaseNames = mutableListOf<String>()
    private fun check(block: suspend () -> Unit) = runBlocking(Dispatchers.IO) { block() }

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        root = File(app.cacheDir, "workspace-test-${UUID.randomUUID()}").apply { mkdirs() }
        legacy = File(root, "legacy").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(app, AgentWorkspaceDatabase::class.java).build()
        repo = AgentWorkspaceRepository(db, File(root, "workspace"), legacy)
    }
    @After fun close() {
        db.close()
        databaseNames.forEach { app.deleteDatabase(it) }
        root.deleteRecursively()
    }
    private fun snapshot(values: Map<String, *>): AgentLegacySnapshot = AgentLegacyReader.read(values).single()
    private fun message(id: String = "m", attachment: AgentAttachment? = null) = AgentChatMessage(
        id, "user", "画星澜", timestamp = 1234, attachments = attachment?.let(::listOf))
    private fun attachment(name: String = "reference.txt"): AgentAttachment {
        val file = File(legacy, name).apply { writeText("reference content") }
        return AgentAttachment(name, "file", file.toURI().toString(), name, file.length(), "reference content")
    }
    private fun media(localUri: String? = null) = MediaOutput(
        "same.png", "", "output", MediaType.IMAGE, timestamp = 4567, promptId = "remote", localUri = localUri)
    private suspend fun failure(block: suspend () -> Unit): Throwable {
        try { block() } catch (e: Throwable) { return e }
        throw AssertionError("Expected failure")
    }

    @Test fun legacyMessagesPromptsClarificationAndAttachmentSurviveMigration() = check {
        val file = attachment()
        val assistant = AgentChatMessage("a", "assistant", "选哪个服装", timestamp = 2345,
            expandedPrompt = "star_token", expandedNegativePrompt = "blurry",
            positiveAliases = listOf(com.comfyui.mobile.agent.AgentPromptAlias("star_token", "身份触发词")),
            negativeAliases = listOf(com.comfyui.mobile.agent.AgentPromptAlias("blurry", "模糊")),
            clarification = AgentClarification("选哪个", listOf("冬装", "便服"), sourceRequest = "画星澜"))
        val promptOutput = AgentPromptOutput("star_token", "blurry", "written",
            assistant.positiveAliases, assistant.negativeAliases)
        val source = mapOf("agent_messages__local" to gson.toJson(listOf(message(attachment = file), assistant)),
            "agent_prompt_output__local" to gson.toJson(promptOutput))
        val result = repo.migrate(snapshot(source))
        val restored = repo.messages(result.conversationId!!)
        assertEquals(listOf("m", "a"), restored.map { it.id })
        assertEquals(1234L, restored.first().timestamp)
        assertEquals(assistant, restored.last())
        assertTrue(restored.first().attachments!!.single().uri.startsWith(AgentWorkspaceRepository.ASSET_URI))
        assertEquals("reference content", repo.resolveAttachment(restored.first().attachments!!.single().uri)!!.readText())
        assertTrue(File(legacy, file.name).isFile)
        assertTrue(repo.state(result.conversationId)!!.promptOutputJson.contains("blurry"))
        assertEquals(promptOutput, gson.fromJson(repo.state(result.conversationId)!!.promptOutputJson, AgentPromptOutput::class.java))
    }

    @Test fun emptyChatStillMigratesGeneratedMedia() = check {
        val result = repo.migrate(snapshot(mapOf(
            "agent_messages__local" to "[]", "agent_latest_media_list__local" to gson.toJson(listOf(media())))))
        assertEquals("历史生成记录", db.workspace().conversation(result.conversationId!!)!!.title)
        assertEquals(1, repo.media(result.conversationId).size)
        assertTrue(repo.messages(result.conversationId).isEmpty())
        assertEquals("legacy:local", db.workspace().media(result.conversationId).single().sourceDescriptor)
    }

    @Test fun negativePromptAloneCountsAsRecoverableHistory() = check {
        val result = repo.migrate(snapshot(mapOf(
            "agent_prompt_output__local" to """{"positive":"","negative":"bad hands"}""")))
        assertNotNull(result.conversationId)
        assertTrue(repo.state(result.conversationId!!)!!.promptOutputJson.contains("bad hands"))
    }

    @Test fun taskIdsAloneKeepUnresolvedOriginWithoutGuessingServer() = check {
        repo.migrate(snapshot(mapOf("agent_run_prompt_ids__local" to setOf("remote"))))
        val link = db.workspace().allExecutions().single()
        assertEquals("legacy_unresolved", link.submissionState)
        assertEquals("UNKNOWN", link.backend)
        assertNull(link.serverId)
        assertEquals("remote", link.remoteId)
    }

    @Test fun emptyLegacyKeysDoNotProduceEmptyConversation() = check {
        val result = repo.migrate(snapshot(mapOf("agent_messages__local" to "[]")))
        assertNull(result.conversationId)
        assertTrue(db.workspace().allConversations().isEmpty())
        assertNotNull(db.workspace().migration("local"))
    }

    @Test fun unscopedLegacyValuesAreNotCopiedIntoCloud() = check {
        val snapshots = AgentLegacyReader.read(mapOf(
            "agent_messages" to gson.toJson(listOf(message())),
            "agent_run_prompt_ids" to setOf("unscoped"),
            "agent_messages__cloud@cn" to "[]"))
        assertEquals(2, snapshots.size)
        val cloud = snapshots.first { it.scope == "cloud@cn" }
        assertTrue(cloud.messages.isEmpty())
        assertTrue(cloud.runPromptIds.isEmpty())
        snapshots.forEach { repo.migrate(it) }
        assertEquals(listOf("local"), db.workspace().allConversations().map { it.scope })
    }

    @Test fun workflowParametersAndBothBindingsAreMigratedIndependently() = check {
        val source = mapOf(
            "agent_wf_source__local" to "saved", "agent_wf_id__local" to "fixture.json",
            "agent_wf_params_local_saved_fixture.json" to """{"1:text":"positive","2:text":"negative"}""",
            "agent_target_node_saved_fixture.json" to "1",
            "agent_prompt_binding_negative__local_saved_fixture.json" to
                """{"mode":"EXPLICIT","nodeId":"2","key":"text"}""",
            "agent_aux_nodes_local_saved_fixture.json" to """["3:seed"]""")
        val result = repo.migrate(snapshot(source))
        val state = JsonParser.parseString(repo.state(result.conversationId!!)!!.workflowStateJson).asJsonObject
        assertEquals("negative", state["parameters"].asJsonObject["2:text"].asString)
        assertEquals("1", state["positiveBinding"].asJsonObject["nodeId"].asString)
        assertEquals("2", state["negativeBinding"].asJsonObject["nodeId"].asString)
        assertEquals("3:seed", state["auxNodeKeys"].asJsonArray.single().asString)
        assertEquals(snapshot(source).fingerprint, snapshot(source.toList().reversed().toMap()).fingerprint)
    }

    @Test fun malformedLegacyJsonIsAnErrorNotAnEmptySuccessfulMigration() = check {
        failure { AgentLegacyReader.read(mapOf("agent_messages__local" to "{broken")) }
        assertTrue(db.workspace().allMigrations().isEmpty())
    }

    @Test fun oldUnscopedCloudWorkflowIsPreservedAsUnresolvedNotSelectedForLocal() = check {
        val old = snapshot(mapOf("agent_wf_source_cloud" to "saved", "agent_wf_id_cloud" to "cloud.json"))
        assertNull(old.workflowRef)
        assertTrue(old.hasHistory)
        val result = repo.migrate(old)
        val state = JsonParser.parseString(repo.state(result.conversationId!!)!!.workflowStateJson).asJsonObject
        assertEquals("cloud.json", state["unresolvedLegacyCloudWorkflow"].asJsonObject["id"].asString)
        assertTrue(state["source"] == null || state["source"].isJsonNull)
    }

    @Test fun duplicateLegacyMessageIdsDoNotDropOneSilently() = check {
        failure { AgentLegacyReader.read(mapOf("agent_messages__local" to gson.toJson(listOf(message(), message())))) }
        assertTrue(db.workspace().allMessages().isEmpty())
    }

    @Test fun completedMigrationIsIdempotentAcrossRepositoryInstances() = check {
        val old = snapshot(mapOf("agent_messages__local" to gson.toJson(listOf(message()))))
        val first = repo.migrate(old)
        val second = AgentWorkspaceRepository(db, repo.directory, legacy).migrate(old)
        assertEquals(first, second)
        assertEquals(1, db.workspace().allConversations().size)
        assertEquals(1, db.workspace().allMessages().size)
    }

    @Test fun concurrentMigrationCommitsOneConversationAndMarker() = check {
        val old = snapshot(mapOf("agent_messages__local" to gson.toJson(listOf(message()))))
        val other = AgentWorkspaceRepository(db, repo.directory, legacy)
        val results = coroutineScope { listOf(async { repo.migrate(old) }, async { other.migrate(old) }).awaitAll() }
        assertEquals(results.first(), results.last())
        assertEquals(1, db.workspace().allMigrations().size)
    }

    @Test fun midTransactionFailureLeavesNoPartialMigrationAndKeepsOriginalFile() = check {
        val existing = repo.createConversation("local")
        db.workspace().putExecution(AgentExecutionLink("conflicting", "old", existing.id, "local",
            "legacy:local", "UNKNOWN", remoteId = "remote", createdAt = 1, updatedAt = 1))
        val file = attachment()
        val old = snapshot(mapOf("agent_messages__local" to gson.toJson(listOf(message(attachment = file))),
            "agent_run_prompt_ids__local" to setOf("remote")))
        failure { repo.migrate(old) }
        assertNull(db.workspace().migration("local"))
        assertEquals(listOf(existing.id), db.workspace().allConversations().map { it.id })
        assertTrue(db.workspace().allMessages().isEmpty())
        assertTrue(File(legacy, file.name).isFile)
        assertTrue(File(repo.directory, "assets").listFiles().orEmpty().isEmpty())
    }

    @Test fun missingAttachmentPreservesMessageAndReportsMissingAsset() = check {
        val att = AgentAttachment("lost", "image", File(legacy, "missing.jpg").toURI().toString(), "missing.jpg")
        val result = repo.migrate(snapshot(mapOf("agent_messages__local" to gson.toJson(listOf(message(attachment = att))))))
        assertEquals(1, result.missingAssetCount)
        val restored = repo.messages(result.conversationId!!).single().attachments!!.single()
        assertEquals("missing.jpg", restored.name)
        assertNull(repo.resolveAttachment(restored.uri))
    }

    @Test fun deletingConversationKeepsSharedAttachmentAndOriginalThenCleansLastReference() = check {
        val original = attachment()
        val a = repo.createConversation("local")
        val b = repo.createConversation("local")
        repo.replaceMessages(a.id, listOf(message(attachment = original)))
        repo.replaceMessages(b.id, listOf(message(attachment = original)))
        val uri = repo.messages(b.id).single().attachments!!.single().uri
        val copied = repo.resolveAttachment(uri)!!
        repo.deleteConversation(a.id)
        assertTrue(copied.isFile)
        assertNotNull(repo.resolveAttachment(uri))
        repo.deleteConversation(b.id)
        assertFalse(copied.exists())
        assertTrue(File(legacy, original.name).isFile)
    }

    @Test fun externalFileIsNotAdoptedOrDeletedByConversationCleanup() = check {
        val external = File(root, "external.txt").apply { writeText("keep") }
        val a = repo.createConversation("local")
        repo.replaceMessages(a.id, listOf(message(attachment =
            AgentAttachment("external", "file", external.toURI().toString(), "external.txt"))))
        assertTrue(db.workspace().allAssets().single().missing)
        repo.deleteConversation(a.id)
        assertEquals("keep", external.readText())
    }

    @Test fun sameMessageIdAndWorkflowCanHaveIndependentConversationState() = check {
        val a = repo.createConversation("local")
        val b = repo.createConversation("local")
        repo.replaceMessages(a.id, listOf(message()))
        repo.replaceMessages(b.id, listOf(message().copy(content = "different")))
        repo.updateState(AgentConversationState(a.id, """{"model":"A"}""", """{"negative":"negative A"}"""))
        repo.updateState(AgentConversationState(b.id, """{"model":"B"}""", """{"negative":"negative B"}"""))
        assertEquals("画星澜", repo.messages(a.id).single().content)
        assertEquals("different", repo.messages(b.id).single().content)
        assertTrue(repo.state(a.id)!!.promptOutputJson.contains("negative A"))
        assertTrue(repo.state(b.id)!!.workflowStateJson.contains("B"))
    }

    @Test fun databaseRejectsProjectScopeMismatchAtomically() = check {
        db.workspace().putProject(AgentProject("cloud-project", "cloud@cn", "cloud", createdAt = 1, updatedAt = 1))
        failure { repo.createConversation("local", projectId = "cloud-project") }
        assertTrue(db.workspace().allConversations().isEmpty())
        assertTrue(db.workspace().allStates().isEmpty())
    }

    @Test fun sourceIdentitySeparatesIdenticalRemoteIdsAndFileNames() = check {
        val a = repo.createConversation("local")
        val targetA = AgentExecutionTarget("LOCAL_COMFY", "server-a")
        val targetB = AgentExecutionTarget("LOCAL_COMFY", "server-b")
        val first = repo.reserveExecution(a.id, "turn-a", targetA)
        val second = repo.reserveExecution(a.id, "turn-b", targetB)
        repo.confirmExecution(first, "remote")
        repo.confirmExecution(second, "remote")
        repo.recordOutput(first, media())
        repo.recordOutput(second, media())
        assertEquals(2, repo.media(a.id).size)
        assertEquals(first.id, repo.executionForRemote(targetA, "remote")!!.id)
        assertEquals(second.id, repo.executionForRemote(targetB, "remote")!!.id)
    }

    @Test fun duplicateOutputCallbacksAreIdempotent() = check {
        val a = repo.createConversation("local")
        val link = repo.reserveExecution(a.id, "turn", AgentExecutionTarget("LOCAL_COMFY", "server"))
        repo.confirmExecution(link, "remote")
        repeat(3) { assertTrue(repo.recordOutput(link, media())) }
        assertEquals(1, repo.media(a.id).size)
    }

    @Test fun deletionDetachesTaskAndLateOutputDoesNotRecreateConversation() = check {
        val a = repo.createConversation("local")
        val b = repo.createConversation("local")
        val target = AgentExecutionTarget("LOCAL_COMFY", "server")
        val link = repo.reserveExecution(a.id, "turn", target)
        repo.confirmExecution(link, "remote")
        repo.deleteConversation(a.id)
        assertFalse(repo.recordOutput(link, media()))
        assertNull(repo.executionForRemote(target, "remote")!!.conversationId)
        assertNotNull(repo.executionForRemote(target, "remote")!!.detachedAt)
        assertTrue(repo.media(b.id).isEmpty())
        assertNull(db.workspace().conversation(a.id))
    }

    @Test fun uncertainSubmissionKeepsIdentityAndCannotChangeConfirmedRemoteId() = check {
        val a = repo.createConversation("local")
        val link = repo.reserveExecution(a.id, "turn", AgentExecutionTarget("LOCAL_COMFY", "server"))
        repo.markSubmissionUnknown(link)
        assertEquals("unknown", db.workspace().execution(link.id)!!.submissionState)
        assertEquals(1, db.workspace().allExecutions().size)
        repo.confirmExecution(link, "remote")
        failure { repo.confirmExecution(link, "different") }
        assertEquals("remote", db.workspace().execution(link.id)!!.remoteId)
    }

    @Test fun duplicateRemoteIdentityDoesNotStealAnotherExecution() = check {
        val a = repo.createConversation("local")
        val b = repo.createConversation("local")
        val target = AgentExecutionTarget("LOCAL_COMFY", "server")
        val first = repo.reserveExecution(a.id, "first", target)
        val second = repo.reserveExecution(b.id, "second", target)
        repo.confirmExecution(first, "remote")
        failure { repo.confirmExecution(second, "remote") }
        assertEquals(first.id, repo.executionForRemote(target, "remote")!!.id)
        assertNull(db.workspace().execution(second.id)!!.remoteId)
    }

    @Test fun executionTargetCannotCrossScope() = check {
        val local = repo.createConversation("local")
        val cloud = repo.createConversation("cloud@cn")
        failure { repo.reserveExecution(local.id, "x", AgentExecutionTarget("RUNNINGHUB", "cloud", "cn")) }
        failure { repo.reserveExecution(cloud.id, "x", AgentExecutionTarget("RUNNINGHUB", "cloud", "ai")) }
        assertTrue(db.workspace().allExecutions().isEmpty())
    }

    @Test fun databaseReopenPreservesMessagesAndExecutionOwnership() = check {
        db.close()
        val name = "workspace-test-${UUID.randomUUID()}.db".also { databaseNames += it }
        db = Room.databaseBuilder(app, AgentWorkspaceDatabase::class.java, name).build()
        repo = AgentWorkspaceRepository(db, File(root, "persistent"), legacy)
        val a = repo.createConversation("local")
        repo.replaceMessages(a.id, listOf(message()))
        val target = AgentExecutionTarget("LOCAL_COMFY", "server")
        val link = repo.reserveExecution(a.id, "turn", target)
        repo.confirmExecution(link, "remote")
        db.close()
        db = Room.databaseBuilder(app, AgentWorkspaceDatabase::class.java, name).build()
        repo = AgentWorkspaceRepository(db, File(root, "persistent"), legacy)
        assertEquals("画星澜", repo.messages(a.id).single().content)
        assertEquals(a.id, repo.executionForRemote(target, "remote")!!.conversationId)
        assertEquals(a.id, repo.observeConversations("local").first().single().id)
    }

    @Test fun portableBackupRestoresAssetsStateAndKeepsPreviousSnapshot() = check {
        val a = repo.createConversation("local")
        val aliases = listOf(com.comfyui.mobile.agent.AgentPromptAlias("blurry", "模糊"))
        val original = message(attachment = attachment()).copy(expandedNegativePrompt = "blurry", negativeAliases = aliases)
        repo.replaceMessages(a.id, listOf(original))
        val output = AgentPromptOutput("portrait", "blurry", negativeAliases = aliases)
        repo.updateState(AgentConversationState(a.id, """{"model":"A"}""", gson.toJson(output)))
        val link = repo.reserveExecution(a.id, "turn", AgentExecutionTarget("LOCAL_COMFY", "server"))
        repo.confirmExecution(link, "remote")
        repo.recordOutput(link, media(localUri = File(root, "not-packaged.png").toURI().toString()))
        val backup = File(root, "export")
        val payload = repo.exportTo(backup)
        assertFalse(File(backup, "workspace.json").readText().contains(legacy.canonicalPath.replace('\\', '/')))
        assertNull(gson.fromJson(payload.media.single().payloadJson, MediaOutput::class.java).localUri)
        val before = repo.messages(a.id).single().attachments!!.single().uri
        val originalPath = repo.resolveAttachment(before)!!.canonicalPath
        val result = repo.restoreFrom(backup, confirmed = true)
        assertEquals(1, result.conversationCount)
        assertTrue(File(result.previousSnapshotDirectory, "workspace.json").isFile)
        assertEquals("reference content", repo.resolveAttachment(before)!!.readText())
        assertNotEquals(originalPath, repo.resolveAttachment(before)!!.canonicalPath)
        assertTrue(repo.state(a.id)!!.promptOutputJson.contains("blurry"))
        assertEquals(aliases, repo.messages(a.id).single().negativeAliases)
        assertEquals(output, gson.fromJson(repo.state(a.id)!!.promptOutputJson, AgentPromptOutput::class.java))
        assertEquals(1, repo.media(a.id).size)
    }

    @Test fun restoreInvalidatesOldCallbacksButAllowsNewTrackingHandle() = check {
        val a = repo.createConversation("local")
        val target = AgentExecutionTarget("LOCAL_COMFY", "server")
        val old = repo.reserveExecution(a.id, "turn", target)
        repo.confirmExecution(old, "remote")
        val backup = File(root, "export")
        repo.exportTo(backup)
        repo.restoreFrom(backup, confirmed = true)
        assertFalse(repo.recordOutput(old, media()))
        assertFalse(repo.confirmExecution(old, "remote"))
        val resumed = repo.executionForRemote(target, "remote")!!
        assertTrue(resumed.workspaceEpoch > old.workspaceEpoch)
        assertTrue(repo.recordOutput(resumed, media()))
    }

    @Test fun backupExcludesRemovedAttachmentsWithoutChangingOriginalOrLiveAssets() = check {
        val a = repo.createConversation("local")
        val removed = attachment("removed.txt")
        repo.replaceMessages(a.id, listOf(message(attachment = removed)))
        val uri = repo.messages(a.id).single().attachments!!.single().uri
        val managedCopy = repo.resolveAttachment(uri)!!
        repo.replaceMessages(a.id, listOf(message()))
        assertTrue(db.workspace().allRefs().isEmpty())
        assertTrue(db.workspace().allAssets().isNotEmpty()) // cleanup can be retried independently
        val backup = File(root, "export")
        val payload = repo.exportTo(backup)
        assertTrue(payload.assets.isEmpty())
        assertTrue(payload.refs.isEmpty())
        assertFalse(File(backup, "assets").exists())
        assertTrue(managedCopy.isFile) // export does not mutate the live workspace
        assertTrue(File(legacy, removed.name).isFile)
    }

    @Test fun oldBackupWithoutWorkspaceDoesNotClearCurrentConversations() = check {
        val a = repo.createConversation("local")
        assertTrue(repo.restoreFrom(File(root, "old-backup"), confirmed = false).skipped)
        assertNotNull(db.workspace().conversation(a.id))
    }

    @Test fun unknownBackupVersionAndUnconfirmedReplaceLeaveDataUntouched() = check {
        val a = repo.createConversation("local")
        val backup = File(root, "export")
        repo.exportTo(backup)
        failure { repo.restoreFrom(backup, confirmed = false) }
        val file = File(backup, "workspace.json")
        val json = JsonParser.parseString(file.readText()).asJsonObject
        json.addProperty("version", 999)
        file.writeText(json.toString())
        failure { repo.restoreFrom(backup, confirmed = true) }
        assertEquals(listOf(a.id), db.workspace().allConversations().map { it.id })
    }

    @Test fun corruptAttachmentInBackupDoesNotReplaceCurrentWorkspace() = check {
        val a = repo.createConversation("local")
        repo.replaceMessages(a.id, listOf(message(attachment = attachment())))
        val backup = File(root, "export")
        val payload = repo.exportTo(backup)
        File(backup, payload.assets.single().relativePath).writeText("corrupted")
        failure { repo.restoreFrom(backup, confirmed = true) }
        assertEquals("reference content", repo.resolveAttachment(repo.messages(a.id).single().attachments!!.single().uri)!!.readText())
        assertEquals(listOf(a.id), db.workspace().allConversations().map { it.id })
    }

    @Test fun invalidStateAndMissingAssetDoNotReplaceValidMessages() = check {
        val a = repo.createConversation("local")
        repo.replaceMessages(a.id, listOf(message()))
        failure { repo.updateState(AgentConversationState(a.id, "[]")) }
        failure { repo.replaceMessages(a.id, listOf(message(attachment =
            AgentAttachment("unknown", "file", "agent-asset://unknown", "unknown")))) }
        assertEquals("画星澜", repo.messages(a.id).single().content)
        assertEquals("{}", repo.state(a.id)!!.workflowStateJson)
    }

    @Test fun workspacesGroupArchiveAndDeleteWithoutLosingSessions() = check {
        val pc = repo.createProject("local", "  PC  ")
        assertEquals("PC", pc.name)
        assertTrue(failure { repo.createProject("local", " ") }.message!!.contains("工作区名称"))
        val inside = repo.createConversation("local", "分组", pc.id)
        val loose = repo.createConversation("local", "散落")
        assertEquals(listOf(pc.id), repo.observeProjects("local").first().map { it.id })

        val moved = repo.moveConversation(loose.id, "local", pc.id)
        assertEquals(pc.id, moved.projectId)
        assertEquals(loose.updatedAt, moved.updatedAt)
        // A later save from a stale in-memory row must not undo the move.
        repo.saveConversation(loose.copy(lastMessagePreview = "新消息"))
        assertEquals(pc.id, db.workspace().conversation(loose.id)!!.projectId)

        repo.setConversationArchived(inside.id, "local", true)
        assertEquals(listOf(loose.id), repo.observeConversations("local").first().map { it.id })
        assertEquals(listOf(inside.id), repo.observeArchivedConversations("local").first().map { it.id })
        repo.saveConversation(inside.copy(title = "分组改"))
        assertNotNull("Saving keeps the archive flag", db.workspace().conversation(inside.id)!!.archivedAt)
        repo.setConversationArchived(inside.id, "local", false)

        repo.setProjectArchived(pc.id, "local", true)
        assertTrue(repo.observeConversations("local").first().isEmpty())
        assertEquals(setOf(inside.id, loose.id), repo.observeArchivedConversations("local").first().map { it.id }.toSet())
        // Restoring one session out of an archived workspace puts it back as ungrouped.
        val restored = repo.setConversationArchived(loose.id, "local", false)
        assertNull(restored.projectId)
        assertEquals(listOf(loose.id), repo.observeConversations("local").first().map { it.id })
        repo.setProjectArchived(pc.id, "local", false)

        repo.renameProject(pc.id, "local", "APK")
        assertEquals("APK", repo.observeProjects("local").first().single().name)
        repo.deleteProject(pc.id, "local")
        assertTrue(repo.observeProjects("local").first().isEmpty())
        val rows = repo.observeConversations("local").first()
        assertEquals(setOf(inside.id, loose.id), rows.map { it.id }.toSet())
        assertTrue("Deleting a workspace only ungroups its sessions", rows.all { it.projectId == null })
        assertTrue(failure { repo.moveConversation(loose.id, "local", pc.id) }.message!!.contains("工作区已被删除"))
    }
}
