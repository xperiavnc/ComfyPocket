package com.comfyui.mobile.agent

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.charset.Charset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentRuleFilesTest {
    @Test fun noBuiltInRulesAreActiveOnFirstLaunchOrAfterRemoval() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("rule-files-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = AgentRuleFilesStore(prefs)
        assertEquals(AgentRuleFiles(), store.load())
        val doc = AgentRuleFileReader.read("my-rules.md", "<instruction>\r\n用户规则\r\n</instruction>\n".byteInputStream())
        val files = AgentRuleFiles(dialogue = doc)
        store.save(files)
        assertEquals(files, AgentRuleFilesStore(prefs).load())
        store.save(AgentRuleFiles())
        assertEquals(AgentRuleFiles(), AgentRuleFilesStore(prefs).load())
    }

    @Test fun oldDefaultsAndCustomTextAreRetiredOnceAndKeptAsLocalCopies() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("rule-files-migration", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("agent_system_prompt", "OLD_PERSONA")
            .putString("agent_draw_prompt", "OLD_CUSTOM_DRAW").commit()
        val store = AgentRuleFilesStore(prefs)
        assertEquals(AgentRuleFiles(), store.load())
        assertFalse(prefs.contains("agent_system_prompt"))
        assertFalse(prefs.contains("agent_draw_prompt"))
        assertEquals("OLD_PERSONA", prefs.getString("agent_rules_retired_agent_system_prompt", null))
        assertEquals("OLD_CUSTOM_DRAW", prefs.getString("agent_rules_retired_agent_draw_prompt", null))
        val doc = AgentRuleDocument("new.txt", "NEW_USER_RULES")
        store.save(AgentRuleFiles(drawing = doc))
        assertEquals(doc, AgentRuleFilesStore(prefs).load().drawing)
    }

    @Test fun utf8BomUtf16AndGb18030RetainChineseAndLineBreaks() {
        val text = "<instruction>\r\n保持用户原文：中文，标点。\r\n</instruction>\n"
        val variants = listOf(
            text.toByteArray(),
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(),
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE),
            byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE),
            text.toByteArray(Charset.forName("GB18030")))
        for (bytes in variants) {
            assertEquals(text, AgentRuleFileReader.read("规则.txt", bytes.inputStream()).content)
        }
    }

    @Test fun invalidEmptyBinaryAndOversizedImportsLeaveSavedRulesIntact() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("rule-files-invalid", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = AgentRuleFilesStore(prefs)
        store.load()
        val original = AgentRuleFiles(dialogue = AgentRuleDocument("good.txt", "USER_RULES"))
        store.save(original)
        val invalid = listOf(
            "empty.txt" to " \r\n\t".toByteArray(),
            "binary.txt" to byteArrayOf(0, 1, 2, 3),
            "archive.zip" to "text".toByteArray(),
            "too-long.md" to ByteArray(AgentRuleFileReader.MAX_BYTES + 1) { 'a'.code.toByte() })
        for ((name, bytes) in invalid) {
            assertTrue(runCatching {
                store.save(original.replace(AgentRuleKind.DIALOGUE, AgentRuleFileReader.read(name, bytes.inputStream())))
            }.isFailure)
            assertEquals(original, store.load())
        }
    }
}
