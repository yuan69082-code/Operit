package com.ai.assistance.operit.api.chat.enhance

import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.ToolExecutionLimits
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.util.ChatMarkupRegex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationMarkupManagerResultLimitTest {

    @Test
    fun `durable receipt identity survives truncation without claiming unknown success`() {
        val message = ConversationMarkupManager.formatToolResultForMessage(
            ToolResult("actual_tool", true, StringResultData("x".repeat(63000)),
                subjectActionId = "stable-receipt", executionStatus = "unknown"))
        assertTrue(message.contains("id=stable-receipt status=unknown"))
        assertTrue(message.contains("status=\"error\""))
        assertTrue(message.contains("companion_internal_state get section=action id=stable-receipt"))
        assertTrue(message.length <= ToolExecutionLimits.MAX_SINGLE_TOOL_RESULT_MESSAGE_CHARS)
    }

    @Test
    fun `keeps every tool result while bounding each result independently`() {
        val results = listOf(
            ToolResult("first_tool", true, StringResultData("a".repeat(63_000))),
            ToolResult("second_tool", true, StringResultData("b".repeat(63_000)))
        )

        val message = ConversationMarkupManager.buildToolResultMessage(results)
        val resultBlocks = ChatMarkupRegex.toolResultAnyPattern.findAll(message).toList()

        assertEquals(2, resultBlocks.size)
        assertTrue(
            resultBlocks.all {
                it.value.length <= ToolExecutionLimits.MAX_SINGLE_TOOL_RESULT_MESSAGE_CHARS
            }
        )
        assertTrue(message.contains("name=\"first_tool\""))
        assertTrue(message.contains("name=\"second_tool\""))
    }
}
