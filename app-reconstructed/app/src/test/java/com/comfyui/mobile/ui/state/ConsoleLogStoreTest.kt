package com.comfyui.mobile.ui.state

import com.comfyui.mobile.model.LogType
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConsoleLogStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val store = ConsoleLogStore { 123456789L }

    @Test fun capacityKeepsLatestFourHundredMessages() {
        repeat(450) { store.add("message $it") }
        assertEquals(400, store.messages.value.size)
        assertEquals("message 50", store.messages.value.first().text)
        assertEquals("message 449", store.messages.value.last().text)
    }

    @Test fun storedAndExportedMessagesAreRedacted() {
        store.add("https://host/proxy/SECRET/x?apiKey=TOKEN Bearer TOKEN", LogType.ERROR)
        val text = store.render(listOf("baseUrl" to "https://host"))
        assertFalse(text.contains("SECRET"))
        assertFalse(text.contains("TOKEN"))
        assertTrue(text.contains("[ERROR]"))
        assertTrue(text.contains("baseUrl=https://host"))
    }

    @Test fun clearKeepsOnlyTheExistingClearNotice() {
        store.add("old")
        store.clear()
        assertEquals(listOf("日志已清空"), store.messages.value.map { it.text })
        assertEquals(LogType.INFO, store.messages.value.single().type)
    }

    @Test fun exportWritesUtf8AndRecordsTheFileName() {
        assertEquals("(暂无日志)\n", store.render(emptyList()))
        store.add("中文日志")
        val rendered = store.render(emptyList())
        val file = store.export(temporary.root, rendered)
        assertEquals("comfy_log_123456789.txt", file.name)
        assertEquals(rendered, file.readText(Charsets.UTF_8))
        assertEquals("已导出日志: ${file.name}", store.messages.value.last().text)
    }

    @Test fun concurrentWritersDoNotOverwriteEachOthersMessages() {
        val pool = Executors.newFixedThreadPool(8)
        try {
            repeat(200) { i -> pool.submit { store.add("message $i") } }
        } finally {
            pool.shutdown()
        }
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(200, store.messages.value.size)
        assertEquals(200, store.messages.value.map { it.text }.distinct().size)
    }
}
