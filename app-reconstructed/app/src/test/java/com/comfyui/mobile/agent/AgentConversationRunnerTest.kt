package com.comfyui.mobile.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Fake LLM -> production runner/executor -> real inspect/patch tools -> fake submission endpoint. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AgentConversationRunnerTest {
    private class Fixture {
        val session = AgentSessionController()
        val turn = session.begin("fixture")!!
        var graph: Map<String, Any> = mapOf("1" to mapOf("class_type" to "Text", "inputs" to mapOf("text" to "before")))
        val events = mutableListOf<String>()
        var submissions = 0
        var allowPatch = true
        var beforePatch: () -> Unit = {}
        val observer = object : AgentConversationObserver {
            override suspend fun progress(progress: AgentProgress) { events += "progress:${progress.content}" }
            override suspend fun reply(reply: AgentReply) { events += "reply" }
        }
        val port = object : AgentWorkflowPort {
            override suspend fun prepare() { events += "prepare" }
            override suspend fun patchPrompt(prompt: String) {
                beforePatch()
                session.requireCurrent(turn)
                events += "patch"
                val snapshot = AgentWorkflowSnapshot(graph, if (allowPatch) listOf(
                    AgentFieldSpec("1", "text", "Text", AgentFieldKind.TEXT)
                ) else emptyList())
                val tools = AgentTools()
                tools.inspectWorkflow.execute(snapshot).getOrThrow()
                graph = tools.patchWorkflow.execute(AgentPatchRequest(snapshot,
                    listOf(AgentFieldChange("1", "text", prompt)))).getOrThrow().workflow
            }
            override suspend fun promptApplied(prompt: String) { events += "applied" }
            override suspend fun submit(): String { events += "submit"; submissions++; return "pid-1" }
        }
        suspend fun run(reply: AgentReply, mode: AgentMode = AgentMode.DRAW): AgentRunResult =
            AgentConversationRunner(session).run(turn, mode, AgentGenerationSource {
                events += "llm"
                it(AgentProgress("reason", "partial"))
                reply
            }, observer, port)
    }

    @Test fun drawPatchesBeforeSubmittingExactlyOnce() = runTest {
        val f = Fixture()
        val result = f.run(AgentReply("好了", "cat, rain", shouldDraw = true))
        assertEquals("pid-1", result.submittedId)
        assertEquals(1, f.submissions)
        assertEquals(listOf("llm", "progress:partial", "reply", "prepare", "patch", "applied", "submit"), f.events)
        assertEquals(AgentPhase.SUBMITTING, f.session.state.value.turn!!.phase)
        assertEquals("cat, rain", (((f.graph["1"] as Map<*, *>)["inputs"] as Map<*, *>)["text"]))
    }

    @Test fun chatNeverTouchesWorkflowEvenIfReplyContainsDrawFlag() = runTest {
        val f = Fixture()
        val result = f.run(AgentReply("hello", "cat", shouldDraw = true), AgentMode.CHAT)
        assertNull(result.submittedId)
        assertEquals(listOf("llm", "progress:partial", "reply"), f.events)
        assertEquals(0, f.submissions)
    }

    @Test fun promptOnlyAppliesButNeverSubmits() = runTest {
        val f = Fixture()
        assertNull(f.run(AgentReply("tags", "cat", shouldDraw = false)).submittedId)
        assertTrue("applied" in f.events)
        assertEquals(0, f.submissions)
    }

    @Test fun missingPromptDoesNotPrepareOrSubmit() = runTest {
        val f = Fixture()
        try { f.run(AgentReply("empty", shouldDraw = true)); fail() }
        catch (_: AgentExecutionException) {}
        assertFalse("prepare" in f.events)
        assertEquals(0, f.submissions)
    }

    @Test fun invalidPatchDoesNotSubmitOrRememberPrompt() = runTest {
        val f = Fixture()
        f.allowPatch = false
        try { f.run(AgentReply("bad", "cat", shouldDraw = true)); fail() }
        catch (_: IllegalStateException) {}
        assertFalse("applied" in f.events)
        assertEquals(0, f.submissions)
        assertEquals("before", (((f.graph["1"] as Map<*, *>)["inputs"] as Map<*, *>)["text"]))
    }

    @Test fun stopBetweenGenerationAndPatchPreventsModification() = runTest {
        val f = Fixture()
        f.beforePatch = { f.session.stop() }
        try { f.run(AgentReply("late", "cat", shouldDraw = true)); fail() }
        catch (_: CancellationException) {}
        assertFalse("patch" in f.events)
        assertEquals(0, f.submissions)
    }

    @Test fun lateUncooperativeLlmCannotUpdateANewerTurn() = runTest {
        val f = Fixture()
        val release = CompletableDeferred<Unit>()
        val job = launch {
            try {
                AgentConversationRunner(f.session).run(f.turn, AgentMode.DRAW, AgentGenerationSource { progress ->
                    release.await()
                    progress(AgentProgress("", "late"))
                    AgentReply("late", "cat", shouldDraw = true)
                }, f.observer, f.port)
                fail()
            } catch (_: CancellationException) {}
        }
        runCurrent()
        f.session.stop(invalidateContext = true)
        val next = f.session.begin("new")!!
        release.complete(Unit)
        job.join()
        assertTrue(f.events.isEmpty())
        assertTrue(f.session.isCurrent(next))
        assertEquals(0, f.submissions)
    }

    @Test fun llmFailureDoesNotPrepareOrSubmit() = runTest {
        val f = Fixture()
        try {
            AgentConversationRunner(f.session).run(f.turn, AgentMode.DRAW, AgentGenerationSource {
                throw java.io.IOException("fixture failure")
            }, f.observer, f.port)
            fail()
        } catch (_: java.io.IOException) {}
        assertTrue(f.events.isEmpty())
        assertEquals(0, f.submissions)
    }
}
