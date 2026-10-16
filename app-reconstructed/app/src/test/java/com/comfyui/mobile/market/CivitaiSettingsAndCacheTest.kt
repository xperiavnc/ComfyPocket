package com.comfyui.mobile.market

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CivitaiSettingsAndCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun proxyAcceptsUrlOrHostPortAndPreservesPasswordWhitespace() {
        val value = CivitaiSettings(proxyEnabled = true, proxyAddress = " 127.0.0.1:7890 ",
            proxyPassword = " password ").validated()
        assertEquals("127.0.0.1", value.proxyUrl()?.host)
        assertEquals(7890, value.proxyUrl()?.port)
        assertEquals(" password ", value.proxyPassword)
    }

    @Test fun proxyRejectsOtherSchemesEmbeddedCredentialsAndNonProxyUrls() {
        listOf("", "socks5://localhost:1080", "https://localhost:7890",
            "http://user:password@localhost:7890", "http://localhost:7890/path",
            "http://localhost:7890/?token=secret", "http://localhost:7890/#fragment",
            "http://localhost:99999").forEach {
            assertTrue(it, runCatching { CivitaiSettings(proxyEnabled = true, proxyAddress = it).validated() }.isFailure)
        }
    }

    @Test fun noProxyDoesNotValidateAnUnusedAddress() {
        assertNull(CivitaiSettings(proxyAddress = "not-used").validated().proxyUrl())
    }

    @Test fun keyMustBeRawAndCannotInjectHeaders() {
        listOf("Bearer token", "a\nb", "a\rb", "key with spaces").forEach {
            assertTrue(runCatching { CivitaiSettings(apiKey = it).validated() }.isFailure)
        }
        assertEquals("key", CivitaiSettings(apiKey = " key ").validated().apiKey)
    }

    @Test fun diagnosticsRedactAllCredentialValues() {
        val value = CivitaiSettings(apiKey = "private-personal-key", proxyPassword = "private-proxy-password")
        assertFalse(value.toString().contains("private-personal-key"))
        assertFalse(value.toString().contains("private-proxy-password"))
    }

    @Test fun cacheSeparatesAccountsContentLevelAndBridgeServers() {
        val first = CivitaiSettings(apiKey = "key-a")
        assertNotEquals(first.cacheNamespace(), first.copy(apiKey = "key-b").cacheNamespace())
        assertNotEquals(first.cacheNamespace(), first.copy(includeMatureContent = true).cacheNamespace())
        assertEquals(first.cacheNamespace("server-a"), first.cacheNamespace("server-b"))
        val bridge = first.copy(mode = MarketConnectionMode.BRIDGE)
        assertNotEquals(bridge.cacheNamespace("server-a"), bridge.cacheNamespace("server-b"))
        assertFalse(first.cacheNamespace().contains("key-a"))
    }

    @Test fun cacheRoundTripsAndDetectsExpiryAndClockChanges() {
        var now = 1000L
        val cache = MarketDiskCache(temporary.newFolder(), { now })
        cache.put("account/query", """{"items":[]}""")
        val entry = cache.get("account/query")!!
        assertEquals("""{"items":[]}""", entry.body)
        assertTrue(entry.fresh(1100, 500))
        assertFalse(entry.fresh(1500, 500))
        assertFalse(entry.fresh(900, 500))
        assertNull(cache.get("another-account/query"))
    }

    @Test fun corruptCacheIsIgnoredWithoutBreakingSearch() {
        val directory = temporary.newFolder()
        File(directory, marketHash("broken") + ".json").writeText("invalid")
        assertNull(MarketDiskCache(directory).get("broken"))
    }

    @Test fun cacheLimitsCountAndSizeAndDoesNotExposeKeysInFileNames() {
        val directory = temporary.newFolder()
        val cache = MarketDiskCache(directory, maxEntries = 2, maxBytes = 1024)
        repeat(5) { cache.put("private-key-$it", "small-response-$it") }
        assertTrue(directory.listFiles().orEmpty().size <= 2)
        assertTrue(directory.listFiles().orEmpty().none { it.name.contains("private-key") })
        cache.put("too-large", "x".repeat(2000))
        assertNull(cache.get("too-large"))
    }
}
