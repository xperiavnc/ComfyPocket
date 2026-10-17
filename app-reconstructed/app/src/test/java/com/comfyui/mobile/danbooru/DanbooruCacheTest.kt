package com.comfyui.mobile.danbooru

import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class DanbooruCacheTest {
    private val dir: File = kotlin.io.path.createTempDirectory("danbooru-cache").toFile()
    @After fun close() { dir.deleteRecursively() }

    @Test fun normalizedKeysAndProxyNamespacesAreIsolated() {
        val cache = DanbooruCache(dir, now = { 10_000 })
        val result = DanbooruApiResult(LookupStatus.NOT_FOUND, fetchedAt = 10_000)
        cache.put("direct", DanbooruApiType.TAG, "Blue Hair", result)
        assertNotNull(cache.get("direct", DanbooruApiType.TAG, "blue_hair"))
        assertNull(cache.get("proxy", DanbooruApiType.TAG, "blue_hair"))
        assertNull(cache.get("direct", DanbooruApiType.ALIAS, "blue_hair"))
    }
    @Test fun tagExpiresAfterOneDayAndWikiAfterSevenDays() {
        var clock = 100L
        val cache = DanbooruCache(dir, now = { clock })
        val result = DanbooruApiResult(LookupStatus.FOUND, fetchedAt = clock)
        cache.put("a", DanbooruApiType.TAG, "cat", result)
        cache.put("a", DanbooruApiType.WIKI, "cat", result)
        clock += 86_400_000
        assertNull(cache.get("a", DanbooruApiType.TAG, "cat"))
        assertNotNull(cache.get("a", DanbooruApiType.WIKI, "cat"))
        clock += 6 * 86_400_000
        assertNull(cache.get("a", DanbooruApiType.WIKI, "cat"))
    }
    @Test fun capacityIsBoundedAndNetworkFailuresAreNotCached() {
        val cache = DanbooruCache(dir, now = { 1_000 }, maxEntries = 2)
        repeat(5) { cache.put("a", DanbooruApiType.TAG, "term$it", DanbooruApiResult(LookupStatus.NOT_FOUND, fetchedAt = 1_000)) }
        assertTrue(dir.listFiles()!!.size <= 2)
        cache.put("a", DanbooruApiType.TAG, "bad", DanbooruApiResult(LookupStatus.NETWORK_ERROR, fetchedAt = 1_000))
        assertNull(cache.get("a", DanbooruApiType.TAG, "bad"))
    }
}
