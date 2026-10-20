package com.comfyui.mobile.agent.workspace

import androidx.room.Room
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Uses the pre-P2 API too, so these same tests can run against the preserved source baseline. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentSessionOrderingRegressionTest {
    private fun withRepository(block: suspend (AgentWorkspaceRepository) -> Unit) = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, AgentWorkspaceDatabase::class.java).build()
        try {
            block(AgentWorkspaceRepository(db, File(context.filesDir, "ordering"),
                File(context.filesDir, "legacy")))
        } finally { db.close() }
    }

    @Test fun deletingPrimaryNeverReusesTheDeletedSessionIdentity() = withRepository { repo ->
        val empty = repo.primaryConversation("local")
        repo.saveConversation(empty)
        repo.deleteConversation(empty.id)
        assertNotEquals("A new session must not accept callbacks owned by a deleted ID",
            empty.id, repo.primaryConversation("local").id)
    }

    @Test fun deletionFallbackChoosesMostRecentNotAlphabeticalIdAndKeepsOtherScopesOut() = withRepository { repo ->
        val time = System.currentTimeMillis()
        repo.saveConversation(AgentConversation("zzz-old", "local", "old", createdAt = time, updatedAt = time))
        Thread.sleep(10)
        repo.saveConversation(AgentConversation("aaa-recent", "local", "recent", createdAt = time, updatedAt = time))
        repo.saveConversation(AgentConversation("cloud", "cloud@ai", "cloud", createdAt = time, updatedAt = time))
        repo.saveConversation(AgentConversation("current", "local", "current", createdAt = time, updatedAt = time))
        repo.deleteConversation("current")
        assertEquals("aaa-recent", repo.primaryConversation("local").id)
    }
}
