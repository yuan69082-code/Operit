package com.ai.assistance.operit.core.chat

import com.ai.assistance.operit.data.model.ChatMessage
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReviewedSummaryBatchesTest {
    @Test fun requestCountersDoNotInvalidateCacheButModelEditsDo() {
        val config = com.ai.assistance.operit.data.model.ModelConfigData(id = "test", name = "test",
            apiKeyPool = listOf(com.ai.assistance.operit.data.model.ApiKeyInfo("a", "not-a-real-key")))
        val afterRequest = config.copy(currentKeyIndex = 1, apiKeyPool = config.apiKeyPool.map {
            it.copy(usageCount = 3, lastUsed = 12345, errorCount = 1)
        })
        assertEquals(ReviewedSummaryBatches.modelFingerprint(config), ReviewedSummaryBatches.modelFingerprint(afterRequest))
        assertNotEquals(ReviewedSummaryBatches.modelFingerprint(config), ReviewedSummaryBatches.modelFingerprint(config.copy(modelName = "different")))
    }

    private fun turn(index: Int) = listOf(ChatMessage("user", "问题$index", timestamp = index * 2L),
        ChatMessage("ai", "答".repeat(8000), timestamp = index * 2L + 1))

    @Test fun stablePrefixExcludesTwoRecentTurnsAndDoesNotChangeWithAppend() {
        val history = (1..5).flatMap(::turn)
        val prepared = ReviewedSummaryBatches.preparationPrefix(history)
        assertEquals((1..2).flatMap(::turn).map { it.content }, prepared.map { it.content })
        val oldBatches = ReviewedSummaryBatches.plan(history)
        val newBatches = ReviewedSummaryBatches.plan(history + turn(6))
        assertEquals(oldBatches.map { it.map { message -> message.content } }, newBatches.take(5).map { it.map { message -> message.content } })
    }

    @Test fun enormousSingleTurnIsNeverSilentlyTruncated() {
        val messages = listOf(ChatMessage("user", "原文".repeat(20000)), ChatMessage("ai", "收到"))
        assertEquals(messages, ReviewedSummaryBatches.plan(messages).single())
    }

    @Test fun approvedBatchIsReusedWithoutAnotherModelRequest() = runTest {
        val key = UUID.randomUUID().toString()
        var drafts = 0; var reviews = 0
        repeat(2) {
            assertEquals("approved", ReviewedSummaryBatches.resolve(key, { drafts++; "draft" }, { reviews++; "approved" }, { _, _, _ -> }))
        }
        assertEquals(1, drafts); assertEquals(1, reviews)
    }

    @Test fun cacheProbeWaitsForAnOngoingReviewButNeverTreatsDraftAsApproved() = runTest {
        val key = UUID.randomUUID().toString()
        assertNull(ReviewedSummaryBatches.approved(key))
        val decision = CompletableDeferred<String>()
        val preparation = async { ReviewedSummaryBatches.resolve(key, { "draft" }, { decision.await() }, { _, _, _ -> }) }
        runCurrent()
        val probe = async { ReviewedSummaryBatches.approved(key) }
        runCurrent()
        assertFalse(probe.isCompleted)
        decision.complete("reviewed")
        preparation.await()
        assertEquals("reviewed", probe.await())
    }

    @Test fun failedReviewRetainsDraftButNeverReturnsItAsApproved() = runTest {
        val key = UUID.randomUUID().toString()
        var drafts = 0
        try {
            ReviewedSummaryBatches.resolve(key, { drafts++; "draft" }, { error("timeout") }, { _, _, _ -> })
            fail("Unreviewed draft must not be returned")
        } catch (_: IllegalStateException) { }
        assertEquals("revised", ReviewedSummaryBatches.resolve(key, { drafts++; "wrong" }, { assertEquals("draft", it); "revised" }, { _, _, _ -> }))
        assertEquals(1, drafts)
    }

    @Test fun concurrentPreparationAndCompressionShareTheSameReview() = runTest {
        val key = UUID.randomUUID().toString()
        val decision = CompletableDeferred<String>()
        var reviews = 0
        val first = async { ReviewedSummaryBatches.resolve(key, { "draft" }, { reviews++; decision.await() }, { _, _, _ -> }) }
        runCurrent()
        val second = async { ReviewedSummaryBatches.resolve(key, { error("must reuse") }, { error("must reuse") }, { _, _, _ -> }) }
        runCurrent(); assertFalse(second.isCompleted)
        decision.complete("approved")
        assertEquals(first.await(), second.await()); assertEquals(1, reviews)
    }

    @Test fun onlyExactRepeatedToolResultsAreRemoved() {
        val tool = "<tool_result name=\"lookup\" status=\"success\"><content>证据</content></tool_result>"
        val input = "我记得她说：保留这句话。$tool\n$tool"
        val result = ReviewedSummaryBatches.deduplicateToolResults(input, mutableSetOf())
        assertTrue(result.contains("我记得她说：保留这句话。"))
        assertEquals(1, Regex("<content>证据</content>").findAll(result).count())
        assertTrue(result.contains("重复正文已省略"))
    }
}
