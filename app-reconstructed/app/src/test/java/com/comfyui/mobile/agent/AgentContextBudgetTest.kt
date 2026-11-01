package com.comfyui.mobile.agent

import com.comfyui.mobile.network.ChatMessageDto
import org.junit.Assert.*
import org.junit.Test

class AgentContextBudgetTest {
    private fun message(role: String, text: String) = AgentContextMessage(ChatMessageDto(role, text))

    @Test fun trimsOldestWholeTurnsAndKeepsSystemAndCurrentRequestIntact() {
        val system = message("system", "rules")
        val current = message("user", "current instruction")
        val history = listOf(message("user", "old".repeat(100)), message("assistant", "old answer".repeat(100)),
            message("user", "recent"), message("assistant", "recent answer"))
        val result = AgentContextBudget.fit(system, history, listOf(current),
            AgentGenerationLimits(1024, 128), 4)
        assertEquals(listOf("rules", "recent", "recent answer", "current instruction"),
            result.messages.map { it.message.content })
        assertEquals(2, result.removedMessages)
        assertTrue(result.estimatedInputTokens + 128 <= 1024)
    }

    @Test fun requiredInputOverflowIsVisibleRatherThanTruncatingTheCurrentInstruction() {
        val exception = assertThrows(AgentExecutionException::class.java) {
            AgentContextBudget.fit(message("system", "rules"), emptyList(),
                listOf(message("user", "x".repeat(1200))), AgentGenerationLimits(1024, 128), 4)
        }
        assertTrue(exception.message!!.contains("未截断当前指令"))
    }

    @Test fun defaultRetainsLegacyHistoryCountAndExplicitBudgetCanIncludeMore() {
        val history = (1..8).flatMap { listOf(message("user", "u$it"), message("assistant", "a$it")) }
        val fixed = listOf(message("user", "current"))
        val original = AgentContextBudget.fit(message("system", "rules"), history, fixed, AgentGenerationLimits(), 4)
        val configured = AgentContextBudget.fit(message("system", "rules"), history, fixed,
            AgentGenerationLimits(32768, 1024), 4)
        assertEquals(6, original.messages.size)
        assertEquals(18, configured.messages.size)
    }

    @Test fun imagesConsumeBudgetAndDetachedAssistantHistoryIsSkipped() {
        val image = AgentContextMessage(ChatMessageDto("user", "image"), listOf("fixture"))
        assertTrue(AgentContextBudget.estimate(image) >= 4096)
        val selected = AgentContextBudget.fit(message("system", "rules"),
            listOf(message("assistant", "orphan"), image, message("assistant", "answer")),
            listOf(message("user", "now")), AgentGenerationLimits(4096, 512), 4)
        assertEquals(2, selected.messages.size)
        assertEquals("now", selected.messages.last().message.content)
    }

    @Test fun validatesNumericRangesAndOutputReservation() {
        for (value in listOf(AgentGenerationLimits(100, 128), AgentGenerationLimits(1024, 1024),
            AgentGenerationLimits(outputTokens = -1))) {
            assertThrows(IllegalArgumentException::class.java) { value.validated() }
        }
        assertEquals(4096, AgentGenerationLimits(16384).validated().outputReserve)
        assertEquals(OutputTokenParameter.COMPLETION, OutputTokenParameter.fromStored("unknown"))
    }

    @Test fun toolSchemaReserveTrimsHistoryButKeepsTheCurrentTaskWhole() {
        val history = listOf(message("user", "old".repeat(100)), message("assistant", "answer".repeat(100)))
        val current = message("user", "原始任务和已确认答案")
        val selected = AgentContextBudget.fit(message("system", "rules"), history, listOf(current),
            AgentGenerationLimits(2048, 128), 4, reservedInputTokens = 1500)
        assertEquals(2, selected.removedMessages)
        assertEquals(current, selected.messages.last())
        assertTrue(selected.estimatedInputTokens >= 1500)
        assertTrue(selected.estimatedInputTokens + 128 <= 2048)
    }

