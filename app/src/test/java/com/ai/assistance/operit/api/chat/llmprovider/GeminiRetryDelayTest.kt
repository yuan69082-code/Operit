package com.ai.assistance.operit.api.chat.llmprovider

import org.junit.Assert.*
import org.junit.Test

class GeminiRetryDelayTest {
    @Test fun rateLimitsBackOffAndHonorServerWait() {
        assertEquals(listOf(5000L, 10000L, 20000L, 40000L, 60000L, 60000L),
            (1..6).map { GeminiRetryDelay.delayMillis(it, null, 0) })
        assertEquals(120_000L, GeminiRetryDelay.delayMillis(1, 120_000L, 500))
        assertEquals(5500L, GeminiRetryDelay.delayMillis(1, 1000L, 500))
    }

    @Test fun retryAfterAcceptsSecondsAndHttpDateWithoutOverflow() {
        assertEquals(120_000L, GeminiRetryDelay.retryAfterMillis("120", 0))
        assertEquals(60_000L, GeminiRetryDelay.retryAfterMillis("Thu, 1 Jan 1970 00:01:00 GMT", 0))
        assertNull(GeminiRetryDelay.retryAfterMillis("invalid", 0))
        assertNull(GeminiRetryDelay.retryAfterMillis(null, 0))
        assertTrue(GeminiRetryDelay.retryAfterMillis(Long.MAX_VALUE.toString(), 0)!! > 0)
    }
}
