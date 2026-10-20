package com.comfyui.mobile.agent.workspace

import androidx.room.withTransaction
import com.comfyui.mobile.model.AgentChatMessage
import com.comfyui.mobile.model.MediaOutput
import java.io.File
import java.util.UUID

/** Versioned portable payload embedded by the production adapter in .cui.bak. */
data class AgentWorkspaceBackup(
    val version: Int = 1,
    val projects: List<AgentProject>,
    val conversations: List<AgentConversation>,
    val states: List<AgentConversationState>,
    val messages: List<AgentMessage>,
    val executions: List<AgentExecutionLink>,
    val media: List<AgentConversationMedia>,
    val assets: List<AgentAsset>,
    val refs: List<AgentAssetRef>,
    val migrations: List<AgentMigrationState>,
    val meta: List<AgentWorkspaceMeta>
)

data class AgentWorkspaceRestoreResult(
    val conversationCount: Int,
    val previousSnapshotDirectory: File?,
    val skipped: Boolean = false
)

private fun containedFile(base: File, relative: String): File {
    require(relative.startsWith("assets/") && '\\' !in relative) { "Invalid packaged asset path" }
    val file = File(base, relative).canonicalFile
    require(file.path.startsWith(base.canonicalPath + File.separator)) { "Packaged asset leaves backup" }
    require(file.path.startsWith(File(base, "assets").canonicalPath + File.separator)) { "Invalid packaged asset location" }
    return file
}

private suspend fun AgentWorkspaceRepository.snapshot(): AgentWorkspaceBackup = database.withTransaction {
    val refs = dao.allRefs()
    val referencedAssetIds = refs.mapTo(hashSetOf()) { it.assetId }
    AgentWorkspaceBackup(projects = dao.allProjects(), conversations = dao.allConversations(),
        states = dao.allStates(), messages = dao.allMessages(), executions = dao.allExecutions(),
        media = dao.allMedia().map { row ->
            // Generated media remain in the media library/server, not in the attachment package.
            val output = gson.fromJson(row.payloadJson, MediaOutput::class.java)
            row.copy(payloadJson = gson.toJson(output.copy(localUri = null)))
        },
        assets = dao.allAssets().filter { it.id in referencedAssetIds }, refs = refs,
        migrations = dao.allMigrations(), meta = dao.allMeta())
}

private suspend fun AgentWorkspaceRepository.exportUnlocked(destination: File): AgentWorkspaceBackup {
    require(!destination.exists() || destination.listFiles().isNullOrEmpty()) { "Backup destination must be empty" }
    destination.mkdirs()
    val payload = snapshot()
    validate(payload)
    for (asset in payload.assets) {
        if (asset.missing) continue
        val source = assetFile(asset.relativePath)
        require(source.isFile && workspaceHash(source.readBytes()) == asset.sha256) { "Attachment missing or changed" }
        val target = containedFile(destination, asset.relativePath)
        target.parentFile!!.mkdirs()
        source.copyTo(target)
    }
    File(destination, "workspace.json").writeText(gson.toJson(payload), Charsets.UTF_8)
    return payload
}

suspend fun AgentWorkspaceRepository.exportTo(destination: File): AgentWorkspaceBackup =
    locked { exportUnlocked(destination) }