    @Test fun nativeToolArgumentsAndReplayedReasoningConsumeInputBudget() {
        val call = com.comfyui.mobile.network.ChatToolCall("q",
            com.comfyui.mobile.network.ChatFunctionCall("ask_user_question", """{"questions":[{"id":"q1","question":"哪套？"}]}"""))
        val native = AgentContextMessage(ChatMessageDto("assistant", reasoning_content = "reason".repeat(100),
            tool_calls = listOf(call)))
        assertTrue(AgentContextBudget.estimate(native) > 600)
        assertThrows(AgentExecutionException::class.java) {
            AgentContextBudget.fit(message("system", "rules"), emptyList(),
                listOf(native, message("tool", "answer")), AgentGenerationLimits(1024, 128), 4,
                reservedInputTokens = 500)
        }
    }

    @Test fun historyWindowMovesInChunksNotOneTurnAtATime() {
        // With a budget that fits about six turns, the oldest kept message must stay put for
        // several consecutive requests so the cached prefix after the system message survives.
        val firsts = (8..16).map { turns ->
            val history = (1..turns).flatMap { listOf(message("user", "u$it " + "x".repeat(200)), message("assistant", "a$it")) }
            val fitted = AgentContextBudget.fit(message("system", "rules"), history, listOf(message("user", "current")),
                AgentGenerationLimits(1024, 128), 4)
            fitted.messages[1].message.content
        }
        val changes = firsts.zipWithNext().count { (a, b) -> a != b }
        assertTrue("window slid on every turn: $firsts", changes <= firsts.size / 2)
    }

    private val growing = listOf(
        ChatMessageDto("system", "rules"),
        ChatMessageDto("user", "old1" + "x".repeat(1000)), ChatMessageDto("assistant", "a1"),
        ChatMessageDto("user", "old2" + "y".repeat(1000)), ChatMessageDto("assistant", "a2"),
        ChatMessageDto("user", "task"),
        ChatMessageDto("tool", "answer" + "z".repeat(2000), tool_call_id = "c1"))
    private val growingImages = mapOf(1 to listOf("data:old"), 5 to listOf("data:task"))

    @Test fun growingToolTurnDropsOldestWholeHistoryTurnsAndKeepsTheTaskAndToolAnswers() {
        val roomy = AgentContextBudget.trimRequest(growing, growingImages, historyCount = 4, limit = 20_000)
        assertEquals(0, roomy.droppedHistory)
        assertSame(growing, roomy.messages)

        val trimmed = AgentContextBudget.trimRequest(growing, growingImages, historyCount = 4, limit = 9_000)
        assertTrue(trimmed.fits)
        assertEquals(2, trimmed.droppedHistory)
        assertEquals(listOf("system", "user", "assistant", "user", "tool"), trimmed.messages.map { it.role })
        assertEquals("a2", trimmed.messages[2].content)
        assertEquals("task", trimmed.messages[3].content)
        assertEquals(mapOf(3 to listOf("data:task")), trimmed.images)
        assertTrue(trimmed.cost <= 9_000)
    }

    @Test fun overflowWithoutAnyHistoryIsReportedInsteadOfCuttingTheTaskOrToolAnswers() {
        val tight = AgentContextBudget.trimRequest(growing, growingImages, historyCount = 4, limit = 7_000)
        assertTrue(tight.fits)
        assertEquals(4, tight.droppedHistory)
        assertEquals(listOf("rules", "task"), tight.messages.take(2).map { it.content })
        assertEquals(mapOf(1 to listOf("data:task")), tight.images)

        val over = AgentContextBudget.trimRequest(growing, growingImages, historyCount = 4, limit = 6_000)
        assertFalse(over.fits)
        assertEquals(4, over.droppedHistory)
        assertEquals(listOf("system", "user", "tool"), over.messages.map { it.role })
        assertTrue(over.messages.last().content!!.startsWith("answer"))
    }

    @Test fun toolHeadroomOnlyShortensTheHistoryAndNeverFailsTheTurn() {
        val history = (1..6).flatMap { listOf(message("user", "u$it " + "x".repeat(600)), message("assistant", "a$it")) }
        val limits = AgentGenerationLimits(4096, 128)
        val plain = AgentContextBudget.fit(message("system", "rules"), history, listOf(message("user", "current")), limits, 4)
        val roomy = AgentContextBudget.fit(message("system", "rules"), history, listOf(message("user", "current")), limits, 4,
            growthHeadroom = 100_000)
        assertEquals(12, plain.keptHistory)
        assertEquals(8, roomy.keptHistory)
        assertEquals(roomy.keptHistory + 2, roomy.messages.size)
        assertEquals("u3 " + "x".repeat(600), roomy.messages[1].message.content)
        assertEquals("current", roomy.messages.last().message.content)
    }
}
