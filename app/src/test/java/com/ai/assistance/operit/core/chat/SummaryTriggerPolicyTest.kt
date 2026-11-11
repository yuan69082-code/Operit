package com.ai.assistance.operit.core.chat

import com.ai.assistance.operit.data.model.ChatMessage
import org.junit.Assert.*
import org.junit.Test

class SummaryTriggerPolicyTest {
    private fun turn(index: Int) = listOf(
        ChatMessage("user", "question $index", timestamp = index * 100L),
        ChatMessage("ai", "answer $index", timestamp = index * 100L + 10, sentAt = index * 100L)
    )

    private fun compress(history: List<ChatMessage>, completedAt: Long = 5_000): List<ChatMessage> {
        val slice = requireNotNull(SummaryContextSlice.plan(history))
        val summary = slice.completedSummary(
            ChatMessage("summary", "summary", timestamp = slice.beforeTimestamp + 1), completedAt)
        return slice.source + summary + slice.retained
    }

    private fun shouldCompress(history: List<ChatMessage>, tokens: Long = 700,
        byCount: Boolean = false, count: Int = 16) = SummaryTriggerPolicy.shouldGenerateSummary(
        history, tokens, 1_000, 0.70, true, byCount, count)

    @Test fun eightRetainedTurnsDoNotImmediatelyTriggerAnotherCompressionAtSeventyPercent() {
        val history = compress((1..12).flatMap(::turn))
        assertEquals(8, SummaryContextSlice.activeHistory(history).count { it.sender == "user" })
        assertEquals(0, SummaryTriggerPolicy.newUserTurns(history))
        assertFalse(shouldCompress(history))
        assertFalse(shouldCompress(history, tokens = 960))
    }

    @Test fun oneOrTwoFreshTurnsDoNotCountTheRetainedTailAgain() {
        val history = compress((1..12).flatMap(::turn))
        assertFalse(shouldCompress(history + turn(13)))
        assertFalse(shouldCompress(history + turn(13) + turn(14)))
        assertTrue(shouldCompress(history + (13..15).flatMap(::turn)))
    }

    @Test fun nearCapacityStillAllowsCompressionAfterOneLargeNewTurn() {
        val history = compress((1..12).flatMap(::turn)) + turn(13)
        assertTrue(shouldCompress(history, tokens = 950))
    }

    @Test fun configuredMessageCountIncludesOnlyTurnsAfterTheSnapshot() {
        val history = compress((1..12).flatMap(::turn))
        assertFalse(shouldCompress(history + (13..20).flatMap(::turn), tokens = 100, byCount = true))
        assertTrue(shouldCompress(history + (13..28).flatMap(::turn), tokens = 100, byCount = true))
    }

    @Test fun concurrentlyAppendedMessageRemainsFreshEvenBeforeCompressionCompletes() {
        val original = (1..12).flatMap(::turn)
        val slice = requireNotNull(SummaryContextSlice.plan(original))
        val pending = ChatMessage("user", "arrived during compression", timestamp = 1_300)
        assertTrue(SummaryReview.canCommit(slice.source, original + pending, slice.beforeTimestamp, slice.afterTimestamp))
        val summary = slice.completedSummary(
            ChatMessage("summary", "summary", timestamp = slice.beforeTimestamp + 1), completedAt = 5_000)
        val saved = slice.source + summary + slice.retained + pending
        assertEquals(1_210L, summary.sentAt)
        assertEquals(5_000L, summary.completedAt)
        assertEquals(1, SummaryTriggerPolicy.newUserTurns(saved))
        assertEquals(pending, SummaryContextSlice.activeHistory(saved).last())
    }

    @Test fun legacySummaryKeepsItsOldCountingUntilACompressionWritesTheNewBoundary() {
        val legacy = listOf(ChatMessage("summary", "older version", timestamp = 50)) + (1..12).flatMap(::turn)
        assertEquals(12, SummaryTriggerPolicy.newUserTurns(legacy))
        assertTrue(shouldCompress(legacy))
        val updated = compress(legacy)
        assertEquals(0, SummaryTriggerPolicy.newUserTurns(updated))
        assertFalse(shouldCompress(updated))
    }

    @Test fun aSecondCompressionReplacesTheBoundaryIncludingItsNewRetainedTail() {
        val first = compress((1..12).flatMap(::turn))
        val second = compress(first + (13..15).flatMap(::turn), completedAt = 6_000)
        assertEquals(1_510L, second.last { it.sender == "summary" }.sentAt)
        assertEquals(0, SummaryTriggerPolicy.newUserTurns(second))
        assertFalse(shouldCompress(second))
        assertEquals(1, SummaryTriggerPolicy.newUserTurns(second + turn(16)))
    }

    @Test fun autonomousGroupRepliesCanStillCompressNearCapacityWithoutAUserMessage() {
        val history = compress((1..12).flatMap(::turn)) + listOf(
            ChatMessage("ai", "tool call", timestamp = 1_300, sentAt = 1_300),
            ChatMessage("ai", "tool result", timestamp = 1_310, sentAt = 1_300),
            ChatMessage("ai", "reply", timestamp = 1_320, sentAt = 1_300))
        assertFalse(shouldCompress(history))
        assertTrue(shouldCompress(history, tokens = 950))
    }
}
