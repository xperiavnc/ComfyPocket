package com.comfyui.mobile.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerProfileTest {

    @Test
    fun testIsDefaultRhName() {
        // Default / empty names
        assertTrue(ServerProfile.isDefaultRhName(null))
        assertTrue(ServerProfile.isDefaultRhName(""))
        assertTrue(ServerProfile.isDefaultRhName("   "))
        assertTrue(ServerProfile.isDefaultRhName("RunningHub"))
        assertTrue(ServerProfile.isDefaultRhName("RunningHub (CN站)"))
        assertTrue(ServerProfile.isDefaultRhName("RunningHub (AI站)"))
        assertTrue(ServerProfile.isDefaultRhName("RunningHub CN站"))
        assertTrue(ServerProfile.isDefaultRhName("RunningHub AI站"))

        // Legacy auto-generated names
        assertTrue(ServerProfile.isDefaultRhName("RunningHub CN站 (标准 (24G))"))
        assertTrue(ServerProfile.isDefaultRhName("RunningHub AI站 (Plus (48G))"))
        assertTrue(ServerProfile.isDefaultRhName("RunningHub CN站 (Ultra (84G))"))

        // Custom names should NOT be recognized as default
        assertFalse(ServerProfile.isDefaultRhName("我的跑图机"))
        assertFalse(ServerProfile.isDefaultRhName("SDXL 云端专用"))
        assertFalse(ServerProfile.isDefaultRhName("公司服务器"))
    }

    @Test
    fun testCloudServerDisplayName() {
        // Without custom name (null or blank)
        val defaultCn = ServerProfile(
            serverType = ServerType.RUNNINGHUB,
            rhSite = RunningHubSite.DOMESTIC_CN,
            rhGpuTier = RunningHubGpuTier.STANDARD_24G,
        )
        assertEquals("RunningHub (CN站)", defaultCn.displayName)

        val defaultAi = ServerProfile(
            serverType = ServerType.RUNNINGHUB,
            rhSite = RunningHubSite.OVERSEAS_AI,
            rhGpuTier = RunningHubGpuTier.PLUS_48G,
        )
        assertEquals("RunningHub (AI站)", defaultAi.displayName)

        // Legacy auto-generated name should be cleaned up to concise display
        val legacyProfile = ServerProfile(
            name = "RunningHub CN站 (标准 (24G))",
            serverType = ServerType.RUNNINGHUB,
            rhSite = RunningHubSite.DOMESTIC_CN,
            rhGpuTier = RunningHubGpuTier.STANDARD_24G,
        )
        assertEquals("RunningHub (CN站)", legacyProfile.displayName)

        // Custom name should be preserved
        val customProfile = ServerProfile(
            name = "深度学习服务器",
            serverType = ServerType.RUNNINGHUB,
            rhSite = RunningHubSite.DOMESTIC_CN,
        )
        assertEquals("深度学习服务器", customProfile.displayName)
    }

    @Test
    fun testLocalServerDisplayName() {
        val defaultLocal = ServerProfile(
            serverType = ServerType.LOCAL_COMFYUI,
            host = "192.168.1.100",
        )
        assertEquals("192.168.1.100", defaultLocal.displayName)

        val customLocal = ServerProfile(
            name = "客厅主机",
            serverType = ServerType.LOCAL_COMFYUI,
            host = "192.168.1.100",
        )
        assertEquals("客厅主机", customLocal.displayName)

        val emptyLocal = ServerProfile(
            serverType = ServerType.LOCAL_COMFYUI,
            host = "",
        )
        assertEquals("本地服务器", emptyLocal.displayName)
    }

    @Test
    fun testParseErrorToCode() {
        val (c1, m1) = com.comfyui.mobile.ui.screens.ServerConnectionTestHelper.parseErrorToCode("HTTP 401: Unauthorized access")
        assertEquals("401 UNAUTHORIZED", c1)
        assertEquals("Unauthorized access", m1)

        val (c2, m2) = com.comfyui.mobile.ui.screens.ServerConnectionTestHelper.parseErrorToCode("RunningHub API Key 无效，请检查配置 (HTTP 401)")
        assertEquals("401 UNAUTHORIZED", c2)
        assertEquals("RunningHub API Key 无效，请检查配置", m2)

        val (c3, m3) = com.comfyui.mobile.ui.screens.ServerConnectionTestHelper.parseErrorToCode("错误代码 802")
        assertEquals("CODE 802 (KEY_INVALID)", c3)

        val (c4, m4) = com.comfyui.mobile.ui.screens.ServerConnectionTestHelper.parseErrorToCode("连接超时，请重试")
        assertEquals("408 TIMEOUT", c4)

        val (c5, m5) = com.comfyui.mobile.ui.screens.ServerConnectionTestHelper.parseErrorToCode("Key属于AI站(USD)，站点不匹配")
        assertEquals("SITE_MISMATCH", c5)
        assertEquals("Key属于AI站(USD)，站点不匹配", m5)
    }

    @Test
    fun testRunningHubSiteFromUrl() {
        assertEquals(RunningHubSite.DOMESTIC_CN, RunningHubSite.fromUrl("https://www.runninghub.cn/post/123456"))
        assertEquals(RunningHubSite.DOMESTIC_CN, RunningHubSite.fromUrl("https://runninghub.cn/workflow/123456"))
        assertEquals(RunningHubSite.OVERSEAS_AI, RunningHubSite.fromUrl("https://www.runninghub.ai/post/987654"))
        assertEquals(RunningHubSite.OVERSEAS_AI, RunningHubSite.fromUrl("https://runninghub.ai/workflow/987654"))
        assertEquals(null, RunningHubSite.fromUrl("1234567890"))
        assertEquals(null, RunningHubSite.fromUrl(""))
        assertEquals(null, RunningHubSite.fromUrl(null))
    }

    @Test
    fun testEffectiveRhSiteInference() {
        val aiInferred = ServerProfile(
            serverType = ServerType.RUNNINGHUB,
            rhSite = null,
            host = "https://www.runninghub.ai"
        )
        assertEquals(RunningHubSite.OVERSEAS_AI, aiInferred.effectiveRhSite)

        val cnInferred = ServerProfile(
            serverType = ServerType.RUNNINGHUB,
            rhSite = null,
            host = "https://www.runninghub.cn"
        )
        assertEquals(RunningHubSite.DOMESTIC_CN, cnInferred.effectiveRhSite)

        val explicitOverHost = ServerProfile(
            serverType = ServerType.RUNNINGHUB,
            rhSite = RunningHubSite.OVERSEAS_AI,
            host = "https://www.runninghub.cn"
        )
        assertEquals(RunningHubSite.OVERSEAS_AI, explicitOverHost.effectiveRhSite)
    }
}
