package com.ai.assistance.operit.core.chat

import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.model.ChatMessageDisplayMode

/** The marker replaces only source; retained turns remain verbatim after that marker. */
internal data class SummaryContextSlice(
    val source: List<ChatMessage>,
    val retained: List<ChatMessage>
) {
    val beforeTimestamp: Long get() = source.last().timestamp
    val afterTimestamp: Long get() = retained.first().timestamp
    val snapshotTimestamp: Long get() = (source + retained).maxOf { it.timestamp }

    fun completedSummary(summary: ChatMessage, completedAt: Long): ChatMessage {
        require(summary.sender == "summary")
        // For summary markers only, sentAt records the immutable snapshot boundary, including
        // retained turns. Completion time would incorrectly swallow concurrently appended turns.
        return summary.copy(sentAt = snapshotTimestamp, completedAt = completedAt)
    }

    companion object {
        fun activeHistory(messages: List<ChatMessage>): List<ChatMessage> =
            messages.drop(messages.indexOfLast { it.sender == "summary" }.coerceAtLeast(0))

        fun plan(
            messages: List<ChatMessage>,
            retainedCharBudget: Long = 16_384,
            maxRetainedTurns: Int = 8,
        ): SummaryContextSlice? {
            require(retainedCharBudget >= 0 && maxRetainedTurns >= 2)
            val active = activeHistory(messages)
            val turnStarts = mutableListOf<Int>()
            var hasAssistant = false
            var assistantSentAt = 0L
            active.forEachIndexed { index, message ->
                if (message.sender == "user") {
                    turnStarts += index
                    hasAssistant = false
                } else if (message.sender == "ai") {
                    // Group members can start a new request without another user message.
                    // Fragments/tool results from one request share sentAt and stay together.
                    if (turnStarts.isEmpty() || hasAssistant && assistantSentAt > 0L &&
                        message.sentAt > 0L && message.sentAt != assistantSentAt) {
                        turnStarts += index
                    }
                    hasAssistant = true
                    assistantSentAt = message.sentAt
                }
            }
            // Never cut between a user's message and its assistant/tool response chain.
            if (turnStarts.size < 2) return null
            // Keep the latest whole turn even when it alone exceeds the budget. Older turns
            // must fit: an unconditional two-turn minimum kept huge tool chains after compression.
            // Spend remaining room on earlier turns so short recent references still survive.
            var retainedTurns = 1
            var retainedChars = active.drop(turnStarts[turnStarts.size - retainedTurns])
                .sumOf { it.content.length.toLong() }
            while (retainedTurns < minOf(maxRetainedTurns, turnStarts.size - 1)) {
                val candidate = turnStarts.size - retainedTurns - 1
                val chars = active.subList(turnStarts[candidate], turnStarts[candidate + 1])
                    .sumOf { it.content.length.toLong() }
                if (retainedChars + chars > retainedCharBudget) break
                retainedChars += chars
                retainedTurns++
            }
            val boundary = turnStarts[turnStarts.size - retainedTurns]
            val source = active.take(boundary)
            // A quiet wake can leave only the assistant's hidden note, with no visible reply.
            // Such completed experiences are compressible even while the user remains silent;
            // an ordinary unanswered user message alone must still never be compressed.
            if (source.none {
                it.sender == "ai" ||
                    it.sender == "user" && it.displayMode == ChatMessageDisplayMode.HIDDEN_PLACEHOLDER
            }) return null
            return SummaryContextSlice(source.map { it.copy() }, active.drop(boundary).map { it.copy() })
        }
    }
}
