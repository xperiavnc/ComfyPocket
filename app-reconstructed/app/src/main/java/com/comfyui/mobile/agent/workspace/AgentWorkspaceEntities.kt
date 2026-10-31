package com.comfyui.mobile.agent.workspace

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "agent_projects", indices = [Index(value = ["id", "scope"], unique = true)])
data class AgentProject(
    @PrimaryKey val id: String,
    val scope: String,
    val name: String,
    val description: String = "",
    val instructions: String = "",
    val defaultWorkflowRef: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val archivedAt: Long? = null
)

@Entity(
    tableName = "agent_conversations",
    foreignKeys = [ForeignKey(
        entity = AgentProject::class, parentColumns = ["id", "scope"],
        childColumns = ["projectId", "scope"], onDelete = ForeignKey.RESTRICT
    )],
    indices = [Index(value = ["projectId", "scope"]), Index(value = ["scope", "updatedAt"])]
)
data class AgentConversation(
    @PrimaryKey val id: String,
    val scope: String,
    val title: String,
    val projectId: String? = null,
    val mode: String = "draw",
    val llmProfileId: String? = null,
    val executionTargetRef: String? = null,
    val workflowRef: String? = null,
    val lastMessagePreview: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    /** Archived sessions leave the active sidebar list but keep every message and asset. */
    val archivedAt: Long? = null
)

@Entity(
    tableName = "agent_conversation_state",
    foreignKeys = [ForeignKey(
        entity = AgentConversation::class, parentColumns = ["id"],
        childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE
    )]
)
data class AgentConversationState(
    @PrimaryKey val conversationId: String,
    val workflowStateJson: String = "{}",
    val promptOutputJson: String = "{}",
    val draftJson: String = "{}",
    val scrollAnchorJson: String = "{}",
    val schemaVersion: Int = 1
)

@Entity(
    tableName = "agent_messages", primaryKeys = ["conversationId", "id"],
    foreignKeys = [ForeignKey(
        entity = AgentConversation::class, parentColumns = ["id"],
        childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["conversationId", "sequence"], unique = true)]
)
data class AgentMessage(
    val conversationId: String,
    val id: String,
    val sequence: Long,
    val payloadJson: String,
    val createdAt: Long,
    val payloadVersion: Int = 1
)

@Entity(
    tableName = "agent_execution_links",
    foreignKeys = [ForeignKey(
        entity = AgentConversation::class, parentColumns = ["id"],
        childColumns = ["conversationId"], onDelete = ForeignKey.SET_NULL
    )],
    indices = [
        Index("conversationId"),
        Index(value = ["targetKey", "remoteId"], unique = true)
    ]
)
data class AgentExecutionLink(
    @PrimaryKey val id: String,
    val turnId: String,
    val conversationId: String?,
    val scope: String,
    val targetKey: String,
    val backend: String,
    val serverId: String? = null,
    val siteId: String? = null,
    val remoteId: String? = null,
    val taskLocalId: String? = null,
    val submissionState: String = "reserved",
    val snapshotJson: String = "{}",
    val workspaceEpoch: Long = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val detachedAt: Long? = null
)

@Entity(
    tableName = "agent_conversation_media", primaryKeys = ["conversationId", "mediaKey"],
    foreignKeys = [
        ForeignKey(entity = AgentConversation::class, parentColumns = ["id"],
            childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = AgentExecutionLink::class, parentColumns = ["id"],
            childColumns = ["executionId"], onDelete = ForeignKey.SET_NULL)
    ],
    indices = [Index("executionId")]
)
data class AgentConversationMedia(
    val conversationId: String,
    val mediaKey: String,
    val sourceDescriptor: String,
    val payloadJson: String,
    val executionId: String? = null,
    val createdAt: Long
)

@Entity(tableName = "agent_assets", indices = [Index(value = ["relativePath"], unique = true)])
data class AgentAsset(
    @PrimaryKey val id: String,
    val relativePath: String,
    val name: String,
    val kind: String,
    val sizeBytes: Long,
    val sha256: String?,
    val missing: Boolean = false,
    val createdAt: Long
)

@Entity(
    tableName = "agent_asset_refs",
    foreignKeys = [
        ForeignKey(entity = AgentAsset::class, parentColumns = ["id"],
            childColumns = ["assetId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = AgentConversation::class, parentColumns = ["id"],
            childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = AgentProject::class, parentColumns = ["id"],
            childColumns = ["projectId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = AgentMessage::class, parentColumns = ["conversationId", "id"],
            childColumns = ["conversationId", "messageId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [
        Index("assetId"), Index("projectId"), Index(value = ["conversationId", "messageId"])
    ]
)
data class AgentAssetRef(
    @PrimaryKey val id: String,
    val assetId: String,
    val ownerType: String,
    val ownerKey: String,
    val conversationId: String? = null,
    val messageId: String? = null,
    val projectId: String? = null
)

@Entity(tableName = "agent_migrations", primaryKeys = ["scope", "version"])
data class AgentMigrationState(
    val scope: String,
    val version: Int = 1,
    val sourceFingerprint: String,
    val conversationId: String?,
    val messageCount: Int,
    val mediaCount: Int,
    val missingAssetCount: Int,
    val completedAt: Long
)

@Entity(tableName = "agent_workspace_meta")
data class AgentWorkspaceMeta(@PrimaryKey val key: String, val value: String)
