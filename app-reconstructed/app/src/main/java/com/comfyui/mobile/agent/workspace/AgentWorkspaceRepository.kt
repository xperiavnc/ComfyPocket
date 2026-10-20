package com.comfyui.mobile.agent.workspace

import androidx.room.withTransaction
import com.comfyui.mobile.model.AgentAttachment
import com.comfyui.mobile.model.AgentChatMessage
import com.comfyui.mobile.model.MediaOutput
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AgentExecutionTarget(val backend: String, val serverId: String, val siteId: String? = null) {
    init {
        require(backend in setOf("LOCAL_COMFY", "RUNNINGHUB") && serverId.isNotBlank())
        require(backend != "RUNNINGHUB" || !siteId.isNullOrBlank())
    }
    val key: String get() = "target:" + workspaceId(backend, serverId, siteId.orEmpty())
}

/**
 * P1 storage boundary. No production adapter is enabled yet: do not run migration while
 * SharedPreferences remains the live writer. All filesystem mutations are confined to assets/.
 */
class AgentWorkspaceRepository(
    internal val database: AgentWorkspaceDatabase,
    internal val directory: File,
    private val legacyAttachments: File,
    internal val gson: Gson = Gson()
) {
    internal val dao = database.workspace()
    internal val mutex = directoryLocks.computeIfAbsent(directory.canonicalPath) { Mutex() }
    internal suspend fun <T> locked(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block() }
    }
    private fun now() = System.currentTimeMillis()
    private fun validScope(scope: String) {
        require(scope == "local" || scope.matches(Regex("cloud@[a-zA-Z0-9._-]+"))) { "Invalid scope" }
    }
    internal fun validObject(json: String) {
        require(JsonParser.parseString(json).isJsonObject) { "Expected JSON object" }
    }
    internal fun assetFile(relativePath: String): File {
        require(relativePath.startsWith("assets/") && '\\' !in relativePath) { "Invalid asset path" }
        val base = File(directory, "assets").canonicalFile
        val file = File(directory, relativePath).canonicalFile
        require(file.path.startsWith(base.path + File.separator)) { "Asset path leaves workspace" }
        return file
    }

    fun observeConversations(scope: String) = dao.observeConversations(scope.also(::validScope))

    suspend fun createConversation(
        scope: String, title: String = "新会话", projectId: String? = null
    ): AgentConversation = locked {
        validScope(scope)
        require(title.isNotBlank())
        val time = now()
        val row = AgentConversation(UUID.randomUUID().toString(), scope, title,
            projectId = projectId, createdAt = time, updatedAt = time)
        database.withTransaction {
            dao.putConversation(row) // Composite FK enforces project scope, even outside this repository.
            dao.putState(AgentConversationState(row.id))
        }
        row
    }

    suspend fun state(id: String): AgentConversationState? = locked { dao.state(id) }
    suspend fun messages(id: String): List<AgentChatMessage> = locked {
        dao.messages(id).map { gson.fromJson(it.payloadJson, AgentChatMessage::class.java) }
    }
    suspend fun media(id: String): List<MediaOutput> = locked {
        dao.media(id).map { gson.fromJson(it.payloadJson, MediaOutput::class.java) }
    }
    suspend fun updateState(state: AgentConversationState) = locked {
        require(state.schemaVersion == 1)
        listOf(state.workflowStateJson, state.promptOutputJson, state.draftJson, state.scrollAnchorJson)
            .forEach(::validObject)
        database.withTransaction {
            requireNotNull(dao.conversation(state.conversationId)) { "Conversation was deleted" }
            dao.putState(state)
        }
    }

    private data class Prepared(
        val messages: List<AgentChatMessage>,
        val assets: List<AgentAsset>,
        val refs: List<AgentAssetRef>
    )

    private suspend fun prepare(conversationId: String, messages: List<AgentChatMessage>): Prepared {
        require(messages.map { it.id }.distinct().size == messages.size) { "Duplicate message IDs" }
        val assets = linkedMapOf<String, AgentAsset>()
        val refs = linkedMapOf<String, AgentAssetRef>()
        val rewritten = try {
            messages.map { message ->
                require(message.id.isNotBlank() && message.role.isNotBlank())
                message.copy(isThinking = false, attachments = message.attachments?.map { attachment ->
                    val existingId = attachment.uri.takeIf { it.startsWith(ASSET_URI) }?.removePrefix(ASSET_URI)
                    val asset = if (existingId != null) {
                        requireNotNull(dao.asset(existingId)) { "Unknown attachment asset" }
                    } else importLegacyAsset(attachment)
                    assets[asset.id] = asset
                    val owner = workspaceId(conversationId, message.id)
                    val refId = workspaceId(asset.id, owner)
                    refs[refId] = AgentAssetRef(refId, asset.id, "message", owner,
                        conversationId = conversationId, messageId = message.id)
                    attachment.copy(uri = ASSET_URI + asset.id)
                })
            }
        } catch (error: Throwable) {
            discardUncommittedAssets(Prepared(emptyList(), assets.values.toList(), emptyList()))
            throw error
        }
        return Prepared(rewritten, assets.values.toList(), refs.values.toList())
    }

    private fun importLegacyAsset(attachment: AgentAttachment): AgentAsset {
        require(attachment.sizeBytes >= 0)
        val source = runCatching {
            val uri = URI(attachment.uri)
            if (uri.scheme != "file") null else File(uri).canonicalFile.takeIf {
                it.path.startsWith(legacyAttachments.canonicalPath + File.separator) && it.isFile
            }
        }.getOrNull()
        val bytes = source?.readBytes()
        val digest = bytes?.let(::workspaceHash)
        val id = "legacy-" + workspaceId(attachment.uri, digest.orEmpty())
        val relative = "assets/$id"
        if (bytes != null) {
            val target = assetFile(relative)
            target.parentFile!!.mkdirs()
            if (target.isFile) require(workspaceHash(target.readBytes()) == digest) { "Asset content mismatch" }
            else {
                val temp = File(target.parentFile, ".${UUID.randomUUID()}.tmp")
                try {
                    temp.writeBytes(bytes)
                    check(temp.renameTo(target)) { "Attachment staging failed" }
                } finally { temp.delete() }
            }
        }
        return AgentAsset(id, relative, attachment.name, attachment.kind,
            bytes?.size?.toLong() ?: attachment.sizeBytes, digest, missing = bytes == null, createdAt = now())
    }

    private suspend fun writeMessages(conversation: AgentConversation, prepared: Prepared) {
        dao.deleteMessages(conversation.id) // cascades only this conversation's message asset references
        dao.insertMessages(prepared.messages.mapIndexed { index, msg ->
            AgentMessage(conversation.id, msg.id, index.toLong(), gson.toJson(msg), msg.timestamp)
        })
        prepared.assets.forEach { dao.putAsset(it) }
        prepared.refs.forEach { dao.putRef(it) }
        dao.putConversation(conversation.copy(
            lastMessagePreview = prepared.messages.lastOrNull()?.content?.take(180).orEmpty(), updatedAt = now()))
    }

    suspend fun replaceMessages(conversationId: String, messages: List<AgentChatMessage>) = locked {
        val conversation = requireNotNull(dao.conversation(conversationId)) { "Conversation was deleted" }
        val prepared = prepare(conversationId, messages)
        try {
            database.withTransaction {
                requireNotNull(dao.conversation(conversationId))
                writeMessages(conversation, prepared)
            }
        } catch (error: Throwable) {
            discardUncommittedAssets(prepared)
            throw error
        }
    }

    private suspend fun discardUncommittedAssets(prepared: Prepared) = withContext(NonCancellable) {
        prepared.assets.forEach { asset ->
            runCatching {
                if (dao.asset(asset.id) == null) assetFile(asset.relativePath).delete()
            }
        }
    }

    suspend fun migrate(snapshot: AgentLegacySnapshot): AgentMigrationState = locked {
        validScope(snapshot.scope)
        validObject(snapshot.workflowStateJson)
        dao.migration(snapshot.scope)?.let { return@locked it }
        val id = "legacy-" + workspaceId(snapshot.scope, "v1")
        val prepared = if (snapshot.hasHistory) prepare(id, snapshot.messages)
            else Prepared(emptyList(), emptyList(), emptyList())
        val time = now()
        try {
            database.withTransaction {
                // The marker is authoritative and committed with all rows. A retry never duplicates data.
                dao.migration(snapshot.scope)?.let { return@withTransaction it }
                if (snapshot.hasHistory) {
                    val conversation = AgentConversation(id, snapshot.scope,
                        if (snapshot.messages.isEmpty()) "历史生成记录" else "历史会话",
                        mode = snapshot.mode, llmProfileId = snapshot.llmProfileId,
                        workflowRef = snapshot.workflowRef, createdAt = time, updatedAt = time)
                    dao.putConversation(conversation)
                    writeMessages(conversation, prepared)
                    dao.putState(AgentConversationState(id, snapshot.workflowStateJson, gson.toJson(snapshot.promptOutput)))
                    val target = "legacy:${snapshot.scope}"
                    val links = snapshot.runPromptIds.associateWith { remote ->
                        AgentExecutionLink("legacy-" + workspaceId(snapshot.scope, remote), "legacy",
                            id, snapshot.scope, target, "UNKNOWN", remoteId = remote,
                            submissionState = "legacy_unresolved", workspaceEpoch = epochUnlocked(),
                            createdAt = time, updatedAt = time)
                    }
                    links.values.forEach {
                        require(dao.executionByRemote(it.targetKey, it.remoteId!!) == null) {
                            "Legacy task already has another owner"
                        }
                        dao.putExecution(it)
                    }
                    snapshot.media.forEach { media ->
                        dao.putMedia(mediaRow(id, target, media, links[media.promptId]?.id))
                    }
                }
                AgentMigrationState(snapshot.scope, sourceFingerprint = snapshot.fingerprint,
                    conversationId = id.takeIf { snapshot.hasHistory }, messageCount = prepared.messages.size,
                    mediaCount = snapshot.media.map { mediaKey("legacy:${snapshot.scope}", it) }.distinct().size,
                    missingAssetCount = prepared.assets.count { it.missing }, completedAt = time
                ).also { dao.putMigration(it) }
            }
        } catch (error: Throwable) {
            discardUncommittedAssets(prepared)
            throw error
        }
    }

    internal suspend fun epochUnlocked(): Long = dao.meta("epoch")?.toLongOrNull() ?: 0

    suspend fun reserveExecution(
        conversationId: String, turnId: String, target: AgentExecutionTarget, snapshotJson: String = "{}"
    ): AgentExecutionLink = locked {
        validObject(snapshotJson)
        val conversation = requireNotNull(dao.conversation(conversationId)) { "Conversation was deleted" }
        require(if (conversation.scope == "local") target.backend == "LOCAL_COMFY"
            else target.backend == "RUNNINGHUB" && conversation.scope == "cloud@${target.siteId}") {
            "Execution target is outside conversation scope"
        }
        val time = now()
        AgentExecutionLink(UUID.randomUUID().toString(), turnId, conversationId, conversation.scope,
            target.key, target.backend, target.serverId, target.siteId, snapshotJson = snapshotJson,
            workspaceEpoch = epochUnlocked(), createdAt = time, updatedAt = time
        ).also { dao.putExecution(it) }
    }

    suspend fun confirmExecution(link: AgentExecutionLink, remoteId: String, taskLocalId: String? = null): Boolean = locked {
        require(remoteId.isNotBlank())
        val current = dao.execution(link.id) ?: return@locked false
        if (current.workspaceEpoch != link.workspaceEpoch) return@locked false
        require(current.remoteId == null || current.remoteId == remoteId) { "Remote identity is immutable" }
        val owner = dao.executionByRemote(current.targetKey, remoteId)
        require(owner == null || owner.id == current.id) { "Remote task already belongs to another execution" }
        dao.updateExecution(current.copy(remoteId = remoteId, taskLocalId = taskLocalId ?: current.taskLocalId,
            submissionState = "confirmed", updatedAt = now()))
        true
    }

    suspend fun markSubmissionUnknown(link: AgentExecutionLink) = locked {
        dao.execution(link.id)?.takeIf { it.workspaceEpoch == link.workspaceEpoch && it.remoteId == null }?.let {
            dao.updateExecution(it.copy(submissionState = "unknown", updatedAt = now()))
        }
    }

    suspend fun executionForRemote(target: AgentExecutionTarget, remoteId: String): AgentExecutionLink? =
        locked { dao.executionByRemote(target.key, remoteId) }

    suspend fun recordOutput(link: AgentExecutionLink, media: MediaOutput): Boolean = locked {
        database.withTransaction {
            val current = dao.execution(link.id) ?: return@withTransaction false
            val conversationId = current.conversationId ?: return@withTransaction false
            if (current.workspaceEpoch != link.workspaceEpoch || current.detachedAt != null) return@withTransaction false
            require(media.promptId == null || current.remoteId == null || media.promptId == current.remoteId) {
                "Media remote identity differs from execution"
            }
            requireNotNull(dao.conversation(conversationId))
            dao.putMedia(mediaRow(conversationId, current.targetKey, media, current.id))
            true
        }
    }

    private fun mediaRow(id: String, source: String, media: MediaOutput, executionId: String?) =
        AgentConversationMedia(id, mediaKey(source, media), source, gson.toJson(media), executionId, media.timestamp)

    suspend fun deleteConversation(id: String) = locked {
        database.withTransaction {
            dao.detachExecutions(id, now())
            dao.deleteConversation(id)
        }
        cleanupUnlocked()
    }

    suspend fun cleanupUnreferencedAssets(): Int = locked { cleanupUnlocked() }

    private suspend fun cleanupUnlocked(): Int {
        var count = 0
        for (asset in dao.unreferencedAssets()) {
            if (dao.refs(asset.id).isNotEmpty()) continue
            val file = assetFile(asset.relativePath)
            if ((!file.exists() || file.delete()) && dao.deleteUnreferencedAsset(asset.id) == 1) count++
        }
        return count
    }

    suspend fun resolveAttachment(uri: String): File? = locked {
        val id = uri.takeIf { it.startsWith(ASSET_URI) }?.removePrefix(ASSET_URI) ?: return@locked null
        val asset = dao.asset(id)?.takeUnless { it.missing } ?: return@locked null
        assetFile(asset.relativePath).takeIf { it.isFile }
    }

    companion object {
        private val directoryLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
        const val ASSET_URI = "agent-asset://"
        fun mediaKey(source: String, media: MediaOutput): String = workspaceId(
            source, media.filename, media.subfolder, media.type, media.promptId.orEmpty())
    }
}
