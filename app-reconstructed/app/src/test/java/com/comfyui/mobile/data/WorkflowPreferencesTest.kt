package com.comfyui.mobile.data

import com.comfyui.mobile.model.MediaOutput
import com.comfyui.mobile.model.MediaType
import org.junit.Assert.*
import org.junit.Test

class WorkflowPreferencesTest {
    private val disk = mutableMapOf<String, String>()
    private var namespace = "local"
    private val store = WorkflowPreferences(
        read = { disk[it] },
        write = { key, json, legacy -> disk[key] = json; if (legacy != null) disk.remove(legacy) },
        currentNamespace = { namespace }
    )

    @Test fun sameNamedWorkflowIsIsolatedBetweenLocalAndCloudSites() {
        for (ns in listOf("local", "cloud@cn", "cloud@ai")) {
            namespace = ns
            store.persistMap("same", "labels", mapOf("1:text" to ns))
        }
        for (ns in listOf("local", "cloud@cn", "cloud@ai")) {
            namespace = ns
            assertEquals(ns, store.loadStringMap("same", "labels")["1:text"])
        }
    }

    @Test fun pinnedNamespaceSurvivesBackendSwitchForLateResults() {
        store.namespaces["old"] = "cloud@cn"
        namespace = "local"
        store.persistStringList("old", "order", listOf("1:text"))
        assertTrue(disk.containsKey("workflow@cloud@cn:old:order"))
        assertFalse(disk.containsKey("workflow@local:old:order"))
    }

    @Test fun legacyFallbackReadsDoNotModifyStorage() {
        namespace = "cloud@cn"
        disk["workflow:old:labels"] = """{"1:text":"shared"}"""
        val before = disk.toMap()
        assertEquals("shared", store.loadStringMap("old", "labels")["1:text"])
        assertEquals(before, disk)
        disk["workflow@cloud:old:labels"] = """{"1:text":"cloud"}"""
        assertEquals("cloud", store.loadStringMap("old", "labels")["1:text"])
        disk["workflow@cloud@cn:old:labels"] = """{"1:text":"site"}"""
        assertEquals("site", store.loadStringMap("old", "labels")["1:text"])
    }

    @Test fun firstCloudWriteRemovesOnlyThePreSiteKey() {
        namespace = "cloud@ai"
        disk["workflow:old:labels"] = "{}"
        disk["workflow@cloud:old:labels"] = "{}"
        disk["workflow@cloud@cn:old:labels"] = "{}"
        store.persistMap("old", "labels", mapOf("1:text" to "new"))
        assertFalse(disk.containsKey("workflow@cloud:old:labels"))
        assertTrue(disk.containsKey("workflow:old:labels"))
        assertTrue(disk.containsKey("workflow@cloud@cn:old:labels"))
        assertEquals("new", store.loadStringMap("old", "labels")["1:text"])
    }

    @Test fun malformedAndNullJsonKeepTheExistingEmptyFallbacks() {
        for (raw in listOf("{", "null")) {
            disk["workflow@local:x:test"] = raw
            assertTrue(store.loadStringList("x", "test").isEmpty())
            assertTrue(store.loadStringMap("x", "test").isEmpty())
            assertTrue(store.loadBooleanMap("x", "test").isEmpty())
            assertTrue(store.loadAnyMap("x", "test").isEmpty())
            assertTrue(store.loadMediaList("x", "test").isEmpty())
        }
    }

    @Test fun numericPersistenceRetainsOriginalNormalizationRules() {
        store.persistMap("x", "values", mapOf("width" to 512.0, "cfg" to 7.0))
        val values = store.loadAnyMap("x", "values")
        assertEquals(512, values["width"])
        assertEquals(7.0, values["cfg"])
    }

    @Test fun mediaRoundTripKeepsIdentityAndTimestamp() {
        val media = MediaOutput("a.mp4", "videos", "output", MediaType.VIDEO, 123L, "pid", "file:///a.mp4")
        store.persistMediaList("x", "results", listOf(media))
        assertEquals(listOf(media), store.loadMediaList("x", "results"))
    }

    @Test fun missingValuesReturnIndependentMutableCollections() {
        val first = store.loadStringList("x", "order")
        first.add("temporary")
        assertTrue(store.loadStringList("x", "order").isEmpty())
        assertTrue(store.loadStringMap("x", "labels").isEmpty())
        assertTrue(store.loadBooleanMap("x", "visibility").isEmpty())
        assertTrue(store.loadAnyMap("x", "values").isEmpty())
        assertTrue(store.loadMediaList("x", "results").isEmpty())
    }
}
