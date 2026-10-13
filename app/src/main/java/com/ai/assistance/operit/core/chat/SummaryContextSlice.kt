package com.ai.assistance.operit.core.chat

import com.ai.assistance.operit.data.model.ChatMessage

/** The marker replaces only source; retained turns remain verbatim after that marker. */
internal data class SummaryContextSlice(
    val source: List<ChatMessage>,
    val retained: List<ChatMessage>
) {
    val beforeTimestamp: Long get() = source.last().timestamp
    val afterTimestamp: Long get() = retained.first().timestamp

    companion object {
        fun activeHistory(messages: List<ChatMessage>): List<ChatMessage> =
            messages.drop(messages.indexOfLast { it.sender == "summary" }.coerceAtLeast(0))

        fun plan(messages: List<ChatMessage>): SummaryContextSlice? {
            val active = activeHistory(messages)
            val userStarts = active.indices.filter { active[it].sender == "user" }
            // Never cut between a user's message and its assistant/tool response chain.
            if (userStarts.size < 2) return null
            val retainedTurns = minOf(2, userStarts.size - 1)
            val boundary = userStarts[userStarts.size - retainedTurns]
            val source = active.take(boundary)
            if (source.none { it.sender == "ai" }) return null
            return SummaryContextSlice(source.map { it.copy() }, active.drop(boundary).map { it.copy() })
        }
    }
}
