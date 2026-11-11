package com.ai.assistance.operit.services.core

import com.ai.assistance.operit.api.chat.EnhancedAIService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Test

class TokenStatisticsDelegateTest {
    @Test
    fun completedUsageDoesNotReadResetServiceCountersAndPreviewDoesNotCountAgain() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val statistics = TokenStatisticsDelegate(scope) {
                error("A completed turn must use its captured tokens, not a newer service request")
            }
            statistics.setActiveChatId("chat")
            statistics.setTokenCounts("chat", 300, 50, 600)
            val completedTurn = EnhancedAIService.TurnTokenSnapshot(100, 20, 40)

            statistics.accumulateTurnTokens("chat", completedTurn)
            assertEquals(400L to 70L, statistics.getCumulativeTokenCounts("chat"))
            statistics.updateContextWindow("chat", 725)

            assertEquals(400L to 70L, statistics.getCumulativeTokenCounts("chat"))
            assertEquals(725L, statistics.currentWindowSizeFlow.value)
            assertEquals(400L, statistics.cumulativeInputTokensFlow.value)
            assertEquals(70L, statistics.cumulativeOutputTokensFlow.value)
        } finally { scope.cancel() }
    }

    @Test
    fun backgroundPreviewDoesNotOverwriteTheSelectedChatsWindowOrUsage() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val statistics = TokenStatisticsDelegate(scope) { null }
            statistics.setTokenCounts("previous", 10, 2, 200)
            statistics.setTokenCounts("current", 40, 8, 800)
            statistics.setActiveChatId("current")

            statistics.accumulateTurnTokens("previous", EnhancedAIService.TurnTokenSnapshot(5, 1, 0))
            statistics.updateContextWindow("previous", 230)

            assertEquals(40L, statistics.cumulativeInputTokensFlow.value)
            assertEquals(800L, statistics.currentWindowSizeFlow.value)
            statistics.setActiveChatId("previous")
            assertEquals(15L to 3L, statistics.getCumulativeTokenCounts())
            assertEquals(230L, statistics.currentWindowSizeFlow.value)
        } finally { scope.cancel() }
    }
}
