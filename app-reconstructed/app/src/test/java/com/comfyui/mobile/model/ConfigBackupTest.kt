package com.comfyui.mobile.model

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBackupTest {

    private val gson = Gson()

    @Test
    fun testBackupDtoKeyCounting() {
        val server1 = ServerProfile(
            id = "rh-1",
            serverType = ServerType.RUNNINGHUB,
            rhApiKey = "rh_key_12345"
        )
        val server2 = ServerProfile(
            id = "rh-2",
            serverType = ServerType.RUNNINGHUB,
            rhApiKey = ""
        )
        val server3 = ServerProfile(
            id = "local-1",
            serverType = ServerType.LOCAL_COMFYUI,
            rhApiKey = "should_be_ignored_for_local"
        )

        val llm1 = LlmProfile(
            id = "llm-1",
            name = "DeepSeek",
            apiKey = "sk-deepseek-123"
        )
        val llm2 = LlmProfile(
            id = "llm-2",
            name = "OpenAI",
            apiKey = ""
        )

        val dtoWithKeys = ConfigBackupDto(
            servers = listOf(server1, server2, server3),
            llmProfiles = listOf(llm1, llm2),
            includeKeys = true
        )

        assertEquals(1, dtoWithKeys.rhKeysCount)
        assertEquals(1, dtoWithKeys.llmKeysCount)
        assertEquals(2, dtoWithKeys.totalKeysCount)
        assertTrue(dtoWithKeys.hasKeys)

        val dtoWithoutKeys = ConfigBackupDto(
            servers = listOf(server2),
            llmProfiles = listOf(llm2),
            includeKeys = false
        )

        assertEquals(0, dtoWithoutKeys.rhKeysCount)
        assertEquals(0, dtoWithoutKeys.llmKeysCount)
        assertEquals(0, dtoWithoutKeys.totalKeysCount)
        assertFalse(dtoWithoutKeys.hasKeys)
    }

    @Test
    fun testBackupSerializationWithKeysPreserved() {
        val backup = ConfigBackupDto(
            version = 1,
            servers = listOf(
                ServerProfile(
                    id = "srv-1",
                    serverType = ServerType.RUNNINGHUB,
                    rhSite = RunningHubSite.DOMESTIC_CN,
                    rhApiKey = "runninghub_secret_key"
                )
            ),
            llmProfiles = listOf(
                LlmProfile(
                    id = "profile-1",
                    name = "Claude",
                    apiKey = "sk-claude-test-key"
                )
            ),
            includeKeys = true
        )

        val json = gson.toJson(backup)
        val restored = gson.fromJson(json, ConfigBackupDto::class.java)

        assertTrue(restored.hasKeys)
        assertEquals(1, restored.rhKeysCount)
        assertEquals(1, restored.llmKeysCount)
        assertEquals("runninghub_secret_key", restored.servers?.first()?.effectiveRhApiKey)
        assertEquals("sk-claude-test-key", restored.llmProfiles?.first()?.apiKey)
        assertTrue(restored.includeKeys)
    }

    @Test
    fun testLegacyBackupDeserializationCompatibility() {
        // 模拟旧版备份（无 includeKeys 字段，且 keys 为空串）
        val legacyJson = """
            {
                "version": 1,
                "exportTime": 1700000000000,
                "servers": [
                    {
                        "id": "srv-old",
                        "serverType": "RUNNINGHUB",
                        "rhApiKey": ""
                    }
                ],
                "llmProfiles": [
                    {
                        "id": "llm-old",
                        "apiKey": ""
                    }
                ]
            }
        """.trimIndent()

        val restored = gson.fromJson(legacyJson, ConfigBackupDto::class.java)
        assertEquals(0, restored.totalKeysCount)
        assertFalse(restored.hasKeys)
        assertEquals("", restored.servers?.first()?.effectiveRhApiKey)
        assertEquals("", restored.llmProfiles?.first()?.apiKey)
    }
}
