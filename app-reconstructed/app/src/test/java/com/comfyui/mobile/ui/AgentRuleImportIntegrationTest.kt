package com.comfyui.mobile.ui

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import com.comfyui.mobile.agent.*
import com.comfyui.mobile.model.BackupExportOptions
import com.comfyui.mobile.model.ConfigBackupDto
import com.comfyui.mobile.model.ParsedBackup
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class AgentRuleImportIntegrationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var vm: MainViewModel
    private lateinit var holder: ViewModelStore

    @Before fun setup() {
        app.getSharedPreferences("comfy_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        start()
    }
    private fun start() {
        vm = MainViewModel(app)
        holder = ViewModelStore().apply { put("vm", vm) }
        awaitAgentStorage(vm)
    }
    private fun stop() {
        holder.clear()
        runBlocking { vm.agentWorkspace.awaitClosed() }
        vm.repository.disconnect()
    }
    @After fun close() = stop()
    private fun pump(done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        do {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            if (done()) return
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        fail("Timed out waiting for import")
    }
    private fun <T> io(block: suspend () -> T): T {
        val job = CoroutineScope(SupervisorJob() + Dispatchers.IO).async { block() }
        pump { job.isCompleted }
        return runBlocking { job.await() }
    }
    private fun import(kind: AgentRuleKind, name: String, text: String): File {
        val file = File(app.cacheDir, name).apply { writeText(text) }
        vm.importAgentRules(kind, Uri.fromFile(file))
        pump { vm.agentRulesImporting.value.isEmpty() }
        return file
    }

    @Test fun fileImportCopiesExactTextPersistsWithoutSourceAndRemovalStaysEmptyAfterRestart() {
        val text = "<instruction>\r\nUSER_DRAWING_RULES_854\r\n</instruction>\n"
        val file = import(AgentRuleKind.DRAWING, "自定义规则.md", text)
        assertEquals(text, vm.agentDrawPrompt.value)
        assertEquals("自定义规则.md", vm.agentRuleFiles.value.drawing!!.name)
        assertTrue(file.delete())
        stop(); start()
        assertEquals(text, vm.agentDrawPrompt.value)
        assertEquals(text, AgentLlmSettings.capture(vm).drawRules)
        vm.removeAgentRules(AgentRuleKind.DRAWING)
        stop(); start()
        assertEquals("", vm.agentDrawPrompt.value)
        assertNull(vm.agentRuleFiles.value.drawing)
    }

    @Test fun lateImportIsDiscardedWhenUserRemovesTheRuleBeforeItCommits() {
        import(AgentRuleKind.DIALOGUE, "old.txt", "OLD")
        val file = File(app.cacheDir, "late.txt").apply { writeText("LATE") }
        vm.importAgentRules(AgentRuleKind.DIALOGUE, Uri.fromFile(file))
        vm.removeAgentRules(AgentRuleKind.DIALOGUE)
        // Drain background work and UI commits.
        repeat(20) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10)); Thread.sleep(5) }
        assertEquals("", vm.agentSystemPrompt.value)
        assertNull(AgentRuleFilesStore(vm.prefs).load().dialogue)
    }

    @Test fun importedRulesDoNotStopTheActiveTurnAndSettingsStayFrozenUntilNextTurn() {
        import(AgentRuleKind.DIALOGUE, "one.txt", "FIRST")
        val turn = vm.agentSession.begin("rules-test")!!
        val version = vm.agentSession.contextVersion()
        val settings = AgentLlmSettings.capture(vm)
        import(AgentRuleKind.DIALOGUE, "two.txt", "SECOND")
        assertTrue(vm.agentSession.isCurrent(turn))
        assertEquals(version, vm.agentSession.contextVersion())
        assertEquals("FIRST", settings.persona)
        assertEquals("SECOND", AgentLlmSettings.capture(vm).persona)
        vm.agentSession.complete(turn)
    }

    @Test fun backupRoundTripIncludesImportedFilesAndEmptyBackupClearsThemWithoutRevivingLegacyText() {
        import(AgentRuleKind.DIALOGUE, "对话.txt", "DIALOGUE_SOURCE")
        import(AgentRuleKind.DRAWING, "drawing.md", "DRAW_SOURCE")
        val original = vm.agentRuleFiles.value
        val file = io { vm.exportBackupToFile(BackupExportOptions(includeKeys = false,
            includeAgentWorkspace = false, includeWorkflows = false, includeAiApps = false, includeTaskHistory = false)) }
        val parsed = io { MainViewModelBackup.parseBackupBytes(vm, file.readBytes()) }
        assertEquals(original, parsed.dto.agentRuleFiles)
        assertNull(parsed.dto.agentSystemPrompt)
        vm.agentRules.restore(AgentRuleFiles())
        assertTrue(io { vm.restoreParsedBackup(parsed) }.isSuccess)
        assertEquals(original, vm.agentRuleFiles.value)
        assertTrue(io { vm.restoreParsedBackup(ParsedBackup(ConfigBackupDto(agentRuleFiles = AgentRuleFiles()))) }.isSuccess)
        assertEquals(AgentRuleFiles(), vm.agentRuleFiles.value)
        assertTrue(io { vm.restoreParsedBackup(ParsedBackup(ConfigBackupDto(
            agentSystemPrompt = "OLD_BUILTIN", agentDrawPrompt = "OLD_BUILTIN_DRAW"))) }.isSuccess)
        assertEquals("", vm.agentSystemPrompt.value)
        assertEquals("", vm.agentDrawPrompt.value)
    }
}
