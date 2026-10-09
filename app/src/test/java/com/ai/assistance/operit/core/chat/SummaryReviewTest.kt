package com.ai.assistance.operit.core.chat

import com.ai.assistance.operit.data.model.ChatMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SummaryReviewTest {
    @Test fun approvalReturnsTheExactDraftOnlyAfterReviewFinishes() = runTest {
        val answer = CompletableDeferred<String>()
        val draft = "  约好明天见；地点尚未确定。\n"
        val pending = async { SummaryReview.finalize(draft) { assertEquals(draft, it); answer.await() } }
        runCurrent()
        assertFalse(pending.isCompleted)
        answer.complete("""{"decision":"approve"}""")
        assertEquals(draft, pending.await())
    }

    @Test fun revisionReplacesTheDraftWithTheCompleteReviewedText() = runTest {
        val reviewed = SummaryReview.finalize("已经确定在北京见面") {
            """{"decision":"revise","summary":"约好明天见；地点尚未确定。"}"""
        }
        assertEquals("约好明天见；地点尚未确定。", reviewed)
    }

    @Test fun invalidOrRejectedReviewNeverReturnsAnUnreviewedDraft() = runTest {
        listOf(
            "", "没问题", "{}", """{"decision":"reject"}""",
            """{"decision":"revise","summary":" "}""",
            """{"decision":"revise","summary":123}""",
            """{"decision":"approve","summary":"actually changed"}""",
            """{"decision":true}"""
        ).forEach { response ->
            var failed = false
            try { SummaryReview.finalize("保留的原上下文", { response }) } catch (_: Exception) { failed = true }
            assertTrue("Must not compress on: $response", failed)
        }
    }

    @Test fun emptyDraftNeverInvokesTheReviewer() = runTest {
        var called = false
        try {
            SummaryReview.finalize(" ") { called = true; """{"decision":"approve"}""" }
            fail("An empty draft must not pass")
        } catch (_: IllegalArgumentException) { }
        assertFalse(called)
    }

    @Test fun cancellationAndNetworkFailureDoNotCommitDrafts() = runTest {
        val pending = async { SummaryReview.finalize("draft") { awaitCancellation() } }
        runCurrent()
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
        try {
            SummaryReview.finalize("draft") { throw java.io.IOException("offline") }
            fail("Failure must be propagated")
        } catch (e: java.io.IOException) { assertEquals("offline", e.message) }
    }

    @Test fun sourceCheckAllowsNewTurnsButRejectsChangesInsideReviewedHistory() {
        val source = listOf(
            ChatMessage("user", "明天见", timestamp = 10),
            ChatMessage("ai", "地点还没定", timestamp = 20, roleName = "当前角色")
        )
        assertTrue(SummaryReview.sourceUnchanged(source, source + ChatMessage("user", "新消息", timestamp = 30)))
        assertFalse(SummaryReview.sourceUnchanged(source, listOf(source[0], source[1].copy(content = "换了回答"))))
        assertFalse(SummaryReview.sourceUnchanged(source, listOf(source[1])))
        assertFalse(SummaryReview.sourceUnchanged(source, listOf(source[0], ChatMessage("summary", "另一份摘要", timestamp = 15), source[1])))
        assertFalse(SummaryReview.sourceUnchanged(source, listOf(source[0], source[1].copy(roleName = "其他角色"))))
        assertFalse(SummaryReview.sourceUnchanged(emptyList(), source))
    }
}
