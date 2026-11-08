package com.ai.assistance.operit.services.core

import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.stream.emptyStream
import com.ai.assistance.operit.util.stream.share
import com.ai.assistance.operit.util.stream.stream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

class MessageProcessingDelegateTest {
    private var previousSystemLogEnabled = true

    @Before
    fun disableAndroidSystemLogForJvmTests() {
        previousSystemLogEnabled = AppLogger.enableSystemLog
        AppLogger.enableSystemLog = false
    }

    @After
    fun restoreAndroidSystemLog() {
        AppLogger.enableSystemLog = previousSystemLogEnabled
    }

    @Test
    fun completeInterruptedMessage_appliesTurnSnapshotAndCompletesPartialContent() {
        val message =
            ChatMessage(
                sender = "ai",
                content = "stale",
                timestamp = 10L,
                sentAt = 20L,
                contentStream = emptyStream(),
            )
        val snapshot =
            MessageProcessingDelegate.TurnCancellationSnapshot(
                inputTokens = 120L,
                outputTokens = 34L,
                cachedInputTokens = 56L,
                sentAt = 30L,
                outputDurationMs = 4_000L,
                waitDurationMs = 500L,
            )

        val result =
            MessageProcessingDelegate.completeInterruptedMessage(
                streamingMessage = message,
                finalContent = "partial response",
                snapshot = snapshot,
                completedAt = 5_000L,
            )

        assertEquals("partial response", result.content)
        assertNull(result.contentStream)
        assertEquals(120L, result.inputTokens)
        assertEquals(34L, result.outputTokens)
        assertEquals(56L, result.cachedInputTokens)
        assertEquals(30L, result.sentAt)
        assertEquals(4_000L, result.outputDurationMs)
        assertEquals(500L, result.waitDurationMs)
        assertEquals(5_000L, result.completedAt)
    }

    @Test
    fun recordedProviderRejectionIsNotSuccessfulEmptyCompletion() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val expected = IOException("API request failed: 400")
        val response =
            stream<String> { throw expected }.share(
                scope = scope,
                replay = Int.MAX_VALUE,
                propagateCompletionCause = false,
            )
        val received = StringBuilder()
        try {
            response.collect { received.append(it) }
            assertEquals("", received.toString())
            assertSame(
                expected,
                runCatching { MessageProcessingDelegate.requireSuccessfulResponse(response) }.exceptionOrNull(),
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun failedResponsePreservesPartialContentWithoutSuccessfulCompletionTimestamp() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val expected = IOException("tool continuation failed")
        val response =
            stream {
                emit("partial response")
                throw expected
            }.share(scope = scope, replay = Int.MAX_VALUE, propagateCompletionCause = false)
        val received = StringBuilder()
        try {
            response.collect { received.append(it) }
            assertSame(
                expected,
                runCatching { MessageProcessingDelegate.requireSuccessfulResponse(response) }.exceptionOrNull(),
            )
            val preserved =
                MessageProcessingDelegate.completeInterruptedMessage(
                    streamingMessage = ChatMessage(sender = "ai", contentStream = response),
                    finalContent = received.toString(),
                    snapshot = null,
                    completedAt = 0L,
                )
            assertEquals("partial response", preserved.content)
            assertNull(preserved.contentStream)
            assertEquals(0L, preserved.completedAt)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun recordedCancellationRemainsCancellation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val expected = CancellationException("cancelled by user")
        val response =
            stream<String> { throw expected }.share(
                scope = scope,
                propagateCompletionCause = false,
            )
        try {
            response.collect { }
            assertSame(
                expected,
                runCatching { MessageProcessingDelegate.requireSuccessfulResponse(response) }.exceptionOrNull(),
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun successfulQuietResponseIsAllowedWithoutInventingText() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val response = emptyStream<String>().share(scope = scope, propagateCompletionCause = false)
        val received = StringBuilder()
        try {
            response.collect { received.append(it) }
            MessageProcessingDelegate.requireSuccessfulResponse(response)
            assertEquals("", received.toString())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun successfulDisplayWrapperDoesNotHideUpstreamFailure() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val expected = IOException("upstream failed")
        val response =
            stream<String> { throw expected }.share(scope = scope, propagateCompletionCause = false)
        val display = emptyStream<String>().share(scope = scope, propagateCompletionCause = false)
        try {
            response.collect { }
            display.collect { }
            assertSame(
                expected,
                runCatching { MessageProcessingDelegate.requireSuccessfulResponse(response, display) }.exceptionOrNull(),
            )
        } finally {
            scope.cancel()
        }
    }
}
