package com.ai.assistance.operit.core.chat

import com.ai.assistance.operit.data.model.ChatMessage

/** Automatic compression counts new requests, not the verbatim tail retained by its last run. */
internal object SummaryTriggerPolicy {
    private fun freshMessages(messages: List<ChatMessage>): List<ChatMessage> {
        val lastSummary = messages.indexOfLast { it.sender == "summary" }
        // Legacy summaries have sentAt == 0; keep their previous counting semantics until
        // the next compression stores a snapshot boundary. Ordinary message timings are untouched.
        val snapshotTimestamp = messages.getOrNull(lastSummary)?.sentAt ?: 0L
        return messages.drop(lastSummary + 1).filter {
            snapshotTimestamp <= 0L || it.timestamp > snapshotTimestamp
        }
    }

    fun newUserTurns(messages: List<ChatMessage>): Int =
        freshMessages(messages).count { it.sender == "user" }

    fun shouldGenerateSummary(
        messages: List<ChatMessage>,
        currentTokens: Long,
        maxTokens: Int,
        tokenUsageThreshold: Double,
        enableSummary: Boolean,
        enableSummaryByMessageCount: Boolean,
        summaryMessageCountThreshold: Int
    ): Boolean {
        if (!enableSummary) return false
        val fresh = freshMessages(messages)
        val newUsers = fresh.count { it.sender == "user" }
        // Group members may make autonomous requests without another user message. Preserve
        // their ability to compress; fragments sharing sentAt are still one request.
        val newTurns = if (newUsers > 0) newUsers else fresh.asSequence()
            .filter { it.sender == "ai" }.map { it.sentAt }.distinct().count()
        if (newTurns == 0) return false
        val hasSummary = messages.any { it.sender == "summary" }
        if (hasSummary && newTurns < 3 && maxTokens > 0 && currentTokens < maxTokens * 0.95) return false
        if (maxTokens > 0 && currentTokens.toDouble() / maxTokens >= tokenUsageThreshold) return true
        return enableSummaryByMessageCount && newUsers >= summaryMessageCountThreshold
    }
}
