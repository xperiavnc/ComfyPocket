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
}