private fun AgentWorkspaceRepository.validate(payload: AgentWorkspaceBackup) {
    require(payload.version == 1) { "Unsupported workspace backup version" }
    fun <T> unique(rows: List<T>, key: (T) -> String) {
        require(rows.map(key).distinct().size == rows.size) { "Duplicate backup identity" }
    }
    fun scope(value: String) = value == "local" || value.matches(Regex("cloud@[a-zA-Z0-9._-]+"))
    unique(payload.projects) { it.id }
    unique(payload.conversations) { it.id }
    unique(payload.states) { it.conversationId }
    unique(payload.messages) { workspaceId(it.conversationId, it.id) }
    unique(payload.messages) { workspaceId(it.conversationId, it.sequence.toString()) }
    unique(payload.executions) { it.id }
    unique(payload.media) { workspaceId(it.conversationId, it.mediaKey) }
    unique(payload.assets) { it.id }
    unique(payload.assets) { it.relativePath }
    unique(payload.refs) { it.id }
    unique(payload.migrations) { workspaceId(it.scope, it.version.toString()) }
    unique(payload.meta) { it.key }
    val projects = payload.projects.associateBy { it.id }
    val conversations = payload.conversations.associateBy { it.id }
    val assets = payload.assets.associateBy { it.id }
    val executions = payload.executions.associateBy { it.id }
    val messages = payload.messages.associateBy { workspaceId(it.conversationId, it.id) }
    payload.projects.forEach { require(it.id.isNotBlank() && scope(it.scope) && it.name.isNotBlank()) }
    payload.conversations.forEach { row ->
        require(row.id.isNotBlank() && scope(row.scope) && row.mode in setOf("draw", "chat"))
        row.projectId?.let { require(projects[it]?.scope == row.scope) { "Project scope mismatch" } }
    }
    payload.states.forEach {
        require(it.schemaVersion == 1 && conversations.containsKey(it.conversationId))
        listOf(it.workflowStateJson, it.promptOutputJson, it.draftJson, it.scrollAnchorJson).forEach(::validObject)
        val draft = com.google.gson.JsonParser.parseString(it.draftJson).asJsonObject
        draft["attachments"]?.asJsonArray?.forEach { element ->
            val attachment = gson.fromJson(element, com.comfyui.mobile.model.AgentAttachment::class.java)
            require(attachment.uri.startsWith(AgentWorkspaceRepository.ASSET_URI))
            val asset = attachment.uri.removePrefix(AgentWorkspaceRepository.ASSET_URI)
            require(assets.containsKey(asset) && payload.refs.any { ref ->
                ref.assetId == asset && ref.ownerType == "draft" && ref.conversationId == it.conversationId
            }) { "Missing draft asset ownership" }
        }
    }
    require(payload.states.map { it.conversationId }.toSet() == conversations.keys) { "Incomplete conversation state" }
    payload.messages.forEach {
        require(it.payloadVersion == 1 && it.sequence >= 0 && conversations.containsKey(it.conversationId))
        val msg = gson.fromJson(it.payloadJson, AgentChatMessage::class.java)
        require(msg.id == it.id && msg.role.isNotBlank())
        msg.attachments.orEmpty().forEach { att ->
            require(att.uri.startsWith(AgentWorkspaceRepository.ASSET_URI))
            val assetId = att.uri.removePrefix(AgentWorkspaceRepository.ASSET_URI)
            require(assets.containsKey(assetId)) { "Missing message asset" }
            require(payload.refs.any { ref -> ref.assetId == assetId && ref.ownerType == "message" &&
                ref.conversationId == it.conversationId && ref.messageId == it.id }) { "Missing message asset ownership" }
        }
    }
    payload.executions.forEach {
        require(scope(it.scope) && it.id.isNotBlank() && it.targetKey.isNotBlank())
        it.conversationId?.let { id -> require(conversations[id]?.scope == it.scope) { "Execution scope mismatch" } }
        validObject(it.snapshotJson)
    }
    payload.media.forEach {
        require(conversations.containsKey(it.conversationId))
        it.executionId?.let { id -> require(executions[id]?.conversationId == it.conversationId) { "Media owner mismatch" } }
        require(gson.fromJson(it.payloadJson, MediaOutput::class.java).localUri == null) { "Nonportable media URI" }
    }
    payload.assets.forEach {
        assetFile(it.relativePath)
        require(it.id.isNotBlank() && it.sizeBytes >= 0)
        require(it.missing || it.sha256?.matches(Regex("[0-9a-f]{64}")) == true) { "Invalid asset checksum" }
    }
    payload.refs.forEach {
        require(assets.containsKey(it.assetId))
        when (it.ownerType) {
            "message" -> require(it.projectId == null && it.conversationId != null &&
                messages.containsKey(workspaceId(it.conversationId, it.messageId.orEmpty())))
            "draft" -> require(it.projectId == null && it.messageId == null && conversations.containsKey(it.conversationId))
            "project" -> require(it.conversationId == null && it.messageId == null && projects.containsKey(it.projectId))
            else -> error("Unknown asset owner type")
        }
    }
    payload.migrations.forEach { require(it.version == 1 && scope(it.scope)) }
}

