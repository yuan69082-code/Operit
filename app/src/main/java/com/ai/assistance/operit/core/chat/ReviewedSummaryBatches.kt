package com.ai.assistance.operit.core.chat

import com.ai.assistance.operit.data.model.ChatMessage
import java.security.MessageDigest
import java.util.LinkedHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Cache only in this process: changing source, role, prompts or model settings invalidates it. */
internal object ReviewedSummaryBatches {
    const val TARGET_CHARS = 8_000
    private data class CachedSummary(val draft: String, val approved: String? = null)
    private val cache = object : LinkedHashMap<String, CachedSummary>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedSummary>?) = size > 32
    }
    private val locks = Array(32) { Mutex() }

    fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun modelFingerprint(config: com.ai.assistance.operit.data.model.ModelConfigData?): String {
        // Requests update key counters and rotation state; those are not prompt/model edits.
        val stable = config?.copy(currentKeyIndex = 0, apiKeyPool = config.apiKeyPool.map {
            it.copy(usageCount = 0, lastUsed = 0, errorCount = 0,
                availabilityStatus = com.ai.assistance.operit.data.model.ApiKeyAvailabilityStatus.UNTESTED)
        })
        return fingerprint(stable.toString())
    }

    fun deduplicateToolResults(text: String, seen: MutableSet<String>): String =
        com.ai.assistance.operit.util.ChatMarkupRegex.toolResultTag.replace(text) { match ->
            if (seen.add(match.value)) match.value else "[此处工具返回与本批前文完全相同，重复正文已省略]"
        }

    // Stable boundaries at user turns let the same completed batches be reused as history grows.
    fun plan(messages: List<ChatMessage>): List<List<ChatMessage>> {
        val batches = mutableListOf<List<ChatMessage>>()
        var batch = mutableListOf<ChatMessage>()
        var chars = 0
        for (message in messages) {
            if (message.sender == "user" && chars >= TARGET_CHARS && batch.isNotEmpty()) {
                batches.add(batch.toList()); batch = mutableListOf(); chars = 0
            }
            batch.add(message)
            chars += message.content.length
        }
        if (batch.isNotEmpty()) batches.add(batch.toList())
        return batches
    }

    fun preparationPrefix(messages: List<ChatMessage>): List<ChatMessage> {
        val lastSummary = messages.indexOfLast { it.sender == "summary" }
        val tail = messages.drop(lastSummary + 1)
        val users = tail.indices.filter { tail[it].sender == "user" }
        if (users.size < 4) return emptyList()
        val stable = tail.take(users[users.size - 2]).filter { it.sender == "user" || it.sender == "ai" }
        val complete = plan(stable).dropLast(1).flatten()
        return if (complete.isEmpty()) emptyList() else messages.take(lastSummary + 1) + complete
    }

    suspend fun resolve(
        key: String,
        draft: suspend () -> String,
        review: suspend (String) -> String,
        stage: (String, Boolean, Long) -> Unit
    ): String = locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            val old = synchronized(cache) { cache[key] }
            old?.approved?.let { stage("approved", true, 0); return@withLock it }
            var start = System.nanoTime()
            val proposed = old?.draft ?: draft().also {
                require(it.isNotBlank()) { "压缩草稿为空，原上下文已保留。" }
                synchronized(cache) { cache[key] = CachedSummary(it) }
            }
            stage("draft", old != null, (System.nanoTime() - start) / 1_000_000)
            start = System.nanoTime()
            val approved = review(proposed)
            require(approved.isNotBlank()) { "审阅结果为空，原上下文已保留。" }
            synchronized(cache) { cache[key] = CachedSummary(proposed, approved) }
            stage("review", false, (System.nanoTime() - start) / 1_000_000)
            approved
    }
}
