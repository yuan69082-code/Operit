package com.ai.assistance.operit.core.chat

import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.core.config.FunctionalPrompts
import com.ai.assistance.operit.core.config.SummaryFidelityPrompts
import com.ai.assistance.operit.data.model.ConversationSummaryConfig
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

/** Simulated draft/review replies verify the actual bounded review and injection contract,
 * not a claim that any model will never invent a plan. */
class SummaryPastFactsTest {
    @Test fun customSectionsCannotPromoteRequestsOrOldSummaryPlansToDecisions() {
        for (english in listOf(false, true)) {
            val prompt = FunctionalPrompts.buildSummarySystemPrompt("以后必须等她来", english,
                ConversationSummaryConfig(globalRules = "下一步请自行安排"))
            assertTrue(prompt.endsWith(SummaryFidelityPrompts.forLanguage(english).trimIndent()))
            assertTrue(prompt.contains(if (english) "Her request is not my agreement" else "她提出要求不等于我已同意"))
            assertTrue(prompt.contains(if (english) "original source unverified" else "原文待核对"))
            assertTrue(prompt.contains(if (english) "cancellations and disagreements" else "取消、双方不同意见"))
        }
    }

    @Test fun twoReviewedCompressionsPreservePlansAndCancelledUnagreedProposals(): Unit = runBlocking {
        val source = "她提出今晚去看书，我说还没决定。已约定明早谈这件事。她取消了晚上的提议，我同意。"
        var calls = 0
        val once = SummaryReview.finalize("我决定今晚去看书，以后等她联系。") {
            calls++
            "<summary_review>revise</summary_review>\n$source"
        }
        val twice = SummaryReview.finalize(once) {
            calls++
            "<summary_review>approve</summary_review>"
        }
        assertEquals(source, twice)
        assertEquals(2, calls)
        val turn = PromptTurn(PromptTurnKind.SUMMARY, twice)
        val sent = SegmentedSummary.promptTurns(listOf(turn)).single()
        assertTrue(sent.content.contains(source))
        assertTrue(sent.content.startsWith(SummaryFidelityPrompts.READING_BOUNDARY))
        assertEquals(sent, SegmentedSummary.promptTurns(listOf(sent)).single())
        assertEquals(source, turn.content)
    }
}
