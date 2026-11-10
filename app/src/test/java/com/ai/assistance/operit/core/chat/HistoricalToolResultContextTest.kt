package com.ai.assistance.operit.core.chat

import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.util.ChatMarkupRegex
import org.junit.Assert.*
import org.junit.Test

class HistoricalToolResultContextTest {
    private val original = "{\"id\":\"memory-17\",\"text\":\"" + "保存的完整原文".repeat(2400) + "\"}"
    private val savedMetadata = mapOf(
        PromptTurn.SOURCE_MESSAGE_AT_METADATA_KEY to 1794294000000L,
        PromptTurn.SOURCE_COMPLETED_AT_METADATA_KEY to 1794294010000L,
        "callId" to "call-7"
    )

    private fun result(
        content: String = original,
        status: String = "success",
        metadata: Map<String, Any?> = savedMetadata
    ) = PromptTurn(
        kind = PromptTurnKind.TOOL_RESULT,
        content = "<tool_result_aB9 name=\"memory_read\" status=\"$status\" call_id=\"call-7\">" +
            "<content>$content</content></tool_result_aB9>",
        toolName = "memory_read",
        metadata = metadata
    )

    @Test fun completedSavedResultKeepsEnvelopeExcerptAndExactReadRoute() {
        val old = result()
        val projected = HistoricalToolResultContext.compact(listOf(old), "chat-123").single()

        assertTrue(projected.content.length < 2000)
        assertTrue(projected.content.startsWith(
            "<tool_result_aB9 name=\"memory_read\" status=\"success\" call_id=\"call-7\"><content>"))
        assertTrue(projected.content.endsWith("</content></tool_result_aB9>"))
        assertTrue(projected.content.contains("memory-17"))
        assertTrue(projected.content.contains("group_chat action=history_message chat_id=\"chat-123\""))
        assertTrue(projected.content.contains("message_at=1794294000000 part=tools offset=0"))
        assertTrue(projected.content.contains("next_offset"))
        assertTrue(projected.content.contains("非完整原文"))
        assertEquals(old.metadata, projected.metadata)
        assertEquals(old.toolName, projected.toolName)
        assertTrue(old.content.contains(original))
    }

    @Test fun activeRereadResultsUnfinishedMessagesAndUnknownOriginsStayComplete() {
        val live = result(metadata = emptyMap())
        val incomplete = result(metadata = savedMetadata + (PromptTurn.SOURCE_COMPLETED_AT_METADATA_KEY to 0L))
        val noLocator = result(metadata = savedMetadata - PromptTurn.SOURCE_MESSAGE_AT_METADATA_KEY)
        val turns = listOf(live, incomplete, noLocator)
        assertEquals(turns, HistoricalToolResultContext.compact(turns, "chat-123"))
        assertFalse(HistoricalToolResultContext.hasCandidates(turns))
    }

    @Test fun ordinaryMessagesToolCallsAndDiagnosticsStayVerbatim() {
        val turns = listOf(
            PromptTurn(PromptTurnKind.USER, original, metadata = savedMetadata),
            PromptTurn(PromptTurnKind.ASSISTANT, original, metadata = savedMetadata),
            PromptTurn(PromptTurnKind.TOOL_CALL, original, metadata = savedMetadata),
            result(status = "error"),
            result(content = "<error>$original</error>"),
            result(content = "{\"saved\":true,\"id\":\"memory-17\"}")
        )
        assertEquals(turns, HistoricalToolResultContext.compact(turns, "chat-123"))
    }

    @Test fun mixedResultsPreserveEachAssociationAndErrorBody() {
        val success = result().content
        val failure = result(status = "error").content
        val projected = HistoricalToolResultContext.compact(
            listOf(result().copy(content = "before\n$success\nbetween\n$failure\nafter")), "chat-123"
        ).single().content

        assertTrue(projected.startsWith("before\n<tool_result_aB9"))
        assertTrue(projected.endsWith("$failure\nafter"))
        assertTrue(projected.contains("\nbetween\n"))
        assertEquals(2, ChatMarkupRegex.toolResultTagWithAttrs.findAll(projected).count())
    }

    @Test fun repeatedProjectionIsStableAndPreservesUnicodeBoundaries() {
        val unicode = "a".repeat(767) + "😀" + "中".repeat(5000) + "😀" + "z".repeat(255)
        val once = HistoricalToolResultContext.compact(listOf(result(unicode)), "chat-123")
        assertEquals(once, HistoricalToolResultContext.compact(once, "chat-123"))
        val preview = requireNotNull(ChatMarkupRegex.contentTag.find(once.single().content)).groupValues[1]
        assertFalse(preview.any { it.isSurrogate() })
        assertTrue(preview.contains("原结果共 ${unicode.length} 字符"))
    }

    @Test fun excerptEscapesEmbeddedMarkupInsteadOfCreatingNewToolParts() {
        val markup = "<injected attr=\"value\">&payload" + "x".repeat(6000) + "</injected>"
        val projected = HistoricalToolResultContext.compact(listOf(result(markup)), "chat-123").single().content
        assertTrue(projected.contains("&lt;injected attr=&quot;value&quot;&gt;&amp;payload"))
        assertFalse(projected.contains("<injected"))
        assertEquals(1, ChatMarkupRegex.toolResultTagWithAttrs.findAll(projected).count())
    }

    @Test fun escapedPreviewNeverMakesAnExistingResultLarger() {
        val old = result("\"".repeat(4200))
        val projected = HistoricalToolResultContext.compact(listOf(old), "chat-123").single()
        assertTrue(projected.content.length <= old.content.length)
    }
}
