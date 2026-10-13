package com.ai.assistance.operit.core.chat

import com.ai.assistance.operit.data.model.ChatMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SummaryContinuityTest {
    private fun turn(index: Int) = listOf(
        ChatMessage("user", "user $index", timestamp = index * 100L),
        ChatMessage("ai", "answer $index", timestamp = index * 100L + 10)
    )

    @Test fun summaryKeepsTwoRecentWholeTurnsVerbatim() {
        val history = (1..5).flatMap(::turn)
        val slice = requireNotNull(SummaryContextSlice.plan(history))
        assertEquals(history.take(6), slice.source)
        assertEquals(history.takeLast(4), slice.retained)
        assertEquals(history, slice.source + slice.retained)
        assertEquals(310L, slice.beforeTimestamp)
        assertEquals(400L, slice.afterTimestamp)
    }

    @Test fun completedToolChainAndPreviousConclusionRemainInNextContext() {
        val recent = listOf(
            ChatMessage("user", "逗你", timestamp = 300),
            ChatMessage("ai", "我知道，记账了。<tool name=\"use_package\"><param name=\"package_name\">ob</param></tool>", timestamp = 310),
            ChatMessage("ai", "<tool_result name=\"use_package\" status=\"success\">Using MCP server: ob</tool_result>", timestamp = 320),
            ChatMessage("ai", "<tool name=\"ob:hold\"><param name=\"value\">0.6</param></tool>", timestamp = 330),
            ChatMessage("ai", "<tool_result name=\"ob:hold\" status=\"success\">已完成</tool_result>记好了。", timestamp = 340)
        )
        val history = turn(1) + turn(2) + recent
        val slice = requireNotNull(SummaryContextSlice.plan(history))
        val summary = ChatMessage("summary", "较早的约定", timestamp = slice.beforeTimestamp + 1)
        val nextUser = ChatMessage("user", "错了错了，和你聊，你要怎么聊", timestamp = 400)
        // Same active-history selector used by the model request. Covered messages disappear;
        // every retained response, tool result and next user input still appears exactly once.
        val nextContext = SummaryContextSlice.activeHistory(slice.source + summary + slice.retained + nextUser)
        assertEquals(listOf(summary) + slice.retained + nextUser, nextContext)
        recent.forEach { assertEquals(1, nextContext.count { m -> m == it }) }
    }

    @Test fun oldSummaryIsIncludedWhenPreparingTheNextOne() {
        val old = ChatMessage("summary", "上周的约定不能丢", timestamp = 50)
        val history = listOf(ChatMessage("ai", "已经被概括的旧话", timestamp = 10), old) + (1..4).flatMap(::turn)
        val slice = requireNotNull(SummaryContextSlice.plan(history))
        assertEquals(old, slice.source.first())
        assertFalse(slice.source.any { it.timestamp == 10L })
        assertEquals(turn(3) + turn(4), slice.retained)
    }

    @Test fun shortHistoryKeepsAtLeastOneWholeTurn() {
        val slice = requireNotNull(SummaryContextSlice.plan(turn(1) + turn(2)))
        assertEquals(turn(1), slice.source)
        assertEquals(turn(2), slice.retained)
        assertNull(SummaryContextSlice.plan(turn(1)))
        assertNull(SummaryContextSlice.plan(emptyList()))
    }

    @Test fun newUserInputStaysOutsideTheCompressedPrefix() {
        val pending = ChatMessage("user", "还没回答的问题", timestamp = 400)
        val slice = requireNotNull(SummaryContextSlice.plan((1..3).flatMap(::turn) + pending))
        assertEquals(turn(3) + pending, slice.retained)
        assertFalse(slice.source.contains(pending))
    }

    @Test fun groupSpeakersAndToolFragmentsKeepTheirIdentityInRetainedTail() {
        val members = listOf(
            ChatMessage("user", "两位聊一聊", timestamp = 200),
            ChatMessage("ai", "A 的结论", timestamp = 210, roleName = "A"),
            ChatMessage("ai", "B 的回应", timestamp = 220, roleName = "B")
        )
        assertEquals(members, requireNotNull(SummaryContextSlice.plan(turn(1) + members)).retained)
    }

    @Test fun reviewedSourceIsASnapshotNotMutableLiveMessages() {
        val history = (1..3).flatMap(::turn)
        val slice = requireNotNull(SummaryContextSlice.plan(history))
        history.first().content = "改过的内容"
        assertEquals("user 1", slice.source.first().content)
        assertFalse(SummaryReview.canCommit(slice.source, history, slice.beforeTimestamp, slice.afterTimestamp))
    }

    @Test fun commitRejectsAMarkerThatWouldHideUnsummarizedMessages() {
        val slice = requireNotNull(SummaryContextSlice.plan((1..4).flatMap(::turn)))
        assertTrue(SummaryReview.canCommit(slice.source, slice.source + slice.retained, slice.beforeTimestamp, slice.afterTimestamp))
        assertFalse(SummaryReview.canCommit(slice.source, slice.source + slice.retained, slice.retained.last().timestamp, null))
        assertFalse(SummaryReview.canCommit(slice.source, slice.source, slice.beforeTimestamp, slice.beforeTimestamp))
    }

    @Test fun queuedCompressionWaitsForToolReplyAndPersistenceAndCapturesFreshSource() = runTest {
        val gate = SummaryTurnGate()
        val tool = CompletableDeferred<Unit>()
        val persist = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val turn = async {
            events += "tool started"
            tool.await()
            events += "tool result"
            events += "final AI reply"
            persist.await()
            events += "saved"
        }
        val summary = async { gate.afterTurn("A", { turn.join() }) { events += "summary source read" } }
        runCurrent()
        assertTrue(gate.isReserved("A"))
        assertFalse(gate.startTurnIfAllowed("A") { fail("a new turn must wait for compression"); true })
        assertFalse(summary.isCompleted)
        tool.complete(Unit)
        runCurrent()
        assertFalse(summary.isCompleted)
        persist.complete(Unit)
        summary.await()
        assertEquals(listOf("tool started", "tool result", "final AI reply", "saved", "summary source read"), events)
        assertFalse(gate.isReserved("A"))
        assertTrue(gate.startTurnIfAllowed("A") { true })
    }

    @Test fun cancellingPendingCompressionDoesNotCancelTheToolOrCommitAnything() = runTest {
        val gate = SummaryTurnGate()
        val tool = CompletableDeferred<Unit>()
        val turn = async { tool.await() }
        var committed = false
        val summary = async { gate.afterTurn("A", { turn.join() }) { committed = true } }
        runCurrent()
        summary.cancelAndJoin()
        assertTrue(turn.isActive)
        assertFalse(committed)
        assertFalse(gate.isReserved("A"))
        tool.complete(Unit)
        turn.await()
    }

    @Test fun reservationLastsThroughReviewAndIsReleasedOnFailure() = runTest {
        val gate = SummaryTurnGate()
        val review = CompletableDeferred<Unit>()
        val summary = async {
            runCatching { gate.afterTurn("A", {}) { review.await(); error("review rejected") } }
        }
        runCurrent()
        assertTrue(gate.isReserved("A"))
        review.complete(Unit)
        assertTrue(summary.await().isFailure)
        assertFalse(gate.isReserved("A"))
    }

    @Test fun repeatedTriggerCannotStartASecondCompressionButOtherChatsAreIndependent() = runTest {
        val gate = SummaryTurnGate()
        val turn = CompletableDeferred<Unit>()
        val first = async { gate.afterTurn("A", { turn.await() }) { "A" } }
        runCurrent()
        val duplicate = runCatching { gate.afterTurn("A", {}) { fail("duplicate commit") } }
        assertTrue(duplicate.isFailure)
        assertTrue(gate.isReserved("A"))
        assertEquals("B", gate.afterTurn("B", {}) { "B" })
        turn.complete(Unit)
        assertEquals("A", first.await())
    }
}