/** Preflight is also used before touching global settings in the enclosing backup container. */
suspend fun AgentWorkspaceRepository.validateBackup(source: File) = locked {
    val payload = gson.fromJson(File(source, "workspace.json").readText(Charsets.UTF_8), AgentWorkspaceBackup::class.java)
    validate(payload)
    payload.assets.filterNot { it.missing }.forEach { asset ->
        val file = containedFile(source, asset.relativePath)
        require(file.isFile && file.length() == asset.sizeBytes &&
            workspaceHash(file.readBytes()) == asset.sha256) { "Backup attachment checksum mismatch" }
    }
    payload.conversations.size
}

/**
 * An old package with no workspace.json is a no-op. Replacement needs explicit confirmation.
 * Files are staged under fresh paths; the DB replacement is atomic and the old package is retained.
 */
suspend fun AgentWorkspaceRepository.restoreFrom(
    source: File, confirmed: Boolean
): AgentWorkspaceRestoreResult = locked {
    val manifest = File(source, "workspace.json")
    if (!manifest.isFile) return@locked AgentWorkspaceRestoreResult(0, null, skipped = true)
    require(confirmed) { "Workspace replacement needs confirmation" }
    val payload = gson.fromJson(manifest.readText(Charsets.UTF_8), AgentWorkspaceBackup::class.java)
    validate(payload)
    // Validate every source before creating any restored files.
    payload.assets.filterNot { it.missing }.forEach { asset ->
        val file = containedFile(source, asset.relativePath)
        require(file.isFile && file.length() == asset.sizeBytes &&
            workspaceHash(file.readBytes()) == asset.sha256) { "Backup attachment checksum mismatch" }
    }
    val restoreId = UUID.randomUUID().toString()
    val stagedFiles = mutableListOf<File>()
    var installed = false
    try {
        val mappedAssets = payload.assets.map { asset ->
            val relative = "assets/restore-$restoreId/${workspaceId(asset.id)}"
            if (!asset.missing) {
                val file = containedFile(source, asset.relativePath)
                val target = assetFile(relative)
                target.parentFile!!.mkdirs()
                stagedFiles += target
                file.copyTo(target)
            }
            asset.copy(relativePath = relative)
        }
        val previous = File(directory, "restore-backups/$restoreId")
        exportUnlocked(previous)
        val epoch = epochUnlocked() + 1
        database.withTransaction {
            dao.clearRefs()
            dao.clearMedia()
            dao.clearExecutions()
            dao.clearConversations()
            dao.clearProjects()
            dao.clearAssets()
            dao.clearMigrations()
            dao.clearMeta()
            payload.projects.forEach { dao.putProject(it) }
            payload.conversations.forEach { dao.putConversation(it) }
            payload.states.forEach { dao.putState(it) }
            dao.insertMessages(payload.messages.map { row ->
                val message = gson.fromJson(row.payloadJson, AgentChatMessage::class.java)
                row.copy(payloadJson = gson.toJson(message.copy(isThinking = false,
                    errorMessage = if (message.isThinking) "上次回复已中断" else message.errorMessage)))
            })
            mappedAssets.forEach { dao.putAsset(it) }
            payload.refs.forEach { dao.putRef(it) }
            payload.executions.forEach { dao.putExecution(it.copy(workspaceEpoch = epoch)) }
            payload.media.forEach { row ->
                val media = gson.fromJson(row.payloadJson, MediaOutput::class.java)
                dao.putMedia(row.copy(payloadJson = gson.toJson(media.copy(workspaceEpoch = epoch))))
            }
            payload.migrations.forEach { dao.putMigration(it) }
            payload.meta.filterNot { it.key == "epoch" }.forEach { dao.putMeta(it) }
            dao.putMeta(AgentWorkspaceMeta("epoch", epoch.toString()))
        }
        installed = true
        AgentWorkspaceRestoreResult(payload.conversations.size, previous)
    } catch (error: Throwable) {
        if (!installed) stagedFiles.forEach { it.delete() }
        throw error
    }
}
