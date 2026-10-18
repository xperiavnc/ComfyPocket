package com.comfyui.mobile.modelcenter

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalModelCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val item = LocalModel(id = "a", serverId = "bridge-a", rootId = "root-a",
        name = "unknown.safetensors", category = "LORA", present = true, size = 100,
        annotation = ModelAnnotation(alias = "角色A常服", aliases = listOf("Character A"),
            useCases = listOf("character", "clothing"), character = "角色A", outfit = "常服",
            trainedWords = listOf("EXACT_Trigger (A)"), favorite = true))

    @Test fun twoServersWithSameFilenamesNeverShareCacheOrPreferences() {
        val root = temporary.newFolder()
        val a = LocalModelCache(root, "profile-a|https://host-a")
        val b = LocalModelCache(root, "profile-b|https://host-b")
        a.save(ModelCacheSnapshot("bridge-a", items = listOf(item), savedAt = 12,
            query = LocalModelQuery(useCase = "character"), compact = true, scrollIndex = 4, scrollOffset = 99))
        assertTrue(b.load().items.isEmpty())
        assertEquals("角色A常服", a.load().items.single().annotation.alias)
        assertEquals(4, a.load().scrollIndex)
        assertEquals(99, a.load().scrollOffset)
        assertTrue(a.load().compact)
        assertNotEquals(LocalModelCache.cacheKey("profile|host-a"), LocalModelCache.cacheKey("profile|host-b"))
    }

    @Test fun atomicOverwriteAndRestartKeepPreviousSavedFields() {
        val root = temporary.newFolder()
        val a = LocalModelCache(root, "profile")
        a.save(ModelCacheSnapshot(items = listOf(item), savedAt = 1))
        a.save(a.load().copy(savedAt = 2, query = LocalModelQuery(q = "角色")))
        assertEquals(2L, LocalModelCache(root, "profile").load().savedAt)
        assertEquals(listOf("EXACT_Trigger (A)"), a.load().items.single().triggerWords)
    }

    @Test fun classificationAliasesAndOutfitUseSameStructuredQuery() {
        assertTrue(LocalModelQuery(q = "角色a", useCase = "character").matches(item))
        assertTrue(LocalModelQuery(q = "character a", useCase = "clothing", favorite = true).matches(item))
        assertTrue(LocalModelQuery(q = "EXACT_Trigger (A)").matches(item))
        assertFalse(LocalModelQuery(useCase = "style").matches(item))
        assertFalse(LocalModelQuery(useCase = "unclassified").matches(item))
        assertFalse(LocalModelQuery(rootId = "other-root").matches(item))
    }

    @Test fun formatIsNotAUseCaseAndUnknownDoesNotInventProviderFacts() {
        val gguf = item.copy(format = "GGUF", category = "UNET", annotation = ModelAnnotation())
        assertTrue(LocalModelQuery(category = "UNET", source = "unknown", useCase = "unclassified").matches(gguf))
        assertFalse(LocalModelQuery(baseModel = "GGUF").matches(gguf))
        assertTrue(gguf.triggerWords.isEmpty())
        assertEquals("", gguf.baseModel)
    }

    @Test fun userTriggersTakePriorityAndLockedEmptyMeansUnknown() {
        val remote = item.copy(provider = ModelProvider(trainedWords = listOf("REMOTE"), baseModel = "SDXL"))
        assertEquals(listOf("EXACT_Trigger (A)"), remote.triggerWords)
        assertEquals("SDXL", remote.baseModel)
        assertTrue(remote.copy(annotation = ModelAnnotation(fieldLocks = listOf("trainedWords"))).triggerWords.isEmpty())
    }

    @Test fun tenThousandCachedRowsArePagedAndMissingUseTimeStaysUnknown() {
        val rows = (0 until 10000).map { item.copy(id = "$it", name = "model-$it",
            annotation = item.annotation.copy(alias = "中文别名-$it"), size = it.toLong()) }
        val snapshot = ModelCacheSnapshot("bridge-a", items = rows)
        val page = LocalModelCache.page(snapshot, LocalModelQuery(q = "中文别名", sort = "size"), 2)
        assertEquals(10000, page.total)
        assertEquals(40, page.items.size)
        assertEquals("9959", page.items.first().id)
        assertTrue(page.hasMore)
        assertFalse(page.scanComplete)
        assertNull(page.items.first().lastUsed)
    }
}
