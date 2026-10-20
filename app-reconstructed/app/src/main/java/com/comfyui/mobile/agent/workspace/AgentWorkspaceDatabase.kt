package com.comfyui.mobile.agent.workspace

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Internal storage API. Production UI must use the repository, not this DAO. */
@Dao
abstract class AgentWorkspaceDao {
    @Query("SELECT * FROM agent_conversations WHERE scope = :scope ORDER BY updatedAt DESC, id ASC")
    abstract fun observeConversations(scope: String): Flow<List<AgentConversation>>
    @Query("SELECT * FROM agent_conversations WHERE id = :id")
    abstract suspend fun conversation(id: String): AgentConversation?
    @Query("SELECT * FROM agent_conversation_state WHERE conversationId = :id")
    abstract suspend fun state(id: String): AgentConversationState?
    @Query("SELECT * FROM agent_messages WHERE conversationId = :id ORDER BY sequence")
    abstract suspend fun messages(id: String): List<AgentMessage>
    @Query("SELECT * FROM agent_conversation_media WHERE conversationId = :id ORDER BY createdAt DESC, mediaKey")
    abstract suspend fun media(id: String): List<AgentConversationMedia>
    @Query("SELECT * FROM agent_execution_links WHERE id = :id")
    abstract suspend fun execution(id: String): AgentExecutionLink?
    @Query("SELECT * FROM agent_execution_links WHERE targetKey = :target AND remoteId = :remote")
    abstract suspend fun executionByRemote(target: String, remote: String): AgentExecutionLink?
    @Query("SELECT * FROM agent_migrations WHERE scope = :scope AND version = 1")
    abstract suspend fun migration(scope: String): AgentMigrationState?
    @Query("SELECT value FROM agent_workspace_meta WHERE `key` = :key")
    abstract suspend fun meta(key: String): String?
    @Query("SELECT * FROM agent_assets WHERE id = :id")
    abstract suspend fun asset(id: String): AgentAsset?
    @Query("SELECT * FROM agent_asset_refs WHERE assetId = :id")
    abstract suspend fun refs(id: String): List<AgentAssetRef>
    @Query("SELECT * FROM agent_assets WHERE id NOT IN (SELECT assetId FROM agent_asset_refs)")
    abstract suspend fun unreferencedAssets(): List<AgentAsset>

    @Upsert abstract suspend fun putProject(value: AgentProject)
    @Upsert abstract suspend fun putConversation(value: AgentConversation)
    @Upsert abstract suspend fun putState(value: AgentConversationState)
    @Insert abstract suspend fun insertMessages(values: List<AgentMessage>)
    @Insert abstract suspend fun putExecution(value: AgentExecutionLink)
    @Update abstract suspend fun updateExecution(value: AgentExecutionLink)
    @Upsert abstract suspend fun putMedia(value: AgentConversationMedia)
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun putAsset(value: AgentAsset)
    @Upsert abstract suspend fun putRef(value: AgentAssetRef)
    @Insert abstract suspend fun putMigration(value: AgentMigrationState)
    @Upsert abstract suspend fun putMeta(value: AgentWorkspaceMeta)

    @Query("DELETE FROM agent_messages WHERE conversationId = :id")
    abstract suspend fun deleteMessages(id: String)
    @Query("DELETE FROM agent_conversations WHERE id = :id")
    abstract suspend fun deleteConversation(id: String)
    @Query("UPDATE agent_execution_links SET conversationId = NULL, detachedAt = :now, updatedAt = :now WHERE conversationId = :id")
    abstract suspend fun detachExecutions(id: String, now: Long)
    @Query("DELETE FROM agent_assets WHERE id = :id AND id NOT IN (SELECT assetId FROM agent_asset_refs)")
    abstract suspend fun deleteUnreferencedAsset(id: String): Int

    @Query("SELECT * FROM agent_projects ORDER BY id") abstract suspend fun allProjects(): List<AgentProject>
    @Query("SELECT * FROM agent_conversations ORDER BY id") abstract suspend fun allConversations(): List<AgentConversation>
    @Query("SELECT * FROM agent_conversation_state ORDER BY conversationId") abstract suspend fun allStates(): List<AgentConversationState>
    @Query("SELECT * FROM agent_messages ORDER BY conversationId, sequence") abstract suspend fun allMessages(): List<AgentMessage>
    @Query("SELECT * FROM agent_execution_links ORDER BY id") abstract suspend fun allExecutions(): List<AgentExecutionLink>
    @Query("SELECT * FROM agent_conversation_media ORDER BY conversationId, mediaKey") abstract suspend fun allMedia(): List<AgentConversationMedia>
    @Query("SELECT * FROM agent_assets ORDER BY id") abstract suspend fun allAssets(): List<AgentAsset>
    @Query("SELECT * FROM agent_asset_refs ORDER BY id") abstract suspend fun allRefs(): List<AgentAssetRef>
    @Query("SELECT * FROM agent_migrations ORDER BY scope") abstract suspend fun allMigrations(): List<AgentMigrationState>
    @Query("SELECT * FROM agent_workspace_meta ORDER BY `key`") abstract suspend fun allMeta(): List<AgentWorkspaceMeta>

    @Query("DELETE FROM agent_conversation_media") abstract suspend fun clearMedia()
    @Query("DELETE FROM agent_asset_refs") abstract suspend fun clearRefs()
    @Query("DELETE FROM agent_execution_links") abstract suspend fun clearExecutions()
    @Query("DELETE FROM agent_conversations") abstract suspend fun clearConversations()
    @Query("DELETE FROM agent_projects") abstract suspend fun clearProjects()
    @Query("DELETE FROM agent_assets") abstract suspend fun clearAssets()
    @Query("DELETE FROM agent_migrations") abstract suspend fun clearMigrations()
    @Query("DELETE FROM agent_workspace_meta") abstract suspend fun clearMeta()
}

@Database(
    entities = [
        AgentProject::class, AgentConversation::class, AgentConversationState::class,
        AgentMessage::class, AgentExecutionLink::class, AgentConversationMedia::class,
        AgentAsset::class, AgentAssetRef::class, AgentMigrationState::class, AgentWorkspaceMeta::class
    ],
    version = 1, exportSchema = true
)
abstract class AgentWorkspaceDatabase : RoomDatabase() {
    abstract fun workspace(): AgentWorkspaceDao

    companion object {
        /** Deliberately not opened at app startup until the P1 production adapter is complete. */
        fun open(context: Context): AgentWorkspaceDatabase = Room.databaseBuilder(
            context.applicationContext, AgentWorkspaceDatabase::class.java, "agent_workspace.db"
        ).build()
    }
}
