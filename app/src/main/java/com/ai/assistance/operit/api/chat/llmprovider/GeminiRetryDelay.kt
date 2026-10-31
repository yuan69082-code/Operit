package com.ai.assistance.operit.api.chat.llmprovider

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

internal object GeminiRetryDelay {
    fun retryAfterMillis(header: String?, now: Long): Long? {
        if (header == null) return null
        header.trim().toLongOrNull()?.let {
            return it.coerceIn(0, Long.MAX_VALUE / 1000) * 1000
        }
        return runCatching {
            (ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli() - now).coerceAtLeast(0)
        }.getOrNull()
    }

    fun delayMillis(attempt: Int, retryAfter: Long?, jitter: Long = Random.nextLong(1001)): Long {
        val backoff = (5_000L shl (attempt - 1).coerceIn(0, 4)).coerceAtMost(60_000L)
        return maxOf(backoff + jitter.coerceIn(0, 1000), retryAfter ?: 0L)
    }
}
